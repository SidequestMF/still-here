package stillhere.wear

import android.content.Context
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import stillhere.core.AlertEvent
import stillhere.core.NightSettings
import stillhere.core.TrustedContact
import java.net.HttpURLConnection
import java.net.URL

/** Watch → phone alerts over the Wearable Data Layer, plus a direct-to-relay path for LTE watches. */
object PhoneLink {
    const val ALERT_PATH = "/stillhere/alert"
    const val SETTINGS_PATH = "/stillhere/settings"

    fun sendAlert(context: Context, event: AlertEvent, settings: NightSettings) {
        val body = event.relayJson(settings.contacts).toByteArray()
        CoroutineScope(Dispatchers.IO).launch {
            // 1. To the phone, which adds location and sends the texts.
            runCatching {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, ALERT_PATH, body).await() }
            }
            // 2. Straight to the relay too, in case the phone is gone. The relay sends each alert once.
            val url = settings.relayUrl ?: return@launch
            runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 15_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer ${settings.relayToken}")
                    outputStream.use { it.write(body) }
                    responseCode
                }
            }
        }
    }
}

/** Saves settings sent from the phone. Plain SharedPreferences; contacts as "name|phone" lines. */
object SettingsStore {
    private const val PREFS = "stillhere"

    fun load(context: Context): NightSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return NightSettings(
            wearerName = p.getString("name", "") ?: "",
            contacts = (p.getString("contacts", "") ?: "").lines().mapNotNull {
                val parts = it.split("|"); if (parts.size == 2) TrustedContact(parts[0], parts[1]) else null
            },
            checkInMinutes = p.getInt("interval", 30),
            responseWindowSeconds = p.getInt("window", 60),
            relayUrl = p.getString("relayUrl", null),
            relayToken = p.getString("relayToken", "") ?: "",
        )
    }

    fun save(context: Context, map: com.google.android.gms.wearable.DataMap) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", map.getString("name"))
            .putString("contacts", map.getString("contacts"))
            .putInt("interval", map.getInt("interval", 30))
            .putInt("window", map.getInt("window", 60))
            .putString("relayUrl", map.getString("relayUrl"))
            .putString("relayToken", map.getString("relayToken"))
            .apply()
    }
}

/** Receives settings whenever the phone app changes them. */
class SettingsListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PhoneLink.SETTINGS_PATH }
            .forEach { SettingsStore.save(this, DataMapItem.fromDataItem(it.dataItem).dataMap) }
    }
}
