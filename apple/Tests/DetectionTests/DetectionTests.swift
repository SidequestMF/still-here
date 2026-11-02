import XCTest
@testable import StillHereCore

/// Same scenarios as tests/simulate-nights.mjs, in Swift, for anyone with a Mac.
/// Run from the `apple` folder: swift test
final class DetectionTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 0)
    func at(_ minutes: Double) -> Date { t0.addingTimeInterval(minutes * 60) }

    /// Feeds heart rate every 5 s and a constant motion level at 10 Hz. Returns minutes when checks fired.
    func run(minutes: Double, hr: (Double) -> Double?, g: (Double) -> Double,
             impacts: [Double: Double] = [:]) -> [(String, Double)] {
        var hrw = HeartRateWatcher(start: t0)
        var mw = MotionWatcher(start: t0)
        var fired: [(String, Double)] = []
        var pendingImpacts = impacts
        var i = 0
        while Double(i) / 600 <= minutes {
            let m = Double(i) / 600, now = at(m)
            var gv = g(m)
            if let k = pendingImpacts.keys.first(where: { $0 <= m }) { gv = pendingImpacts.removeValue(forKey: k)! }
            if let e = mw.add(g: gv, at: now) { fired.append(("\(e)", m)) }
            if i % 50 == 0, let bpm = hr(m),
               hrw.add(bpm: bpm, at: now, secondsSinceMovement: mw.secondsSinceMovement(at: now), intensity: mw.intensity) {
                fired.append(("heartRate", m)); hrw.snooze(from: now)
            }
            i += 1
        }
        return fired
    }

    func testDancingAllNightIsQuiet() {
        let f = run(minutes: 120, hr: { $0 < 15 ? 88 : 125 }, g: { $0 < 15 ? 0.02 + ($0.truncatingRemainder(dividingBy: 0.5) < 0.05 ? 0.2 : 0) : 0.35 })
        XCTAssertTrue(f.isEmpty, "\(f)")
    }

    func testHeartRateDropWhileStillFires() {
        let f = run(minutes: 120,
                    hr: { m in m < 60 ? 90 : max(55, 90 - (m - 60) * 3) },
                    g: { m in m < 60 ? (m.truncatingRemainder(dividingBy: 1) < 0.1 ? 0.2 : 0.02) : 0.005 })
        XCTAssertEqual(f.first?.0, "heartRate", "\(f)")
        XCTAssertLessThan(f.first!.1, 80)
    }

    func testFallThenStillIsAStumble() {
        let f = run(minutes: 40, hr: { _ in 110 }, g: { $0 < 30 ? 0.35 : 0.004 }, impacts: [30: 3.6])
        XCTAssertEqual(f.first?.0, "stumble"); XCTAssertEqual(f.first!.1, 30.17, accuracy: 0.05)
    }

    func testBumpWhileDancingIsIgnored() {
        let f = run(minutes: 40, hr: { _ in 120 }, g: { _ in 0.35 }, impacts: [20: 3.5])
        XCTAssertTrue(f.isEmpty, "\(f)")
    }

    func testSensorGlitchesIgnored() {
        var hrw = HeartRateWatcher(start: t0)
        for v in [0, -5, 300, .nan, .infinity] {
            XCTAssertFalse(hrw.add(bpm: v, at: at(30), secondsSinceMovement: 0, intensity: 0))
        }
    }

    func testPassedHeartRateCheckAcceptsNewLevel() {
        let f = run(minutes: 150, hr: { $0 < 30 ? 92 : 62 }, g: { m in m.truncatingRemainder(dividingBy: 2) < 0.05 ? 0.15 : 0.01 })
        XCTAssertEqual(f.filter { $0.0 == "heartRate" }.count, 1, "\(f)")
    }

    func testResponseRule() {
        XCTAssertTrue(ResponseRule.passes(seconds: 1.8, errors: 0, baseline: 1.8))
        XCTAssertTrue(ResponseRule.passes(seconds: 3.9, errors: 0, baseline: 1.8))
        XCTAssertFalse(ResponseRule.passes(seconds: 4.4, errors: 0, baseline: 1.8))
        XCTAssertFalse(ResponseRule.passes(seconds: 2.0, errors: 3, baseline: 1.8))
        XCTAssertTrue(ResponseRule.passes(seconds: 9, errors: 0, baseline: nil))
    }

    func testAlertMessageMentionsNameAnd911() {
        let e = AlertEvent(wearerName: "Jess", trigger: .stumble, cause: .noResponse, createdAt: t0,
                           latitude: 44.26, longitude: -88.41)
        let m = e.message()
        XCTAssertTrue(m.contains("Jess")); XCTAssertTrue(m.contains("911")); XCTAssertTrue(m.contains("maps.apple.com"))
    }

    func testCampusPhoneFormats() {
        XCTAssertEqual(Phone.normalize("(617) 495-1212"), "+16174951212")
        XCTAssertEqual(Phone.normalize("1-617-495-1212"), "+16174951212")
        XCTAssertNil(Phone.normalize("495-1212"))
        XCTAssertEqual(Phone.display("+16174951212"), "617-495-1212")
    }

    func testCampusAlertLines() {
        var e = AlertEvent(wearerName: "Jess", trigger: .stumble, cause: .noResponse, createdAt: t0)
        e.campus = AlertCampus(policeName: "MIT Police", policePhone: "+16172531212", home: "Next House")
        let m = e.message()
        XCTAssertTrue(m.contains("Jess lives at: Next House"))
        XCTAssertTrue(m.contains("Campus police (MIT Police): 617-253-1212"))
    }

    func testShakyRule() {
        XCTAssertTrue(ResponseRule.isShaky(seconds: 3.0, errors: 0, baseline: 1.8, attempt: 1))
        XCTAssertTrue(ResponseRule.isShaky(seconds: 1.8, errors: 1, baseline: 1.8, attempt: 1))
        XCTAssertTrue(ResponseRule.isShaky(seconds: 1.8, errors: 0, baseline: 1.8, attempt: 2))
        XCTAssertFalse(ResponseRule.isShaky(seconds: 2.5, errors: 0, baseline: 1.8, attempt: 1))
    }
}
