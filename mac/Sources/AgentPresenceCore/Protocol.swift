import Foundation

/// One change in what an agent is doing on a device. See SPEC.md §1: the same JSON goes to the Mac app and the Android host.
public struct PresenceEvent: Codable, Equatable {
    public enum Kind: String, Codable, CaseIterable {
        case start, intervene, step, detail, directive, attention, resume, done, fail, end, expand, collapse
    }

    public var v: Int = 1
    public var id: String
    public var type: Kind
    public var agent: String?
    public var app: String?
    public var device: String?
    public var title: String?
    public var steps: [Step]?
    public var step: Int?
    public var detail: String?
    public var dont: [String]?
    public var dos: [String]?
    public var message: String?
    public var ts: Double?

    enum CodingKeys: String, CodingKey {
        case v, id, type, agent, app, device, title, steps, step, detail, dont, message, ts
        case dos = "do"
    }

    public init(id: String, type: Kind) {
        self.id = id
        self.type = type
    }

    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        v = (try? c.decodeIfPresent(Int.self, forKey: .v)) ?? 1
        id = try c.decode(String.self, forKey: .id)
        type = try c.decode(Kind.self, forKey: .type)
        agent = try? c.decodeIfPresent(String.self, forKey: .agent)
        app = try? c.decodeIfPresent(String.self, forKey: .app)
        device = try? c.decodeIfPresent(String.self, forKey: .device)
        title = try? c.decodeIfPresent(String.self, forKey: .title)
        steps = try? c.decodeIfPresent([Step].self, forKey: .steps)
        step = try? c.decodeIfPresent(Int.self, forKey: .step)
        detail = try? c.decodeIfPresent(String.self, forKey: .detail)
        dont = Self.list(c, .dont)
        dos = Self.list(c, .dos)
        message = try? c.decodeIfPresent(String.self, forKey: .message)
        ts = try? c.decodeIfPresent(Double.self, forKey: .ts)
    }

    /// `"dont": "x"` is accepted as `["x"]`.
    private static func list(_ c: KeyedDecodingContainer<CodingKeys>, _ k: CodingKeys) -> [String]? {
        if let a = try? c.decodeIfPresent([String].self, forKey: k) { return a }
        if let s = try? c.decodeIfPresent(String.self, forKey: k) { return [s] }
        return nil
    }

    /// One event or a JSON array of events.
    public static func decodeMany(_ data: Data) throws -> [PresenceEvent] {
        let dec = JSONDecoder()
        if let one = try? dec.decode(PresenceEvent.self, from: data) { return [one] }
        return try dec.decode([PresenceEvent].self, from: data)
    }

    public func json() -> String {
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return String(decoding: (try? enc.encode(self)) ?? Data(), as: UTF8.self)
    }
}

public enum Phase: String, Codable {
    case running, waiting, done, failed
}

/// One task of the plan: a label, plus what the agent itself drives during it (SPEC.md §1 "steps").
/// In JSON either a plain string or `{"title", "uses": ["keyboard","mouse","touch"], "dont": [...]}`.
public struct Step: Codable, Equatable, ExpressibleByStringLiteral {
    public enum Input: String, Codable, CaseIterable { case keyboard, mouse, touch }

    public var title: String
    public var uses: [Input]
    public var dont: [String]

    public init(_ title: String, uses: [Input] = [], dont: [String] = []) {
        self.title = title
        self.uses = uses
        self.dont = dont
    }

    public init(stringLiteral value: String) { self.init(value) }

    enum Keys: String, CodingKey { case title, uses, dont }

    public init(from d: Decoder) throws {
        if let one = try? d.singleValueContainer().decode(String.self) {
            self.init(one)
            return
        }
        let c = try d.container(keyedBy: Keys.self)
        let raw = (try? c.decodeIfPresent([String].self, forKey: .uses)) ?? []
        var dont: [String] = []
        if let a = try? c.decodeIfPresent([String].self, forKey: .dont) { dont = a.filter { !$0.isEmpty } }
        else if let one = try? c.decodeIfPresent(String.self, forKey: .dont), !one.isEmpty { dont = [one] }
        self.init((try? c.decode(String.self, forKey: .title)) ?? "",
                  uses: raw.compactMap { Input(rawValue: $0.lowercased()) }, dont: dont)
    }

    public func encode(to e: Encoder) throws {
        if uses.isEmpty && dont.isEmpty {
            var c = e.singleValueContainer()
            try c.encode(title)
            return
        }
        var c = e.container(keyedBy: Keys.self)
        try c.encode(title, forKey: .title)
        if !uses.isEmpty { try c.encode(uses, forKey: .uses) }
        if !dont.isEmpty { try c.encode(dont, forKey: .dont) }
    }
}

