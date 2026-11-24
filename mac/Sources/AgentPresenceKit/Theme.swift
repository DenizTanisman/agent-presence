import AgentPresenceCore
import SwiftUI

/// Colors and words from SPEC.md §4–5, shared by every view.
public enum Theme {
    static let surface = Color.black
    static let text = Color.white
    static let secondary = Color(hex: 0xA1A1AA)
    static let blue = Color(hex: 0x3B82F6)
    static let blueOrbit = Color(hex: 0x60A5FA)
    static let blueFill = Color(hex: 0x16233F)
    static let blueText = Color(hex: 0x93C5FD)
    static let green = Color(hex: 0x34C759)
    static let amber = Color(hex: 0xF5A524)
    static let red = Color(hex: 0xFF453A)
    static let pendingFill = Color(hex: 0x2C2C2E)
    static let pendingText = Color(hex: 0x8E8E93)
    static let dontFill = Color(hex: 0x3A1515)
    static let dontText = Color(hex: 0xFFB4AB)
    static let doFill = Color(hex: 0x12301C)
    static let doText = Color(hex: 0xA7F3C0)
    static let line = Color(hex: 0x3A3A3C)

    static func accent(_ p: Phase) -> Color {
        switch p {
        case .running: blue
        case .waiting: amber
        case .done: green
        case .failed: red
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
