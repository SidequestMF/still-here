package stillhere.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// Kotlin port of apple/Shared/Models.swift. Field names match, so the same relay serves both.

data class TrustedContact(val name: String, val phone: String)   // phone in +1... format

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
        lines += "Please call $wearerName or go to them now. If you can't reach them and think they're in danger, call 911."
        return lines.joinToString("\n")
    }

    /** JSON body for the relay: {"event": {...}, "contacts": [...]}. No library needed. */
    fun relayJson(contacts: List<TrustedContact>): String {
        fun s(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        fun n(v: Double?) = v?.takeIf { it.isFinite() }?.toString() ?: "null"
        val event = listOf(
            "\"id\":${s(id)}", "\"timeZone\":${s(timeZone)}", "\"wearerName\":${s(wearerName)}",
            "\"trigger\":${s(trigger.wire)}", "\"cause\":${s(cause.wire)}",
            "\"createdAt\":${s(createdAt.toString())}",
            "\"lastHeartRate\":${n(lastHeartRate)}", "\"baselineHeartRate\":${n(baselineHeartRate)}",
            "\"testSeconds\":${n(testSeconds)}", "\"baselineTestSeconds\":${n(baselineTestSeconds)}",
            "\"latitude\":${n(latitude)}", "\"longitude\":${n(longitude)}",
        ).joinToString(",")
        val people = contacts.joinToString(",") { "{\"name\":${s(it.name)},\"phone\":${s(it.phone)}}" }
        return "{\"event\":{$event},\"contacts\":[$people]}"
    }
}
