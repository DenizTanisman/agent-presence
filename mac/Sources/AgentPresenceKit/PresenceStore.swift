import AgentPresenceCore
import AppKit
import SwiftUI

/// Holds the sessions and decides how big the island is (SPEC.md §2, Mac column). Main thread only.
@MainActor
public final class PresenceStore: ObservableObject {
    public enum Mode: Equatable { case minimal, compact, expanded }

    /// Why the island is expanded: an announcement is click-through while the agent drives the mouse;
    /// one the owner opened (or one that waits for them) takes clicks.
    public enum Reason: Equatable { case announce, user, sticky }

    public struct Timing {
        /// macOS keeps a banner on screen about this long.
        public var announce: TimeInterval = 5
        public var flash: TimeInterval = 3
        /// The owner opened it: shrink again after this long without the pointer on it.
        public var userOpen: TimeInterval = 8
        public init() {}
    }

    @Published public private(set) var sessions: [Session] = []
    @Published public private(set) var mode: Mode = .minimal
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

    public init() {}

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
        if mode != .expanded { return true }
        return !(reason == .announce && p.phase == .running && p.handsOff(on: host))
    }

    // MARK: events

    public func handle(_ e: PresenceEvent) {
        let before = primary?.id
        let cue = Reducer.apply(e, to: &sessions, now: nowMillis())
        switch cue {
        case .announce:
            focusID = e.id
            let s = sessions.first { $0.id == e.id }
            if s?.phase == .waiting { show(.expanded, .sticky, for: nil) } else { show(.expanded, .announce, for: timing.announce) }
            sound(for: e.type)
        case .flash:
            guard primary?.id == e.id else { break }
            if mode == .expanded { break }
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
        onChange?(sessions)
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
        if mode == .expanded {
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
            self.settle()
        }
        revert = w
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: w)
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
