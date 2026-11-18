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
import kotlin.math.roundToInt

data class ActiveWalkState(
    val isWalking: Boolean = false,
    val isPaused: Boolean = false,
    val currentLocation: GeoPoint? = null,
    val currentAccuracyMeters: Float? = null,
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
    val nextManeuverDirection: TurnDirection = TurnDirection.STRAIGHT,
    val afterNextManeuverHint: String? = null,
    val distanceToNextTurnMeters: Int? = null,
    val nearestBeaconMeters: Int? = null,
    val routeClosestPoint: GeoPoint? = null,
    val routeClosestSegmentIdx: Int = 0,
    val offRouteMeters: Double = 0.0,
    val remainingRouteMeters: Double = 0.0,
    val eventFlash: String? = null
)

object WalkSessionHolder {
    private val _state = MutableStateFlow(ActiveWalkState())
    val state: StateFlow<ActiveWalkState> = _state.asStateFlow()

    var activeRoute: LoopRoute? = null
    var exploredBaseline: Set<String> = emptySet()
    var hapticEnabled: Boolean = true
    var voiceEnabled: Boolean = true

    fun updateLocationPassive(pt: GeoPoint, accuracy: Float? = null, bearing: Float? = null) {
        _state.update {
            it.copy(
                currentLocation = pt,
                currentAccuracyMeters = accuracy ?: it.currentAccuracyMeters,
                currentBearing = bearing ?: it.currentBearing
            )
        }
    }

    fun startNewSession(
        startPt: GeoPoint?,
        route: LoopRoute?,
        knownSectors: Set<String>,
        haptic: Boolean,
        voice: Boolean
    ) {
        activeRoute = route
        exploredBaseline = knownSectors
        hapticEnabled = haptic
        voiceEnabled = voice
        val firstManeuver = route?.maneuvers?.getOrNull(1) ?: route?.maneuvers?.firstOrNull()
        val secondManeuver = route?.maneuvers?.getOrNull(2)
        _state.value = ActiveWalkState(
            isWalking = true,
            isPaused = false,
            currentLocation = startPt,
            currentAccuracyMeters = _state.value.currentAccuracyMeters,
            walkStartPoint = startPt,
            walkedMeters = 0.0,
            elapsedSeconds = 0L,
            sessionSteps = 0,
            breadcrumbTrail = listOfNotNull(startPt),
            newlyDiscoveredSectors = emptySet(),
            activeBeacons = route?.beacons ?: emptyList(),
            nextManeuverPoint = firstManeuver?.point,
            nextManeuverHint = firstManeuver?.instruction,
            nextManeuverDirection = firstManeuver?.direction ?: TurnDirection.STRAIGHT,
            afterNextManeuverHint = secondManeuver?.instruction,
            remainingRouteMeters = route?.distanceMeters ?: 0.0
        )
    }

    fun replaceActiveRoute(newRoute: LoopRoute, announcement: String? = null) {
        activeRoute = newRoute
        val firstManeuver = newRoute.maneuvers.getOrNull(1) ?: newRoute.maneuvers.firstOrNull()
        val secondManeuver = newRoute.maneuvers.getOrNull(2)
        _state.update {
            it.copy(
                routeClosestSegmentIdx = 0,
                routeClosestPoint = newRoute.points.firstOrNull(),
                offRouteMeters = 0.0,
                remainingRouteMeters = newRoute.distanceMeters,
                nextManeuverPoint = firstManeuver?.point,
                nextManeuverHint = firstManeuver?.instruction,
                nextManeuverDirection = firstManeuver?.direction ?: TurnDirection.STRAIGHT,
                afterNextManeuverHint = secondManeuver?.instruction,
                eventFlash = announcement ?: it.eventFlash
            )
        }
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
            currentAccuracyMeters = snapshot.currentAccuracyMeters,
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
    private var voiceGuide: WalkVoiceGuide? = null

    private var lastSensorTotal = -1
    private var accumulatedSessionSteps = 0
    private var lastGpsTimeMs = 0L
    private val voiceAlertedManeuvers = HashSet<Int>()
    private val hapticAlertedManeuvers = HashSet<Int>()
    private var wasOffRouteAlerted = false
    private var homeArrivalAnnounced = false

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        storage = StorageManager(this)
        voiceGuide = WalkVoiceGuide(this)
        createNotificationChannel()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        lastSensorTotal = -1
        accumulatedSessionSteps = 0
        lastGpsTimeMs = 0L
        voiceAlertedManeuvers.clear()
        hapticAlertedManeuvers.clear()
        wasOffRouteAlerted = false
        homeArrivalAnnounced = false

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

        voiceGuide?.speak("Маршрут запущен. Следуйте по зелёному контуру.")

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
        val acc = if (loc.hasAccuracy()) loc.accuracy else null
        val st = WalkSessionHolder.state.value
        val prev = st.currentLocation
        val nowMs = System.currentTimeMillis()

        if (!st.isWalking || st.isPaused) {
            WalkSessionHolder.mutate {
                it.copy(
                    currentLocation = pt,
                    currentAccuracyMeters = acc ?: it.currentAccuracyMeters,
                    currentBearing = bearing ?: it.currentBearing
                )
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
        val newWalkedMeters = st.walkedMeters + addedDist

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
                    voiceGuide?.speak("${beacon.title} расшифрован. Плюс ${beacon.xpReward} опыта.")
                    beacon.copy(collected = true)
                } else beacon
            } else beacon
        }

