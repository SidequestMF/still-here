package stillhere.core

/**
 * The night-mode state machine, with no Android code so it can be tested on any JVM.
 * The watch service feeds it sensor readings and calls [tick] about once a second;
 * it answers through [Listener]. Mirrors MonitorEngine.swift.
 */
class NightMonitor(
    private val settings: NightSettings,
    private val baselineTestSeconds: Double?,
    private val listener: Listener,
) {
    interface Listener {
        fun onPhaseChanged(phase: Phase)
        fun onCheckReminder()                       // buzz again during an open check-in
        fun onAlert(event: AlertEvent)
    }

    sealed class Phase {
        object Idle : Phase()
        object Armed : Phase()
        data class Checking(val trigger: Trigger, val attempt: Int) : Phase()
        data class Alerted(val cause: AlertCause) : Phase()
    }

    var phase: Phase = Phase.Idle
        private set(value) { field = value; listener.onPhaseChanged(value) }
    var heartRate: Double? = null
        private set
    var nextCheckInAt = 0.0
        private set

    private var hr = HeartRateWatcher(0.0)
    private var motion = MotionWatcher(0.0)
    private var checkDeadline = 0.0
    private var lastReminder = 0.0
    private var lastHeartRateAt = 0.0

    fun start(now: Double) {
        hr = HeartRateWatcher(now)
        motion = MotionWatcher(now)
        lastHeartRateAt = now
        phase = Phase.Armed
        scheduleNext(now)
    }

    fun stop() { phase = Phase.Idle }

    val baselineHeartRate: Double? get() = hr.baseline

    fun onHeartRate(bpm: Double, now: Double) {
        heartRate = bpm
        lastHeartRateAt = now
        val fire = hr.add(bpm, now, motion.secondsSinceMovement(now), motion.intensity)
        if (fire) beginCheck(Trigger.HEART_RATE_CHANGE, now)
    }

    /** User acceleration magnitude in g (gravity removed), about 10 times a second. */
    fun onMotion(g: Double, now: Double) {
        when (motion.add(g, now)) {
            MotionWatcher.Event.STUMBLE -> beginCheck(Trigger.STUMBLE, now)
            MotionWatcher.Event.STILLNESS -> beginCheck(Trigger.STILLNESS, now)
            null -> {}
        }
    }

    fun tick(now: Double) {
        when (val p = phase) {
            Phase.Armed -> {
                if (now >= nextCheckInAt) beginCheck(Trigger.SCHEDULED, now)
                else if (now - lastHeartRateAt > Tuning.WATCH_OFF_SECONDS) {
                    lastHeartRateAt = now               // one check per gap
                    beginCheck(Trigger.WATCH_OFF, now)
                }
            }
            is Phase.Checking -> {
                if (now >= checkDeadline) raiseAlert(AlertCause.NO_RESPONSE, null, p.trigger, now)
                else if (now - lastReminder >= 4) { lastReminder = now; listener.onCheckReminder() }
            }
            else -> {}
        }
    }

    fun beginCheck(trigger: Trigger, now: Double) {
        if (phase != Phase.Armed) return               // already checking, alerted or stopped
        checkDeadline = now + settings.responseWindowSeconds
        lastReminder = now
        phase = Phase.Checking(trigger, attempt = 1)
    }

    /** Result of one tap test. Two failures in one check-in raise an alert. */
    fun recordTest(seconds: Double, errors: Int, now: Double): Boolean {
        val p = phase as? Phase.Checking ?: return false
        val passed = ResponseRule.passes(seconds, errors, baselineTestSeconds)
        when {
            passed -> {
                if (p.trigger == Trigger.HEART_RATE_CHANGE) hr.snooze(now)
                phase = Phase.Armed
                scheduleNext(now)
            }
            p.attempt >= 2 -> raiseAlert(AlertCause.FAILED_TESTS, seconds, p.trigger, now)
            else -> phase = p.copy(attempt = p.attempt + 1)
        }
        return passed
    }

    fun askForHelp(now: Double) {
        val trigger = (phase as? Phase.Checking)?.trigger ?: Trigger.HELP_BUTTON
        if (phase is Phase.Idle || phase is Phase.Alerted) return
        raiseAlert(AlertCause.ASKED_FOR_HELP, null, trigger, now)
    }

    /** TODO: require the wearer's PIN before an all-clear, so someone else can't cancel an alert. */
    fun allClear(now: Double) {
        if (phase !is Phase.Alerted) return
        phase = Phase.Armed
        scheduleNext(now)
    }

    private fun scheduleNext(now: Double) { nextCheckInAt = now + settings.checkInMinutes * 60.0 }

    private fun raiseAlert(cause: AlertCause, testSeconds: Double?, trigger: Trigger, now: Double) {
        phase = Phase.Alerted(cause)
        listener.onAlert(AlertEvent(
            wearerName = settings.wearerName, trigger = trigger, cause = cause,
            lastHeartRate = heartRate, baselineHeartRate = hr.baseline,
            testSeconds = testSeconds, baselineTestSeconds = baselineTestSeconds,
        ))
    }
}