/// Something the owner should (not) do right now, shown only in the closed sizes.
public struct Directive: Equatable, Hashable {
    public enum Kind { case dont, doThis }
    public var text: String
    public var kind: Kind
}

/// What one agent task looks like right now.
public struct Session: Codable, Equatable, Identifiable {
    public var id: String
    public var agent = "Claude"
    public var app: String?
    public var device = "mac"
    public var title = ""
    public var steps: [Step] = []
    /// 1-based. With no steps it still counts, but the circle shows no number.
    public var step = 1
    public var detail: String?
    public var dont: [String] = []
    public var dos: [String] = []
    public var message: String?
    public var phase = Phase.running
    public var stopRequested = false
    /// The owner has seen the attention request (tapped it), so it may shrink.
    public var acknowledged = false
    public var started: Double
    public var updated: Double

    enum CodingKeys: String, CodingKey {
        case id, agent, app, device, title, steps, step, detail, dont, message, phase, stopRequested, acknowledged, started, updated
        case dos = "do"
    }

    public init(id: String, now: Double) {
        self.id = id
        started = now
        updated = now
    }

    public var total: Int { steps.count }
    public var hasSteps: Bool { !steps.isEmpty }
    /// Finished steps (all of them once done).
    public var completed: Int { phase == .done ? total : max(0, min(step - 1, total)) }
    public var currentLabel: String? { hasSteps && step >= 1 && step <= total ? steps[step - 1].title : nil }

    /// Steps not finished yet: the current one and the ones after it.
    public var remaining: ArraySlice<Step> {
        guard hasSteps else { return [] }
        return steps[min(max(step, 1), total) - 1 ..< total]
    }

    /// What the owner must (not) do now (SPEC.md "Directives on screen"). If even one step still to come drives the
    /// keyboard or the mouse, the warning is on. Nothing while the agent waits for the owner (they must act) or after it.
    public func directives(tr: Bool) -> [Directive] {
        guard phase == .running else { return [] }
        let uses = Set(remaining.flatMap(\.uses))
        var out: [Directive] = []
        func add(_ t: String, _ k: Directive.Kind = .dont) {
            if !t.isEmpty && !out.contains(where: { $0.text == t }) { out.append(Directive(text: t, kind: k)) }
        }
        switch (uses.contains(.keyboard), uses.contains(.mouse)) {
        case (true, true): add(tr ? "Klavye ve fareye dokunma" : "Don't touch the keyboard or mouse")
        case (true, false): add(tr ? "Klavyeye dokunma" : "Don't type")
        case (false, true): add(tr ? "Fareye dokunma" : "Don't touch the mouse")
        default: break
        }
        if uses.contains(.touch) { add(tr ? "Ekrana dokunma" : "Don't touch the screen") }
        remaining.flatMap(\.dont).forEach { add($0) }
        dont.forEach { add($0) }
        dos.forEach { add($0, .doThis) }
        return out
    }

    /// Some directive is in force: the island must not shrink below compact.
    public var handsOff: Bool { !directives(tr: false).isEmpty }

    /// Directives belong to the device being driven. A mirror on the other device shows the progress, never the
    /// "don't touch" (owner, 2026-11-24: a phone task must not tell him to keep off the Mac). nil host = anywhere.
    public func handsOff(on host: String?) -> Bool { (host == nil || device == host) && handsOff }

    public func directives(tr: Bool, on host: String?) -> [Directive] {
        host == nil || device == host ? directives(tr: tr) : []
    }
    public var isLive: Bool { phase == .running || phase == .waiting }
}

/// What the screen should do after an event.
public enum Cue: Equatable {
    /// Open up for a few seconds: something the owner must know about.
    case announce
    /// A small change: show it briefly at compact size.
    case flash
    case none
    case removed
    case expand
    case collapse
}