        // Монотонная проекция на маршрут внутри скользящего окна вперед, чтобы не перескакивать на финиш
        val route = WalkSessionHolder.activeRoute
        var projPoint: GeoPoint? = st.routeClosestPoint
        var segIdx = st.routeClosestSegmentIdx
        var offRoute = 0.0
        var remMeters = st.remainingRouteMeters
        var nextHint = st.nextManeuverHint
        var nextDir = st.nextManeuverDirection
        var afterNextHint = st.afterNextManeuverHint
        var nextTurnPt = st.nextManeuverPoint
        var nextTurnDist: Int? = null

        if (route != null && route.points.size >= 2) {
            val winStart = (st.routeClosestSegmentIdx - 3).coerceAtLeast(0)
            val winEnd = (st.routeClosestSegmentIdx + 48).coerceAtMost(route.points.lastIndex)
            var proj = RouteEngine.projectOntoRoute(pt, route.points, winStart, winEnd)

            // Если отошли далеко от локального окна, проверяем оставшуюся часть трека впереди
            if (proj.offRouteMeters > 55.0 && winEnd < route.points.lastIndex) {
                val widerProj = RouteEngine.projectOntoRoute(pt, route.points, winStart, route.points.lastIndex)
                if (widerProj.offRouteMeters < proj.offRouteMeters) {
                    proj = widerProj
                }
            }

            projPoint = proj.projectedPoint
            segIdx = proj.segmentIndex
            offRoute = proj.offRouteMeters
            remMeters = proj.remainingMeters

            if (offRoute > 45.0 && !wasOffRouteAlerted) {
                wasOffRouteAlerted = true
                vibratePattern(longArrayOf(0, 160, 90, 160))
                voiceGuide?.speak("Вы отклонились от маршрута.")
            } else if (offRoute <= 28.0) {
                wasOffRouteAlerted = false
            }

            val maneuvers = route.maneuvers
            val upcomingIdx = maneuvers.indexOfFirst { m ->
                m.shapeIndex > segIdx && pt.distanceToAsDouble(m.point) > 10.0
            }

            if (upcomingIdx >= 0) {
                val m = maneuvers[upcomingIdx]
                val distAlong = RouteEngine.distanceAlongPath(proj.projectedPoint, segIdx, m.shapeIndex, route.points)
                val distRounded = distAlong.roundToInt()
                nextHint = m.instruction
                nextDir = m.direction
                nextTurnPt = m.point
                nextTurnDist = distRounded
                afterNextHint = maneuvers.getOrNull(upcomingIdx + 1)?.instruction

                if (distAlong <= 65.0 && upcomingIdx !in voiceAlertedManeuvers) {
                    voiceAlertedManeuvers.add(upcomingIdx)
                    voiceGuide?.speak("Через $distRounded метров ${m.instruction.lowercase(Locale.getDefault())}")
                }
                if (distAlong <= 24.0 && upcomingIdx !in hapticAlertedManeuvers) {
                    hapticAlertedManeuvers.add(upcomingIdx)
                    vibratePattern(longArrayOf(0, 130))
                }
            } else if (maneuvers.isNotEmpty()) {
                val lastM = maneuvers.last()
                val dFinish = pt.distanceToAsDouble(lastM.point).roundToInt()
                nextHint = lastM.instruction
                nextDir = TurnDirection.FINISH
                nextTurnPt = lastM.point
                nextTurnDist = dFinish
                afterNextHint = null
            }
        }

        val startPt = st.walkStartPoint ?: pt
        if (newWalkedMeters >= 300.0 && !homeArrivalAnnounced && startPt.distanceToAsDouble(pt) <= 65.0) {
            homeArrivalAnnounced = true
            vibratePattern(longArrayOf(0, 100, 60, 100, 60, 180))
            voiceGuide?.speak("Вы вернулись в зону старта. Круг можно завершать.")
            flashMsg = "[+] ВЫ В ЗОНЕ СТАРТА — МОЖНО ЗАВЕРШАТЬ КРУГ"
        }

        WalkSessionHolder.mutate {
            it.copy(
                currentLocation = pt,
                currentAccuracyMeters = acc ?: it.currentAccuracyMeters,
                currentBearing = bearing ?: it.currentBearing,
                walkStartPoint = startPt,
                walkedMeters = newWalkedMeters,
                breadcrumbTrail = if (addedDist > 0.0 || it.breadcrumbTrail.isEmpty()) it.breadcrumbTrail + pt else it.breadcrumbTrail,
                newlyDiscoveredSectors = updatedSectors,
                activeBeacons = updatedBeacons,
                nextManeuverPoint = nextTurnPt,
                nextManeuverHint = nextHint,
                nextManeuverDirection = nextDir,
                afterNextManeuverHint = afterNextHint,
                distanceToNextTurnMeters = nextTurnDist,
                nearestBeaconMeters = minBeaconDist,
                routeClosestPoint = projPoint,
                routeClosestSegmentIdx = segIdx,
                offRouteMeters = offRoute,
                remainingRouteMeters = remMeters,
                eventFlash = flashMsg
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val total = event.values[0].toInt()
        if (lastSensorTotal < 0) {
            lastSensorTotal = total
            return
        }
        val delta = (total - lastSensorTotal).coerceAtLeast(0)
        lastSensorTotal = total

        val st = WalkSessionHolder.state.value
        if (!st.isWalking || st.isPaused) return

        accumulatedSessionSteps += delta
        WalkSessionHolder.mutate { it.copy(sessionSteps = accumulatedSessionSteps) }
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
        voiceGuide?.shutdown()
        try {
            locationManager.removeUpdates(this)
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
