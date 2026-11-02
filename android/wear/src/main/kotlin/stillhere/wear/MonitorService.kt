package stillhere.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.concurrent.futures.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import stillhere.core.AlertEvent
import stillhere.core.NightMonitor
import stillhere.core.NightSettings
import kotlin.math.sqrt

/**
 * Runs night mode in the background on the watch.
 *  - An "exercise" session in Health Services keeps live heart rate coming every second or so
 *    (the Wear OS equivalent of the Apple Watch workout session).
 *  - The linear-acceleration sensor (gravity already removed) gives movement at ~10 Hz.
 *  - All decisions are made by NightMonitor in the shared module.
 */
class MonitorService : LifecycleService(), NightMonitor.Listener, SensorEventListener {

    companion object {
        private val _phase = MutableStateFlow<NightMonitor.Phase>(NightMonitor.Phase.Idle)
        val phase: StateFlow<NightMonitor.Phase> = _phase
        val heartRate = MutableStateFlow<Double?>(null)
        val nextCheckInAt = MutableStateFlow(0.0)
        /** Set when a check-in was passed shakily and the student has a campus safe ride. */
        val rideOffer = MutableStateFlow<stillhere.core.CampusLine?>(null)
        /** Set after an all-clear, to show next-morning advice. */
        val aftercare = MutableStateFlow(false)
        var campusSupport: stillhere.core.CampusLine? = null; private set
        var instance: MonitorService? = null; private set

        fun start(context: Context, baselineTestSeconds: Double) {
            context.startForegroundService(Intent(context, MonitorService::class.java)
                .putExtra("baseline", baselineTestSeconds))
        }
        fun now() = SystemClock.elapsedRealtime() / 1000.0
    }

    private lateinit var monitor: NightMonitor
    private lateinit var settings: NightSettings
    private val exercise by lazy { HealthServices.getClient(this).exerciseClient }
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        instance = this
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)

        settings = SettingsStore.load(this)
        campusSupport = settings.campus?.support
        monitor = NightMonitor(settings, intent?.getDoubleExtra("baseline", 0.0)?.takeIf { it > 0 }, this)
        monitor.start(now())

        lifecycleScope.launch { startHeartRate() }
        sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensors.registerListener(this, it, 100_000)   // 10 Hz
        }
        lifecycleScope.launch {
            while (isActive) {
                monitor.tick(now())
                nextCheckInAt.value = monitor.nextCheckInAt
                delay(1000)
            }
        }
        return START_STICKY
    }

    private suspend fun startHeartRate() {
        exercise.setUpdateCallback(object : ExerciseUpdateCallback {
            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                val bpm = update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value ?: return
                heartRate.value = bpm
                monitor.onHeartRate(bpm, now())
            }
            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
            override fun onRegistered() {}
            override fun onRegistrationFailed(throwable: Throwable) {}
            override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
        })
        val config = ExerciseConfig.builder(ExerciseType.WORKOUT)
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false)
            .build()
        runCatching { exercise.startExerciseAsync(config).await() }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        monitor.onMotion(g.toDouble(), now())
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // Called by the screens
    fun recordTest(seconds: Double, errors: Int) = monitor.recordTest(seconds, errors, now())
    fun askForHelp() = monitor.askForHelp(now())
    fun allClear() { monitor.allClear(now()); aftercare.value = true }
    fun manualCheck() = monitor.beginCheck(stillhere.core.Trigger.MANUAL, now())

    fun endNight() {
        monitor.stop()
        sensors.unregisterListener(this)
        lifecycleScope.launch { runCatching { exercise.endExerciseAsync().await() } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { instance = null; _phase.value = NightMonitor.Phase.Idle; super.onDestroy() }

    // NightMonitor.Listener
    override fun onPhaseChanged(phase: NightMonitor.Phase) {
        _phase.value = phase
        if (phase is NightMonitor.Phase.Checking) {
            buzz(longArrayOf(0, 300, 150, 300, 150, 300))
            // Bring the check-in screen to the front even if the watch face is showing.
            startActivity(Intent(this, WearActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    override fun onCheckReminder() = buzz(longArrayOf(0, 400))
    override fun onShakyPass(ride: stillhere.core.CampusLine) { rideOffer.value = ride; buzz(longArrayOf(0, 200)) }
    override fun onAlert(event: AlertEvent) {
        buzz(longArrayOf(0, 600, 200, 600))
        PhoneLink.sendAlert(this, event, settings)
    }

    private fun buzz(pattern: LongArray) =
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("night", "Night mode", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, WearActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "night")
            .setContentTitle("Still Here is watching")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
