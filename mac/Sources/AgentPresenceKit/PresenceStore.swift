import AgentPresenceCore
import AppKit
import SwiftUI

/// Holds the sessions and decides how big the island is (SPEC.md §2, Mac column). Main thread only.
@MainActor
public final class PresenceStore: ObservableObject {
    /// `warning`: the card that tells the owner what to (not) do, always before any process info (SPEC §2).
    public enum Mode: Equatable { case minimal, compact, expanded, warning }

    /// Why the island is expanded: an announcement is click-through while the agent drives the mouse;
    /// one the owner opened (or one that waits for them) takes clicks.
    public enum Reason: Equatable { case announce, user, sticky }

    public struct Timing {
        /// macOS keeps a banner on screen about this long.
        public var announce: TimeInterval = 5
        public var flash: TimeInterval = 3
        /// The warning card: long enough to read one or two short lines.
        public var warning: TimeInterval = 3
        /// The owner opened it: shrink again after this long without the pointer on it.
        public var userOpen: TimeInterval = 8
        public init() {}
    }

    @Published public private(set) var sessions: [Session] = []
    @Published public private(set) var mode: Mode = .minimal
    /// A session on this device is hidden (SPEC `hide`): no written parts, red screen edges instead.
    @Published public private(set) var hidden = false
    @Published public private(set) var reason: Reason = .announce
    @Published private var focusID: String?

    public var timing = Timing()
    /// The device this store draws on: only its own sessions' directives are shown here.
    public var host = "mac"
    public var sounds = true
    /// After every change (persist it, report it back).
    public var onChange: (([Session]) -> Void)?

    /// The pointer is over the island (set by the panel): it does not shrink under the owner's eyes.
    public var hovering = false {
        didSet {
            if oldValue && !hovering, revertDue { scheduleRevert(after: 1.5) }
        }
    }

    private var revert: DispatchWorkItem?
    private var revertDue = false

    private var watchdog: Timer?

