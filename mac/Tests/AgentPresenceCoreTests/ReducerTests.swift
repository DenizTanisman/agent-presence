import AgentPresenceCore
import XCTest

final class ReducerTests: XCTestCase {
    func ev(_ t: PresenceEvent.Kind, _ id: String = "a", _ f: (inout PresenceEvent) -> Void = { _ in }) -> PresenceEvent {
        var e = PresenceEvent(id: id, type: t)
        f(&e)
        return e
    }

    func testStartStepDone() {
        var s: [Session] = []
        XCTAssertEqual(Reducer.apply(ev(.start) { $0.steps = ["a", "b", "c"]; $0.dont = ["x"] }, to: &s, now: 1), .announce)
        XCTAssertEqual(s[0].step, 1)
        XCTAssertTrue(s[0].handsOff)
        XCTAssertEqual(Reducer.apply(ev(.step), to: &s, now: 2), .flash)
        XCTAssertEqual(s[0].step, 2)
        XCTAssertEqual(s[0].completed, 1)
        Reducer.apply(ev(.step) { $0.step = 9 }, to: &s, now: 3)
        XCTAssertEqual(s[0].step, 3, "clamped to the plan")
        XCTAssertEqual(Reducer.apply(ev(.done) { $0.message = "ok" }, to: &s, now: 4), .announce)
        XCTAssertEqual(s[0].phase, .done)
        XCTAssertEqual(s[0].completed, 3)
        XCTAssertFalse(s[0].handsOff, "done clears the don'ts")
        XCTAssertTrue(s[0].dos.isEmpty, "and the do's, unless the done event brings its own")
        XCTAssertEqual(Reducer.apply(ev(.end), to: &s, now: 5), .removed)
        XCTAssertTrue(s.isEmpty)
    }

    func testDirectiveChangeFlashesCompact() {
        var s: [Session] = []
        Reducer.apply(ev(.start), to: &s, now: 1)
        XCTAssertEqual(Reducer.apply(ev(.directive) { $0.dont = ["x"] }, to: &s, now: 2), .flash)
        XCTAssertEqual(Reducer.apply(ev(.directive) { $0.dont = ["x"] }, to: &s, now: 3), .none)
        XCTAssertEqual(Reducer.apply(ev(.directive) { $0.dont = [] }, to: &s, now: 4), .flash)
        XCTAssertFalse(s[0].handsOff)
    }

    func testStepUsesDriveTheWarning() {
        var s: [Session] = []
        Reducer.apply(ev(.start) {
            $0.steps = ["Aç", Step("Bul", uses: [.mouse]), Step("Yaz", uses: [.keyboard, .mouse]), "Kontrol"]
        }, to: &s, now: 1)
        XCTAssertEqual(s[0].directives(tr: true).map(\.text), ["Klavyeye ve trackpad'e dokunma"], "one later step is enough")
        Reducer.apply(ev(.step) { $0.step = 3 }, to: &s, now: 2)
        XCTAssertEqual(s[0].directives(tr: true).map(\.text), ["Klavyeye ve trackpad'e dokunma"])
        Reducer.apply(ev(.step), to: &s, now: 3)
        XCTAssertTrue(s[0].directives(tr: true).isEmpty, "gone once the last such step is finished")
        XCTAssertFalse(s[0].handsOff)
        Reducer.apply(ev(.start, "p") { $0.steps = ["a", "b"] }, to: &s, now: 4)
        XCTAssertFalse(s[1].handsOff, "plain steps: no directive, just the circle")
    }

