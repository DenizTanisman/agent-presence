import AgentPresenceCore
import Foundation

// `presence`: what an agent calls while it drives a device. Shows the island on the Mac (AgentPresence.app) and, over
// adb, on an Android phone that has the Agent Presence host app. See SPEC.md and README.md.

let usage = """
presence: tell the owner what the agent is doing on their Mac or phone.

  presence start "<what>" [--app A] [--steps "a [klavye,fare]|b [fare]|c"] [--dont "x"]... [--do "y"]... [--on mac|phone]
      a step tagged [klavye], [fare] or [ekran] makes the island say "don't touch" until that step is finished
  presence intervene ["<what>"] [same options]   about to touch the device again (announces)
  presence step [N] [--detail D] [--title T]       step N is now active (default: next)
  presence detail "<D>"                            the one number/word on the right (3/10, %40)
  presence dont "<x>" ["<y>"...]                   what the owner must NOT do (replaces; announces)
  presence do "<x>" ["<y>"...]                     what the owner should do/know (replaces)
  presence clear                                   no directives
  presence wait "<why>"                            needs the owner (stays open, sound)
  presence resume                                  carries on after wait
  presence done ["<summary>"]                      finished (stays until the owner closes it)
  presence fail "<why>"                            gave up
  presence end                                     take it away now
  presence ask "<msg>" [--choice id=Label]... [--primary ID] [--destructive ID] [--open URL] [--timeout S]
                [--no-wait] [--keep]               ask the owner on the island; prints the picked id
  presence hide [--for S] | unhide                 agent only: warnings off the screen, edges glow red (default 30 s)
  presence expand | collapse | status [--json] | demo [--on mac|phone] [--fast]
  presence auto mac|phone [--tool NAME]            hook helper: "Claude is using your Mac/phone" until finish
  presence finish ["<summary>"]                    hook helper: mark this agent session's running ones done
  presence config capture on|off                   show the island in screenshots
  presence config sound on|off
  presence config language tr|en|auto             words on the island (auto: the Mac's language)
  presence send '<json>' [--to mac,phone]          raw SPEC.md events
  presence snapshot <file.png>                     draw what the Mac island shows now (no screen permission needed)

Options: --ttl SECONDS (start/intervene, default 120: the device finishes the task itself after that much silence),
--id ID (default: the last session this agent started), --agent NAME (default Claude), --on mac|phone (device
being driven; phone work is also shown on the Mac unless --no-mirror, Mac work stays on the Mac), --serial S
(adb device), --owner ID (agent session; default $CLAUDE_CODE_SESSION_ID).
Exit codes: 0 ok, 2 usage, 3 the owner pressed Stop (stop now), 4 no display could be reached.
"""

// MARK: arguments

struct Args {
    var command = ""
    var positional: [String] = []
    var values: [String: [String]] = [:]
    var flags: Set<String> = []

    static let boolFlags: Set<String> = ["no-mirror", "json", "quiet", "fast", "help", "no-wait", "keep"]
    static let aliases = ["t": "title", "a": "app", "s": "steps", "d": "detail", "h": "help"]

    init(_ argv: [String]) {
        var i = 0
        while i < argv.count {
            let a = argv[i]
            if a.hasPrefix("-") && a.count > 1 && Double(a) == nil {
                var name = String(a.drop(while: { $0 == "-" }))
                var inline: String?
                if let eq = name.firstIndex(of: "=") {
                    inline = String(name[name.index(after: eq)...])
                    name = String(name[..<eq])
                }
                name = Self.aliases[name] ?? name
                if Self.boolFlags.contains(name) {
                    flags.insert(name)
                } else if let v = inline ?? (i + 1 < argv.count ? argv[i + 1] : nil) {
                    values[name, default: []].append(v)
                    if inline == nil { i += 1 }
                }
            } else if command.isEmpty {
                command = a
            } else {
                positional.append(a)
            }
            i += 1
        }
    }

    func one(_ k: String) -> String? { values[k]?.last }
    func many(_ k: String) -> [String]? { values[k] }
    var text: String? { positional.isEmpty ? nil : positional.joined(separator: " ") }
}

