import AgentPresenceCore
import AppKit
import SwiftUI

/// The one circle that tracks the whole process (SPEC.md §3). Instead of a long row of steps it shows the current one,
/// orbits while the agent works, draws a check when a step ends and slides the next number in, ripples once at the end,
/// pulses amber while it waits for the owner and shakes red when it failed.
public struct StepDot: View {
    var phase: Phase
    /// Current step number; nil when the session has no plan.
    var number: Int?
    var size: CGFloat
    /// "mac" / "phone": a small device badge (the session is a mirror of another device).
    var badge: String?

    /// Holds the old number while a finished step celebrates; nil = show `number`.
    @State private var held: Int?
    @State private var celebrating = false
    /// nil = fully drawn (also how an offscreen render sees it).
    @State private var checkTrim: CGFloat?
    @State private var pop: CGFloat = 1
    @State private var ripple: CGFloat = 1
    @State private var rippleAlpha: Double = 0
    @State private var shake: CGFloat = 0
    @State private var celebration = 0

    public init(phase: Phase, number: Int?, size: CGFloat = 28, badge: String? = nil) {
        self.phase = phase
        self.number = number
        self.size = size
        self.badge = badge
    }

    private var ring: CGFloat { max(1.5, size / 14) }
    private var green: Bool { celebrating || phase == .done }

    public var body: some View {
        ZStack {
            if phase == .waiting { Halo(size: size) }
            Circle()
                .stroke(Theme.green, lineWidth: ring)
                .frame(width: size, height: size)
                .scaleEffect(ripple)
                .opacity(rippleAlpha)
            Circle()
                .fill(fill)
                .frame(width: size, height: size)
                .overlay {
                    if phase == .running && !celebrating {
                        Circle().strokeBorder(Theme.blue, lineWidth: ring)
                    }
                }
            content
            if phase == .running && !celebrating { Orbit(size: size + ring * 4, width: ring) }
        }
        .scaleEffect(pop)
        .offset(x: shake)
        .frame(width: size + ring * 5, height: size + ring * 5)
        .overlay(alignment: .bottomTrailing) {
            if let badge { Badge(kind: badge, size: max(11, size * 0.42)) }
        }
        .onChange(of: number) { old, new in
            guard let new, let old, new > old, phase == .running else {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { held = nil }
                return
            }
            celebrate(from: old)
        }
        .onChange(of: phase) { old, new in
            switch new {
            case .done: finish()
            case .failed: fail()
            default: withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { held = nil }
            }
        }
    }

    private var fill: Color {
        if green { return Theme.green }
        switch phase {
        case .waiting: return Theme.amber
        case .failed: return Theme.red
        default: return Theme.blueFill
        }
    }

    @ViewBuilder private var content: some View {
        if green {
            CheckShape()
                .trim(from: 0, to: checkTrim ?? 1)
                .stroke(Color.black.opacity(0.85), style: StrokeStyle(lineWidth: max(2, size / 11), lineCap: .round, lineJoin: .round))
                .frame(width: size, height: size)
        } else if phase == .waiting {
            Text("!").font(.system(size: size * 0.6, weight: .black, design: .rounded)).foregroundStyle(.black)
        } else if phase == .failed {
            Image(systemName: "xmark").font(.system(size: size * 0.42, weight: .bold)).foregroundStyle(.white)
        } else if let shown = held ?? number {
            Text("\(shown)")
                .font(.system(size: size * 0.5, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(Theme.blueText)
                .contentTransition(.numericText(countsDown: false))
                .id(shown)
                .transition(.asymmetric(insertion: .scale(scale: 0.4).combined(with: .opacity), removal: .opacity))
        } else {
            Circle().fill(Theme.blueText).frame(width: size * 0.22, height: size * 0.22)
        }
    }

    /// Step finished: green + the check draws itself + pop, then the next number comes in.
    private func celebrate(from old: Int) {
        celebration += 1
        let mine = celebration
        held = old
        checkTrim = 0
        withAnimation(.easeOut(duration: 0.18)) { celebrating = true }
        withAnimation(.easeOut(duration: 0.35).delay(0.05)) { checkTrim = 1 }
        withAnimation(.spring(response: 0.22, dampingFraction: 0.45)) { pop = 1.12 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) { pop = 1 }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.85) {
            guard mine == celebration else { return }
            withAnimation(.spring(response: 0.4, dampingFraction: 0.75)) {
                celebrating = false
                held = nil
            }
        }
    }

    /// Everything done: check draws, one ripple ring goes out and fades.
    private func finish() {
        celebration += 1
        celebrating = false
        checkTrim = 0
        withAnimation(.easeOut(duration: 0.4).delay(0.05)) { checkTrim = 1 }
        withAnimation(.spring(response: 0.25, dampingFraction: 0.45)) { pop = 1.15 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) { pop = 1 }
        }
        ripple = 1
        rippleAlpha = 0.8
        withAnimation(.easeOut(duration: 0.8)) {
            ripple = 1.8
            rippleAlpha = 0
        }
    }