    func testRepeatedWarningIsSaidOnce() {
        var s: [Session] = []
        Reducer.apply(ev(.start) {
            $0.steps = [Step("Sürükle", uses: [.keyboard, .mouse], dont: ["Klavyeye dokunma"])]
            $0.dont = ["Trackpad'e ve klavyeye dokunma", "Finder pencerelerini kapatma"]
        }, to: &s, now: 1)
        XCTAssertEqual(s[0].directives(tr: true).map(\.text), ["Klavyeye ve trackpad'e dokunma", "Finder pencerelerini kapatma"])
        Reducer.apply(ev(.start, "p") {
            $0.device = "phone"; $0.steps = [Step("Dokun", uses: [.touch])]; $0.dont = ["Telefona dokunma", "Kabloya dokunma"]
        }, to: &s, now: 2)
        XCTAssertEqual(s[1].directives(tr: true).map(\.text), ["Ekrana dokunma", "Kabloya dokunma"])
        Reducer.apply(ev(.start, "q") { $0.dont = ["Trackpad'e dokunma"] }, to: &s, now: 3)
        XCTAssertEqual(s[2].directives(tr: true).map(\.text), ["Trackpad'e dokunma"], "without tags the hand-written one stays")
    }

    func testTTLExpiry() {
        var s: [Session] = []
        Reducer.apply(ev(.start) { $0.ttl = 30 }, to: &s, now: 1_000)
        XCTAssertFalse(s[0].expired(now: 30_000))
        XCTAssertTrue(s[0].expired(now: 31_500), "30 s of silence")
        Reducer.apply(ev(.step), to: &s, now: 31_000)
        XCTAssertFalse(s[0].expired(now: 31_500), "any event refreshes it")
        Reducer.apply(ev(.attention), to: &s, now: 32_000)
        XCTAssertFalse(s[0].expired(now: 999_000), "waiting never expires")
        Reducer.apply(ev(.start, "n"), to: &s, now: 1_000)
        XCTAssertFalse(s.first { $0.id == "n" }!.expired(now: 999_000), "no ttl, no expiry")
        Reducer.apply(ev(.done), to: &s, now: 40_000)
        Reducer.apply(ev(.step), to: &s, now: 41_000)
        XCTAssertEqual(s.first { $0.id == "a" }?.phase, .running, "a later step continues a finished session")
    }

    func testHideUnhide() {
        var s: [Session] = []
        Reducer.apply(ev(.start), to: &s, now: 0)
        Reducer.apply(ev(.hide) { $0.forSeconds = 10 }, to: &s, now: 1_000)
        XCTAssertTrue(s[0].hidden(now: 5_000))
        XCTAssertFalse(s[0].hidden(now: 11_500), "comes back by itself")
        Reducer.apply(ev(.hide), to: &s, now: 20_000)
        XCTAssertTrue(s[0].hidden(now: 49_000), "default 30 s")
        Reducer.apply(ev(.unhide), to: &s, now: 21_000)
        XCTAssertFalse(s[0].hidden(now: 21_000))
        Reducer.apply(ev(.hide), to: &s, now: 22_000)
        Reducer.apply(ev(.attention), to: &s, now: 23_000)
        XCTAssertFalse(s[0].hidden(now: 23_000), "asking the owner shows everything")
        Reducer.apply(ev(.hide), to: &s, now: 24_000)
        Reducer.apply(ev(.done), to: &s, now: 25_000)
        XCTAssertFalse(s[0].hidden(now: 25_000))
    }

    func testWaitingSetsDirectivesAside() {
        var s: [Session] = []
        Reducer.apply(ev(.start) { $0.steps = [Step("Yaz", uses: [.keyboard])] }, to: &s, now: 1)
        Reducer.apply(ev(.attention), to: &s, now: 2)
        XCTAssertTrue(s[0].directives(tr: true).isEmpty)
        Reducer.apply(ev(.resume), to: &s, now: 3)
        XCTAssertEqual(s[0].directives(tr: true).map(\.text), ["Klavyeye dokunma"])
    }

