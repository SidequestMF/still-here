package stillhere.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// Kotlin port of apple/Shared/Models.swift. Field names match, so the same relay serves both.

data class TrustedContact(val name: String, val phone: String)   // phone in +1... format

/** A campus phone line: police, safe ride, or confidential support. */
data class CampusLine(val name: String, val phone: String, val hours: String = "")

/** Campus mode, set up by the student. Police is required; the rest is optional. */
data class CampusInfo(
    val name: String,
    val police: CampusLine,
    val ride: CampusLine? = null,
    val support: CampusLine? = null,
    val home: String = "",                  // where they live on campus, to help friends find them
)

/** 6175551212 or +16175551212 → 617-555-1212 for display. */
fun formatPhone(e164: String): String =
    Regex("^\\+1(\\d{3})(\\d{3})(\\d{4})$").find(e164)?.destructured?.let { (a, b, c) -> "$a-$b-$c" } ?: e164

/** Any common US format → +1XXXXXXXXXX, or null if it isn't a phone number. */
fun normalizePhone(raw: String): String? {
    val d = raw.filter { it.isDigit() }
    return when {
        d.length == 10 -> "+1$d"
        d.length == 11 && d[0] == '1' -> "+$d"
        raw.trim().startsWith("+") && d.length in 11..15 -> "+$d"
        else -> null
    }
}

enum class Trigger(val wire: String, val plainEnglish: String) {
    SCHEDULED("scheduled", "a scheduled check-in"),
    HEART_RATE_CHANGE("heartRateChange", "an unusual change in heart rate"),
    STUMBLE("stumble", "a stumble or fall"),
    STILLNESS("stillness", "being unusually still"),
    MANUAL("manual", "a check the wearer asked for"),
    HELP_BUTTON("helpButton", "the wearer pressing the help button"),
    WATCH_OFF("watchOff", "the watch losing contact with the wearer's wrist"),
}

enum class AlertCause(val wire: String) {
    NO_RESPONSE("noResponse"), FAILED_TESTS("failedTests"), ASKED_FOR_HELP("askedForHelp")
}

data class NightSettings(
    val wearerName: String = "",
    val contacts: List<TrustedContact> = emptyList(),
    val checkInMinutes: Int = 30,
    val responseWindowSeconds: Int = 60,
    val relayUrl: String? = null,
    val relayToken: String = "",
    val campus: CampusInfo? = null,
)

data class AlertEvent(
    val wearerName: String,
    val trigger: Trigger,
    val cause: AlertCause,
    val createdAt: Instant = Instant.now(),
    val id: String = UUID.randomUUID().toString().uppercase(),
    val timeZone: String = ZoneId.systemDefault().id,
    val lastHeartRate: Double? = null,
    val baselineHeartRate: Double? = null,
    val testSeconds: Double? = null,
    val baselineTestSeconds: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val campus: CampusInfo? = null,
) {
    /** The text contacts receive. Matches the Swift app and the relay. */
    fun message(): String {
        val zone = runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneId.systemDefault())
        val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(zone).format(createdAt)
        val lines = mutableListOf("Still Here alert for $wearerName at $time.")
        lines += when (cause) {
            AlertCause.NO_RESPONSE -> "No response to a check-in after ${trigger.plainEnglish}."
            AlertCause.FAILED_TESTS -> "Response tests were much slower than normal after ${trigger.plainEnglish}."
            AlertCause.ASKED_FOR_HELP -> "$wearerName asked for help."
        }
        if (latitude != null && longitude != null) {
            lines += "Location: https://maps.google.com/?q=%.5f,%.5f".format(Locale.US, latitude, longitude)
        }
        campus?.let { c ->
            if (c.home.isNotBlank()) lines += "$wearerName lives at: ${c.home}"
            lines += "Campus police (${c.police.name}): ${formatPhone(c.police.phone)}. They can usually reach $wearerName fastest on campus."
        }
        lines += "Please call $wearerName or go to them now. If you can't reach them and think they're in danger, call 911."
        return lines.joinToString("\n")
    }

    /** JSON body for the relay: {"event": {...}, "contacts": [...]}. No library needed. */
    fun relayJson(contacts: List<TrustedContact>): String {
        fun s(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        fun n(v: Double?) = v?.takeIf { it.isFinite() }?.toString() ?: "null"
        val event = (listOf(
            "\"id\":${s(id)}", "\"timeZone\":${s(timeZone)}", "\"wearerName\":${s(wearerName)}",
            "\"trigger\":${s(trigger.wire)}", "\"cause\":${s(cause.wire)}",
            "\"createdAt\":${s(createdAt.toString())}",
            "\"lastHeartRate\":${n(lastHeartRate)}", "\"baselineHeartRate\":${n(baselineHeartRate)}",
            "\"testSeconds\":${n(testSeconds)}", "\"baselineTestSeconds\":${n(baselineTestSeconds)}",
            "\"latitude\":${n(latitude)}", "\"longitude\":${n(longitude)}",
        ) + listOfNotNull(campus?.let {
            "\"campus\":{\"policeName\":${s(it.police.name)},\"policePhone\":${s(it.police.phone)},\"home\":${s(it.home)}}"
        })).joinToString(",")
        val people = contacts.joinToString(",") { "{\"name\":${s(it.name)},\"phone\":${s(it.phone)}}" }
        return "{\"event\":{$event},\"contacts\":[$people]}"
    }
}
