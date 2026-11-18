package com.loopwalk.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.util.Locale

data class ActiveWalkState(
    val isWalking: Boolean = false,
    val isPaused: Boolean = false,
    val currentLocation: GeoPoint? = null,
    val currentBearing: Float? = null,
    val walkStartPoint: GeoPoint? = null,
    val walkedMeters: Double = 0.0,
    val elapsedSeconds: Long = 0L,
    val sessionSteps: Int = 0,
    val breadcrumbTrail: List<GeoPoint> = emptyList(),
    val newlyDiscoveredSectors: Set<String> = emptySet(),
    val activeBeacons: List<RouteBeacon> = emptyList(),
    val nextManeuverPoint: GeoPoint? = null,
    val nextManeuverHint: String? = null,
    val distanceToNextTurnMeters: Int? = null,
    val nearestBeaconMeters: Int? = null,
    val eventFlash: String? = null
)

object WalkSessionHolder {
    private val _state = MutableStateFlow(ActiveWalkState())
    val state: StateFlow<ActiveWalkState> = _state.asStateFlow()

    var activeRoute: LoopRoute? = null
    var exploredBaseline: Set<String> = emptySet()
    var hapticEnabled: Boolean = true

    fun updateLocationPassive(pt: GeoPoint, bearing: Float? = null) {
        _state.update {
            it.copy(
                currentLocation = pt,
                currentBearing = bearing ?: it.currentBearing
            )
        }
    }

    fun startNewSession(startPt: GeoPoint?, route: LoopRoute?, knownSectors: Set<String>, haptic: Boolean) {
        activeRoute = route
        exploredBaseline = knownSectors
        hapticEnabled = haptic
        val firstManeuver = route?.maneuvers?.getOrNull(1)
        _state.value = ActiveWalkState(
            isWalking = true,
            isPaused = false,
            currentLocation = startPt,
            walkStartPoint = startPt,
            walkedMeters = 0.0,
            elapsedSeconds = 0L,
            sessionSteps = 0,
            breadcrumbTrail = listOfNotNull(startPt),
            newlyDiscoveredSectors = emptySet(),
            activeBeacons = route?.beacons ?: emptyList(),
            nextManeuverPoint = firstManeuver?.point,
            nextManeuverHint = firstManeuver?.instruction
        )
    }

    fun togglePause() {
        _state.update { it.copy(isPaused = !it.isPaused) }
    }

    fun clearEventFlash() {
        _state.update { it.copy(eventFlash = null) }
    }

    fun stopSession(): ActiveWalkState {
        val snapshot = _state.value
        _state.value = ActiveWalkState(
            currentLocation = snapshot.currentLocation,
            currentBearing = snapshot.currentBearing
        )
        return snapshot
    }

    internal fun mutate(block: (ActiveWalkState) -> ActiveWalkState) {
        _state.update(block)
    }
}

class WalkTrackingService : Service(), LocationListener, SensorEventListener {

