import AgentPresenceCore
import AppKit
import SwiftUI

/// Colors and words from SPEC.md §4–5 (v1.2 look), shared by every view.
public enum Theme {
    static let surface = Color.black
    static let text = Color(hex: 0xF5F5F7)
    static let secondary = Color(hex: 0x9A9AA2)
    static let blue = Color(hex: 0x3B82F6)
    static let blueOrbit = Color(hex: 0x60A5FA)
    static let blueFill = Color(hex: 0x16233F)
    static let blueText = Color(hex: 0x7AB8FF)
    static let green = Color(hex: 0x30D158)
    static let greenText = Color(hex: 0x4ADE80)
    static let amber = Color(hex: 0xFF9F0A)
    static let amberText = Color(hex: 0xFFB340)
    static let red = Color(hex: 0xFF453A)
    static let redText = Color(hex: 0xFF6961)
    static let pendingFill = Color(hex: 0x2C2C2E)
    static let pendingText = Color(hex: 0x8E8E93)
    static let capsule = Color(hex: 0x141416)
    static let panel = Color.white.opacity(0.04)
    static let line = Color(hex: 0x3A3A3C)
    // Kept for the circle and older callers.
    static let dontFill = Color(hex: 0x3A1515)
    static let dontText = redText
    static let doFill = Color(hex: 0x12301C)
    static let doText = greenText

    static func accent(_ p: Phase) -> Color {
        switch p {
        case .running: blue
        case .waiting: amber
        case .done: green
        case .failed: red
        }
    }

    static func accentText(_ p: Phase) -> Color {
        switch p {
        case .running: blueText
        case .waiting: amberText
        case .done: greenText
        case .failed: redText
        }
    }
}

/// The real icon of the app the agent drives ("Spotify" → Spotify's own icon), looked up once per name.
enum AppIcons {
    private static var cache: [String: NSImage?] = [:]

    static func icon(for name: String?) -> NSImage? {
        guard let name, !name.isEmpty else { return nil }
        if let hit = cache[name] { return hit }
        let fm = FileManager.default
        let home = fm.homeDirectoryForCurrentUser.path
        let dirs = ["/Applications", "/System/Applications", "/System/Applications/Utilities", "\(home)/Applications", "/System/Library/CoreServices",
                    "/Applications/Utilities"]
        var found: NSImage?
        for d in dirs {
            let path = "\(d)/\(name).app"
            if fm.fileExists(atPath: path) { found = NSWorkspace.shared.icon(forFile: path); break }
        }
        if found == nil, let apps = try? fm.contentsOfDirectory(atPath: "/Applications") {
            // "spotify" or "Visual Studio Code" written differently: a case-insensitive match on the bundle name.
            if let hit = apps.first(where: { $0.lowercased() == name.lowercased() + ".app" }) {
                found = NSWorkspace.shared.icon(forFile: "/Applications/\(hit)")
            }
        }
        cache[name] = found
        return found
    }
}

/// The target app's icon, or a neutral glyph when it is not on this Mac.
struct AppBadge: View {
    var name: String?
    var size: CGFloat

    var body: some View {
        if let img = AppIcons.icon(for: name) {
            Image(nsImage: img)
                .resizable()
                .interpolation(.high)
                .frame(width: size, height: size)
        } else {
            Image(systemName: "app.fill")
                .font(.system(size: size * 0.62, weight: .medium))
                .foregroundStyle(Theme.secondary)
                .frame(width: size, height: size)
                .background(RoundedRectangle(cornerRadius: size * 0.24, style: .continuous).fill(Theme.pendingFill))
        }
    }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
}

/// Turkish when the Mac speaks Turkish, English otherwise.
public enum L {
    /// "tr", "en", or nil to follow the Mac's language.
    public static var language: String?
    static var tr: Bool { (language ?? Locale.preferredLanguages.first ?? "en").hasPrefix("tr") }

    static var stop: String { tr ? "Durdur" : "Stop" }
    static var stopping: String { tr ? "Durdurma istendi" : "Stop requested" }
    static var minimize: String { tr ? "Küçült" : "Minimize" }
    static var close: String { tr ? "Kapat" : "Close" }
    static var ok: String { tr ? "Tamam" : "OK" }
    static var done: String { tr ? "Bitti" : "Done" }
    static var failed: String { tr ? "Olmadı" : "Failed" }
    static var waiting: String { tr ? "Seni bekliyor" : "Needs you" }
    static var handsOff: String { tr ? "Dokunma" : "Hands off" }
    static var onMac: String { tr ? "Mac'te" : "On Mac" }
    static var onPhone: String { tr ? "Telefonda" : "On phone" }
    static var working: String { tr ? "Çalışıyor" : "Working" }
    static var running: String { tr ? "Sürüyor" : "Running" }
    static var pending: String { tr ? "Sırada" : "Next" }
    static func stepOf(_ a: Int, _ b: Int) -> String { tr ? "Adım \(a)/\(b)" : "Step \(a) of \(b)" }
}
