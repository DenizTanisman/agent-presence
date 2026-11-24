// swift-tools-version:6.0
import PackageDescription

let v5: [SwiftSetting] = [.swiftLanguageMode(.v5)]

let package = Package(
    name: "AgentPresence",
    platforms: [.macOS(.v14)],
    products: [
        // Protocol, session reducer and file locations. Foundation only: use it from any Swift tool.
        .library(name: "AgentPresenceCore", targets: ["AgentPresenceCore"]),
        // The island itself (SwiftUI + an NSPanel under the notch): embed it in your own Mac app.
        .library(name: "AgentPresenceKit", targets: ["AgentPresenceKit"]),
        // Ready-made host app that draws the island for events from the CLI.
        .executable(name: "AgentPresence", targets: ["AgentPresenceApp"]),
        // `presence start|step|done ...`: what an agent calls. Also forwards to an Android phone over adb.
        .executable(name: "presence", targets: ["presence"]),
    ],
    targets: [
        .target(name: "AgentPresenceCore", swiftSettings: v5),
        .target(name: "AgentPresenceKit", dependencies: ["AgentPresenceCore"], swiftSettings: v5),
        .executableTarget(name: "AgentPresenceApp", dependencies: ["AgentPresenceKit"], swiftSettings: v5),
        .executableTarget(name: "presence", dependencies: ["AgentPresenceCore"], swiftSettings: v5),
        .testTarget(name: "AgentPresenceCoreTests", dependencies: ["AgentPresenceCore"], swiftSettings: v5),
    ]
)