    companion object {
        const val ACTION_START = "com.loopwalk.app.START_WALK"
        const val ACTION_STOP = "com.loopwalk.app.STOP_WALK"
        private const val CHANNEL_ID = "loopwalk_nav_channel"
        private const val NOTIF_ID = 1042
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var storage: StorageManager

    private var initialSensorSteps = -1
    private var lastGpsTimeMs = 0L
    private val alertedManeuverIndices = HashSet<Int>()

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        storage = StorageManager(this)
        createNotificationChannel()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        initialSensorSteps = -1
        lastGpsTimeMs = 0L
        alertedManeuverIndices.clear()

        val notification = buildNotification("Прогулка запущена", "Ожидание GPS...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 5f, this)
        } catch (_: Exception) {
        }

        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepSensor != null) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
        }

        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (true) {
                delay(1000L)
                val st = WalkSessionHolder.state.value
                if (st.isWalking && !st.isPaused) {
                    val nextSec = st.elapsedSeconds + 1L
                    WalkSessionHolder.mutate { it.copy(elapsedSeconds = nextSec) }
                    updateNotification(nextSec, st.walkedMeters, st.sessionSteps, st.nextManeuverHint)
                }
            }
        }

        return START_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        val pt = GeoPoint(loc.latitude, loc.longitude)
        val bearing = if (loc.hasBearing() && loc.speed > 0.6f) loc.bearing else null
        val st = WalkSessionHolder.state.value
        val prev = st.currentLocation
        val nowMs = System.currentTimeMillis()

        if (!st.isWalking || st.isPaused) {
            WalkSessionHolder.mutate {
                it.copy(currentLocation = pt, currentBearing = bearing ?: it.currentBearing)
            }
            lastGpsTimeMs = nowMs
            return
        }

        if (loc.accuracy > 35f) return

        var addedDist = 0.0
        if (prev != null) {
            val delta = prev.distanceToAsDouble(pt)
            val dtSec = ((nowMs - lastGpsTimeMs) / 1000.0).coerceAtLeast(1.0)
            val speedMs = delta / dtSec
            if (delta in 2.0..80.0 && speedMs <= 5.6) {
                addedDist = delta
            }
        }
        lastGpsTimeMs = nowMs

        val secKey = storage.sectorKey(pt)
        val updatedSectors = if (secKey !in WalkSessionHolder.exploredBaseline) {
            st.newlyDiscoveredSectors + secKey
        } else st.newlyDiscoveredSectors

        var flashMsg = st.eventFlash
        var minBeaconDist: Int? = null
        val updatedBeacons = st.activeBeacons.map { beacon ->
            val d = pt.distanceToAsDouble(beacon.point)
            if (!beacon.collected) {
                val dInt = d.toInt()
                if (minBeaconDist == null || dInt < minBeaconDist!!) {
                    minBeaconDist = dInt
                }
                if (d <= 35.0) {
                    vibratePattern(longArrayOf(0, 90, 70, 140))
                    flashMsg = "[+] ${beacon.title} РАСШИФРОВАН (+${beacon.xpReward} XP)"
                    beacon.copy(collected = true)
                } else beacon
            } else beacon
        }

        var nextHint = st.nextManeuverHint
        var nextTurnPt = st.nextManeuverPoint
        var nextTurnDist: Int? = null
        val maneuvers = WalkSessionHolder.activeRoute?.maneuvers.orEmpty()
        for (i in 1 until maneuvers.size) {
            if (i in alertedManeuverIndices) continue
            val m = maneuvers[i]
            val distToTurn = pt.distanceToAsDouble(m.point)
            nextHint = m.instruction
            nextTurnPt = m.point
            nextTurnDist = distToTurn.toInt()
            if (distToTurn <= 30.0) {
                alertedManeuverIndices.add(i)
                vibratePattern(longArrayOf(0, 120))
            }
            break
        }

        WalkSessionHolder.mutate {
            it.copy(
                currentLocation = pt,
                currentBearing = bearing ?: it.currentBearing,
                walkStartPoint = it.walkStartPoint ?: pt,
                walkedMeters = it.walkedMeters + addedDist,
                breadcrumbTrail = if (addedDist > 0.0 || it.breadcrumbTrail.isEmpty()) it.breadcrumbTrail + pt else it.breadcrumbTrail,
                newlyDiscoveredSectors = updatedSectors,
                activeBeacons = updatedBeacons,
                nextManeuverPoint = nextTurnPt,
                nextManeuverHint = nextHint,
                distanceToNextTurnMeters = nextTurnDist,
                nearestBeaconMeters = minBeaconDist,
                eventFlash = flashMsg
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val st = WalkSessionHolder.state.value
        if (!st.isWalking || st.isPaused) return
        val total = event.values[0].toInt()
        if (initialSensorSteps < 0) {
            initialSensorSteps = total
        }
        val deltaSteps = (total - initialSensorSteps).coerceAtLeast(0)
        WalkSessionHolder.mutate { it.copy(sessionSteps = deltaSteps) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun vibratePattern(pattern: LongArray) {
        if (!WalkSessionHolder.hapticEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LoopWalk Навигация",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(sec: Long, meters: Double, steps: Int, turnHint: String?) {
        val title = String.format(
            Locale.US,
            "%02d:%02d | %.2f км | %d шаг.",
            sec / 60, sec % 60, meters / 1000.0, steps
        )
        val body = turnHint ?: "Следуйте по зелёному контуру"
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(title, body))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        timerJob?.cancel()
        serviceScope.cancel()
        try {
            locationManager.removeUpdates(this)
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
