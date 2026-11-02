import Foundation

// Pure decision logic with no Apple frameworks, so it can be unit-tested anywhere
// (including Linux) and fed recorded or simulated data.

/// Learns the wearer's normal heart rate and flags sustained abnormal readings.
///
/// The baseline only uses "calm" readings (little movement), so dancing and walking never
/// count as normal. It prefers calm readings from 20–60 minutes ago, so a slow drop over the
/// last 20 minutes is compared against how the wearer was before it started. Early in the
/// night, before there are 20-minute-old readings, it uses calm readings at least 2 minutes old.
/// When the wearer passes a check-in, their current level is accepted as a new normal.
struct HeartRateWatcher {
    let start: Date
    private var calm: [(t: Date, bpm: Double)] = []
    private var recent: [(t: Date, bpm: Double, out: Bool)] = []
    private var accepted: (bpm: Double, until: Date)?
    private var snoozedUntil: Date = .distantPast

    /// Current baseline, or nil while there isn't enough calm data yet.
    private(set) var baseline: Double?

    init(start: Date) { self.start = start }

    /// Feed one reading.
    /// - secondsSinceMovement: from MotionWatcher
    /// - intensity: MotionWatcher.intensity (about a one-minute average of movement, in g)
    /// Returns true when a check-in should start.
    mutating func add(bpm: Double, at now: Date, secondsSinceMovement: Double, intensity: Double) -> Bool {
        guard bpm.isFinite, bpm > 20, bpm < 250 else { return false }   // ignore sensor glitches

        if intensity < Tuning.calmIntensityG { calm.append((now, bpm)) }
        calm.removeAll { now.timeIntervalSince($0.t) > Tuning.calmWindowMinutes * 60 }
        baseline = currentBaseline(now)

        var out = false
        if let base = baseline {
            let tooLow = bpm < base * (1 - Tuning.heartRateDropFraction)
            let tooHighWhileStill = secondsSinceMovement > 60 && bpm > base * (1 + Tuning.heartRateRiseFraction)
            out = tooLow || tooHighWhileStill
        }
        recent.append((now, bpm, out))
        recent.removeAll { now.timeIntervalSince($0.t) > Tuning.heartRateSustainSeconds }

        guard now >= snoozedUntil,
              let first = recent.first, now.timeIntervalSince(first.t) >= Tuning.heartRateSustainSeconds - 10
        else { return false }
        let outShare = Double(recent.filter(\.out).count) / Double(recent.count)
        if outShare >= Tuning.heartRateOutShare { return true }   // engine ignores repeats while a check is open
        return false
    }

    private var lastCalmBaseline: Double?

    private mutating func currentBaseline(_ now: Date) -> Double? {
        let lagged = calm.filter { now.timeIntervalSince($0.t) >= Tuning.calmLagMinutes * 60 }.map(\.bpm)
        var base: Double?
        if lagged.count >= Tuning.calmMinReadings {
            base = Self.median(lagged)
        } else if now.timeIntervalSince(start) < (Tuning.calmLagMinutes + 20) * 60 {
            let early = calm.filter { now.timeIntervalSince($0.t) >= 120 }.map(\.bpm)
            if early.count >= Tuning.calmMinReadings { base = Self.median(early) }
        }
        if let b = base { lastCalmBaseline = b }
        // After a long stretch of dancing there may be no recent calm readings; keep the last one.
        if base == nil { base = lastCalmBaseline }
        // After a passed check-in, the level the wearer said was fine is their normal for a while.
        if let a = accepted, now < a.until { base = a.bpm }
        return base
    }

    /// Call after a check-in passes: accept the current level as normal and pause
    /// heart-rate checks for a while.
    mutating func snooze(from now: Date) {
        if !recent.isEmpty {
            accepted = (Self.median(recent.map(\.bpm)), now.addingTimeInterval(Tuning.acceptedMinutes * 60))
        }
        snoozedUntil = now.addingTimeInterval(Tuning.heartRateSnoozeMinutes * 60)
        recent.removeAll()
    }

    static func median(_ xs: [Double]) -> Double {
        let s = xs.sorted()
        return s[s.count / 2]
    }
}

/// Tracks movement from acceleration magnitude (in g, gravity removed), sampled ~10 Hz.
struct MotionWatcher {
    enum Event: Equatable { case stumble, stillness }

    private var window: [Double] = []
    private(set) var lastMovementAt: Date
    private var impactAt: Date?
    /// Roughly a one-minute average of movement in g (exponential moving average).
    private(set) var intensity: Double = 0

    init(start: Date) { lastMovementAt = start }

    func secondsSinceMovement(at now: Date) -> Double { now.timeIntervalSince(lastMovementAt) }

    mutating func add(g: Double, at now: Date) -> Event? {
        guard g.isFinite, g >= 0 else { return nil }
        window.append(g)
        if window.count > 100 { window.removeFirst() }
        intensity += (min(g, 1.5) - intensity) / 600   // ~60 s time constant at 10 Hz

        if g > Tuning.impactG { impactAt = now }

        // "Moving" = average acceleration over the last ~2 seconds above a small threshold.
        // Samples within 2 s of an impact are ignored, so the impact itself doesn't
        // hide the stillness that follows it.
        let recent = window.suffix(20)
        let recentMean = recent.reduce(0, +) / Double(recent.count)
        let inImpactWindow = impactAt.map { now.timeIntervalSince($0) < 2 } ?? false
        if recentMean > Tuning.movingMeanG && !inImpactWindow {
            lastMovementAt = now
        }

        if let hit = impactAt, now.timeIntervalSince(hit) >= Tuning.postImpactStillSeconds {
            impactAt = nil
            // Still since shortly after the impact → likely went down and stayed down.
            if now.timeIntervalSince(lastMovementAt) >= Tuning.postImpactStillSeconds - 2 {
                return .stumble
            }
        }
        if now.timeIntervalSince(lastMovementAt) > Tuning.stillnessMinutes * 60 {
            lastMovementAt = now   // don't fire again for another full stretch
            return .stillness
        }
        return nil
    }
}

/// Pass/fail rule for one response test.
enum ResponseRule {
    static func passes(seconds: Double, errors: Int, baseline: Double?) -> Bool {
        guard let base = baseline, base > 0, seconds.isFinite else { return true }
        let limit = max(base * Tuning.failMultiplier, base + Tuning.failMinExtraSeconds)
        return seconds <= limit && errors <= Tuning.maxTestErrors
    }
}