    func testStepJSONStringOrObject() throws {
        let e = try PresenceEvent.decodeMany(Data(#"{"id":"a","type":"start","steps":["Aç",{"title":"Yaz","uses":["keyboard","nose"],"dont":"Kapatma"}]}"#.utf8))[0]
        XCTAssertEqual(e.steps, ["Aç", Step("Yaz", uses: [.keyboard], dont: ["Kapatma"])])
        let back = try PresenceEvent.decodeMany(Data(e.json().utf8))[0]
        XCTAssertEqual(back.steps, e.steps)
    }

    func testAttentionWinsPrimary() {
        var s: [Session] = []
        Reducer.apply(ev(.start, "a") { $0.dont = ["x"] }, to: &s, now: 1)
        Reducer.apply(ev(.start, "b") { $0.dont = ["y"] }, to: &s, now: 2)
        XCTAssertEqual(Reducer.primary(s)?.id, "b")
        Reducer.apply(ev(.attention, "a") { $0.message = "?" }, to: &s, now: 3)
        XCTAssertEqual(Reducer.primary(s)?.id, "a")
        XCTAssertFalse(s.first { $0.id == "a" }!.handsOff)
        Reducer.apply(ev(.resume, "a"), to: &s, now: 4)
        XCTAssertEqual(s.first { $0.id == "a" }?.phase, .running)
    }

    func testHandsOffWinsOverNewer() {
        var s: [Session] = []
        Reducer.apply(ev(.start, "phone") { $0.dont = ["x"] }, to: &s, now: 1)
        Reducer.apply(ev(.start, "mac"), to: &s, now: 2)
        XCTAssertEqual(Reducer.primary(s)?.id, "phone")
        Reducer.apply(ev(.done, "phone"), to: &s, now: 3)
        XCTAssertEqual(Reducer.primary(s)?.id, "mac", "a finished one gives way to a live one")
    }

    func testDirectivesOnlyOnTheDrivenDevice() {
        var s: [Session] = []
        Reducer.apply(ev(.start, "p") { $0.device = "phone"; $0.steps = [Step("Dokun", uses: [.touch])] }, to: &s, now: 1)
        Reducer.apply(ev(.start, "m") { $0.device = "mac"; $0.steps = ["Bak"] }, to: &s, now: 2)
        XCTAssertTrue(s[0].handsOff(on: "phone"))
        XCTAssertFalse(s[0].handsOff(on: "mac"), "a phone task does not tell you to keep off the Mac")
        XCTAssertTrue(s[0].directives(tr: true, on: "mac").isEmpty)
        XCTAssertEqual(Reducer.primary(s, host: "mac")?.id, "m", "on the Mac the phone task is just progress")
        XCTAssertEqual(Reducer.primary(s, host: "phone")?.id, "p")
    }

    func testNewStartClearsFinishedOnes() {
        var s: [Session] = []
        Reducer.apply(ev(.start, "a"), to: &s, now: 1)
        Reducer.apply(ev(.done, "a"), to: &s, now: 2)
        Reducer.apply(ev(.start, "b"), to: &s, now: 3)
        Reducer.apply(ev(.start, "c"), to: &s, now: 4)
        XCTAssertEqual(s.map(\.id), ["b", "c"], "done a is gone, live b stays")
    }

    func testUnknownSessionStepCreatesIt() {
        var s: [Session] = []
        XCTAssertEqual(Reducer.apply(ev(.step, "z") { $0.step = 2 }, to: &s, now: 1), .announce)
        XCTAssertEqual(s.count, 1)
        XCTAssertEqual(Reducer.apply(ev(.collapse, "nope"), to: &s, now: 2), .none)
        XCTAssertEqual(s.count, 1)
    }

    func testDecodeLenient() throws {
        let one = try PresenceEvent.decodeMany(Data(#"{"id":"a","type":"start","dont":"x","do":["y"],"extra":1}"#.utf8))
        XCTAssertEqual(one.first?.dont, ["x"])
        XCTAssertEqual(one.first?.dos, ["y"])
        let many = try PresenceEvent.decodeMany(Data(#"[{"id":"a","type":"start"},{"id":"a","type":"end"}]"#.utf8))
        XCTAssertEqual(many.map(\.type), [.start, .end])
        let back = try PresenceEvent.decodeMany(Data(one[0].json().utf8))
        XCTAssertEqual(back.first?.dos, ["y"], "\"do\" survives a round trip")
    }
}
