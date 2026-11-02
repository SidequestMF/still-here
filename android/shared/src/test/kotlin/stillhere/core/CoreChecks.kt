package stillhere.core

import java.time.Instant
import kotlin.system.exitProcess

/** Plain-Kotlin checks of the shared code (no test framework needed). Run via tests/run-kotlin-checks.sh. */
fun main() {
    var failed = 0
    fun check(name: String, ok: Boolean) { println((if (ok) "PASS  " else "FAIL  ") + name); if (!ok) failed++ }

    check("same speed passes", ResponseRule.passes(1.8, 0, 1.8))
    check("2.2x baseline passes", ResponseRule.passes(3.9, 0, 1.8))
    check("baseline + 2.6 s fails", !ResponseRule.passes(4.4, 0, 1.8))
    check("3 wrong taps fail", !ResponseRule.passes(2.0, 3, 1.8))
    check("no baseline never fails", ResponseRule.passes(9.0, 0, null))
    check("infinite time doesn't fail on its own", ResponseRule.passes(Double.POSITIVE_INFINITY, 0, 1.8))

    val hr = HeartRateWatcher(0.0)
    check("glitch readings ignored", listOf(0.0, -5.0, 300.0, Double.NaN).none { hr.add(it, 1800.0, 0.0, 0.0) })

    val e = AlertEvent("Jess", Trigger.STUMBLE, AlertCause.NO_RESPONSE,
        createdAt = Instant.parse("2026-11-03T05:42:00Z"), timeZone = "America/Chicago",
        latitude = 44.2619, longitude = -88.4154, id = "3F2504E0-4F89-11D3-9A0C-0305E82C3301")
    val m = e.message()
    check("message: name and local time", m.startsWith("Still Here alert for Jess at 11:42 PM."))
    check("message: cause", m.contains("No response to a check-in after a stumble or fall."))
    check("message: map link", m.contains("maps.google.com/?q=44.26190,-88.41540"))
    check("message: 911", m.contains("call 911"))

    val j = AlertEvent("Zoë \"Z\"", Trigger.HELP_BUTTON, AlertCause.ASKED_FOR_HELP, id = "X")
        .relayJson(listOf(TrustedContact("Maya", "+16085551234")))
    check("json escapes quotes", j.contains("\"wearerName\":\"Zoë \\\"Z\\\"\""))
    check("json missing location is null", j.contains("\"latitude\":null"))
    check("json has contacts", j.contains("\"contacts\":[{\"name\":\"Maya\",\"phone\":\"+16085551234\"}]"))
    check("json uses relay's trigger names", j.contains("\"trigger\":\"helpButton\"") && j.contains("\"cause\":\"askedForHelp\""))

    println(if (failed == 0) "All core checks passed." else "$failed core check(s) failed.")
    if (failed > 0) exitProcess(1)
}
