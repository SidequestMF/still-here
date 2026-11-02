package stillhere.core

// Kotlin port of apple/Shared/Detection.swift. Same rules, same thresholds.
// Pure Kotlin: no Android imports, so it is unit-tested on any JVM.
// Times are in seconds (any consistent clock, e.g. SystemClock.elapsedRealtime() / 1000.0).

/** All detection thresholds. Starting guesses, NOT validated values. See docs/RESEARCH.md. */
object Tuning {
    const val HEART_RATE_DROP_FRACTION = 0.30
    const val HEART_RATE_RISE_FRACTION = 0.45
    const val HEART_RATE_SUSTAIN_SECONDS = 120.0
    const val HEART_RATE_OUT_SHARE = 0.8
    const val HEART_RATE_SNOOZE_MINUTES = 20.0
    const val ACCEPTED_MINUTES = 60.0
    const val CALM_INTENSITY_G = 0.1
    const val CALM_WINDOW_MINUTES = 60.0
    const val CALM_LAG_MINUTES = 20.0
    const val CALM_MIN_READINGS = 60
    const val IMPACT_G = 2.5
    const val POST_IMPACT_STILL_SECONDS = 10.0
    const val STILLNESS_MINUTES = 15.0
    const val WATCH_OFF_SECONDS = 180.0
    const val MOVING_MEAN_G = 0.04
    const val FAIL_MULTIPLIER = 2.2
    const val FAIL_MIN_EXTRA_SECONDS = 2.5
    const val MAX_TEST_ERRORS = 2
    const val SHAKY_MULTIPLIER = 1.5
}

private fun median(xs: List<Double>): Double = xs.sorted()[xs.size / 2]

/**
 * Learns the wearer's normal heart rate from calm moments and flags sustained abnormal readings.
 * See the Swift version for the reasoning behind each step.
 */
class HeartRateWatcher(private val start: Double) {
    private data class Calm(val t: Double, val bpm: Double)
    private data class Recent(val t: Double, val bpm: Double, val out: Boolean)

    private val calm = ArrayDeque<Calm>()
    private val recent = ArrayDeque<Recent>()
    private var acceptedBpm: Double? = null
    private var acceptedUntil = Double.NEGATIVE_INFINITY
    private var snoozedUntil = Double.NEGATIVE_INFINITY
    private var lastCalmBaseline: Double? = null

    /** Current baseline, or null while there isn't enough calm data yet. */
    var baseline: Double? = null
        private set

    /** Returns true when a check-in should start. */
    fun add(bpm: Double, now: Double, secondsSinceMovement: Double, intensity: Double): Boolean {
        if (!bpm.isFinite() || bpm <= 20 || bpm >= 250) return false   // sensor glitch

        if (intensity < Tuning.CALM_INTENSITY_G) calm.addLast(Calm(now, bpm))
        calm.removeAll { now - it.t > Tuning.CALM_WINDOW_MINUTES * 60 }
        baseline = currentBaseline(now)

        var out = false
        baseline?.let { base ->
            val tooLow = bpm < base * (1 - Tuning.HEART_RATE_DROP_FRACTION)
            val tooHighWhileStill = secondsSinceMovement > 60 && bpm > base * (1 + Tuning.HEART_RATE_RISE_FRACTION)
            out = tooLow || tooHighWhileStill
        }
        recent.addLast(Recent(now, bpm, out))
        recent.removeAll { now - it.t > Tuning.HEART_RATE_SUSTAIN_SECONDS }

        if (now < snoozedUntil) return false
        val first = recent.firstOrNull() ?: return false
        if (now - first.t < Tuning.HEART_RATE_SUSTAIN_SECONDS - 10) return false
        val share = recent.count { it.out }.toDouble() / recent.size
        return share >= Tuning.HEART_RATE_OUT_SHARE
    }

    private fun currentBaseline(now: Double): Double? {
        val lagged = calm.filter { now - it.t >= Tuning.CALM_LAG_MINUTES * 60 }.map { it.bpm }
        var base: Double? = null
        if (lagged.size >= Tuning.CALM_MIN_READINGS) {
            base = median(lagged)
        } else if (now - start < (Tuning.CALM_LAG_MINUTES + 20) * 60) {
            val early = calm.filter { now - it.t >= 120 }.map { it.bpm }
            if (early.size >= Tuning.CALM_MIN_READINGS) base = median(early)
        }
        if (base != null) lastCalmBaseline = base
        if (base == null) base = lastCalmBaseline
        val a = acceptedBpm
        if (a != null && now < acceptedUntil) base = a
        return base
    }

    /** After a passed heart-rate check-in: accept the current level as normal and pause checks. */
    fun snooze(now: Double) {
        if (recent.isNotEmpty()) {
            acceptedBpm = median(recent.map { it.bpm })
            acceptedUntil = now + Tuning.ACCEPTED_MINUTES * 60
        }
        snoozedUntil = now + Tuning.HEART_RATE_SNOOZE_MINUTES * 60
        recent.clear()
    }
}

/** Tracks movement from acceleration magnitude in g (gravity removed), sampled ~10 Hz. */
class MotionWatcher(start: Double) {
    enum class Event { STUMBLE, STILLNESS }

    private val window = ArrayDeque<Double>()
    var lastMovementAt = start
        private set
    private var impactAt: Double? = null
    /** Roughly a one-minute average of movement in g. */
    var intensity = 0.0
        private set

    fun secondsSinceMovement(now: Double) = now - lastMovementAt

    fun add(g: Double, now: Double): Event? {
        if (!g.isFinite() || g < 0) return null
        window.addLast(g)
        if (window.size > 100) window.removeFirst()
        intensity += (minOf(g, 1.5) - intensity) / 600

        if (g > Tuning.IMPACT_G) impactAt = now

        val recentSamples = window.takeLast(20)
        val recentMean = recentSamples.sum() / recentSamples.size
        val inImpactWindow = impactAt?.let { now - it < 2 } ?: false
        if (recentMean > Tuning.MOVING_MEAN_G && !inImpactWindow) lastMovementAt = now

        val hit = impactAt
        if (hit != null && now - hit >= Tuning.POST_IMPACT_STILL_SECONDS) {
            impactAt = null
            if (now - lastMovementAt >= Tuning.POST_IMPACT_STILL_SECONDS - 2) return Event.STUMBLE
        }
        if (now - lastMovementAt > Tuning.STILLNESS_MINUTES * 60) {
            lastMovementAt = now
            return Event.STILLNESS
        }
        return null
    }
}

/** Pass/fail rule for one response test. */
object ResponseRule {
    fun passes(seconds: Double, errors: Int, baseline: Double?): Boolean {
        if (baseline == null || baseline <= 0 || !seconds.isFinite()) return true
        val limit = maxOf(baseline * Tuning.FAIL_MULTIPLIER, baseline + Tuning.FAIL_MIN_EXTRA_SECONDS)
        return seconds <= limit && errors <= Tuning.MAX_TEST_ERRORS
    }

    /** Passed, but slower than usual, with a wrong tap, or only on the second try: offer a safe ride. */
    fun isShaky(seconds: Double, errors: Int, baseline: Double?, attempt: Int): Boolean =
        attempt > 1 || errors >= 1 || (baseline != null && baseline > 0 && seconds > baseline * Tuning.SHAKY_MULTIPLIER)
}
