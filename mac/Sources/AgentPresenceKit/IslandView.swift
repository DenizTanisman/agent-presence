import AgentPresenceCore
import SwiftUI

/// Where the island lives on this screen.
public struct IslandGeometry: Equatable {
    /// Built-in display with a camera notch: the island grows out of it.
    public var hasNotch: Bool
    public var notchWidth: CGFloat
    /// Menu bar height (= notch height on a notched screen).
    public var barHeight: CGFloat
    public var panelSize: CGSize

    public static let panel = CGSize(width: 760, height: 440)
}

struct HitRectsKey: PreferenceKey {
    static let defaultValue: [CGRect] = []
    static func reduce(value: inout [CGRect], nextValue: () -> [CGRect]) { value += nextValue() }
}

private extension View {
    /// This view takes clicks: report where it is (panel coordinates, top-left origin).
    func hitArea() -> some View {
        background(GeometryReader { g in
            Color.clear.preference(key: HitRectsKey.self, value: [g.frame(in: .named("panel"))])
        })
    }
}

/// Everything drawn in the panel: the primary session as minimal / compact / expanded and the other sessions as
/// small circles beside it (SPEC.md §2).
public struct IslandRoot: View {
    @ObservedObject var store: PresenceStore
    var geo: IslandGeometry
    /// The device this host runs on: sessions of the other device get a badge.
    var host: String
    var onHitRects: ([CGRect]) -> Void

    public init(store: PresenceStore, geo: IslandGeometry, host: String = "mac", onHitRects: @escaping ([CGRect]) -> Void) {
        self.store = store
        self.geo = geo
        self.host = host
        self.onHitRects = onHitRects
    }

    private let wing: CGFloat = 112
    private let gap: CGFloat = 7
    private var dot: CGFloat { geo.hasNotch ? min(geo.barHeight - 4, 30) : 32 }
    private var topY: CGFloat { geo.hasNotch ? 0 : geo.barHeight + 6 }

    private func width(_ m: PresenceStore.Mode, _ s: Session) -> CGFloat {
        switch m {
        case .minimal: dot
        case .compact: geo.hasNotch ? geo.notchWidth + 2 * wing : 300
        case .expanded: max(geo.hasNotch ? geo.notchWidth + 2 * 142 : 0, 470)
        }
    }

    public var body: some View {
        ZStack(alignment: .top) {
            Color.clear
            if let p = store.primary {
                let w = width(store.mode, p)
                Group {
                    if store.mode == .minimal {
                        MinimalCircle(s: p, d: dot, badge: badge(p))
                            .onTapGesture { store.tapIsland() }
                            .hitArea()
                            .transition(.scale(scale: 0.5).combined(with: .opacity))
                    } else {
                        island(p, width: w)
                            .transition(.scale(scale: 0.6, anchor: .top).combined(with: .opacity))
                    }
                }
                .offset(x: store.mode == .minimal && geo.hasNotch ? geo.notchWidth / 2 + gap + dot / 2 : 0,
                        y: store.mode == .minimal && geo.hasNotch ? (geo.barHeight - dot) / 2 : topY)

                // Directives live only in the closed sizes, in their own capsule under the island (SPEC.md §2).
                if store.mode != .expanded {
                    let caps = capsules(primary: p)
                    if !caps.isEmpty {
                        VStack(spacing: 5) {
                            ForEach(caps, id: \.self) { c in DirectiveCapsule(item: c) }
                        }
                        .frame(maxWidth: max(width(.compact, p), 300))
                        .offset(y: (store.mode == .compact ? topY + (geo.hasNotch ? geo.barHeight : 38) : geo.barHeight) + 6)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                        .allowsHitTesting(false)
                    }
                }

                let others = store.others
                if !others.isEmpty {
                    let rowW = CGFloat(others.count) * dot + CGFloat(others.count - 1) * gap
                    let start: CGFloat = store.mode == .minimal && geo.hasNotch
                        ? geo.notchWidth / 2 + gap + dot + gap
                        : w / 2 + gap
                    HStack(spacing: gap) {
                        ForEach(others) { o in
                            MinimalCircle(s: o, d: dot, badge: badge(o))
                                .onTapGesture { store.tapOther(o.id) }
                                .hitArea()
                                .transition(.scale(scale: 0.4).combined(with: .opacity))
                        }
                    }
                    .offset(x: start + rowW / 2, y: geo.hasNotch ? (geo.barHeight - dot) / 2 : topY)
                }
            }
        }
        .frame(width: geo.panelSize.width, height: geo.panelSize.height, alignment: .top)
        .coordinateSpace(name: "panel")
        .onPreferenceChange(HitRectsKey.self) { rects in onHitRects(rects) }
        .environment(\.colorScheme, .dark)
        .animation(.spring(response: 0.42, dampingFraction: 0.82), value: store.mode)
        .animation(.spring(response: 0.42, dampingFraction: 0.82), value: store.sessions.map(\.id))
    }