func fail(_ msg: String, code: Int32 = 2) -> Never {
    FileHandle.standardError.write(Data("presence: \(msg)\n".utf8))
    exit(code)
}

// MARK: remembered session

/// Sessions this CLI started: where each one shows and which agent session owns it. "Current" is kept per owner, so
/// several Claude sessions running side by side never pick up each other's session.
struct Registry: Codable {
    struct Entry: Codable {
        var device: String
        /// First = the device being driven (must be reached); the rest are mirrors (skipped quietly when offline).
        var show: [String]
        var owner: String?
        var at: Double
    }

    var current: String?
    var currentByOwner: [String: String] = [:]
    var sessions: [String: Entry] = [:]

    static func load() -> Registry {
        guard let d = try? Data(contentsOf: PresencePaths.cli), let r = try? JSONDecoder().decode(Registry.self, from: d)
        else { return Registry() }
        return r
    }

    func save() {
        var r = self
        let cutoff = nowMillis() - 24 * 3600_000
        r.sessions = r.sessions.filter { $0.value.at > cutoff }
        r.currentByOwner = r.currentByOwner.filter { r.sessions[$0.value] != nil }
        try? FileManager.default.createDirectory(at: PresencePaths.support, withIntermediateDirectories: true)
        try? JSONEncoder().encode(r).write(to: PresencePaths.cli, options: .atomic)
    }

    func currentID(owner: String?) -> String? {
        if let owner, let id = currentByOwner[owner] { return id }
        return owner == nil ? current : nil
    }

    mutating func remember(_ id: String, _ e: Entry, makeCurrent: Bool = true) {
        sessions[id] = e
        guard makeCurrent else { return }
        if let o = e.owner { currentByOwner[o] = id } else { current = id }
    }
}

/// The agent session behind this call (Claude Code sets it for every tool command), or --owner.
func ownerID(_ a: Args) -> String? {
    a.one("owner") ?? ProcessInfo.processInfo.environment["CLAUDE_CODE_SESSION_ID"].flatMap { $0.isEmpty ? nil : $0 }
}

// MARK: Mac display

@discardableResult
func run(_ path: String, _ args: [String], timeout: TimeInterval = 10) -> (code: Int32, out: String) {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: path)
    p.arguments = args
    let pipe = Pipe()
    p.standardOutput = pipe
    p.standardError = pipe
    do { try p.run() } catch { return (127, "\(error)") }
    var data = Data()
    let reader = DispatchQueue(label: "read")
    let done = DispatchSemaphore(value: 0)
    reader.async {
        data = pipe.fileHandleForReading.readDataToEndOfFile()
        done.signal()
    }
    if done.wait(timeout: .now() + timeout) == .timedOut {
        p.terminate()
        return (124, "timed out")
    }
    p.waitUntilExit()
    return (p.terminationStatus, String(decoding: data, as: UTF8.self))
}

func macAlive() -> Bool {
    guard let s = try? String(contentsOf: PresencePaths.ready, encoding: .utf8),
          let pid = Int32(s.trimmingCharacters(in: .whitespacesAndNewlines)) else { return false }
    return kill(pid, 0) == 0
}

/// AgentPresence.app next to this binary (when `presence` runs from inside the bundle), else by bundle id.
func launchMac() -> Bool {
    let exe = URL(fileURLWithPath: CommandLine.arguments[0]).resolvingSymlinksInPath()
    var candidates: [[String]] = []
    if let r = exe.path.range(of: ".app/Contents/MacOS/") {
        candidates.append(["-g", String(exe.path[..<r.lowerBound]) + ".app"])
    }
    candidates.append(["-g", "-b", PresencePaths.bundleID])
    for args in candidates where run("/usr/bin/open", args).code == 0 {
        for _ in 0..<100 {
            if macAlive() { return true }
            usleep(50_000)
        }
    }
    return false
}

func readMacState() -> PresenceState? {
    guard let d = try? Data(contentsOf: PresencePaths.state) else { return nil }
    return try? JSONDecoder().decode(PresenceState.self, from: d)
}

