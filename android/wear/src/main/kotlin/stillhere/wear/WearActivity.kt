package stillhere.wear

import android.Manifest
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.*
import kotlinx.coroutines.launch
import stillhere.core.NightMonitor
import kotlin.math.hypot
import kotlin.random.Random

private val Amber = Color(0xFFF2B544)
private val Danger = Color(0xFFFF6F61)

class WearActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(arrayOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS,
        ))
        setContent { MaterialTheme { App() } }
    }
}

@Composable
private fun App() {
    val phase by MonitorService.phase.collectAsState()
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when (val p = phase) {
            NightMonitor.Phase.Idle -> IdleScreen()
            NightMonitor.Phase.Armed -> ArmedScreen()
            is NightMonitor.Phase.Checking -> CheckScreen(p.attempt)
            is NightMonitor.Phase.Alerted -> AlertedScreen()
        }
    }
}

@Composable
private fun IdleScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { SettingsStore.load(context) }
    var rounds by remember { mutableStateOf<List<Double>?>(null) }

    val r = rounds
    if (r != null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Tap 1–4 in order", fontWeight = FontWeight.Bold)
            Text("Round ${r.size + 1} of 2", fontSize = 12.sp, color = Color.Gray)
            key(r.size) {
                ResponseTest { seconds, _ ->
                    val next = r + seconds
                    if (next.size < 2) rounds = next
                    else { rounds = null; MonitorService.start(context, next.average()) }
                }
            }
        }
        return
    }
    ScalingLazyColumn(horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("Still Here", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        if (settings.contacts.isEmpty()) {
            item { Text("Add trusted contacts in the phone app first.", textAlign = TextAlign.Center, fontSize = 13.sp) }
        } else {
            item { Text("${settings.contacts.size} contacts ready", fontSize = 13.sp) }
            item {
                Chip(onClick = { rounds = emptyList() }, label = { Text("Start night mode") },
                    colors = ChipDefaults.chipColors(backgroundColor = Amber, contentColor = Color.Black))
            }
        }
    }
}

@Composable
private fun ArmedScreen() {
    val hr by MonitorService.heartRate.collectAsState()
    val next by MonitorService.nextCheckInAt.collectAsState()
    var now by remember { mutableStateOf(MonitorService.now()) }
    LaunchedEffect(Unit) { while (true) { now = MonitorService.now(); kotlinx.coroutines.delay(1000) } }
    val left = (next - now).coerceAtLeast(0.0).toInt()

    ScalingLazyColumn(horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("Watching", color = Color(0xFF62D6A2), fontSize = 13.sp) }
        item { Text("%d:%02d".format(left / 60, left % 60), fontSize = 30.sp) }
        item { Text("until next check", fontSize = 11.sp, color = Color.Gray) }
        hr?.let { item { Text("${it.toInt()} bpm", fontSize = 13.sp) } }
        item { HoldForHelp { MonitorService.instance?.askForHelp() } }
        item { Chip(onClick = { MonitorService.instance?.manualCheck() }, label = { Text("Check me now") }) }
        item { Chip(onClick = { MonitorService.instance?.endNight() }, label = { Text("End night") },
            colors = ChipDefaults.secondaryChipColors()) }
    }
}

/** Must be held for 3 seconds so a bump can't trigger it. */
@Composable
private fun HoldForHelp(onHelp: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxWidth(0.85f).height(44.dp).clip(CircleShape).background(Danger.copy(alpha = 0.25f))
            .semantics { contentDescription = "Hold for three seconds to alert your contacts" }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.animateTo(1f, tween(3000, easing = LinearEasing))
                        onHelp()
                    }
                    tryAwaitRelease()
                    job.cancel()
                    scope.launch { progress.snapTo(0f) }
                })
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(Danger))
        Text("Hold for help", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CheckScreen(attempt: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Are you OK?", fontWeight = FontWeight.Bold)
        Text(if (attempt == 1) "Tap 1–4 in order" else "Once more", fontSize = 11.sp, color = Color.Gray)
        key(attempt) { ResponseTest { s, e -> MonitorService.instance?.recordTest(s, e) } }
        CompactChip(onClick = { MonitorService.instance?.askForHelp() }, label = { Text("I need help") },
            colors = ChipDefaults.chipColors(backgroundColor = Danger, contentColor = Color.Black))
    }
}

@Composable
private fun AlertedScreen() {
    ScalingLazyColumn(horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("Alerting your people", color = Danger, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
        item { Text("Your contacts are being texted your location.", fontSize = 12.sp, textAlign = TextAlign.Center) }
        // TODO: require the wearer's PIN before sending an all-clear.
        item { Chip(onClick = { MonitorService.instance?.allClear() }, label = { Text("I'm safe now") },
            colors = ChipDefaults.chipColors(backgroundColor = Amber, contentColor = Color.Black)) }
    }
}

/** Four numbered circles at random spots. Time runs from the first tap to the last. */
@Composable
private fun ResponseTest(onDone: (seconds: Double, errors: Int) -> Unit) {
    val points = remember { layout() }
    var next by remember { mutableIntStateOf(1) }
    var errors by remember { mutableIntStateOf(0) }
    var startedAt by remember { mutableStateOf<Long?>(null) }

    BoxWithConstraints(Modifier.size(140.dp)) {
        points.forEachIndexed { i, (x, y) ->
            val n = i + 1
            Box(
                Modifier.offset(maxWidth * x - 20.dp, maxHeight * y - 20.dp).size(40.dp).clip(CircleShape)
                    .background(if (n < next) Amber else Color.DarkGray)
                    .semantics { contentDescription = "Target $n" }
                    .pointerInput(n) {
                        detectTapGestures {
                            if (startedAt == null) startedAt = SystemClock.elapsedRealtime()
                            if (n == next) {
                                next++
                                if (next > 4) onDone((SystemClock.elapsedRealtime() - startedAt!!) / 1000.0, errors)
                            } else errors++
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text("$n", fontWeight = FontWeight.Bold, color = if (n < next) Color.Black else Color.White) }
        }
    }
}

private fun layout(): List<Pair<Float, Float>> {
    val pts = mutableListOf<Pair<Float, Float>>()
    var tries = 0
    while (pts.size < 4 && tries++ < 500) {
        val p = Random.nextDouble(0.18, 0.82).toFloat() to Random.nextDouble(0.18, 0.82).toFloat()
        if (pts.all { hypot((it.first - p.first).toDouble(), (it.second - p.second).toDouble()) > 0.3 }) pts += p
    }
    return pts
}