public enum Reducer {
    /// Applies one event (SPEC.md §1). Sessions are kept oldest → newest by last start/intervene.
    @discardableResult
    public static func apply(_ e: PresenceEvent, to sessions: inout [Session], now: Double) -> Cue {
        if e.type == .end {
            sessions.removeAll { $0.id == e.id }
            return .removed
        }
        if e.type == .start {
            // A new task takes the place of finished ones (the owner has seen them, or no longer needs to).
            sessions.removeAll { $0.id == e.id || $0.phase == .done || $0.phase == .failed }
        }
        var i = sessions.firstIndex { $0.id == e.id }
        var wasNew = false
        if i == nil {
            if e.type == .expand || e.type == .collapse { return .none }
            sessions.append(Session(id: e.id, now: now))
            i = sessions.count - 1
            wasNew = true
        }
        var s = sessions[i!]
        let oldDont = s.dont, oldDo = s.dos, oldStep = s.step

        if let a = e.agent, !a.isEmpty { s.agent = a }
        if let a = e.app { s.app = a.isEmpty ? nil : a }
        if let d = e.device, !d.isEmpty { s.device = d }
        if let t = e.title { s.title = t }
        if let st = e.steps { s.steps = st }
        if let d = e.detail { s.detail = d.isEmpty ? nil : d }
        if let m = e.message { s.message = m.isEmpty ? nil : m }
        if let d = e.dont { s.dont = d.filter { !$0.isEmpty } }
        if let d = e.dos { s.dos = d.filter { !$0.isEmpty } }
        s.updated = now

        var cue = Cue.none
        switch e.type {
        case .start:
            s.step = max(1, e.step ?? 1)
            s.phase = .running
            cue = .announce
        case .intervene:
            if let n = e.step { s.step = max(1, n) }
            s.phase = .running
            s.acknowledged = false
            cue = .announce
        case .step:
            s.step = max(1, e.step ?? (oldStep + 1))
            if s.hasSteps { s.step = min(s.step, s.total) }
            if s.phase != .running { s.phase = .running }
            cue = wasNew ? .announce : .flash
        case .detail:
            cue = wasNew ? .announce : .none
        case .directive:
            // Directives live in the compact capsule, so a change shows there (never in expanded).
            cue = wasNew ? .announce : (s.dont != oldDont || s.dos != oldDo ? .flash : .none)
        case .attention:
            s.phase = .waiting
            s.acknowledged = false
            cue = .announce
        case .resume:
            s.phase = .running
            cue = .flash
        case .done:
            s.phase = .done
            if s.hasSteps { s.step = s.total }
            s.dont = []
            if e.dos == nil { s.dos = [] }
            cue = .announce
        case .fail:
            s.phase = .failed
            s.dont = []
            if e.dos == nil { s.dos = [] }
            cue = .announce
        case .expand:
            cue = .expand
        case .collapse:
            cue = .collapse
        case .end:
            break
        }
        // A new start/intervene puts the session last (it is the one being looked at).
        sessions[i!] = s
        if e.type == .start || e.type == .intervene || e.type == .attention, i! != sessions.count - 1 {
            sessions.append(sessions.remove(at: i!))
        }
        return cue
    }

    /// The session the big island shows: one that needs the owner, else a live one with a "don't" (its directive must
    /// stay on screen), else the newest live one, else the newest.
    public static func primary(_ sessions: [Session], host: String? = nil) -> Session? {
        if let w = sessions.last(where: { $0.phase == .waiting && !$0.acknowledged }) { return w }
        return sessions.max {
            ($0.isLive ? 1 : 0, $0.handsOff(on: host) ? 1 : 0, $0.updated) < ($1.isLive ? 1 : 0, $1.handsOff(on: host) ? 1 : 0, $1.updated)
        }
    }
}

/// What a host reports back to the agent (SPEC.md §1, "State the host reports back").
public struct PresenceState: Codable {
    public struct Entry: Codable {
        public var id: String
        public var phase: Phase
        public var step: Int
        public var total: Int
        public var stopRequested: Bool
        public var acknowledged: Bool
        public var updated: Double
    }

    public var v = 1
    public var sessions: [Entry]
    /// `ts` of the last event the host applied: lets a sender wait until its event has landed.
    public var lastTs: Double?
    /// Full sessions (Mac host only), so a restarted app can put them back on screen.
    public var full: [Session]?

    public init(_ sessions: [Session], lastTs: Double?, full: Bool) {
        self.sessions = sessions.map {
            Entry(id: $0.id, phase: $0.phase, step: $0.step, total: $0.total, stopRequested: $0.stopRequested,
                  acknowledged: $0.acknowledged, updated: $0.updated)
        }
        self.lastTs = lastTs
        self.full = full ? sessions : nil
    }
}

public enum PresencePaths {
    public static let bundleID = "dev.deniz.agentpresence"
    public static let notification = "dev.deniz.agentpresence.event"

    public static var support: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("AgentPresence", isDirectory: true)
    }

    public static var state: URL { support.appendingPathComponent("state.json") }
    public static var ready: URL { support.appendingPathComponent("ready") }
    public static var cli: URL { support.appendingPathComponent("cli.json") }
    public static var log: URL { support.appendingPathComponent("events.log") }
}

public func nowMillis() -> Double { (Date().timeIntervalSince1970 * 1000).rounded() }
