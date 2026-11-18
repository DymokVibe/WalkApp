package com.loopwalk.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AmoledBlack = Color(0xFF000000)
private val CardDark = Color(0xFF0A0A0A)
private val BorderDark = Color(0xFF222222)
private val PhosphorGreen = Color(0xFF00FF66)
private val AmberAccent = Color(0xFFFFB000)
private val CyanAccent = Color(0xFF00E5FF)
private val TextMuted = Color(0xFF888888)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContent {
            LoopWalkApp()
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun LoopWalkApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = remember { StorageManager(context) }

    var profile by remember { mutableStateOf(storage.loadProfile()) }
    val mutator = remember { storage.getTodayMutator() }
    val walkState by WalkSessionHolder.state.collectAsState()

    var generatedRoute by remember { mutableStateOf<LoopRoute?>(null) }
    var isGenerating by remember { mutableStateOf(false) }

    var inputMode by remember { mutableStateOf(InputMode.TIME) }
    var routeProfile by remember { mutableStateOf(RouteProfile.SCENIC) }
    var sliderValue by remember { mutableFloatStateOf(15f) }

    var lastRewardMessage by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showLogbook by remember { mutableStateOf(false) }

    val targetMeters = remember(inputMode, sliderValue, profile) {
        when (inputMode) {
            InputMode.TIME -> (sliderValue / 60.0) * profile.paceKmh * 1000.0
            InputMode.DISTANCE -> sliderValue * 1000.0
            InputMode.STEPS -> sliderValue * profile.strideMeters
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
                WalkSessionHolder.updateLocationPassive(GeoPoint(lastGps.latitude, lastGps.longitude))
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

        val hasLoc = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasLoc && walkState.currentLocation == null) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (lastGps != null) {
                WalkSessionHolder.updateLocationPassive(GeoPoint(lastGps.latitude, lastGps.longitude))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .padding(10.dp)
    ) {
        // Верхний HUD профиля
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            border = BorderStroke(1.dp, BorderDark),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LVL ${profile.level} // ${profile.rankTitle}",
                        color = PhosphorGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "[ЖУРНАЛ]",
                            color = CyanAccent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { showLogbook = true }
                        )
                        Text(
                            text = "[CFG]",
                            color = TextMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.clickable { showSettings = true }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "СЕРИЯ: ${profile.streakDays}д (x${String.format(Locale.US, "%.1f", profile.streakMultiplier)})",
                        color = AmberAccent,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "КРИО-ЩИТЫ: ${profile.cryoShields} | XP: ${profile.xpInCurrentLevel}/500",
                        color = TextMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { profile.xpInCurrentLevel / 500f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = PhosphorGreen,
                    trackColor = BorderDark
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${mutator.title} ${mutator.description}",
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }

        // Уведомления о награде или расшифровке маяка
        val activeBanner = walkState.eventFlash ?: lastRewardMessage
        if (activeBanner != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, PhosphorGreen, RoundedCornerShape(4.dp))
                    .background(Color(0xFF001C0B))
                    .clickable {
                        WalkSessionHolder.clearEventFlash()
                        lastRewardMessage = null
                    }
                    .padding(8.dp)
            ) {
                Text(
                    text = activeBanner,
                    color = PhosphorGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Карта AMOLED + Туман войны + Захваченные контуры + Маяки
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .border(1.dp, BorderDark, RoundedCornerShape(6.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
                        controller.setZoom(15.5)
                    }
                },
                update = { mapView ->
                    mapView.overlays.clear()

                    // 1. Захваченные замкнутые территории ("Контуры")
                    for (polyPoints in profile.capturedPolygons.takeLast(30)) {
                        val territory = Polygon().apply {
                            points = polyPoints
                            fillPaint.color = AndroidColor.argb(35, 0, 229, 255)
                            outlinePaint.color = AndroidColor.argb(120, 0, 229, 255)
                            outlinePaint.strokeWidth = 2f
                        }
                        mapView.overlays.add(territory)
                    }

                    // 2. Открытые сектора Тумана войны
                    val allSectors = profile.exploredSectors + walkState.newlyDiscoveredSectors
                    for (sec in allSectors.toList().takeLast(160)) {
                        val poly = Polygon().apply {
                            points = storage.sectorPolygon(sec)
                            fillPaint.color = AndroidColor.argb(32, 0, 255, 102)
                            outlinePaint.color = AndroidColor.argb(65, 0, 255, 102)
                            outlinePaint.strokeWidth = 1f
                        }
                        mapView.overlays.add(poly)
                    }

                    // 3. Плановый круговой маршрут
                    generatedRoute?.let { route ->
                        val line = Polyline().apply {
                            setPoints(route.points)
                            outlinePaint.color = AndroidColor.rgb(0, 255, 102)
                            outlinePaint.strokeWidth = 9f
                        }
                        mapView.overlays.add(line)
                    }

                    // 4. Сигнальные маяки на маршруте
                    val beaconsToShow = if (walkState.isWalking) {
                        walkState.activeBeacons
                    } else {
                        generatedRoute?.beacons.orEmpty()
                    }
                    for (b in beaconsToShow) {
                        val bMarker = Marker(mapView).apply {
                            position = b.point
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = if (b.collected) "[OK] ${b.title}" else "[!] ${b.title} (+${b.xpReward} XP)"
                        }
                        mapView.overlays.add(bMarker)
                    }

                    // 5. Пройденный след
                    if (walkState.breadcrumbTrail.size >= 2) {
                        val trail = Polyline().apply {
                            setPoints(walkState.breadcrumbTrail)
                            outlinePaint.color = AndroidColor.rgb(255, 176, 0)
                            outlinePaint.strokeWidth = 7f
                        }
                        mapView.overlays.add(trail)
                    }

                    // 6. Текущая позиция
                    walkState.currentLocation?.let { loc ->
                        val marker = Marker(mapView).apply {
                            position = loc
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            title = "ВЫ"
                        }
                        mapView.overlays.add(marker)
                        if (generatedRoute == null || walkState.isWalking) {
                            mapView.controller.animateTo(loc)
                        }
                    }

                    mapView.invalidate()
                },
                modifier = Modifier.fillMaxSize()
            )

            // Верхний инфо-оверлей на карте
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                generatedRoute?.let { route ->
                    Box(
                        modifier = Modifier
                            .background(AmoledBlack.copy(alpha = 0.88f), RoundedCornerShape(4.dp))
                            .border(1.dp, BorderDark, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        val modeTag = if (route.isOfflineFallback) " [OFFLINE]" else ""
                        Text(
                            text = String.format(
                                Locale.US,
                                "КРУГ: %.2f км | ~%d мин | ~%d шаг.%s",
                                route.distanceMeters / 1000.0,
                                route.estimatedMinutes,
                                route.estimatedSteps,
                                modeTag
                            ),
                            color = PhosphorGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }

                if (walkState.isWalking && walkState.nextManeuverHint != null) {
                    Box(
                        modifier = Modifier
                            .background(AmoledBlack.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                            .border(1.dp, AmberAccent, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        val distPrefix = walkState.distanceToNextTurnMeters?.let { "Через $it м -> " } ?: ""
                        Text(
                            text = "$distPrefix${walkState.nextManeuverHint}",
                            color = AmberAccent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Нижняя панель управления
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            border = BorderStroke(1.dp, BorderDark),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (!walkState.isWalking) {
                    // Выбор единицы измерения (Время / Дистанция / Шаги)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        InputMode.entries.forEach { mode ->
                            val selected = inputMode == mode
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .border(
                                        1.dp,
                                        if (selected) PhosphorGreen else BorderDark,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .background(if (selected) Color(0xFF00220D) else AmoledBlack)
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

                    Spacer(modifier = Modifier.height(6.dp))

                    // Выбор профиля дороги (Парк / Асфальт / Вечер-Фонари)
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
                                    .border(
                                        1.dp,
                                        if (selected) CyanAccent else BorderDark,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .background(if (selected) Color(0xFF001C22) else AmoledBlack)
                                    .clickable { routeProfile = rp }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    text = rp.label,
                                    color = if (selected) CyanAccent else TextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val range = when (inputMode) {
                        InputMode.TIME -> 10f..90f
                        InputMode.DISTANCE -> 1f..12f
                        InputMode.STEPS -> 1000f..15000f
                    }
                    val formattedTarget = when (inputMode) {
                        InputMode.TIME -> "${sliderValue.toInt()} мин (~${String.format(Locale.US, "%.1f", targetMeters / 1000.0)} км)"
                        InputMode.DISTANCE -> "${String.format(Locale.US, "%.1f", sliderValue)} км (~${((targetMeters / 1000.0) / profile.paceKmh * 60).toInt()} мин)"
                        InputMode.STEPS -> "${sliderValue.toInt()} шагов (~${String.format(Locale.US, "%.1f", targetMeters / 1000.0)} км)"
                    }

                    Text(
                        text = "ЦЕЛЬ: $formattedTarget",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )

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
                        colors = SliderDefaults.colors(
                            thumbColor = PhosphorGreen,
                            activeTrackColor = PhosphorGreen,
                            inactiveTrackColor = BorderDark
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val loc = walkState.currentLocation
                                if (loc == null) {
                                    Toast.makeText(context, "Ожидание GPS-сигнала...", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isGenerating = true
                                scope.launch {
                                    generatedRoute = RouteEngine.generateLoop(loc, targetMeters, routeProfile, profile)
                                    isGenerating = false
                                }
                            },
                            enabled = !isGenerating,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF141414)),
                            border = BorderStroke(1.dp, PhosphorGreen),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (isGenerating) "РАСЧЁТ..." else "НОВЫЙ КРУГ",
                                color = PhosphorGreen,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
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
                                    WalkSessionHolder.startNewSession(
                                        startPt = walkState.currentLocation,
                                        route = generatedRoute,
                                        knownSectors = profile.exploredSectors,
                                        haptic = profile.hapticNavEnabled
                                    )
                                    val svcIntent = Intent(context, WalkTrackingService::class.java).apply {
                                        action = WalkTrackingService.ACTION_START
                                    }
                                    ContextCompat.startForegroundService(context, svcIntent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PhosphorGreen),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "СТАРТ",
                                    color = AmoledBlack,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
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
                                border = BorderStroke(1.dp, BorderDark),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "GPX",
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                } else {
                    // Активная прогулка
                    val mins = walkState.elapsedSeconds / 60
                    val secs = walkState.elapsedSeconds % 60
                    val displaySteps = if (walkState.sessionSteps > 0) {
                        walkState.sessionSteps
                    } else {
                        (walkState.walkedMeters / profile.strideMeters).toInt()
                    }
                    val collectedCount = walkState.activeBeacons.count { it.collected }
                    val totalBeacons = walkState.activeBeacons.size

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "%s %02d:%02d",
                                if (walkState.isPaused) "[ПАУЗА]" else "ВРЕМЯ:",
                                mins,
                                secs
                            ),
                            color = AmberAccent,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = String.format(Locale.US, "%.2f км | %d шаг.", walkState.walkedMeters / 1000.0, displaySteps),
                            color = PhosphorGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "СЕКТОРА: +${walkState.newlyDiscoveredSectors.size} | МАЯКИ: $collectedCount/$totalBeacons",
                            color = CyanAccent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        walkState.nearestBeaconMeters?.let { dist ->
                            Text(
                                text = "ДО МАЯКА: $dist м",
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { WalkSessionHolder.togglePause() },
                            border = BorderStroke(1.dp, BorderDark),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (walkState.isPaused) "ПРОДОЛЖИТЬ" else "ПАУЗА",
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = {
                                val finalState = WalkSessionHolder.stopSession()
                                val stopIntent = Intent(context, WalkTrackingService::class.java).apply {
                                    action = WalkTrackingService.ACTION_STOP
                                }
                                context.startService(stopIntent)

                                val homeDist = if (finalState.walkStartPoint != null && finalState.currentLocation != null) {
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
                                    returnedHome = homeDist <= 85.0
                                )
                                profile = updated
                                lastRewardMessage = "[+] КРУГ ЗАВЕРШЁН: +$earnedXp XP | СЕРИЯ: ${updated.streakDays} ДН."
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AmberAccent),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Text(
                                text = "ЗАВЕРШИТЬ",
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
    }

    // Диалог настроек и калибровки
    if (showSettings) {
        var heightInput by remember { mutableStateOf(profile.heightCm.toString()) }
        var paceInput by remember { mutableStateOf(profile.paceKmh.toString()) }
        var hapticInput by remember { mutableStateOf(profile.hapticNavEnabled) }

        AlertDialog(
            onDismissRequest = { showSettings = false },
            containerColor = CardDark,
            title = {
                Text("КАЛИБРОВКА (ZERO-TELEMETRY)", color = PhosphorGreen, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = heightInput,
                        onValueChange = { heightInput = it },
                        label = { Text("Рост (см) для расчёта шага", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = paceInput,
                        onValueChange = { paceInput = it },
                        label = { Text("Средний темп (км/ч)", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { hapticInput = !hapticInput }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Вибро-сигнал поворотов и маяков",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        Checkbox(
                            checked = hapticInput,
                            onCheckedChange = { hapticInput = it }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val h = heightInput.toIntOrNull() ?: profile.heightCm
                    val p = paceInput.replace(',', '.').toDoubleOrNull() ?: profile.paceKmh
                    profile = storage.updateSettings(h, p, hapticInput)
                    showSettings = false
                }) {
                    Text("СОХРАНИТЬ", color = PhosphorGreen, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    // Диалог журнала прогулок и достижений
    if (showLogbook) {
        val dateFmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
        AlertDialog(
            onDismissRequest = { showLogbook = false },
            containerColor = CardDark,
            title = {
                Text("ЖУРНАЛ КОНТУРОВ И АЧИВКИ", color = CyanAccent, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = String.format(
                                Locale.US,
                                "Всего: %.1f км | Сектора: %d | Контуры: %.2f га | Маяки: %d",
                                profile.totalMeters / 1000.0,
                                profile.exploredSectors.size,
                                profile.totalCapturedAreaSqM / 10000.0,
                                profile.totalBeaconsCollected
                            ),
                            color = PhosphorGreen,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "--- ДОСТИЖЕНИЯ ---",
                            color = AmberAccent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    items(profile.achievements) { ach ->
                        val mark = if (ach.unlocked) "[UNLOCKED]" else "[LOCKED]"
                        val col = if (ach.unlocked) PhosphorGreen else TextMuted
                        Text(
                            text = "$mark ${ach.title}: ${ach.description}",
                            color = col,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "--- ПОСЛЕДНИЕ ПРОГУЛКИ ---",
                            color = AmberAccent,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    if (profile.walkHistory.isEmpty()) {
                        item {
                            Text(
                                text = "Записей пока нет.",
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    } else {
                        items(profile.walkHistory.take(15)) { rec ->
                            val dt = dateFmt.format(Date(rec.timestampMs))
                            val areaTag = if (rec.capturedAreaSqM > 0) " | +${rec.capturedAreaSqM.toInt()} м2" else ""
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%s | %.2f км (%d мин) | +%d XP%s",
                                    dt,
                                    rec.distanceMeters / 1000.0,
                                    rec.elapsedSeconds / 60,
                                    rec.earnedXp,
                                    areaTag
                                ),
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogbook = false }) {
                    Text("ЗАКРЫТЬ", color = CyanAccent, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}
