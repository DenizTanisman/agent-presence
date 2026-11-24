import AgentPresenceCore
import AgentPresenceKit
import AppKit

/// The host app: no Dock icon, no menu. It draws the island and listens for events from `presence` (SPEC.md §7).
@MainActor
final class Host {
    let store = PresenceStore()
    let island: IslandController
    let defaults = UserDefaults(suiteName: PresencePaths.bundleID) ?? .standard
    var lastTs: Double?

    init() {
        island = IslandController(store: store, capturable: defaults.bool(forKey: "capture"))
        L.language = defaults.string(forKey: "language")
        store.sounds = defaults.object(forKey: "sound") as? Bool ?? true
        store.onChange = { [weak self] sessions in self?.save(sessions) }
        try? FileManager.default.createDirectory(at: PresencePaths.support, withIntermediateDirectories: true)
        restore()

        let dnc = DistributedNotificationCenter.default()
        dnc.addObserver(forName: Notification.Name(PresencePaths.notification), object: nil, queue: .main) { n in
            let text = (n.object as? String) ?? (n.userInfo?["json"] as? String) ?? ""
            MainActor.assumeIsolated { self.receive(text) }
        }
        dnc.addObserver(forName: Notification.Name(PresencePaths.notification + ".config"), object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { self.reloadConfig() }
        }
        dnc.addObserver(forName: Notification.Name(PresencePaths.notification + ".snapshot"), object: nil, queue: .main) { n in
            let path = (n.object as? String) ?? ""
            MainActor.assumeIsolated { if !path.isEmpty { self.island.snapshot(to: URL(fileURLWithPath: path)) } }
        }
        try? "\(getpid())".write(to: PresencePaths.ready, atomically: true, encoding: .utf8)
    }

    func receive(_ text: String) {
        guard let data = text.data(using: .utf8), let events = try? PresenceEvent.decodeMany(data) else {
            log("bad event: \(text.prefix(300))")
            return
        }
        for e in events {
            log(e.json())
            store.handle(e)
            if let ts = e.ts { lastTs = max(lastTs ?? 0, ts) }
        }
        save(store.sessions)
    }

    func reloadConfig() {
        defaults.synchronize()
        island.capturable = defaults.bool(forKey: "capture")
        L.language = defaults.string(forKey: "language")
        store.sounds = defaults.object(forKey: "sound") as? Bool ?? true
    }

    func save(_ sessions: [Session]) {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        if let data = try? enc.encode(PresenceState(sessions, lastTs: lastTs, full: true)) {
            try? data.write(to: PresencePaths.state, options: .atomic)
        }
    }

    /// Sessions from before a restart come back quietly; anything older than three hours is dropped.
    func restore() {
        guard let data = try? Data(contentsOf: PresencePaths.state),
              let st = try? JSONDecoder().decode(PresenceState.self, from: data) else { return }
        lastTs = st.lastTs
        let fresh = (st.full ?? []).filter { nowMillis() - $0.updated < 3 * 3600_000 }
        if !fresh.isEmpty { store.restore(fresh) }
    }

    func log(_ line: String) {
        let url = PresencePaths.log
        let stamp = ISO8601DateFormatter().string(from: Date())
        let data = Data("\(stamp) \(line)\n".utf8)
        if let h = try? FileHandle(forWritingTo: url) {
            h.seekToEndOfFile()
            if h.offsetInFile > 1_000_000 { h.truncateFile(atOffset: 0) }
            h.write(data)
            try? h.close()
        } else {
            try? data.write(to: url)
        }
    }
}

let app = NSApplication.shared
let me = ProcessInfo.processInfo.processIdentifier
if NSRunningApplication.runningApplications(withBundleIdentifier: PresencePaths.bundleID)
    .contains(where: { $0.processIdentifier != me }) {
    exit(0) // already running
}
app.setActivationPolicy(.accessory)
let host = MainActor.assumeIsolated { Host() }
app.run()