    private func badge(_ s: Session) -> String? { s.device == host ? nil : s.device }

    /// The primary session's directives, then every other live session's, named by its app.
    private func capsules(primary p: Session) -> [CapsuleItem] {
        var out: [CapsuleItem] = []
        for s in [p] + store.others {
            let d = s.directives(tr: L.tr, on: host)
            guard !d.isEmpty else { continue }
            let prefix = s.id == p.id ? "" : "\(s.app ?? s.agent): "
            let donts = d.filter { $0.kind == .dont }.map(\.text)
            let dos = d.filter { $0.kind == .doThis }.map(\.text)
            if !donts.isEmpty { out.append(CapsuleItem(text: prefix + donts.joined(separator: " · "), dont: true)) }
            if !dos.isEmpty { out.append(CapsuleItem(text: prefix + dos.joined(separator: " · "), dont: false)) }
        }
        return out
    }

    // MARK: compact + expanded share one black shape that grows out of the notch

    private func island(_ s: Session, width w: CGFloat) -> some View {
        let expanded = store.mode == .expanded
        let radius: CGFloat = expanded ? 26 : (geo.hasNotch ? min(geo.barHeight / 2, 16) : 18)
        let shape = IslandShape(bottomRadius: radius, topRadius: geo.hasNotch ? 0 : radius, ear: geo.hasNotch ? 7 : 0)
        return VStack(spacing: 0) {
            if expanded {
                ExpandedBody(s: s, store: store, geo: geo, badge: badge(s))
                    .transition(.opacity.animation(.easeOut(duration: 0.18).delay(0.08)))
            } else {
                CompactBody(s: s, geo: geo, wing: wing, badge: badge(s))
                    .transition(.opacity.animation(.easeOut(duration: 0.12)))
            }
        }
        .frame(width: w)
        .background(shape.fill(Theme.surface))
        .overlay(geo.hasNotch ? nil : shape.stroke(Color.white.opacity(0.12), lineWidth: 1))
        .clipShape(shape)
        .contentShape(shape)
        .onTapGesture { store.tapIsland() }
        .hitArea()
    }
}

/// Black shape whose top is flush with the screen edge (with small concave "ears" where it meets it) and whose
/// bottom corners are round. Without a notch it is an ordinary rounded rectangle.
struct IslandShape: Shape {
    var bottomRadius: CGFloat
    var topRadius: CGFloat
    var ear: CGFloat

    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(bottomRadius, topRadius) }
        set { bottomRadius = newValue.first; topRadius = newValue.second }
    }

    func path(in r: CGRect) -> Path {
        var p = Path()
        let br = min(bottomRadius, r.height / 2, r.width / 2)
        if ear > 0 {
            let L = r.minX + ear, R = r.maxX - ear
            p.move(to: CGPoint(x: r.minX, y: r.minY))
            p.addLine(to: CGPoint(x: r.maxX, y: r.minY))
            p.addQuadCurve(to: CGPoint(x: R, y: r.minY + ear), control: CGPoint(x: R, y: r.minY))
            p.addLine(to: CGPoint(x: R, y: r.maxY - br))
            p.addArc(tangent1End: CGPoint(x: R, y: r.maxY), tangent2End: CGPoint(x: R - br, y: r.maxY), radius: br)
            p.addLine(to: CGPoint(x: L + br, y: r.maxY))
            p.addArc(tangent1End: CGPoint(x: L, y: r.maxY), tangent2End: CGPoint(x: L, y: r.maxY - br), radius: br)
            p.addLine(to: CGPoint(x: L, y: r.minY + ear))
            p.addQuadCurve(to: CGPoint(x: r.minX, y: r.minY), control: CGPoint(x: L, y: r.minY))
            p.closeSubpath()
        } else {
            p.addRoundedRect(in: r, cornerSize: CGSize(width: br, height: br), style: .continuous)
        }
        return p
    }
}