func sendMac(_ events: [PresenceEvent]) -> PresenceState? {
    if !macAlive() && !launchMac() {
        if !quietErrors { FileHandle.standardError.write(Data("presence: AgentPresence.app could not be started\n".utf8)) }
        return nil
    }
    let data = try! JSONEncoder().encode(events)
    let json = String(decoding: data, as: UTF8.self)
    DistributedNotificationCenter.default().postNotificationName(
        Notification.Name(PresencePaths.notification), object: json, userInfo: nil, deliverImmediately: true)
    let want = events.compactMap(\.ts).max() ?? 0
    for _ in 0..<40 {
        if let st = readMacState(), (st.lastTs ?? 0) >= want { return st }
        usleep(40_000)
    }
    return readMacState()
}

// MARK: phone display (adb)

let receiver = "dev.deniz.agentpresence.host/.EventReceiver"
let action = "dev.deniz.agentpresence.EVENT"

func adbPath() -> String? {
    if let a = ProcessInfo.processInfo.environment["ADB"], FileManager.default.isExecutableFile(atPath: a) { return a }
    let home = FileManager.default.homeDirectoryForCurrentUser.path
    let paths = (ProcessInfo.processInfo.environment["PATH"] ?? "").split(separator: ":").map { "\($0)/adb" }
        + ["\(home)/Library/Android/sdk/platform-tools/adb", "/opt/homebrew/bin/adb", "/usr/local/bin/adb"]
    return paths.first { FileManager.default.isExecutableFile(atPath: $0) }
}

/// Phones to try, best first: an explicit serial, else every attached one with USB before Wi-Fi.
func phoneSerials(_ adb: String, _ explicit: String?) -> [String] {
    let env = ProcessInfo.processInfo.environment
    let attached = run(adb, ["devices"], timeout: 5).out.split(separator: "\n").compactMap { line -> String? in
        let parts = line.split(separator: "\t")
        return parts.count == 2 && parts[1] == "device" ? String(parts[0]) : nil
    }
    // A real phone over USB, then over Wi-Fi; an emulator only when nothing else is attached.
    let real = attached.filter { !$0.hasPrefix("emulator-") }
    let ordered = real.filter { !$0.contains(":") } + real.filter { $0.contains(":") }
        + attached.filter { $0.hasPrefix("emulator-") }
    if let s = explicit ?? env["AGENT_PRESENCE_SERIAL"] ?? env["ANDROID_SERIAL"] {
        // The chosen link first; the other links of the same phone are the fallback when it drops.
        return [s] + ordered.filter { $0 != s }
    }
    return ordered
}