    private func fail() {
        celebration += 1
        celebrating = false
        let steps: [CGFloat] = [-4, 4, -3, 3, -1.5, 0]
        for (i, x) in steps.enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.06 * Double(i)) {
                withAnimation(.linear(duration: 0.06)) { shake = x }
            }
        }
    }
}

/// Snapshots (ImageRenderer) cannot draw AppKit layers: they get a still SwiftUI frame instead.
struct SnapshotKey: EnvironmentKey { static let defaultValue = false }

extension EnvironmentValues {
    var presenceSnapshot: Bool {
        get { self[SnapshotKey.self] }
        set { self[SnapshotKey.self] = newValue }
    }
}

/// A short arc that orbits just outside the ring: "devam ediyor". Core Animation runs it in the window server, so a
/// circle that spins for hours costs the app next to nothing.
private struct Orbit: View {
    var size: CGFloat
    var width: CGFloat
    @Environment(\.presenceSnapshot) private var still

    var body: some View {
        Group {
            if still {
                Circle()
                    .trim(from: 0, to: 0.25)
                    .stroke(Theme.blueOrbit, style: StrokeStyle(lineWidth: width, lineCap: .round))
                    .rotationEffect(.degrees(40))
            } else {
                LayerView(kind: .orbit(width: width))
            }
        }
        .frame(width: size, height: size)
        .allowsHitTesting(false)
    }
}

/// Amber halo that keeps pulsing while the agent waits for the owner.
private struct Halo: View {
    var size: CGFloat
    @Environment(\.presenceSnapshot) private var still

    var body: some View {
        Group {
            if still {
                Circle().fill(Theme.amber.opacity(0.25)).scaleEffect(1.25)
            } else {
                LayerView(kind: .halo)
            }
        }
        .frame(width: size, height: size)
        .allowsHitTesting(false)
    }
}

private struct LayerView: NSViewRepresentable {
    enum Kind: Equatable { case orbit(width: CGFloat), halo }
    var kind: Kind

    func makeNSView(context: Context) -> AnimatedLayerView { AnimatedLayerView(kind: kind) }
    func updateNSView(_ v: AnimatedLayerView, context: Context) { v.kind = kind }
}

final class AnimatedLayerView: NSView {
    fileprivate var kind: LayerView.Kind { didSet { if kind != oldValue { needsLayout = true } } }
    private let spinner = CALayer()
    private let arc = CAShapeLayer()
    private let halo = CAShapeLayer()

    fileprivate init(kind: LayerView.Kind) {
        self.kind = kind
        super.init(frame: .zero)
        wantsLayer = true
        layer?.masksToBounds = false
        arc.fillColor = nil
        arc.strokeColor = NSColor(Theme.blueOrbit).cgColor
        arc.lineCap = .round
        arc.strokeStart = 0
        arc.strokeEnd = 0.25
        spinner.addSublayer(arc)
        halo.fillColor = NSColor(Theme.amber).cgColor
        layer?.addSublayer(spinner)
        layer?.addSublayer(halo)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layout() {
        super.layout()
        let b = bounds
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        spinner.frame = b
        arc.frame = b
        halo.frame = b
        switch kind {
        case .orbit(let w):
            spinner.isHidden = false
            halo.isHidden = true
            arc.lineWidth = w
            arc.path = CGPath(ellipseIn: b.insetBy(dx: w / 2, dy: w / 2), transform: nil)
        case .halo:
            spinner.isHidden = true
            halo.isHidden = false
            halo.path = CGPath(ellipseIn: b, transform: nil)
        }
        CATransaction.commit()
        animate()
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        animate()
    }

    private func animate() {
        guard window != nil, bounds.width > 0 else { return }
        switch kind {
        case .orbit:
            guard spinner.animation(forKey: "spin") == nil else { return }
            let a = CABasicAnimation(keyPath: "transform.rotation.z")
            a.fromValue = 0
            a.toValue = -2 * Double.pi
            a.duration = 1.1
            a.repeatCount = .infinity
            a.isRemovedOnCompletion = false
            spinner.add(a, forKey: "spin")
        case .halo:
            guard halo.animation(forKey: "pulse") == nil else { return }
            let scale = CABasicAnimation(keyPath: "transform.scale")
            scale.fromValue = 1
            scale.toValue = 1.5
            let fade = CABasicAnimation(keyPath: "opacity")
            fade.fromValue = 0.6
            fade.toValue = 0
            let g = CAAnimationGroup()
            g.animations = [scale, fade]
            g.duration = 1.4
            g.repeatCount = .infinity
            g.isRemovedOnCompletion = false
            halo.add(g, forKey: "pulse")
        }
    }
}

struct CheckShape: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.minX + r.width * 0.29, y: r.minY + r.height * 0.52))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.44, y: r.minY + r.height * 0.67))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.72, y: r.minY + r.height * 0.37))
        return p
    }
}

private struct Badge: View {
    var kind: String
    var size: CGFloat

    var body: some View {
        Image(systemName: kind == "phone" ? "smartphone" : "laptopcomputer")
            .font(.system(size: size * 0.6, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Circle().fill(Color(hex: 0x3A3A3C)))
            .overlay(Circle().stroke(Color.black, lineWidth: 1.5))
    }
}
