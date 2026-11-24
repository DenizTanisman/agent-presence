import AgentPresenceCore
import AppKit
import Combine
import SwiftUI

/// Puts the island on screen: one transparent panel across the top centre of the notched display (or the main one),
/// above full-screen apps and Mission Control, on every desktop. It never takes keyboard focus, and clicks fall through
/// to the app below except on the island itself. Main thread only.
@MainActor
public final class IslandController {
    public let store: PresenceStore
    /// Show the island in screenshots and screen recordings. Off by default, so an agent that looks at the screen does
    /// not see (or click) it.
    public var capturable: Bool { didSet { panel.sharingType = capturable ? .readOnly : .none } }
    public var host = "mac" { didSet { store.host = host; layout() } }

    private let panel: Panel
    private var hits: [CGRect] = []
    private var timer: Timer?
    private var bag: Set<AnyCancellable> = []
    private var geo = IslandGeometry(hasNotch: false, notchWidth: 0, barHeight: 24, panelSize: IslandGeometry.panel)

    public init(store: PresenceStore, capturable: Bool = false) {
        self.store = store
        self.capturable = capturable
        panel = Panel(contentRect: NSRect(origin: .zero, size: IslandGeometry.panel),
                      styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.ignoresMouseEvents = true
        panel.level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.assistiveTechHighWindow)))
        panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle, .fullScreenAuxiliary]
        panel.hidesOnDeactivate = false
        panel.isMovable = false
        panel.sharingType = capturable ? .readOnly : .none
        layout()

        NotificationCenter.default.publisher(for: NSApplication.didChangeScreenParametersNotification)
            .sink { [weak self] _ in self?.layout() }
            .store(in: &bag)
        store.$sessions
            .map(\.isEmpty)
            .removeDuplicates()
            .sink { [weak self] empty in self?.visible(!empty) }
            .store(in: &bag)
        store.$hidden
            .removeDuplicates()
            .sink { [weak self] on in self?.edges(on) }
            .store(in: &bag)
    }

    /// The display with the notch if there is one, else the main display.
    private var screen: NSScreen? {
        NSScreen.screens.first { $0.safeAreaInsets.top > 0 } ?? NSScreen.main ?? NSScreen.screens.first
    }

    public func layout() {
        guard let screen else { return }
        let f = screen.frame
        var midX = f.midX
        if screen.safeAreaInsets.top > 0, let l = screen.auxiliaryTopLeftArea, let r = screen.auxiliaryTopRightArea {
            let notch = f.width - l.width - r.width
            midX = f.minX + l.width + notch / 2
            geo = IslandGeometry(hasNotch: true, notchWidth: notch, barHeight: screen.safeAreaInsets.top,
                                 panelSize: IslandGeometry.panel)
        } else {
            let bar = max(f.maxY - screen.visibleFrame.maxY, NSStatusBar.system.thickness)
            geo = IslandGeometry(hasNotch: false, notchWidth: 0, barHeight: bar, panelSize: IslandGeometry.panel)
        }
        let size = IslandGeometry.panel
        panel.setFrame(NSRect(x: (midX - size.width / 2).rounded(), y: f.maxY - size.height, width: size.width, height: size.height),
                       display: false)
        let root = IslandRoot(store: store, geo: geo, host: host) { [weak self] rects in self?.hits = rects }
        if let hosting = panel.contentView as? FirstMouseHosting {
            hosting.rootView = AnyView(root)
        } else {
            let hosting = FirstMouseHosting(rootView: AnyView(root))
            hosting.frame = NSRect(origin: .zero, size: size)
            panel.contentView = hosting
        }
    }

    private func visible(_ on: Bool) {
        if on {
            panel.orderFrontRegardless()
            if timer == nil {
                let t = Timer(timeInterval: 1 / 20, repeats: true) { [weak self] _ in
                    MainActor.assumeIsolated { self?.track() }
                }
                RunLoop.main.add(t, forMode: .common)
                timer = t
            }
        } else {
            timer?.invalidate()
            timer = nil
            panel.ignoresMouseEvents = true
            // Let the last animation finish before the panel goes.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
                guard let self, self.store.sessions.isEmpty else { return }
                self.panel.orderOut(nil)
            }
        }
    }

    /// Draws what the island shows right now over a stand-in menu bar and notch, into a PNG. Needs no screen-recording
    /// permission (nothing is read from the screen): for tests, docs and bug reports.
    @discardableResult
    public func snapshot(to url: URL, height: CGFloat = 330) -> Bool {
        let g = geo
        let view = ZStack(alignment: .top) {
            LinearGradient(colors: [Color(hex: 0x3B4A66), Color(hex: 0x1C2436)], startPoint: .top, endPoint: .bottom)
            Rectangle().fill(Color.white.opacity(0.10)).frame(height: g.barHeight)
            if g.hasNotch {
                UnevenRoundedRectangle(bottomLeadingRadius: 10, bottomTrailingRadius: 10, style: .continuous)
                    .fill(Color.black)
                    .frame(width: g.notchWidth, height: g.barHeight)
            }
            IslandRoot(store: store, geo: g, host: host) { _ in }
            if store.hidden { EdgeGlow() }
        }
        .environment(\.presenceSnapshot, true)
        .frame(width: g.panelSize.width, height: height, alignment: .top)
        .clipped()
        let r = ImageRenderer(content: view)
        r.scale = 2
        r.colorMode = .nonLinear
        // App icons can push the renderer into an HDR (PQ) space: draw the result into plain 8-bit sRGB.
        guard let raw = r.cgImage, let srgb = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: raw.width, height: raw.height, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: srgb, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
        ctx.draw(raw, in: CGRect(x: 0, y: 0, width: raw.width, height: raw.height))
        guard let cg = ctx.makeImage(),
              let png = NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:]) else { return false }
        return (try? png.write(to: url, options: .atomic)) != nil
    }

    private var edgePanel: Panel?

    /// SPEC "Edge glow while hidden": a click-through layer over the whole screen whose edges glow red.
    private func edges(_ on: Bool) {
        guard on else {
            edgePanel?.orderOut(nil)
            edgePanel = nil
            return
        }
        guard let screen, edgePanel == nil else { return }
        let p = Panel(contentRect: screen.frame, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = false
        p.ignoresMouseEvents = true
        p.level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.assistiveTechHighWindow)))
        p.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle, .fullScreenAuxiliary]
        p.sharingType = capturable ? .readOnly : .none
        p.contentView = NSHostingView(rootView: EdgeGlow())
        p.setFrame(screen.frame, display: true)
        p.orderFrontRegardless()
        edgePanel = p
    }

    /// Clicks reach the panel only while the pointer is on the island; everywhere else they fall through.
    private func track() {
        let m = NSEvent.mouseLocation
        let f = panel.frame
        let p = CGPoint(x: m.x - f.minX, y: f.maxY - m.y)
        let inside = f.contains(m) && hits.contains { $0.insetBy(dx: -3, dy: -3).contains(p) }
        if store.hovering != inside { store.hovering = inside }
        let ignore = !(inside && store.interactive)
        if panel.ignoresMouseEvents != ignore { panel.ignoresMouseEvents = ignore }
    }
}

/// Red light along the screen edges, breathing like Samsung's edge lighting.
struct EdgeGlow: View {
    @State private var bright = false

    var body: some View {
        GeometryReader { g in
            let red = Color(hex: 0xFF3B30)
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(red, lineWidth: 10)
                    .blur(radius: 9)
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(red.opacity(0.9), lineWidth: 3)
                    .blur(radius: 1.5)
            }
            .frame(width: g.size.width, height: g.size.height)
            .opacity(bright ? 1 : 0.55)
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .onAppear { withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) { bright = true } }
    }
}

/// Never key, never main: the app the agent is driving keeps the keyboard.
final class Panel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
    // Borderless windows may sit over the menu bar; do not let AppKit push the panel down.
    override func constrainFrameRect(_ frameRect: NSRect, to screen: NSScreen?) -> NSRect { frameRect }
}

/// The first click on a button acts at once, without first focusing the panel.
final class FirstMouseHosting: NSHostingView<AnyView> {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}