/// Sends over adb and returns the host's state. A dropped link must not swallow an event (an undelivered `done`
/// would leave the phone saying "don't touch"): 3 tries, each over every attached link (USB, then Wi-Fi).
func sendPhone(_ events: [PresenceEvent], serial: String?) -> PresenceState? {
    guard let adb = adbPath() else {
        if !quietErrors { FileHandle.standardError.write(Data("presence: adb not found (set ADB=/path/to/adb)\n".utf8)) }
        return nil
    }
    let b64 = try! JSONEncoder().encode(events).base64EncodedString()
    var last = ""
    for attempt in 0..<3 {
        if attempt > 0 { usleep(700_000) }
        let serials = phoneSerials(adb, serial)
        if serials.isEmpty { last = "no phone over adb"; continue }
        for s in serials {
            // -f 0x20 (FLAG_INCLUDE_STOPPED_PACKAGES): also reaches the host right after install or a force-stop.
            let r = run(adb, ["-s", s, "shell", "am", "broadcast", "-f", "0x20", "-n", receiver, "-a", action, "--es", "b64", b64],
                        timeout: 8)
            if r.code == 0, let m = r.out.range(of: #"data="([^"]*)""#, options: .regularExpression),
               let d = Data(base64Encoded: String(r.out[m].dropFirst(6).dropLast())) {
                return try? JSONDecoder().decode(PresenceState.self, from: d)
            }
            last = "phone did not answer on \(s) (is Agent Presence installed?) \(r.out.prefix(160))"
        }
    }
    if !quietErrors { FileHandle.standardError.write(Data("presence: \(last)\n".utf8)) }
    return nil
}

// MARK: sending

/// Quiet while sending to a mirror: an unplugged phone is normal.
var quietErrors = false

/// Sends to every display the session shows on. The first one is the device being driven; the others are mirrors and
/// are skipped quietly when they are offline. Exits 3 when the owner asked to stop, 4 when the driven one was not reached.
func deliver(_ events: [PresenceEvent], to show: [String], args: Args, checkStop: Bool = true, allRequired: Bool = false) {
    var reachedRequired = false
    var stop = false
    let id = events.last?.id
    for (i, target) in show.enumerated() {
        let mirror = i > 0 && !allRequired
        quietErrors = mirror
        let st = target == "phone" ? sendPhone(events, serial: args.one("serial")) : sendMac(events)
        quietErrors = false
        guard let st else { continue }
        if !mirror { reachedRequired = true }
        if st.sessions.contains(where: { $0.id == id && $0.stopRequested }) { stop = true }
    }
    if !reachedRequired { fail("no display could be reached", code: 4) }
    if checkStop && stop {
        print("STOP: the owner pressed Stop. Stop driving the device now, then send `presence done` or `presence fail`.")
        exit(3)
    }
}

func event(_ type: PresenceEvent.Kind, _ id: String, _ a: Args) -> PresenceEvent {
    var e = PresenceEvent(id: id, type: type)
    e.ts = nowMillis()
    e.agent = a.one("agent")
    e.app = a.one("app")
    e.title = a.one("title")
    e.detail = a.one("detail")
    if let s = a.many("steps") { e.steps = s.flatMap { $0.split(separator: "|").map { parseStep(String($0)) } } }
    if let s = a.one("step").flatMap(Int.init) { e.step = s }
    e.dont = a.many("dont")
    e.dos = a.many("do")
    e.message = a.one("message")
    e.ttl = a.one("ttl").flatMap(Double.init)
    return e
}

/// `"Şarkıları ekle [klavye,fare]"` → a step that drives keyboard and mouse. Inside the brackets: klavye/keyboard,
/// fare/mouse, ekran/dokunma/touch/screen; anything else is a step-only "don't" text.
func parseStep(_ raw: String) -> Step {
    var text = raw.trimmingCharacters(in: .whitespaces)
    guard text.hasSuffix("]"), let open = text.lastIndex(of: "[") else { return Step(text) }
    let inside = text[text.index(after: open)..<text.index(before: text.endIndex)]
    text = text[..<open].trimmingCharacters(in: .whitespaces)
    var uses: [Step.Input] = []
    var dont: [String] = []
    for tok in inside.split(whereSeparator: { $0 == "," || $0 == "+" }).map({ $0.trimmingCharacters(in: .whitespaces) }) where !tok.isEmpty {
        switch tok.lowercased() {
        case "klavye", "keyboard", "keys": uses.append(.keyboard)
        case "fare", "mouse", "mause", "trackpad", "imleç": uses.append(.mouse)
        case "ekran", "dokunma", "touch", "screen": uses.append(.touch)
        default: dont.append(tok)
        }
    }
    return Step(text, uses: uses, dont: dont)
}

func isTurkish() -> Bool {
    (UserDefaults(suiteName: PresencePaths.bundleID)?.string(forKey: "language") ?? Locale.preferredLanguages.first ?? "")
        .hasPrefix("tr")
}

/// What a tool call does, in a word or two ("tıklıyor", "yazıyor"), for the compact right side.
func toolWord(_ tool: String?, device: String) -> String {
    let t = (tool ?? "").lowercased().components(separatedBy: "__").last ?? ""
    let tr = isTurkish()
    func w(_ a: String, _ b: String) -> String { tr ? a : b }
    if t.contains("screenshot") || t.contains("zoom") || t.contains("screencap") { return w("bakıyor", "looking") }
    if t.contains("drag") || t.contains("mouse_move") { return w("sürüklüyor", "dragging") }
    if t.contains("click") || t.contains("tap") { return device == "phone" ? w("dokunuyor", "tapping") : w("tıklıyor", "clicking") }
    if t.contains("type") || t.contains("text") { return w("yazıyor", "typing") }
    if t.contains("key") { return w("tuşa basıyor", "pressing keys") }
    if t.contains("scroll") || t.contains("swipe") { return w("kaydırıyor", "scrolling") }
    if t.contains("open") || t.contains("launch") || t.contains("start") { return w("uygulama açıyor", "opening an app") }
    return w("çalışıyor", "working")
}

/// Where a session shows (SPEC "Which device shows a session"): phone work also on the Mac (the owner sits there),
/// Mac work only on the Mac. --no-mirror keeps phone work on the phone.
func targets(device: String, a: Args) -> [String] {
    device == "phone" && !a.flags.contains("no-mirror") ? ["phone", "mac"] : [device]
}

// MARK: commands

let a = Args(Array(CommandLine.arguments.dropFirst()))
if a.command.isEmpty || a.command == "help" || a.flags.contains("help") {
    print(usage)
    exit(a.command.isEmpty ? 2 : 0)
}

func session() -> (id: String, show: [String]) {
    let reg = Registry.load()
    if let id = a.one("id") {
        if let on = a.one("on") { return (id, targets(device: on, a: a)) }
        return (id, reg.sessions[id]?.show ?? ["mac"])
    }
    guard let id = reg.currentID(owner: ownerID(a)) else {
        fail("no session yet: run `presence start` first (or pass --id)")
    }
    return (id, reg.sessions[id]?.show ?? ["mac"])
}

switch a.command {
case "start", "intervene":
    var reg = Registry.load()
    let mine = reg.currentID(owner: ownerID(a)).flatMap { reg.sessions[$0] }
    let device = a.one("on") ?? (a.command == "intervene" ? mine?.device : nil) ?? "mac"
    guard device == "mac" || device == "phone" else { fail("--on must be mac or phone") }
    let id: String
    let show: [String]
    if a.command == "start" {
        id = a.one("id") ?? "s-\(Int(nowMillis()))"
        show = targets(device: device, a: a)
    } else {
        let s = session()
        id = s.id
        show = a.one("on") != nil ? targets(device: device, a: a) : s.show
    }
    reg.remember(id, .init(device: device, show: show, owner: ownerID(a), at: nowMillis()))
    reg.save()
    var e = event(a.command == "start" ? .start : .intervene, id, a)
    if let t = a.text { e.title = t }
    e.device = device
    if a.command == "start" { e.agent = e.agent ?? "Claude" }
    // SPEC "Never left blocked": after 2 minutes without a word the device finishes the task itself.
    e.ttl = e.ttl ?? 120
    deliver([e], to: show, args: a)
    if !a.flags.contains("quiet") { print(id) }

case "step":
    let s = session()
    var e = event(.step, s.id, a)
    if let n = a.positional.first.flatMap(Int.init) { e.step = n }
    deliver([e], to: s.show, args: a)

case "detail":
    let s = session()
    var e = event(.detail, s.id, a)
    e.detail = a.text ?? e.detail ?? ""
    deliver([e], to: s.show, args: a)

case "dont", "do", "clear", "directive":
    let s = session()
    var e = event(.directive, s.id, a)
    switch a.command {
    case "dont": e.dont = a.positional.isEmpty ? (e.dont ?? []) : a.positional
    case "do": e.dos = a.positional.isEmpty ? (e.dos ?? []) : a.positional
    case "clear": e.dont = []; e.dos = []
    default: break
    }
    deliver([e], to: s.show, args: a)

case "wait", "attention":
    let s = session()
    var e = event(.attention, s.id, a)
    e.message = a.text ?? e.message
    deliver([e], to: s.show, args: a)

case "resume":
    let s = session()
    deliver([event(.resume, s.id, a)], to: s.show, args: a)

case "done", "fail":
    let s = session()
    var e = event(a.command == "done" ? .done : .fail, s.id, a)
    e.message = a.text ?? e.message
    deliver([e], to: s.show, args: a, checkStop: false)

case "end":
    let s = session()
    deliver([event(.end, s.id, a)], to: s.show, args: a, checkStop: false)

case "ask":
    // Something for the owner on the Mac island: a question or a request, with buttons (--choice id=Label) and/or a
    // place to open (--open URL|path|bundle id). Waits for the pick (prints its id) unless --no-wait.
    // Exit: 0 picked, 5 timed out, 6 the owner closed it.
    let owner = ownerID(a)
    let id = a.one("id") ?? "ask-\((owner ?? "x").prefix(8))"
    var e = PresenceEvent(id: id, type: .attention)
    e.ts = nowMillis()
    e.agent = a.one("agent") ?? "Claude"
    e.app = a.one("app")
    e.device = "mac"
    e.title = a.one("title") ?? (isTurkish() ? "Claude seni bekliyor" : "Claude needs you")
    e.message = a.text
    e.open = a.one("open")
    let primary = Set(a.many("primary") ?? []), destructive = Set(a.many("destructive") ?? [])
    e.choices = (a.many("choice") ?? []).compactMap { spec -> Choice? in
        guard let eq = spec.firstIndex(of: "=") else { return nil }
        let cid = String(spec[..<eq]), label = String(spec[spec.index(after: eq)...])
        return Choice(id: cid, label: label, style: primary.contains(cid) ? "primary" : (destructive.contains(cid) ? "destructive" : nil))
    }
    var reg = Registry.load()
    reg.remember(id, .init(device: "mac", show: ["mac"], owner: owner, at: nowMillis()), makeCurrent: false)
    reg.save()
    deliver([e], to: ["mac"], args: a, checkStop: false)
    if a.flags.contains("no-wait") { exit(0) }
    let deadline = Date().addingTimeInterval(a.one("timeout").flatMap(Double.init) ?? 90)
    func endIt(after: Double = 0) {
        if after > 0 { usleep(useconds_t(after * 1_000_000)) }
        var x = PresenceEvent(id: id, type: .end)
        x.ts = nowMillis()
        deliver([x], to: ["mac"], args: a, checkStop: false)
    }
    while Date() < deadline {
        usleep(150_000)
        guard let st = readMacState() else { continue }
        guard let entry = st.sessions.first(where: { $0.id == id }) else { print("dismissed"); exit(6) }
        if let c = entry.choice {
            print(c)
            endIt(after: 0.5) // the pressed state shows for a moment
            exit(0)
        }
    }
    if !a.flags.contains("keep") { endIt() }
    print("timeout")
    exit(5)

case "clear-asks":
    // This agent session's questions/requests are answered elsewhere (or the owner typed): take them off the island.
    guard let owner = ownerID(a) else { exit(0) }
    let reg = Registry.load()
    let live = Set((readMacState()?.sessions ?? []).map(\.id))
    for (id, entry) in reg.sessions where entry.owner == owner && id.hasPrefix("ask-") && live.contains(id) {
        var x = PresenceEvent(id: id, type: .end)
        x.ts = nowMillis()
        deliver([x], to: ["mac"], args: a, checkStop: false)
    }

case "hide", "unhide":
    // Only the agent does this: it must see or tap what is under the island. The written warnings leave the driven
    // device and its screen edges glow red; `unhide` (or the end of `--for`, default 30 s) brings them back.
    let reg = Registry.load()
    let owner = ownerID(a)
    var id = a.one("id") ?? reg.currentID(owner: owner)
    if id == nil, let owner, let dev = a.one("on") { id = "auto-\(owner.prefix(8))-\(dev)" }
    guard let id, let entry = reg.sessions[id] ?? (a.one("on").map { Registry.Entry(device: $0, show: [$0], owner: owner, at: 0) }) else {
        fail("no session to \(a.command): start one first (or pass --id / --on)")
    }
    var e = PresenceEvent(id: id, type: a.command == "hide" ? .hide : .unhide)
    e.ts = nowMillis()
    e.forSeconds = a.one("for").flatMap(Double.init)
    // The driven device only: a mirror keeps showing the progress.
    deliver([e], to: [entry.show.first ?? entry.device], args: a, checkStop: false)

case "expand", "collapse":
    let s = session()
    deliver([event(a.command == "expand" ? .expand : .collapse, s.id, a)], to: s.show, args: a, checkStop: false)

case "status":
    var all: [(String, PresenceState)] = []
    if let st = readMacState() { all.append(("mac", st)) }
    if a.one("on") == "phone", let st = sendPhone([], serial: a.one("serial")) { all.append(("phone", st)) }
    if a.flags.contains("json") {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        for (where_, st) in all { print(where_ + ": " + String(decoding: try! enc.encode(st), as: UTF8.self)) }
    } else {
        print("Mac app: " + (macAlive() ? "running" : "not running"))
        for (where_, st) in all {
            if st.sessions.isEmpty { print("\(where_): no sessions") }
            for s in st.sessions {
                let steps = s.total > 0 ? " step \(s.step)/\(s.total)" : ""
                print("\(where_): \(s.id) \(s.phase.rawValue)\(steps)\(s.stopRequested ? " STOP REQUESTED" : "")")
            }
        }
    }

case "send":
    // Raw events (one JSON object or an array) to the displays named by --to (default mac; "mac,phone" for both).
    guard let text = a.text, let events = try? PresenceEvent.decodeMany(Data(text.utf8)) else {
        fail("usage: presence send '<event json or array>' [--to mac|phone|mac,phone]")
    }
    let stamped = events.map { e -> PresenceEvent in var e = e; if e.ts == nil { e.ts = nowMillis() }; return e }
    deliver(stamped, to: (a.one("to") ?? "mac").split(separator: ",").map(String.init), args: a, checkStop: false,
            allRequired: true)

case "snapshot":
    guard let path = a.positional.first else { fail("usage: presence snapshot <file.png>") }
    let url = URL(fileURLWithPath: path, relativeTo: URL(fileURLWithPath: FileManager.default.currentDirectoryPath)).standardizedFileURL
    try? FileManager.default.removeItem(at: url)
    if !macAlive() && !launchMac() { fail("AgentPresence.app could not be started", code: 4) }
    DistributedNotificationCenter.default().postNotificationName(
        Notification.Name(PresencePaths.notification + ".snapshot"), object: url.path, userInfo: nil, deliverImmediately: true)
    for _ in 0..<50 where !FileManager.default.fileExists(atPath: url.path) { usleep(60_000) }
    guard FileManager.default.fileExists(atPath: url.path) else { fail("no snapshot written", code: 4) }
    print(url.path)

case "auto":
    // For agent hooks: says the agent is driving this device even when it announced nothing itself. One quiet
    // session per agent session and device; later calls only refresh what it is doing. Silent when the agent already
    // runs its own session for the device. Exits 3 when the owner pressed Stop (the hook then blocks the tool).
    guard let device = a.positional.first, device == "mac" || device == "phone" else {
        fail("usage: presence auto mac|phone [--tool NAME] [--owner ID]")
    }
    guard let owner = ownerID(a) else { exit(0) }
    var reg = Registry.load()
    let state = readMacState()
    func live(_ id: String) -> Bool {
        guard let p = state?.sessions.first(where: { $0.id == id })?.phase else { return false }
        return p == .running || p == .waiting
    }
    if reg.sessions.contains(where: { !$0.key.hasPrefix("auto-") && $0.value.owner == owner && $0.value.device == device && live($0.key) }) {
        exit(0)
    }
    let id = "auto-\(owner.prefix(8))-\(device)"
    let doing = toolWord(a.one("tool"), device: device)
    if live(id), let entry = reg.sessions[id] {
        var e = PresenceEvent(id: id, type: .detail)
        e.ts = nowMillis()
        e.detail = doing
        e.ttl = 30
        deliver([e], to: entry.show, args: a)
    } else {
        let show = targets(device: device, a: a)
        reg.remember(id, .init(device: device, show: show, owner: owner, at: nowMillis()), makeCurrent: false)
        reg.save()
        var e = PresenceEvent(id: id, type: .start)
        e.ts = nowMillis()
        e.agent = "Claude"
        e.device = device
        let tr = isTurkish()
        e.app = device == "mac" ? "Mac" : (tr ? "Telefon" : "Phone")
        e.title = device == "mac" ? (tr ? "Mac'i kullanıyor" : "Using your Mac") : (tr ? "Telefonu kullanıyor" : "Using your phone")
        e.dont = [device == "mac" ? (tr ? "Klavyeye ve trackpad'e dokunma" : "Don't touch the keyboard or trackpad")
                                  : (tr ? "Ekrana dokunma" : "Don't touch the screen")]
        e.detail = doing
        // The hook cannot know when the agent is done with the device: 30 s after its last touch it is.
        e.ttl = 30
        deliver([e], to: show, args: a)
    }

case "finish":
    // For agent hooks at the end of a turn: every session of this agent session that is still running is done now
    // (one that waits for the owner stays as it is). Nothing is left looking busy after the agent stopped.
    guard let owner = ownerID(a) else { exit(0) }
    let reg = Registry.load()
    let state = readMacState()
    for (id, entry) in reg.sessions where entry.owner == owner {
        var phase = state?.sessions.first(where: { $0.id == id })?.phase
        if phase == nil, !entry.show.contains("mac") {
            phase = sendPhone([], serial: a.one("serial"))?.sessions.first(where: { $0.id == id })?.phase
        }
        guard phase == .running else { continue }
        var e = PresenceEvent(id: id, type: .done)
        e.ts = nowMillis()
        e.message = a.text
        deliver([e], to: entry.show, args: a, checkStop: false)
    }

case "config":
    let d = UserDefaults(suiteName: PresencePaths.bundleID)!
    if a.positional.count == 2, a.positional[0] == "language", ["tr", "en", "auto"].contains(a.positional[1]) {
        if a.positional[1] == "auto" { d.removeObject(forKey: "language") } else { d.set(a.positional[1], forKey: "language") }
    } else if a.positional.count == 2, ["capture", "sound"].contains(a.positional[0]), ["on", "off"].contains(a.positional[1]) {
        d.set(a.positional[1] == "on", forKey: a.positional[0])
    } else {
        fail("usage: presence config capture|sound on|off  |  presence config language tr|en|auto")
    }
    d.synchronize()
    DistributedNotificationCenter.default().postNotificationName(
        Notification.Name(PresencePaths.notification + ".config"), object: nil, userInfo: nil, deliverImmediately: true)
    print("\(a.positional[0]) \(a.positional[1])")

case "demo":
    let device = a.one("on") ?? "mac"
    let show = targets(device: device, a: a)
    let k = a.flags.contains("fast") ? 0.5 : 1.0
    let id = "demo"
    func send(_ e: PresenceEvent) { deliver([e], to: show, args: a, checkStop: false) }
    func pause(_ s: Double) { usleep(useconds_t(s * k * 1_000_000)) }
    func ev(_ t: PresenceEvent.Kind, _ f: (inout PresenceEvent) -> Void = { _ in }) -> PresenceEvent {
        var e = PresenceEvent(id: id, type: t)
        e.ts = nowMillis()
        f(&e)
        return e
    }
    let tr = (UserDefaults(suiteName: PresencePaths.bundleID)?.string(forKey: "language")
        ?? Locale.preferredLanguages.first ?? "").hasPrefix("tr")
    print(tr ? "Demo: yaklaşık 35 sn" : "Demo: about 35 s")
    send(ev(.start) {
        $0.agent = "Claude"; $0.app = "Spotify"; $0.device = device
        $0.title = tr ? "Çalma listesine şarkı ekliyor" : "Adding songs to a playlist"
        let touch: [Step.Input] = device == "phone" ? [.touch] : [.keyboard, .mouse]
        $0.steps = tr
            ? [Step("Spotify'ı aç", uses: touch), "Listeyi bul", Step("Şarkıları ekle", uses: touch), "Kontrol et"]
            : [Step("Open Spotify", uses: touch), "Find the list", Step("Add songs", uses: touch), "Check"]
    })
    pause(7)
    send(ev(.step) { $0.step = 2 })
    pause(4)
    send(ev(.step) { $0.step = 3; $0.detail = "3/10" })
    pause(2.5)
    send(ev(.detail) { $0.detail = "7/10" })
    pause(2.5)
    send(ev(.attention) { $0.message = tr ? "Spotify giriş istiyor: şifreni sen yaz, sonra Tamam'a bas" : "Spotify wants you to sign in" })
    pause(7)
    send(ev(.resume) { $0.detail = "10/10" })
    pause(2)
    send(ev(.step) { $0.step = 4; $0.detail = "" })
    pause(3)
    send(ev(.done) { $0.message = tr ? "10 şarkı eklendi" : "10 songs added" })
    print(tr ? "Demo bitti. Kapatmak için: presence end --id demo" : "Demo done. Close it with: presence end --id demo")

default:
    fail("unknown command \(a.command)\n\n\(usage)")
}