/// A single circle: minimal size, and every extra session.
struct MinimalCircle: View {
    var s: Session
    var d: CGFloat
    var badge: String?

    var body: some View {
        StepDot(phase: s.phase, number: s.hasSteps ? s.step : nil, size: d - 11, badge: badge)
            .frame(width: d, height: d)
            .background(Circle().fill(Theme.surface))
            .contentShape(Circle())
    }
}

// MARK: compact

struct CompactBody: View {
    var s: Session
    var geo: IslandGeometry
    var wing: CGFloat
    var badge: String?

    var body: some View {
        if geo.hasNotch {
            HStack(spacing: 0) {
                left.frame(width: wing, alignment: .leading).padding(.leading, 12)
                Spacer(minLength: geo.notchWidth - 24)
                right.frame(width: wing, alignment: .trailing).padding(.trailing, 14)
            }
            .frame(height: geo.barHeight)
        } else {
            HStack(spacing: 8) {
                left
                Spacer(minLength: 8)
                right
            }
            .padding(.horizontal, 12)
            .frame(height: 38)
        }
    }

    private var left: some View {
        HStack(spacing: 7) {
            StepDot(phase: s.phase, number: s.hasSteps ? s.step : nil, size: geo.hasNotch ? min(geo.barHeight - 13, 20) : 22,
                    badge: badge)
            Text(s.app ?? s.agent)
                .font(.system(size: 12.5, weight: .semibold))
                .foregroundStyle(Theme.text)
                .lineLimit(1)
        }
    }

    @ViewBuilder private var right: some View {
        if s.stopRequested && s.isLive {
            Label(L.stopping, systemImage: "stop.circle.fill").labelStyle(Tight(color: Theme.amber))
        } else {
            switch s.phase {
            case .waiting: Label(L.waiting, systemImage: "bell.fill").labelStyle(Tight(color: Theme.amber))
            case .done: Label(L.done, systemImage: "checkmark").labelStyle(Tight(color: Theme.green))
            case .failed: Label(L.failed, systemImage: "xmark").labelStyle(Tight(color: Theme.red))
            case .running:
                Text(s.detail ?? (s.hasSteps ? "\(s.step)/\(s.total)" : L.running))
                    .font(.system(size: 12.5, weight: .semibold).monospacedDigit())
                    .foregroundStyle(Theme.blueText)
                    .lineLimit(1)
                    .contentTransition(.numericText())
            }
        }
    }
}

private struct Tight: LabelStyle {
    var color: Color
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) {
            configuration.icon.font(.system(size: 10.5, weight: .bold))
            configuration.title.font(.system(size: 12, weight: .semibold)).lineLimit(1)
        }
        .foregroundStyle(color)
    }
}

struct CapsuleItem: Hashable {
    var text: String
    var dont: Bool
}

/// A directive on its own, under the closed island: while it is in force it never leaves the screen.
struct DirectiveCapsule: View {
    var item: CapsuleItem

    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: item.dont ? "hand.raised.fill" : "checkmark.circle.fill").font(.system(size: 12, weight: .bold))
            Text(item.text).font(.system(size: 13, weight: .semibold)).lineLimit(2).multilineTextAlignment(.center)
        }
        .foregroundStyle(item.dont ? Theme.dontText : Theme.doText)
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .background(Capsule(style: .continuous).fill(item.dont ? Theme.dontFill : Theme.doFill))
        .overlay(Capsule(style: .continuous).stroke(Color.white.opacity(0.08), lineWidth: 1))
        .shadow(color: .black.opacity(0.35), radius: 6, y: 2)
        .fixedSize(horizontal: false, vertical: true)
    }
}

