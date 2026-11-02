package stillhere.core

import kotlin.system.exitProcess

/** Checks of the night-mode state machine: check-ins, retries, timeouts, alerts. */
fun main() {
    var failed = 0
    fun check(name: String, ok: Boolean) { println((if (ok) "PASS  " else "FAIL  ") + name); if (!ok) failed++ }

    class Rec : NightMonitor.Listener {
        val alerts = mutableListOf<AlertEvent>(); var reminders = 0
        override fun onPhaseChanged(phase: NightMonitor.Phase) {}
        override fun onCheckReminder() { reminders++ }
        override fun onAlert(event: AlertEvent) { alerts += event }
    }
    val settings = NightSettings(wearerName = "Jess", contacts = listOf(TrustedContact("Maya", "+16085551234")),
        checkInMinutes = 30, responseWindowSeconds = 60)
    fun fresh(): Pair<NightMonitor, Rec> { val r = Rec(); val m = NightMonitor(settings, 1.8, r); m.start(0.0); return m to r }
    // keep heart rate flowing so the watch-off rule stays quiet unless a test wants it
    fun NightMonitor.runTo(from: Double, to: Double, hrOn: Boolean = true) {
        var t = from; while (t <= to) { if (hrOn && t % 5.0 == 0.0) onHeartRate(90.0, t); tick(t); t += 1.0 }
    }

    run {
        val (m, _) = fresh()
        m.runTo(0.0, 1799.0)
        check("no check-in before 30 minutes", m.phase == NightMonitor.Phase.Armed)
        m.runTo(1800.0, 1800.0)
        check("scheduled check-in at 30 minutes", m.phase == NightMonitor.Phase.Checking(Trigger.SCHEDULED, 1))
    }
    run {
        val (m, r) = fresh()
        m.beginCheck(Trigger.MANUAL, 100.0)
        m.runTo(100.0, 159.0)
        check("still checking just before the 60 s window ends", m.phase is NightMonitor.Phase.Checking)
        check("reminder buzz every 4 s", r.reminders in 14..15)
        m.runTo(160.0, 400.0)
        check("no answer → alert", m.phase == NightMonitor.Phase.Alerted(AlertCause.NO_RESPONSE))
        check("exactly one alert sent", r.alerts.size == 1)
        check("alert carries name and trigger", r.alerts[0].wearerName == "Jess" && r.alerts[0].trigger == Trigger.MANUAL)
    }
    run {
        val (m, r) = fresh()
        m.beginCheck(Trigger.STUMBLE, 100.0)
        check("normal-speed test passes", m.recordTest(2.0, 0, 110.0))
        check("back to night mode", m.phase == NightMonitor.Phase.Armed)
        check("next check-in rescheduled from now", m.nextCheckInAt == 110.0 + 1800)
        check("no alert", r.alerts.isEmpty())
    }
    run {
        val (m, r) = fresh()
        m.beginCheck(Trigger.HEART_RATE_CHANGE, 100.0)
        m.recordTest(6.0, 0, 105.0)
        check("first slow test → second attempt", m.phase == NightMonitor.Phase.Checking(Trigger.HEART_RATE_CHANGE, 2))
        m.recordTest(2.0, 0, 110.0)
        check("normal second attempt passes", m.phase == NightMonitor.Phase.Armed && r.alerts.isEmpty())
        m.beginCheck(Trigger.MANUAL, 200.0)
        m.recordTest(6.0, 0, 205.0); m.recordTest(7.0, 0, 210.0)
        check("two slow tests → alert", m.phase == NightMonitor.Phase.Alerted(AlertCause.FAILED_TESTS))
        check("alert records the slow time", r.alerts.single().testSeconds == 7.0)
    }
    run {
        val (m, r) = fresh()
        m.beginCheck(Trigger.MANUAL, 100.0)
        m.beginCheck(Trigger.STUMBLE, 130.0)
        m.runTo(130.0, 159.0)
        check("a second trigger doesn't restart an open check-in", m.phase == NightMonitor.Phase.Checking(Trigger.MANUAL, 1))
        m.runTo(160.0, 161.0)
        check("original deadline still applies", r.alerts.size == 1)
    }
    run {
        val (m, r) = fresh()
        m.askForHelp(50.0)
        check("help button alerts at once", r.alerts.single().cause == AlertCause.ASKED_FOR_HELP && r.alerts[0].trigger == Trigger.HELP_BUTTON)
        m.askForHelp(51.0)
        check("pressing help again doesn't send twice", r.alerts.size == 1)
        m.runTo(52.0, 4000.0)
        check("no new check-ins or alerts while alerted", r.alerts.size == 1)
        m.allClear(4001.0)
        check("all-clear returns to night mode", m.phase == NightMonitor.Phase.Armed)
    }
    run {
        val (m, r) = fresh()
        m.stop()
        m.askForHelp(10.0); m.runTo(0.0, 4000.0)
        check("nothing happens after night mode ends", r.alerts.isEmpty() && m.phase == NightMonitor.Phase.Idle)
    }
    run {
        val (m, _) = fresh()
        m.runTo(0.0, 60.0)
        m.runTo(61.0, 245.0, hrOn = false)
        check("heart rate stops for 3 minutes → watch-off check", m.phase == NightMonitor.Phase.Checking(Trigger.WATCH_OFF, 1))
    }
    run {
        val (m, _) = fresh()
        m.beginCheck(Trigger.MANUAL, 10.0)
        check("test result outside a check-in is ignored", run { m.recordTest(1.0, 0, 11.0); m.stop(); !m.recordTest(1.0, 0, 12.0) })
    }

    println(if (failed == 0) "All night-mode checks passed." else "$failed night-mode check(s) failed.")
    if (failed > 0) exitProcess(1)
}
