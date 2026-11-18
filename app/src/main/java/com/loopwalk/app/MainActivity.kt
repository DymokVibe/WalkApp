package com.loopwalk.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.infowindow.InfoWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val AmoledBlack = Color(0xFF000000)
private val PanelBg = Color(0xEC080B09)
private val CardBg = Color(0xFF0D120F)
private val BorderSubtle = Color(0xFF1D2922)
private val PhosphorGreen = Color(0xFF00FF66)
private val EmeraldDim = Color(0xFF002B12)
private val AmberAccent = Color(0xFFFFB000)
private val CyanAccent = Color(0xFF00E5FF)
private val AlertRed = Color(0xFFFF5252)
private val TextPrimary = Color(0xFFF0F5F2)
private val TextMuted = Color(0xFF7D8F85)

private val LoopWalkDarkColors = darkColorScheme(
    primary = PhosphorGreen,
    onPrimary = AmoledBlack,
    secondary = CyanAccent,
    onSecondary = AmoledBlack,
    background = AmoledBlack,
    onBackground = TextPrimary,
    surface = CardBg,
    onSurface = TextPrimary,
    surfaceVariant = PanelBg,
    onSurfaceVariant = TextMuted,
    outline = BorderSubtle,
    error = AlertRed
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = AndroidColor.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = AndroidColor.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        Configuration.getInstance().userAgentValue = packageName
        setContent {
            MaterialTheme(colorScheme = LoopWalkDarkColors) {
                LoopWalkApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission", "ClickableViewAccessibility")
@Composable
fun LoopWalkApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val storage = remember { StorageManager(context) }
    val visuals = remember { MapVisuals(context) }

    var profile by remember { mutableStateOf(storage.loadProfile()) }
    val savedGen = remember { storage.loadGeneratorPrefs() }
    val mutator = remember { storage.getTodayMutator() }
    val walkState by WalkSessionHolder.state.collectAsState()

    var generatedRoute by remember { mutableStateOf<LoopRoute?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var isRerouting by remember { mutableStateOf(false) }
    var directionAnchor by remember { mutableStateOf<GeoPoint?>(null) }
    var selectedPin by remember { mutableStateOf<SelectedPinInfo?>(null) }

    var inputMode by remember { mutableStateOf(savedGen.inputMode) }
    var routeProfile by remember { mutableStateOf(savedGen.routeProfile) }
    var sliderValue by remember { mutableFloatStateOf(savedGen.sliderValue) }

    var followUser by remember { mutableStateOf(true) }
    var headingUpMode by remember { mutableStateOf(false) }
    var compassAzimuth by remember { mutableFloatStateOf(0f) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var hasAutoBuiltFirstRoute by remember { mutableStateOf(false) }

    var lastRewardMessage by remember { mutableStateOf<String?>(null) }
    var activeSheetTab by remember { mutableStateOf<Int?>(null) }
    var showStepsDialog by remember { mutableStateOf(false) }
    var confirmFinishArmed by remember { mutableStateOf(false) }

    val targetMeters = remember(inputMode, sliderValue, profile) {
        when (inputMode) {
            InputMode.TIME -> (sliderValue / 60.0) * profile.paceKmh * 1000.0
            InputMode.DISTANCE -> sliderValue * 1000.0
            InputMode.STEPS -> sliderValue * profile.strideMeters
        }
    }

    // Сохраняем параметры генератора при их изменении
    LaunchedEffect(inputMode, routeProfile, sliderValue) {
        storage.saveGeneratorPrefs(inputMode, routeProfile, sliderValue)
    }

    // Жизненный цикл OSMDroid MapView (исправляет подвисание тайлов после блокировки экрана)
    DisposableEffect(lifecycleOwner, mapViewRef) {
        val mv = mapViewRef
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mv?.onResume()
                Lifecycle.Event.ON_PAUSE -> mv?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Не гасить экран во время активной прогулки
    DisposableEffect(walkState.isWalking, profile.keepScreenOn) {
        val window = (context as? Activity)?.window
        if (walkState.isWalking && profile.keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Аппаратный компас для режима "КУРС" с фильтрацией дребезга (не грузит UI)
    DisposableEffect(headingUpMode) {
        if (!headingUpMode) {
            mapViewRef?.setMapOrientation(0f)
            onDispose { }
        } else {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            val rotMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            var smoothed = 0f
            var lastApplied = -999f
            var lastUiMs = 0L
            var initialized = false

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                    SensorManager.getOrientation(rotMatrix, orientation)
                    val rawDeg = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
                    if (!initialized) {
                        smoothed = rawDeg
                        initialized = true
                    } else {
                        var diff = rawDeg - smoothed
                        if (diff > 180f) diff -= 360f
                        if (diff < -180f) diff += 360f
                        smoothed = (smoothed + diff * 0.16f + 360f) % 360f
                    }
                    val now = System.currentTimeMillis()
                    if (abs(smoothed - lastApplied) >= 2.0f && now - lastUiMs >= 70L) {
                        lastApplied = smoothed
                        lastUiMs = now
                        val activeHeading = WalkSessionHolder.state.value.currentBearing ?: smoothed
                        mapViewRef?.setMapOrientation(-activeHeading)
                        compassAzimuth = smoothed
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            if (rotSensor != null) {
                sm.registerListener(listener, rotSensor, SensorManager.SENSOR_DELAY_UI)
            }
            onDispose {
                sm.unregisterListener(listener)
                mapViewRef?.setMapOrientation(0f)
            }
        }
    }

    fun fitRouteToMap(route: LoopRoute) {
        val mv = mapViewRef ?: return
        if (route.points.size < 2) return
        val bbox = BoundingBox.fromGeoPoints(route.points)
        if (bbox.latNorth != bbox.latSouth || bbox.lonEast != bbox.lonWest) {
            followUser = false
            headingUpMode = false
            mv.setMapOrientation(0f)
            mv.post {
                try {
                    mv.zoomToBoundingBox(bbox, true, 150)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun triggerGenerateRoute(anchorOverride: GeoPoint? = directionAnchor, autoFit: Boolean = true) {
        val loc = walkState.currentLocation
        if (loc == null) {
            Toast.makeText(context, "Ожидание GPS-сигнала...", Toast.LENGTH_SHORT).show()
            return
        }
        selectedPin = null
        isGenerating = true
        val prevBearing = if (anchorOverride == null) generatedRoute?.baseBearingDeg else null
        scope.launch {
            val route = RouteEngine.generateLoop(
                start = loc,
                targetMeters = targetMeters,
                routeProfile = routeProfile,
                userProfile = profile,
                directionAnchor = anchorOverride,
                avoidBearingDeg = prevBearing
            )
            generatedRoute = route
            isGenerating = false
            if (autoFit) {
                fitRouteToMap(route)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val hasLoc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLoc) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastGps != null) {
                val pt = GeoPoint(lastGps.latitude, lastGps.longitude)
                val acc = if (lastGps.hasAccuracy()) lastGps.accuracy else null
                WalkSessionHolder.updateLocationPassive(pt, acc)
                mapViewRef?.controller?.setCenter(pt)
            }
        }
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    LaunchedEffect(walkState.currentLocation) {
        val loc = walkState.currentLocation
        if (loc != null && !hasAutoBuiltFirstRoute && generatedRoute == null && !walkState.isWalking) {
            hasAutoBuiltFirstRoute = true
            mapViewRef?.controller?.setCenter(loc)
            triggerGenerateRoute(autoFit = true)
        }
    }

    // Пассивный GPS до старта прогулки
    DisposableEffect(walkState.isWalking) {
        if (walkState.isWalking) {
            onDispose { }
        } else {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    val pt = GeoPoint(loc.latitude, loc.longitude)
                    val acc = if (loc.hasAccuracy()) loc.accuracy else null
                    val brg = if (loc.hasBearing() && loc.speed > 0.6f) loc.bearing else null
                    WalkSessionHolder.updateLocationPassive(pt, acc, brg)
                }
            }
            val hasLoc = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (hasLoc) {
                val lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (lastGps != null && walkState.currentLocation == null) {
                    val pt = GeoPoint(lastGps.latitude, lastGps.longitude)
                    val acc = if (lastGps.hasAccuracy()) lastGps.accuracy else null
                    WalkSessionHolder.updateLocationPassive(pt, acc)
                }
                try {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 3f, listener)
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 6000L, 8f, listener)
                } catch (_: Exception) {
                }
            }
            onDispose {
                try {
                    lm.removeUpdates(listener)
                } catch (_: Exception) {
                }
            }
        }
    }

    LaunchedEffect(confirmFinishArmed) {
        if (confirmFinishArmed) {
            delay(3500L)
            confirmFinishArmed = false
        }
    }

    // Кэшированные полигоны Тумана войны и захваченных контуров (не пересоздаются каждую секунду)
    val cachedCapturedOverlays = remember(profile.capturedPolygons.size) {
        profile.capturedPolygons.takeLast(30).map { polyPoints ->
            Polygon().apply {
                points = polyPoints
                fillPaint.color = AndroidColor.argb(30, 0, 229, 255)
                outlinePaint.color = AndroidColor.argb(115, 0, 229, 255)
                outlinePaint.strokeWidth = 2f
            }
        }
    }

    val allSectorKeys = remember(profile.exploredSectors.size, walkState.newlyDiscoveredSectors.size) {
        (profile.exploredSectors + walkState.newlyDiscoveredSectors).toList().takeLast(180)
    }
    val cachedSectorOverlays = remember(allSectorKeys) {
        allSectorKeys.map { sec ->
            Polygon().apply {
                points = storage.sectorPolygon(sec)
                fillPaint.color = AndroidColor.argb(28, 0, 255, 102)
                outlinePaint.color = AndroidColor.argb(55, 0, 255, 102)
                outlinePaint.strokeWidth = 1f
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack)
    ) {
        // 1. Полноэкранная AMOLED-карта
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    setBuiltInZoomControls(false)
                    overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
                    controller.setZoom(16.0)
                    // При ручном перетаскивании карты пальцем отключаем автоследование, чтобы карта не отпружинивала
                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_MOVE && event.pointerCount == 1) {
                            followUser = false
                        }
                        false
                    }
                    mapViewRef = this
                }
            },
            update = { mapView ->
                InfoWindow.closeAllInfoWindowsOn(mapView)
                mapView.overlays.clear()

                val userLoc = walkState.currentLocation

                fun showPinInfo(title: String, subtitle: String, badge: String, pt: GeoPoint, canSteer: Boolean = true) {
                    val distStr = userLoc?.let { " • ${it.distanceToAsDouble(pt).roundToInt()} м от вас" } ?: ""
                    selectedPin = SelectedPinInfo(
                        title = title,
                        subtitle = "$subtitle$distStr",
                        badge = badge,
                        point = pt,
                        canSteerHere = canSteer
                    )
                }

                val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                        selectedPin = null
                        return false
                    }

                    override fun longPressHelper(p: GeoPoint): Boolean {
                        if (!walkState.isWalking) {
                            directionAnchor = p
                            triggerGenerateRoute(p)
                        }
                        return true
                    }
                })
                mapView.overlays.add(eventsOverlay)
                mapView.overlays.addAll(cachedCapturedOverlays)
                mapView.overlays.addAll(cachedSectorOverlays)

                val anchor = directionAnchor
                if (!walkState.isWalking && anchor != null && userLoc != null) {
                    val guideLine = Polyline().apply {
                        setPoints(listOf(userLoc, anchor))
                        outlinePaint.color = AndroidColor.argb(150, 255, 176, 0)
                        outlinePaint.strokeWidth = 4f
                        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
                    }
                    mapView.overlays.add(guideLine)
                }

                generatedRoute?.let { route ->
                    val splitIdx = if (walkState.isWalking) {
                        walkState.routeClosestSegmentIdx.coerceIn(0, route.points.lastIndex)
                    } else 0

                    if (walkState.isWalking && splitIdx > 0 && splitIdx < route.points.size) {
                        val passedPoints = route.points.subList(0, splitIdx + 1)
                        val passedLine = Polyline().apply {
                            setPoints(passedPoints)
                            outlinePaint.color = AndroidColor.argb(140, 20, 75, 45)
                            outlinePaint.strokeWidth = 7f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                        }
                        mapView.overlays.add(passedLine)
                    }

                    val activePoints = if (walkState.isWalking && splitIdx > 0 && splitIdx < route.points.lastIndex) {
                        val list = ArrayList<GeoPoint>()
                        walkState.routeClosestPoint?.let { list.add(it) }
                        list.addAll(route.points.subList(splitIdx + 1, route.points.size))
                        list
                    } else {
                        route.points
                    }

                    if (activePoints.size >= 2) {
                        val glowLine = Polyline().apply {
                            setPoints(activePoints)
                            outlinePaint.color = AndroidColor.argb(46, 0, 255, 102)
                            outlinePaint.strokeWidth = 24f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                        }
                        mapView.overlays.add(glowLine)

                        val casingLine = Polyline().apply {
                            setPoints(activePoints)
                            outlinePaint.color = AndroidColor.argb(235, 4, 16, 8)
                            outlinePaint.strokeWidth = 13f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                        }
                        mapView.overlays.add(casingLine)

                        val coreLine = Polyline().apply {
                            setPoints(activePoints)
                            outlinePaint.color = AndroidColor.rgb(0, 255, 102)
                            outlinePaint.strokeWidth = 7f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                        }
                        mapView.overlays.add(coreLine)
                    }

                    for (arrow in route.arrows) {
                        val arrowMarker = Marker(mapView).apply {
                            position = arrow.point
                            icon = visuals.directionArrow(arrow.bearingDeg)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            setOnMarkerClickListener { _, _ -> false }
                        }
                        mapView.overlays.add(arrowMarker)
                    }

                    if (profile.showMileposts) {
                        for (mp in route.mileposts) {
                            val mpMarker = Marker(mapView).apply {
                                position = mp.point
                                icon = visuals.milepostPill(mp.label)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                setOnMarkerClickListener { _, _ ->
                                    showPinInfo(
                                        title = "ОТМЕТКА ${mp.label}",
                                        subtitle = "Дистанция от старта: ${mp.metersFromStart} м",
                                        badge = "МЕТКА",
                                        pt = mp.point,
                                        canSteer = false
                                    )
                                    true
                                }
                            }
                            mapView.overlays.add(mpMarker)
                        }
                    }

                    val homePt = walkState.walkStartPoint ?: route.points.firstOrNull()
                    if (homePt != null) {
                        val homeMarker = Marker(mapView).apply {
                            position = homePt
                            icon = visuals.homeBadge()
                            setAnchor(Marker.ANCHOR_CENTER, 0.34f)
                            setOnMarkerClickListener { _, _ ->
                                showPinInfo(
                                    title = "ТОЧКА СТАРТА // ДОМ",
                                    subtitle = "Замкните круг в радиусе 85 м (+60 XP и захват контура)",
                                    badge = "СТАРТ",
                                    pt = homePt,
                                    canSteer = false
                                )
                                true
                            }
                        }
                        mapView.overlays.add(homeMarker)
                    }
                }

                if (walkState.isWalking && walkState.offRouteMeters > 30.0 &&
                    userLoc != null && walkState.routeClosestPoint != null
                ) {
                    val tether = Polyline().apply {
                        setPoints(listOf(userLoc, walkState.routeClosestPoint))
                        outlinePaint.color = AndroidColor.rgb(255, 82, 82)
                        outlinePaint.strokeWidth = 5f
                        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
                    }
                    mapView.overlays.add(tether)
                }

                val beaconsToShow = if (walkState.isWalking) {
                    walkState.activeBeacons
                } else {
                    generatedRoute?.beacons.orEmpty()
                }
                for (b in beaconsToShow) {
                    if (!b.collected) {
                        val zone = Polygon().apply {
                            points = storage.circlePolygon(b.point, 35.0)
                            fillPaint.color = AndroidColor.argb(26, 0, 229, 255)
                            outlinePaint.color = AndroidColor.argb(110, 0, 229, 255)
                            outlinePaint.strokeWidth = 2f
                        }
                        mapView.overlays.add(zone)
                    }

                    val bMarker = Marker(mapView).apply {
                        position = b.point
                        icon = visuals.beaconDiamond(b.collected, b.id)
                        setAnchor(Marker.ANCHOR_CENTER, 0.34f)
                        setOnMarkerClickListener { _, _ ->
                            showPinInfo(
                                title = if (b.collected) "[OK] ${b.title}" else "${b.title} (+${b.xpReward} XP)",
                                subtitle = if (b.collected) {
                                    "Сигнальный пакет уже расшифрован"
                                } else {
                                    "Подойдите ближе 35 м для перехвата сигнала"
                                },
                                badge = "МАЯК #${b.id}",
                                pt = b.point,
                                canSteer = !walkState.isWalking
                            )
                            true
                        }
                    }
                    mapView.overlays.add(bMarker)
                }

                if (walkState.isWalking && walkState.nextManeuverPoint != null) {
                    val turnMarker = Marker(mapView).apply {
                        position = walkState.nextManeuverPoint
                        icon = visuals.nextTurnDot()
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setOnMarkerClickListener { _, _ ->
                            showPinInfo(
                                title = "СЛЕДУЮЩИЙ МАНЕВР",
                                subtitle = walkState.nextManeuverHint ?: "",
                                badge = walkState.nextManeuverDirection.shortLabel,
                                pt = walkState.nextManeuverPoint,
                                canSteer = false
                            )
                            true
                        }
                    }
                    mapView.overlays.add(turnMarker)
                }

                if (!walkState.isWalking && anchor != null) {
                    val anchorMarker = Marker(mapView).apply {
                        position = anchor
                        icon = visuals.targetAnchorReticle()
                        setAnchor(Marker.ANCHOR_CENTER, 0.33f)
                        setOnMarkerClickListener { _, _ ->
                            showPinInfo(
                                title = "ВЕКТОР НАПРАВЛЕНИЯ",
                                subtitle = "Круговой маршрут строится в сторону этой точки",
                                badge = "ВЕКТОР",
                                pt = anchor,
                                canSteer = false
                            )
                            true
                        }
                    }
                    mapView.overlays.add(anchorMarker)
                }

                if (walkState.breadcrumbTrail.size >= 2) {
                    val trailCasing = Polyline().apply {
                        setPoints(walkState.breadcrumbTrail)
                        outlinePaint.color = AndroidColor.argb(220, 20, 12, 0)
                        outlinePaint.strokeWidth = 11f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                    }
                    val trailCore = Polyline().apply {
                        setPoints(walkState.breadcrumbTrail)
                        outlinePaint.color = AndroidColor.rgb(255, 176, 0)
                        outlinePaint.strokeWidth = 6f
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                    }
                    mapView.overlays.add(trailCasing)
                    mapView.overlays.add(trailCore)
                }

                userLoc?.let { loc ->
                    val puckBearing = if (headingUpMode) 0f else (walkState.currentBearing ?: compassAzimuth)
                    val userMarker = Marker(mapView).apply {
                        position = loc
                        icon = visuals.userPuck(puckBearing)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setOnMarkerClickListener { _, _ -> false }
                    }
                    mapView.overlays.add(userMarker)
                    if (followUser) {
                        mapView.controller.animateTo(loc)
                    }
                }

                mapView.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Верхний плавающий HUD
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                color = PanelBg,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(EmeraldDim)
                                    .border(1.dp, PhosphorGreen, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "LVL ${profile.level}",
                                    color = PhosphorGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                            Text(
                                text = profile.rankTitle,
                                color = TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            HudChipButton(
                                label = if (profile.voiceNavEnabled) "ГОЛОС" else "ТИХО",
                                accent = if (profile.voiceNavEnabled) PhosphorGreen else TextMuted
                            ) {
                                val next = !profile.voiceNavEnabled
                                WalkSessionHolder.voiceEnabled = next
                                profile = storage.setVoiceEnabled(next)
                            }
                            HudChipButton(label = "ПРОФИЛЬ", accent = CyanAccent) {
                                activeSheetTab = 0
                            }
                            HudChipButton(label = "CFG", accent = TextMuted) {
                                activeSheetTab = 2
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(7.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF141D18))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((profile.xpInCurrentLevel / 500f).coerceIn(0.02f, 1f))
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFF00B347), PhosphorGreen)
                                    )
                                )
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "СЕРИЯ ${profile.streakDays}д (x${String.format(Locale.US, "%.1f", profile.streakMultiplier)})  |  ЩИТЫ: ${profile.cryoShields}",
                            color = AmberAccent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val gpsTag = walkState.currentAccuracyMeters?.let { "GPS ±${it.roundToInt()}м" }
                            ?: if (walkState.currentLocation != null) "GPS OK" else "ПОИСК GPS"
                        Text(
                            text = "$gpsTag  |  ${profile.xpInCurrentLevel}/500",
                            color = if (walkState.currentLocation != null) TextMuted else AmberAccent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }

                    if (!walkState.isWalking) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${mutator.title} ${mutator.description}",
                            color = TextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Крупная карточка пошаговой навигации во время прогулки
            if (walkState.isWalking) {
                val isOffRoute = walkState.offRouteMeters > 45.0
                val navBorder = if (isOffRoute) AlertRed else AmberAccent
                val navBg = if (isOffRoute) Color(0xF21E0707) else Color(0xF2130F04)

                Surface(
                    color = navBg,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, navBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showStepsDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isOffRoute) Color(0xFF330C0C) else Color(0xFF261D06))
                                .border(1.dp, navBorder, RoundedCornerShape(10.dp))
                        ) {
                            Text(
                                text = if (isOffRoute) "!" else walkState.nextManeuverDirection.glyph,
                                color = navBorder,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            if (isOffRoute) {
                                Text(
                                    text = "ОТКЛОНЕНИЕ ${walkState.offRouteMeters.roundToInt()} М ОТ ТРЕКА",
                                    color = AlertRed,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "Вернитесь к красной пунктирной линии или перестройте путь",
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    maxLines = 2
                                )
                            } else {
                                val distLabel = walkState.distanceToNextTurnMeters?.let { "Через $it м" } ?: "Далее"
                                Text(
                                    text = distLabel.uppercase(Locale.getDefault()),
                                    color = AmberAccent,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = walkState.nextManeuverHint ?: "Следуйте по зелёному контуру",
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                walkState.afterNextManeuverHint?.let { thenHint ->
                                    Text(
                                        text = "Затем: $thenHint",
                                        color = TextMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        if (isOffRoute) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AlertRed)
                                    .clickable(enabled = !isRerouting) {
                                        val cur = walkState.currentLocation ?: return@clickable
                                        val home = walkState.walkStartPoint ?: return@clickable
                                        isRerouting = true
                                        scope.launch {
                                            val rebuilt = RouteEngine.rerouteBackHome(
                                                currentPos = cur,
                                                homePos = home,
                                                remainingTargetMeters = walkState.remainingRouteMeters,
                                                routeProfile = routeProfile,
                                                userProfile = profile,
                                                directShortcut = false
                                            )
                                            generatedRoute = rebuilt
                                            WalkSessionHolder.replaceActiveRoute(
                                                rebuilt,
                                                "[*] МАРШРУТ ПЕРЕСТРОЕН ОТ ТЕКУЩЕЙ ТОЧКИ"
                                            )
                                            isRerouting = false
                                        }
                                    }
                                    .padding(horizontal = 9.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (isRerouting) "..." else "НОВЫЙ
ПУТЬ",
                                    color = AmoledBlack,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }
            }

            val activeBanner = walkState.eventFlash ?: lastRewardMessage
            if (activeBanner != null) {
                Surface(
                    color = Color(0xF0031F0E),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, PhosphorGreen),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            WalkSessionHolder.clearEventFlash()
                            lastRewardMessage = null
                        }
                ) {
                    Text(
                        text = activeBanner,
                        color = PhosphorGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                }
            }
        }

        // 3. Плавающие кнопки управления картой справа
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MapFabButton(
                symbol = "◎",
                label = "GPS",
                active = followUser
            ) {
                followUser = true
                walkState.currentLocation?.let { loc ->
                    mapViewRef?.controller?.animateTo(loc)
                    mapViewRef?.controller?.setZoom(16.8)
                }
            }

            MapFabButton(
                symbol = if (headingUpMode) "🧭" else "N↑",
                label = if (headingUpMode) "КУРС" else "СЕВЕР",
                active = headingUpMode,
                activeColor = CyanAccent
            ) {
                headingUpMode = !headingUpMode
                if (headingUpMode) {
                    followUser = true
                    walkState.currentLocation?.let { mapViewRef?.controller?.animateTo(it) }
                } else {
                    mapViewRef?.setMapOrientation(0f)
                }
            }

            if (generatedRoute != null) {
                MapFabButton(
                    symbol = "⤢",
                    label = "КРУГ",
                    active = false
                ) {
                    fitRouteToMap(generatedRoute!!)
                }
            }

            if (!walkState.isWalking && directionAnchor != null) {
                MapFabButton(
                    symbol = "✕",
                    label = "ВЕКТОР",
                    active = true,
                    activeColor = AmberAccent
                ) {
                    directionAnchor = null
                }
            }
        }

        // 4. Нижняя панель управления + безопасная карточка выбранной метки
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            selectedPin?.let { pin ->
                Surface(
                    color = Color(0xF50A110E),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CyanAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "[${pin.badge}] ${pin.title}",
                                color = CyanAccent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = pin.subtitle,
                                color = TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                        if (pin.canSteerHere && !walkState.isWalking) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(EmeraldDim)
                                    .border(1.dp, PhosphorGreen, RoundedCornerShape(8.dp))
                                    .clickable {
                                        directionAnchor = pin.point
                                        triggerGenerateRoute(pin.point)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "СЮДА",
                                    color = PhosphorGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(CardBg)
                                .clickable { selectedPin = null }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "✕",
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Surface(
                color = PanelBg,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                border = BorderStroke(1.dp, BorderSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    if (!walkState.isWalking) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(CardBg)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            InputMode.entries.forEach { mode ->
                                val selected = inputMode == mode
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) EmeraldDim else Color.Transparent)
                                        .border(
                                            1.dp,
                                            if (selected) PhosphorGreen else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            inputMode = mode
                                            sliderValue = when (mode) {
                                                InputMode.TIME -> 15f
                                                InputMode.DISTANCE -> 3.0f
                                                InputMode.STEPS -> 4000f
                                            }
                                        }
                                        .padding(vertical = 7.dp)
                                ) {
                                    Text(
                                        text = mode.label,
                                        color = if (selected) PhosphorGreen else TextMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RouteProfile.entries.forEach { rp ->
                                val selected = routeProfile == rp
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) Color(0xFF041E24) else CardBg)
                                        .border(
                                            1.dp,
                                            if (selected) CyanAccent else BorderSubtle,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable { routeProfile = rp }
                                        .padding(vertical = 6.dp)
                                ) {
                                    Text(
                                        text = rp.label,
                                        color = if (selected) CyanAccent else TextMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val presets = when (inputMode) {
                            InputMode.TIME -> listOf(15f to "15м", 25f to "25м", 40f to "40м", 60f to "60м")
                            InputMode.DISTANCE -> listOf(1.5f to "1.5км", 3.0f to "3км", 5.0f to "5км", 8.0f to "8км")
                            InputMode.STEPS -> listOf(2000f to "2k", 4000f to "4k", 7000f to "7k", 10000f to "10k")
                        }
                        val formattedTarget = when (inputMode) {
                            InputMode.TIME -> "${sliderValue.toInt()} мин (~${String.format(Locale.US, "%.1f", targetMeters / 1000.0)} км)"
                            InputMode.DISTANCE -> "${String.format(Locale.US, "%.1f", sliderValue)} км (~${((targetMeters / 1000.0) / profile.paceKmh * 60).toInt()} мин)"
                            InputMode.STEPS -> "${sliderValue.toInt()} шагов (~${String.format(Locale.US, "%.1f", targetMeters / 1000.0)} км)"
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formattedTarget,
                                color = TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                presets.forEach { (presetVal, label) ->
                                    val isPresetActive = sliderValue == presetVal
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isPresetActive) EmeraldDim else CardBg)
                                            .border(
                                                1.dp,
                                                if (isPresetActive) PhosphorGreen else BorderSubtle,
                                                RoundedCornerShape(6.dp)
                                            )
                                            .clickable { sliderValue = presetVal }
                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isPresetActive) PhosphorGreen else TextMuted,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }

                        val range = when (inputMode) {
                            InputMode.TIME -> 10f..90f
                            InputMode.DISTANCE -> 1f..12f
                            InputMode.STEPS -> 1000f..15000f
                        }
                        Slider(
                            value = sliderValue,
                            onValueChange = {
                                sliderValue = when (inputMode) {
                                    InputMode.TIME -> (it / 5f).toInt() * 5f
                                    InputMode.DISTANCE -> ((it * 2f).toInt() / 2f)
                                    InputMode.STEPS -> (it / 500f).toInt() * 500f
                                }
                            },
                            valueRange = range,
                            modifier = Modifier.height(30.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = PhosphorGreen,
                                activeTrackColor = PhosphorGreen,
                                inactiveTrackColor = BorderSubtle
                            )
                        )

                        generatedRoute?.let { route ->
                            val estXp = (((route.distanceMeters / 10.0) + 60 + route.beacons.size * 35) * profile.streakMultiplier).roundToInt()
                            Surface(
                                color = CardBg,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, BorderSubtle),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clickable { showStepsDialog = true }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val offlineTag = if (route.isOfflineFallback) " [OFFLINE]" else ""
                                    Column {
                                        Text(
                                            text = String.format(
                                                Locale.US,
                                                "%.2f км  •  ~%d мин  •  ~%d шаг.%s",
                                                route.distanceMeters / 1000.0,
                                                route.estimatedMinutes,
                                                route.estimatedSteps,
                                                offlineTag
                                            ),
                                            color = TextPrimary,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = "Поворотов: ${route.maneuvers.size} (нажмите для списка)  •  Маяков: ${route.beacons.size}",
                                            color = TextMuted,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(EmeraldDim)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "~+$estXp XP",
                                            color = PhosphorGreen,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { triggerGenerateRoute() },
                                enabled = !isGenerating,
                                colors = ButtonDefaults.buttonColors(containerColor = CardBg),
                                border = BorderStroke(1.dp, PhosphorGreen),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text(
                                    text = if (isGenerating) "РАСЧЁТ..." else if (generatedRoute == null) "ПОСТРОИТЬ КРУГ" else "ДРУГОЙ КРУГ",
                                    color = PhosphorGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }

                            if (generatedRoute != null) {
                                Button(
                                    onClick = {
                                        val hasLoc = ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.ACCESS_FINE_LOCATION
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (!hasLoc) {
                                            Toast.makeText(context, "Нужен доступ к геолокации", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        selectedPin = null
                                        followUser = true
                                        headingUpMode = true
                                        confirmFinishArmed = false
                                        WalkSessionHolder.startNewSession(
                                            startPt = walkState.currentLocation,
                                            route = generatedRoute,
                                            knownSectors = profile.exploredSectors,
                                            haptic = profile.hapticNavEnabled,
                                            voice = profile.voiceNavEnabled
                                        )
                                        val svcIntent = Intent(context, WalkTrackingService::class.java).apply {
                                            action = WalkTrackingService.ACTION_START
                                        }
                                        ContextCompat.startForegroundService(context, svcIntent)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PhosphorGreen),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                ) {
                                    Text(
                                        text = "СТАРТ",
                                        color = AmoledBlack,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        val file = storage.exportGpxFile(generatedRoute!!)
                                        val uri = FileProvider.getUriForFile(
                                            context, "${context.packageName}.fileprovider", file
                                        )
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/gpx+xml"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Экспорт GPX"))
                                    },
                                    border = BorderStroke(1.dp, BorderSubtle),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    modifier = Modifier.height(44.dp)
                                ) {
                                    Text(
                                        text = "GPX",
                                        color = TextMuted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (directionAnchor != null) {
                                "Вектор активен: круг ориентирован на выбранную точку"
                            } else {
                                "Удерживайте палец на карте, чтобы задать направление круга"
                            },
                            color = TextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                    } else {
                        // Экран активной прогулки
                        val mins = walkState.elapsedSeconds / 60
                        val secs = walkState.elapsedSeconds % 60
                        val displaySteps = if (walkState.sessionSteps > 0) {
                            walkState.sessionSteps
                        } else {
                            (walkState.walkedMeters / profile.strideMeters).toInt()
                        }
                        val plannedMeters = generatedRoute?.distanceMeters ?: targetMeters
                        val progressRatio = (walkState.walkedMeters / plannedMeters.coerceAtLeast(100.0))
                            .toFloat().coerceIn(0f, 1f)
                        val remMin = ((walkState.remainingRouteMeters / 1000.0) / profile.paceKmh * 60.0).roundToInt()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%s %02d:%02d",
                                    if (walkState.isPaused) "ПАУЗА" else "В ПУТИ",
                                    mins,
                                    secs
                                ),
                                color = AmberAccent,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "ОСТАЛОСЬ: ~${remMin} мин (${(progressRatio * 100).roundToInt()}%)",
                                color = PhosphorGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(CardBg)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progressRatio.coerceAtLeast(0.02f))
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(PhosphorGreen)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MetricBox(
                                label = "ПРОЙДЕНО",
                                value = String.format(Locale.US, "%.2f км", walkState.walkedMeters / 1000.0),
                                accent = PhosphorGreen,
                                modifier = Modifier.weight(1f)
                            )
                            MetricBox(
                                label = "ШАГИ",
                                value = "$displaySteps",
                                accent = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            MetricBox(
                                label = "ОСТАТОК",
                                value = String.format(Locale.US, "%.2f км", walkState.remainingRouteMeters / 1000.0),
                                accent = CyanAccent,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val collectedCount = walkState.activeBeacons.count { it.collected }
                            val totalBeacons = walkState.activeBeacons.size
                            Text(
                                text = "СЕКТОРА: +${walkState.newlyDiscoveredSectors.size}  |  МАЯКИ: $collectedCount/$totalBeacons",
                                color = CyanAccent,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                            walkState.nearestBeaconMeters?.let { dist ->
                                Text(
                                    text = "ДО МАЯКА: $dist м",
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { WalkSessionHolder.togglePause() },
                                border = BorderStroke(1.dp, BorderSubtle),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text(
                                    text = if (walkState.isPaused) "ДАЛЕЕ" else "ПАУЗА",
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    val cur = walkState.currentLocation ?: return@OutlinedButton
                                    val home = walkState.walkStartPoint ?: return@OutlinedButton
                                    isRerouting = true
                                    scope.launch {
                                        val shortcut = RouteEngine.rerouteBackHome(
                                            currentPos = cur,
                                            homePos = home,
                                            remainingTargetMeters = 0.0,
                                            routeProfile = routeProfile,
                                            userProfile = profile,
                                            directShortcut = true
                                        )
                                        generatedRoute = shortcut
                                        WalkSessionHolder.replaceActiveRoute(
                                            shortcut,
                                            "[*] ПОСТРОЕН ПРЯМОЙ ПУТЬ ДОМОЙ"
                                        )
                                        isRerouting = false
                                    }
                                },
                                enabled = !isRerouting,
                                border = BorderStroke(1.dp, CyanAccent),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier
                                    .weight(0.95f)
                                    .height(44.dp)
                            ) {
                                Text(
                                    text = if (isRerouting) "..." else "ДОМОЙ",
                                    color = CyanAccent,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }

                            Button(
                                onClick = {
                                    if (walkState.elapsedSeconds < 60L && !confirmFinishArmed) {
                                        confirmFinishArmed = true
                                        return@Button
                                    }
                                    confirmFinishArmed = false
                                    headingUpMode = false
                                    val finalState = WalkSessionHolder.stopSession()
                                    context.stopService(Intent(context, WalkTrackingService::class.java))

                                    val hDist = if (finalState.walkStartPoint != null && finalState.currentLocation != null) {
                                        finalState.walkStartPoint.distanceToAsDouble(finalState.currentLocation)
                                    } else 999.0

                                    val finalSteps = if (finalState.sessionSteps > 0) {
                                        finalState.sessionSteps
                                    } else {
                                        (finalState.walkedMeters / profile.strideMeters).toInt()
                                    }

                                    val targetSec = ((generatedRoute?.distanceMeters ?: targetMeters) / 1000.0 / profile.paceKmh * 3600).toLong()
                                    val (updated, earnedXp) = storage.completeWalk(
                                        walkedMeters = finalState.walkedMeters,
                                        elapsedSeconds = finalState.elapsedSeconds,
                                        targetSeconds = targetSec,
                                        steps = finalSteps,
                                        trail = finalState.breadcrumbTrail,
                                        newSectors = finalState.newlyDiscoveredSectors,
                                        beaconsCollected = finalState.activeBeacons.count { it.collected },
                                        totalBeacons = finalState.activeBeacons.size,
                                        returnedHome = hDist <= 85.0
                                    )
                                    profile = updated
                                    lastRewardMessage = "[+] КРУГ ЗАВЕРШЁН: +$earnedXp XP  |  СЕРИЯ: ${updated.streakDays} ДН."
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (confirmFinishArmed) AlertRed else AmberAccent
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier
                                    .weight(1.25f)
                                    .height(44.dp)
                            ) {
                                Text(
                                    text = if (confirmFinishArmed) "ТОЧНО?" else "ЗАВЕРШИТЬ",
                                    color = AmoledBlack,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Интерактивная шторка списка поворотов маршрута
    if (showStepsDialog && generatedRoute != null) {
        ModalBottomSheet(
            onDismissRequest = { showStepsDialog = false },
            containerColor = Color(0xFF090D0B),
            contentColor = TextPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            val steps = generatedRoute!!.maneuvers
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "ПОШАГОВЫЙ МАРШРУТ (${steps.size})",
                    color = PhosphorGreen,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = "Нажмите на шаг, чтобы показать перекрёсток на карте",
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(steps) { m ->
                        Surface(
                            color = CardBg,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderSubtle),
                            modifier = Modifier.clickable {
                                followUser = false
                                showStepsDialog = false
                                mapViewRef?.controller?.animateTo(m.point)
                                mapViewRef?.controller?.setZoom(17.5)
                                selectedPin = SelectedPinInfo(
                                    title = m.direction.shortLabel,
                                    subtitle = m.instruction,
                                    badge = "МАНЕВР",
                                    point = m.point,
                                    canSteerHere = false
                                )
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = m.direction.glyph,
                                    color = AmberAccent,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = m.instruction,
                                        color = TextPrimary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                    if (m.distanceKm > 0.0) {
                                        Text(
                                            text = "Участок: ${(m.distanceKm * 1000).roundToInt()} м",
                                            color = TextMuted,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Единая нижняя шторка: Профиль / Журнал / Калибровка
    if (activeSheetTab != null) {
        ModalBottomSheet(
            onDismissRequest = { activeSheetTab = null },
            containerColor = Color(0xFF090D0B),
            contentColor = TextPrimary,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            var currentTab by remember(activeSheetTab) { mutableIntStateOf(activeSheetTab ?: 0) }
            val tabs = listOf("ДОСТИЖЕНИЯ", "ЖУРНАЛ", "КАЛИБРОВКА")

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CardBg)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tabs.forEachIndexed { idx, title ->
                        val selected = currentTab == idx
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) EmeraldDim else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (selected) PhosphorGreen else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { currentTab = idx }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = title,
                                color = if (selected) PhosphorGreen else TextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                when (currentTab) {
                    0 -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MetricBox(
                                label = "ПРОЙДЕНО",
                                value = String.format(Locale.US, "%.1f км", profile.totalMeters / 1000.0),
                                accent = PhosphorGreen,
                                modifier = Modifier.weight(1f)
                            )
                            MetricBox(
                                label = "СЕКТОРА",
                                value = "${profile.exploredSectors.size}",
                                accent = CyanAccent,
                                modifier = Modifier.weight(1f)
                            )
                            MetricBox(
                                label = "ЗАХВАТ",
                                value = String.format(Locale.US, "%.2f га", profile.totalCapturedAreaSqM / 10000.0),
                                accent = AmberAccent,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(profile.achievements) { ach ->
                                Surface(
                                    color = CardBg,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (ach.unlocked) PhosphorGreen else BorderSubtle
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = ach.title,
                                                color = if (ach.unlocked) PhosphorGreen else TextPrimary,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp
                                            )
                                            Text(
                                                text = if (ach.unlocked) "[ОТКРЫТО]" else "${(ach.progressRatio * 100).roundToInt()}%",
                                                color = if (ach.unlocked) PhosphorGreen else TextMuted,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = ach.description,
                                            color = TextMuted,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF141D18))
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth(ach.progressRatio.coerceAtLeast(0.02f))
                                                    .fillMaxHeight()
                                                    .clip(CircleShape)
                                                    .background(if (ach.unlocked) PhosphorGreen else CyanAccent)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        val dateFmt = remember { SimpleDateFormat("dd.MM.yyyy  HH:mm", Locale.getDefault()) }
                        if (profile.walkHistory.isEmpty()) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                            ) {
                                Text(
                                    text = "Журнал пуст — завершите первый круг.",
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 380.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(profile.walkHistory.take(25)) { rec ->
                                    Surface(
                                        color = CardBg,
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, BorderSubtle)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text(
                                                    text = dateFmt.format(Date(rec.timestampMs)),
                                                    color = TextMuted,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 10.sp
                                                )
                                                Text(
                                                    text = String.format(
                                                        Locale.US,
                                                        "%.2f км  •  %d мин  •  %d шаг.",
                                                        rec.distanceMeters / 1000.0,
                                                        rec.elapsedSeconds / 60,
                                                        rec.steps
                                                    ),
                                                    color = TextPrimary,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp
                                                )
                                                if (rec.capturedAreaSqM > 0 || rec.newSectorsCount > 0) {
                                                    Text(
                                                        text = "Сектора: +${rec.newSectorsCount}  •  Контур: ${rec.capturedAreaSqM.roundToInt()} м²",
                                                        color = CyanAccent,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(EmeraldDim)
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = "+${rec.earnedXp} XP",
                                                    color = PhosphorGreen,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        var heightInput by remember { mutableStateOf(profile.heightCm.toString()) }
                        var paceInput by remember { mutableStateOf(profile.paceKmh.toString()) }
                        var hapticInput by remember { mutableStateOf(profile.hapticNavEnabled) }
                        var voiceInput by remember { mutableStateOf(profile.voiceNavEnabled) }
                        var milepostsInput by remember { mutableStateOf(profile.showMileposts) }
                        var screenOnInput by remember { mutableStateOf(profile.keepScreenOn) }

                        val previewStride = ((heightInput.toIntOrNull() ?: profile.heightCm) * 0.415) / 100.0
                        val fieldColors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = PhosphorGreen,
                            unfocusedBorderColor = BorderSubtle,
                            focusedLabelColor = PhosphorGreen,
                            unfocusedLabelColor = TextMuted,
                            cursorColor = PhosphorGreen
                        )

                        LazyColumn(
                            modifier = Modifier.heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Text(
                                    text = String.format(Locale.US, "Расчётная длина шага: %.2f м", previewStride),
                                    color = CyanAccent,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                            }
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = heightInput,
                                        onValueChange = { heightInput = it },
                                        label = { Text("Рост (см)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                                        singleLine = true,
                                        colors = fieldColors,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = paceInput,
                                        onValueChange = { paceInput = it },
                                        label = { Text("Темп (км/ч)", fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                                        singleLine = true,
                                        colors = fieldColors,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            item {
                                SettingToggleRow(
                                    title = "Голосовой навигатор (офлайн TTS)",
                                    checked = voiceInput,
                                    onToggle = { voiceInput = !voiceInput }
                                )
                            }
                            item {
                                SettingToggleRow(
                                    title = "Вибро-сигналы поворотов и маяков",
                                    checked = hapticInput,
                                    onToggle = { hapticInput = !hapticInput }
                                )
                            }
                            item {
                                SettingToggleRow(
                                    title = "Метки дистанции (0.5 км, 1.0 км)",
                                    checked = milepostsInput,
                                    onToggle = { milepostsInput = !milepostsInput }
                                )
                            }
                            item {
                                SettingToggleRow(
                                    title = "Не выключать экран во время прогулки",
                                    checked = screenOnInput,
                                    onToggle = { screenOnInput = !screenOnInput }
                                )
                            }
                            item {
                                Button(
                                    onClick = {
                                        val h = heightInput.toIntOrNull() ?: profile.heightCm
                                        val p = paceInput.replace(',', '.').toDoubleOrNull() ?: profile.paceKmh
                                        WalkSessionHolder.voiceEnabled = voiceInput
                                        WalkSessionHolder.hapticEnabled = hapticInput
                                        profile = storage.updateSettings(h, p, hapticInput, voiceInput, milepostsInput, screenOnInput)
                                        activeSheetTab = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PhosphorGreen),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                ) {
                                    Text(
                                        text = "СОХРАНИТЬ НАСТРОЙКИ",
                                        color = AmoledBlack,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun HudChipButton(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(CardBg)
            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun MapFabButton(
    symbol: String,
    label: String,
    active: Boolean,
    activeColor: Color = PhosphorGreen,
    onClick: () -> Unit
) {
    Surface(
        color = PanelBg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (active) activeColor else BorderSubtle),
        modifier = Modifier
            .width(54.dp)
            .clickable { onClick() }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 7.dp)
        ) {
            Text(
                text = symbol,
                color = if (active) activeColor else TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                color = if (active) activeColor else TextMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp
            )
        }
    }
}

@Composable
private fun MetricBox(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = CardBg,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = label,
                color = TextMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardBg)
            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = AmoledBlack,
                checkedTrackColor = PhosphorGreen
            )
        )
    }
}