// MARK: expanded: the tasks one under another

struct ExpandedBody: View {
    var s: Session
    @ObservedObject var store: PresenceStore
    var geo: IslandGeometry
    var badge: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if geo.hasNotch {
                HStack(spacing: 0) {
                    who.frame(maxWidth: .infinity, alignment: .leading)
                    Spacer(minLength: geo.notchWidth + 8)
                    where_.frame(maxWidth: .infinity, alignment: .trailing)
                }
                .frame(height: geo.barHeight)
            } else {
                HStack { who; Spacer(); where_ }.padding(.top, 12)
            }

            // Without a plan the single task row says it all; with one, the title heads the list.
            if s.hasSteps {
            HStack(alignment: .center, spacing: 12) {
                StepDot(phase: s.phase, number: s.hasSteps ? s.step : nil, size: 32, badge: badge)
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.title.isEmpty ? (s.app ?? s.agent) : s.title)
                        .font(.system(size: 14.5, weight: .semibold))
                        .foregroundStyle(Theme.text)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                    subtitle
                }
                Spacer(minLength: 0)
            }
            }

            TaskList(s: s)

            buttons
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 14)
    }

    private var who: some View {
        HStack(spacing: 5) {
            if let badge {
                Image(systemName: badge == "phone" ? "smartphone" : "laptopcomputer").font(.system(size: 10.5, weight: .semibold))
            }
            // The device icon says where; the narrow band beside the notch keeps just who and what.
            Text(([s.agent, s.app] as [String?]).compactMap { $0 }.joined(separator: " · "))
                .lineLimit(1)
                .minimumScaleFactor(0.85)
                .truncationMode(.tail)
        }
        .font(.system(size: 12, weight: .semibold))
        .foregroundStyle(Theme.secondary)
    }

    private var where_: some View {
        Group {
            switch s.phase {
            case .done: Text(L.done).foregroundStyle(Theme.green)
            case .failed: Text(L.failed).foregroundStyle(Theme.red)
            case .waiting: Text(L.waiting).foregroundStyle(Theme.amber)
            case .running:
                Text(s.hasSteps ? L.stepOf(s.step, s.total) : L.running).foregroundStyle(Theme.blueText)
            }
        }
        .font(.system(size: 12, weight: .semibold).monospacedDigit())
        .lineLimit(1)
    }

    /// Under the title: only what belongs to the whole task (the attention/fail text sits under its own task row).
    @ViewBuilder private var subtitle: some View {
        let line: (String, Color)? = {
            if s.stopRequested && s.isLive { return (L.stopping, Theme.amber) }
            switch s.phase {
            case .done: return (s.message ?? L.done, Theme.green)
            case .running: return s.detail.map { ($0, Theme.secondary) }
            case .waiting, .failed: return s.hasSteps ? nil : s.message.map { ($0, Theme.accent(s.phase)) }
            }
        }()
        if let line {
            Text(line.0)
                .font(.system(size: 12.5, weight: .medium))
                .foregroundStyle(line.1)
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    @ViewBuilder private var buttons: some View {
        HStack(spacing: 8) {
            Spacer()
            switch s.phase {
            case .done, .failed:
                PillButton(title: L.close, tint: .white.opacity(0.14), text: .white) { store.close(s.id) }
            case .waiting:
                if !s.stopRequested {
                    PillButton(title: L.stop, tint: Theme.dontFill, text: Theme.dontText) { store.stop(s.id) }
                }
                PillButton(title: L.ok, tint: Theme.amber, text: .black) { store.minimize() }
            case .running:
                if !s.stopRequested {
                    PillButton(title: L.stop, tint: Theme.dontFill, text: Theme.dontText) { store.stop(s.id) }
                }
                if nowMillis() - s.updated > 10 * 60_000 {
                    PillButton(title: L.close, tint: .white.opacity(0.14), text: .white) { store.close(s.id) }
                }
                PillButton(title: L.minimize, tint: .white.opacity(0.14), text: .white) { store.minimize() }
            }
        }
    }
}

/// Every task with its own state, one under another (at most seven rows around the current one).
struct TaskList: View {
    var s: Session

    private enum RowState { case done, active, next, waiting, failed, skipped }

    private func state(_ i: Int) -> RowState {
        if s.phase == .done || i < s.step { return .done }
        if i > s.step { return s.phase == .failed ? .skipped : .next }
        switch s.phase {
        case .waiting: return .waiting
        case .failed: return .failed
        default: return .active
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if s.hasSteps {
                let lo = max(1, min(s.step - 3, s.total - 6))
                let hi = min(s.total, lo + 6)
                if lo > 1 { more(L.tr ? "\(lo - 1) görev bitti" : "\(lo - 1) done") }
                ForEach(lo...hi, id: \.self) { i in
                    row(number: i, title: s.steps[i - 1].title, state: state(i))
                }
                if hi < s.total { more(L.tr ? "+\(s.total - hi) görev sırada" : "+\(s.total - hi) more") }
            } else {
                let st: RowState = switch s.phase {
                case .running: .active
                case .waiting: .waiting
                case .failed: .failed
                case .done: .done
                }
                row(number: nil, title: s.title.isEmpty ? (s.app ?? s.agent) : s.title, state: st)
            }
        }
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.white.opacity(0.05)))
    }

    private func more(_ t: String) -> some View {
        Text(t)
            .font(.system(size: 11, weight: .medium))
            .foregroundStyle(Theme.pendingText)
            .padding(.leading, 40)
            .padding(.vertical, 3)
    }

    @ViewBuilder private func row(number: Int?, title: String, state: RowState) -> some View {
        let current = state == .active || state == .waiting || state == .failed || (number == nil && state == .done)
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 10) {
                mark(number, state).frame(width: 20, height: 20)
                Text(title)
                    .font(.system(size: 13, weight: current ? .semibold : .regular))
                    .foregroundStyle(state == .next || state == .skipped ? Theme.secondary : (current ? Theme.text : Theme.text.opacity(0.75)))
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer(minLength: 8)
                word(state)
            }
            if let m = s.message, current || (!s.hasSteps && s.phase == .done),
               s.phase == .waiting || s.phase == .failed || (!s.hasSteps && s.phase == .done) {
                Text(m)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Theme.accent(s.phase))
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, 30)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(current ? Color.white.opacity(0.07) : .clear))
        .padding(.horizontal, 4)
    }

    @ViewBuilder private func mark(_ n: Int?, _ st: RowState) -> some View {
        switch st {
        case .done:
            CheckShape()
                .stroke(Color.black.opacity(0.85), style: StrokeStyle(lineWidth: 1.9, lineCap: .round, lineJoin: .round))
                .frame(width: 18, height: 18)
                .background(Circle().fill(Theme.green))
        case .active, .waiting, .failed:
            StepDot(phase: st == .active ? .running : (st == .waiting ? .waiting : .failed), number: n, size: 16)
        case .next, .skipped:
            Group {
                if let n { Text("\(n)").font(.system(size: 10, weight: .semibold, design: .rounded)) } else { Text("") }
            }
            .foregroundStyle(Theme.pendingText)
            .frame(width: 18, height: 18)
            .background(Circle().fill(Theme.pendingFill))
            .opacity(st == .skipped ? 0.5 : 1)
        }
    }

    @ViewBuilder private func word(_ st: RowState) -> some View {
        let (t, c): (String, Color) = switch st {
        case .done: (L.done, Theme.green)
        case .active: s.stopRequested ? (L.stopping, Theme.amber) : (L.running, Theme.blueText)
        case .next: (L.pending, Theme.pendingText)
        case .waiting: (L.waiting, Theme.amber)
        case .failed: (L.failed, Theme.red)
        case .skipped: ("", .clear)
        }
        Text(t).font(.system(size: 11.5, weight: .semibold)).foregroundStyle(c).lineLimit(1)
    }
}

struct PillButton: View {
    var title: String
    var tint: Color
    var text: Color
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(text)
                .padding(.horizontal, 13)
                .padding(.vertical, 6)
                .background(Capsule(style: .continuous).fill(tint))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}