    public init() {
        // SPEC "Never left blocked": a running session that goes quiet past its ttl is finished here, so the owner
        // is told it is over even when the agent died or the link dropped.
        let t = Timer(timeInterval: 5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.expireQuietSessions() }
        }
        RunLoop.main.add(t, forMode: .common)
        watchdog = t
    }

    private func expireQuietSessions() {
        let now = nowMillis()
        for s in sessions where s.hiddenUntil != nil && !s.hidden(now: now) {
            mutate(s.id) { $0.hiddenUntil = nil }
        }
        refreshHidden()
        for s in sessions where s.expired(now: now) {
            var e = PresenceEvent(id: s.id, type: .done)
            e.ts = now
            e.message = L.tr ? "Bitti, artık kullanabilirsin" : "Done, you can use it now"
            handle(e)
        }
    }

    public var primary: Session? {
        if let f = focusID, let s = sessions.first(where: { $0.id == f }) {
            // An unseen attention request elsewhere still wins.
            if let w = sessions.last(where: { $0.phase == .waiting && !$0.acknowledged }), w.id != s.id { return w }
            return s
        }
        return Reducer.primary(sessions, host: host)
    }

    /// Every other session, each as its own small circle (newest first).
    public var others: [Session] {
        let p = primary?.id
        return sessions.filter { $0.id != p }.sorted { $0.updated > $1.updated }
    }

    /// Clicks reach the island (otherwise they fall through to the app under it).
    public var interactive: Bool {
        guard let p = primary else { return false }
        if mode == .warning { return !(p.phase == .running && p.handsOff(on: host)) }
        if mode != .expanded { return true }
        return !(reason == .announce && p.phase == .running && p.handsOff(on: host))
    }

    // MARK: events

    public func handle(_ e: PresenceEvent) {
        let before = primary?.id
        let warnedBefore = localDirectives(e.id)
        let cue = Reducer.apply(e, to: &sessions, now: nowMillis())
        // What to (not) do comes before anything about the process: a directive this device did not show a moment
        // ago opens the warning card first; the task list (start / intervene) or the resting size follows.
        let warnNow = localDirectives(e.id)
        if !warnNow.isEmpty, warnNow != warnedBefore {
            focusID = e.id
            afterWarning = e.type == .start || e.type == .intervene ? .tasks : .rest
            show(.warning, .announce, for: timing.warning)
            refreshHidden()
            onChange?(sessions)
            return
        }
        switch cue {
        case .announce:
            focusID = e.id
            let s = sessions.first { $0.id == e.id }
            if s?.phase == .waiting { show(.expanded, .sticky, for: nil) } else { show(.expanded, .announce, for: timing.announce) }
            sound(for: e.type)
        case .flash:
            guard primary?.id == e.id else { break }
            if mode == .expanded || mode == .warning { break }
            show(.compact, .announce, for: timing.flash)
        case .removed:
            if focusID == e.id { focusID = nil }
            settle()
        case .expand:
            focusID = e.id
            show(.expanded, .user, for: timing.userOpen)
        case .collapse:
            settle()
        case .none:
            if primary?.id != before { settle() }
        }
        refreshHidden()
        onChange?(sessions)
    }

    private func refreshHidden() {
        let now = nowMillis()
        let h = sessions.contains { $0.device == host && $0.hidden(now: now) }
        if h != hidden { hidden = h }
    }

    /// Puts sessions back after a restart (no announcement).
    public func restore(_ saved: [Session]) {
        sessions = saved
        settle()
    }

    // MARK: owner actions

    /// Tap on the island (not on a button).
    public func tapIsland() {
        guard let p = primary else { return }
        // Something waits for the owner and says where: a tap takes them straight there (choices stay on the card).
        if p.phase == .waiting, p.open != nil, p.choices.isEmpty {
            openTarget(p)
            settle()
            return
        }
        if mode == .warning {
            afterWarning = nil
            show(.expanded, .user, for: timing.userOpen)
        } else if mode == .expanded {
            acknowledge(p.id)
            settle()
        } else {
            show(.expanded, .user, for: timing.userOpen)
        }
    }

    public func tapOther(_ id: String) {
        focusID = id
        show(.expanded, .user, for: timing.userOpen)
    }

    public func stop(_ id: String) {
        mutate(id) { $0.stopRequested = true; $0.acknowledged = true }
        show(.expanded, .user, for: 2.5)
    }

    /// The owner pressed a choice on the attention card: it is reported back in the state (`choice`) for the agent
    /// waiting on it; "open" also opens the session's target.
    public func choose(_ id: String, _ choice: String) {
        if choice == "open", let s = sessions.first(where: { $0.id == id }) { openTarget(s) }
        mutate(id) { $0.choice = choice; $0.acknowledged = true }
    }

    /// Opens what the session points at: a URL of any scheme, a file or folder, or an app by name / bundle id.
    public func openTarget(_ s: Session) {
        guard let t = s.open else { return }
        let ws = NSWorkspace.shared
        if t.hasPrefix("/") || t.hasPrefix("~") {
            ws.open(URL(fileURLWithPath: (t as NSString).expandingTildeInPath))
        } else if let u = URL(string: t), u.scheme != nil, !(u.scheme ?? "").contains(".") {
            ws.open(u)
        } else if let app = ws.urlForApplication(withBundleIdentifier: t) {
            ws.openApplication(at: app, configuration: NSWorkspace.OpenConfiguration())
        }
        mutate(s.id) { $0.acknowledged = true }
    }

    public func close(_ id: String) {
        sessions.removeAll { $0.id == id }
        if focusID == id { focusID = nil }
        settle()
        onChange?(sessions)
    }

    public func minimize() {
        if let p = primary { acknowledge(p.id) }
        settle()
    }

    private func acknowledge(_ id: String) {
        mutate(id) { if $0.phase == .waiting { $0.acknowledged = true } }
    }

    private func mutate(_ id: String, _ f: (inout Session) -> Void) {
        guard let i = sessions.firstIndex(where: { $0.id == id }) else { return }
        var s = sessions[i]
        f(&s)
        guard s != sessions[i] else { return }
        sessions[i] = s
        onChange?(sessions)
    }

    // MARK: sizes

    /// The size the island rests at when nothing new is happening.
    public func idleMode(_ s: Session) -> Mode {
        switch s.phase {
        case .waiting: return s.acknowledged ? .compact : .expanded
        case .running: return s.handsOff(on: host) ? .compact : .minimal
        case .done, .failed: return .minimal
        }
    }

    private func settle() {
        revert?.cancel()
        revertDue = false
        afterWarning = nil
        // At rest a session whose "don't" is active takes the big island back, so its directive stays readable.
        if let f = focusID, let s = sessions.first(where: { $0.id == f }), !s.handsOff(on: host),
           sessions.contains(where: { $0.handsOff(on: host) && $0.id != f }) {
            focusID = nil
        }
        guard let p = primary else { return }
        let m = idleMode(p)
        withAnimation(.spring(response: 0.42, dampingFraction: 0.82)) {
            mode = m
            reason = m == .expanded ? .sticky : .announce
        }
    }

    private func show(_ m: Mode, _ r: Reason, for seconds: TimeInterval?) {
        withAnimation(.spring(response: 0.42, dampingFraction: 0.8)) {
            mode = m
            reason = r
        }
        revert?.cancel()
        revertDue = false
        if let seconds { scheduleRevert(after: seconds) }
    }

    private func scheduleRevert(after seconds: TimeInterval) {
        revert?.cancel()
        revertDue = true
        let w = DispatchWorkItem { [weak self] in
            guard let self else { return }
            if self.hovering && self.interactive { return } // mouse leaving reschedules
            if self.mode == .warning, let next = self.afterWarning {
                self.afterWarning = nil
                if next == .tasks {
                    self.show(.expanded, .announce, for: self.timing.announce)
                    return
                }
            }
            self.settle()
        }
        revert = w
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: w)
    }

    private enum AfterWarning { case tasks, rest }
    private var afterWarning: AfterWarning?

    /// The directives this device shows for a session right now (empty for a mirror, while waiting, when over).
    private func localDirectives(_ id: String) -> [String] {
        sessions.first { $0.id == id }.map { $0.directives(tr: L.tr, on: host).map(\.text) } ?? []
    }

    private func sound(for kind: PresenceEvent.Kind) {
        guard sounds else { return }
        let name: String? = switch kind {
        case .attention: "Ping"
        case .done: "Glass"
        case .fail: "Basso"
        default: nil
        }
        if let name { NSSound(named: NSSound.Name(name))?.play() }
    }
}
