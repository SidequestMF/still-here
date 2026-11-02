package stillhere.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import stillhere.core.AlertCause
import stillhere.core.AlertEvent
import stillhere.core.Trigger
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

private const val PREFS = "stillhere"

/** Setup screen: who you are, who to alert, how often to check. Changes sync to the watch. */
class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        ))
        setContent { MaterialTheme { Setup(this) } }
    }

    fun askForBackgroundLocation() = permissions.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    fun askForSms() = permissions.launch(arrayOf(Manifest.permission.SEND_SMS))
}

@Composable
private fun Setup(activity: MainActivity) {
    val p = remember { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf(p.getString("name", "") ?: "") }
    var contacts by remember { mutableStateOf(p.getString("contacts", "") ?: "") }
    var interval by remember { mutableIntStateOf(p.getInt("interval", 30)) }
    var relayUrl by remember { mutableStateOf(p.getString("relayUrl", "") ?: "") }
    var relayToken by remember { mutableStateOf(p.getString("relayToken", "") ?: "") }
    var directSms by remember { mutableStateOf(p.getBoolean("directSms", false)) }
    var saved by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Still Here", style = MaterialTheme.typography.headlineMedium)
        Text("Your watch checks on you during a night out. If you stop responding, these people get a text with your location.")
        OutlinedTextField(name, { name = it }, label = { Text("Your first name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(contacts, { contacts = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
            label = { Text("Who to alert: one per line, Name, +16085551234") })
        Text("Tell these people you added them, so a text from an unknown number doesn't get ignored.",
            style = MaterialTheme.typography.bodySmall)
        Text("Check on me every $interval minutes")
        Slider(interval.toFloat(), { interval = it.toInt() }, valueRange = 10f..120f, steps = 21)
        OutlinedTextField(relayUrl, { relayUrl = it }, label = { Text("Text relay address (see relay/README.md)") },
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(relayToken, { relayToken = it }, label = { Text("Relay token") },
            modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
        Row { Checkbox(directSms, { directSms = it; if (it) activity.askForSms() })
            Text("If the relay can't be reached, send texts from this phone instead") }
        Button(onClick = {
            val list = contacts.lines().mapNotNull(::parseContact)
            if (name.isBlank() || list.isEmpty()) { saved = "Add your name and at least one contact with a number like +16085551234."; return@Button }
            val lines = list.joinToString("\n") { "${it.first}|${it.second}" }
            p.edit().putString("name", name.trim()).putString("contacts", lines).putInt("interval", interval)
                .putString("relayUrl", relayUrl.trim()).putString("relayToken", relayToken).putBoolean("directSms", directSms).apply()
            val req = PutDataMapRequest.create("/stillhere/settings").apply {
                dataMap.putString("name", name.trim()); dataMap.putString("contacts", lines)
                dataMap.putInt("interval", interval); dataMap.putInt("window", 60)
                dataMap.putString("relayUrl", relayUrl.trim()); dataMap.putString("relayToken", relayToken)
                dataMap.putLong("savedAt", System.currentTimeMillis())   // forces a sync even if nothing changed
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(activity).putDataItem(req)
            activity.askForBackgroundLocation()
            saved = "Saved and sent to your watch. ${list.size} contact(s)."
        }) { Text("Save and send to watch") }
        if (saved.isNotEmpty()) Text(saved)
        Text("Still Here is not a medical device and can't detect drugs. It notices when you stop responding normally. Call 911 in an emergency.",
            style = MaterialTheme.typography.bodySmall)
    }
}

/** "Maya, +16085551234" → ("Maya", "+16085551234"). Strips the "|" separator from names. */
fun parseContact(line: String): Pair<String, String>? {
    val comma = line.lastIndexOf(',')
    if (comma <= 0) return null
    val name = line.substring(0, comma).replace("|", "").trim()
    val phone = line.substring(comma + 1).filter { it == '+' || it.isDigit() }
    return if (name.isNotEmpty() && Regex("^\\+[1-9]\\d{7,14}$").matches(phone)) name to phone else null
}

/** Receives alerts from the watch, adds location, sends through the relay, falls back to SMS. */
class AlertListenerService : WearableListenerService() {
    private val handled = mutableSetOf<String>()

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/stillhere/alert") return
        val body = JSONObject(String(event.data))
        val ev = body.getJSONObject("event")
        if (!handled.add(ev.getString("id"))) return

        runBlocking {
            location()?.let { (lat, lon) -> ev.put("latitude", lat); ev.put("longitude", lon) }
        }
        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sent = sendToRelay(body.toString(), p.getString("relayUrl", "") ?: "", p.getString("relayToken", "") ?: "")
        if (!sent && p.getBoolean("directSms", false)) sendSmsDirect(body)
        else if (!sent) notifyHere(ev.optString("wearerName"))
    }

    @SuppressLint("MissingPermission")
    private suspend fun location(): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val client = LocationServices.getFusedLocationProviderClient(this)
        // Don't hold the alert hostage to GPS: give up after 10 seconds and use the last known fix.
        val loc = withTimeoutOrNull(10_000) { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }
            ?: runCatching { client.lastLocation.await() }.getOrNull()
        return loc?.let { it.latitude to it.longitude }
    }

    private fun sendToRelay(json: String, url: String, token: String): Boolean {
        if (url.isBlank()) return false
        repeat(3) { attempt ->
            val ok = runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 15_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                    outputStream.use { it.write(json.toByteArray()) }
                    responseCode in 200..299
                }
            }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(2000L shl attempt)
        }
        return false
    }

    /** Only for sideloaded builds: Google Play restricts SMS permission to default texting apps. */
    private fun sendSmsDirect(body: JSONObject) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
        val ev = body.getJSONObject("event")
        val event = AlertEvent(
            wearerName = ev.optString("wearerName", "Your friend"),
            trigger = Trigger.entries.firstOrNull { it.wire == ev.optString("trigger") } ?: Trigger.SCHEDULED,
            cause = AlertCause.entries.firstOrNull { it.wire == ev.optString("cause") } ?: AlertCause.NO_RESPONSE,
            createdAt = runCatching { Instant.parse(ev.getString("createdAt")) }.getOrDefault(Instant.now()),
            id = ev.getString("id"), timeZone = ev.optString("timeZone", "UTC"),
            latitude = ev.optDouble("latitude").takeIf { !it.isNaN() },
            longitude = ev.optDouble("longitude").takeIf { !it.isNaN() },
        )
        val sms = getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(event.message())
        val people = body.getJSONArray("contacts")
        for (i in 0 until people.length()) {
            runCatching { sms.sendMultipartTextMessage(people.getJSONObject(i).getString("phone"), null, parts, null, null) }
        }
    }

    /** Last resort: a loud notification on this phone in case someone nearby picks it up. */
    private fun notifyHere(name: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("alert", "Alerts", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(1, NotificationCompat.Builder(this, "alert")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Still Here: $name may need help")
            .setContentText("Couldn't send texts. Please check on $name.")
            .setPriority(NotificationCompat.PRIORITY_MAX).build())
    }
}
