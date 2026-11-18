package com.loopwalk.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

object RouteEngine {

    private val beaconNames = listOf(
        "МАЯК-АЛЬФА", "МАЯК-БЕТА", "УЗЕЛ СВЯЗИ", "РЕТРАНСЛЯТОР",
        "ТОЧКА СБРОСА", "СИГНАЛ-09", "ВЕКТОР-К", "КОНТУР-Х"
    )

    private data class ValhallaResult(
        val points: List<GeoPoint>,
        val distanceMeters: Double,
        val maneuvers: List<RouteManeuver>
    )

    suspend fun generateLoop(
        start: GeoPoint,
        targetMeters: Double,
        routeProfile: RouteProfile,
        userProfile: UserProfile,
        directionAnchor: GeoPoint? = null,
        avoidBearingDeg: Double? = null
    ): LoopRoute = withContext(Dispatchers.IO) {
        val baseBearing = if (directionAnchor != null) {
            start.bearingTo(directionAnchor)
        } else if (avoidBearingDeg != null) {
            (avoidBearingDeg + Random.nextDouble(65.0, 295.0)) % 360.0
        } else {
            Random.nextDouble(0.0, 360.0)
        }
        val direction = if (Random.nextBoolean()) 1.0 else -1.0

        var legRadiusMeters = if (directionAnchor != null) {
            val anchorDist = start.distanceToAsDouble(directionAnchor)
            anchorDist.coerceIn(targetMeters * 0.22, targetMeters * 0.40)
        } else {
            targetMeters / 4.35
        }

        var bestResult: ValhallaResult? = null

        for (attempt in 0 until 3) {
            val waypoints = if (targetMeters >= 2200.0) {
                val wp1 = offsetPoint(start, legRadiusMeters * 0.92, baseBearing + 44.0 * direction)
                val wp2 = offsetPoint(start, legRadiusMeters * 1.15, baseBearing)
                val wp3 = offsetPoint(start, legRadiusMeters * 0.92, baseBearing - 44.0 * direction)
                listOf(start, wp1, wp2, wp3, start)
            } else {
                val wp1 = offsetPoint(start, legRadiusMeters, baseBearing + 38.0 * direction)
                val wp2 = offsetPoint(start, legRadiusMeters, baseBearing - 38.0 * direction)
                listOf(start, wp1, wp2, start)
            }

            val result = queryValhallaPedestrian(waypoints, routeProfile, userProfile.paceKmh)
            if (result != null) {
                bestResult = result
                val ratio = targetMeters / result.distanceMeters
                if (ratio in 0.88..1.12) break
                legRadiusMeters *= ratio.coerceIn(0.5, 1.8)
            } else {
                break
            }
        }

        if (bestResult != null && bestResult.points.size >= 2) {
            val minutes = ((bestResult.distanceMeters / 1000.0) / userProfile.paceKmh * 60.0)
                .toInt().coerceAtLeast(1)
            val steps = (bestResult.distanceMeters / userProfile.strideMeters).toInt()
            val beacons = buildBeacons(bestResult.points)
            val mileposts = buildMileposts(bestResult.points, bestResult.distanceMeters)
            val arrows = buildDirectionArrows(bestResult.points, bestResult.distanceMeters)

            LoopRoute(
                points = bestResult.points,
                distanceMeters = bestResult.distanceMeters,
                estimatedMinutes = minutes,
                estimatedSteps = steps,
                profile = routeProfile,
                baseBearingDeg = baseBearing,
                maneuvers = bestResult.maneuvers,
                beacons = beacons,
                mileposts = mileposts,
                arrows = arrows,
                isOfflineFallback = false
            )
        } else {
            val r = targetMeters / 3.25
            val wp1 = offsetPoint(start, r, baseBearing + 38.0 * direction)
            val wp2 = offsetPoint(start, r, baseBearing - 38.0 * direction)
            val fallbackPoints = listOf(start, wp1, wp2, start)
            val minutes = ((targetMeters / 1000.0) / userProfile.paceKmh * 60.0)
                .toInt().coerceAtLeast(1)
            val steps = (targetMeters / userProfile.strideMeters).toInt()
            val beacons = listOf(
                RouteBeacon(1, wp1, beaconNames[0]),
                RouteBeacon(2, wp2, beaconNames[1])
            )
            val mileposts = buildMileposts(fallbackPoints, targetMeters)
            val arrows = buildDirectionArrows(fallbackPoints, targetMeters)
            val offlineManeuvers = listOf(
                RouteManeuver(start, "Двигайтесь к первой точке контура", targetMeters / 3000.0, 0, TurnDirection.START),
                RouteManeuver(wp1, "Поверните на вторую дугу круга", targetMeters / 3000.0, 1, TurnDirection.RIGHT),
                RouteManeuver(wp2, "Возвращайтесь к точке старта", targetMeters / 3000.0, 2, TurnDirection.RIGHT),
                RouteManeuver(start, "Точка старта достигнута", 0.0, 3, TurnDirection.FINISH)
            )

            LoopRoute(
                points = fallbackPoints,
                distanceMeters = targetMeters,
                estimatedMinutes = minutes,
                estimatedSteps = steps,
                profile = routeProfile,
                baseBearingDeg = baseBearing,
                maneuvers = offlineManeuvers,
                beacons = beacons,
                mileposts = mileposts,
                arrows = arrows,
                isOfflineFallback = true
            )
        }
    }

    /**
     * Перестраивает путь домой от текущей позиции (кратчайший или с добором дистанции).
     */
    suspend fun rerouteBackHome(
        currentPos: GeoPoint,
        homePos: GeoPoint,
        remainingTargetMeters: Double,
        routeProfile: RouteProfile,
        userProfile: UserProfile,
        directShortcut: Boolean = false
    ): LoopRoute = withContext(Dispatchers.IO) {
        val directDist = currentPos.distanceToAsDouble(homePos)
        val waypoints = if (!directShortcut && remainingTargetMeters > directDist * 1.45 && remainingTargetMeters >= 500.0) {
            val bearingHome = currentPos.bearingTo(homePos)
            val mid = GeoPoint(
                (currentPos.latitude + homePos.latitude) / 2.0,
                (currentPos.longitude + homePos.longitude) / 2.0
            )
            val sideOffset = ((remainingTargetMeters - directDist) * 0.45).coerceIn(120.0, 900.0)
            val via = offsetPoint(mid, sideOffset, bearingHome + 75.0)
            listOf(currentPos, via, homePos)
        } else {
            listOf(currentPos, homePos)
        }

        val result = queryValhallaPedestrian(waypoints, routeProfile, userProfile.paceKmh)
        if (result != null && result.points.size >= 2) {
            val minutes = ((result.distanceMeters / 1000.0) / userProfile.paceKmh * 60.0)
                .toInt().coerceAtLeast(1)
            val steps = (result.distanceMeters / userProfile.strideMeters).toInt()
            LoopRoute(
                points = result.points,
                distanceMeters = result.distanceMeters,
                estimatedMinutes = minutes,
                estimatedSteps = steps,
                profile = routeProfile,
                maneuvers = result.maneuvers,
                beacons = emptyList(),
                mileposts = buildMileposts(result.points, result.distanceMeters),
                arrows = buildDirectionArrows(result.points, result.distanceMeters),
                isOfflineFallback = false
            )
        } else {
            val fallbackPoints = listOf(currentPos, homePos)
            val dist = directDist.coerceAtLeast(50.0)
            val minutes = ((dist / 1000.0) / userProfile.paceKmh * 60.0).toInt().coerceAtLeast(1)
            LoopRoute(
                points = fallbackPoints,
                distanceMeters = dist,
                estimatedMinutes = minutes,
                estimatedSteps = (dist / userProfile.strideMeters).toInt(),
                profile = routeProfile,
                maneuvers = listOf(
                    RouteManeuver(currentPos, "Двигайтесь по направлению к дому", dist / 1000.0, 0, TurnDirection.STRAIGHT),
                    RouteManeuver(homePos, "Вы прибыли к дому", 0.0, 1, TurnDirection.FINISH)
                ),
                arrows = buildDirectionArrows(fallbackPoints, dist),
                isOfflineFallback = true
            )
        }
    }

    /**
     * Проецирует текущие GPS-координаты на полилинию маршрута внутри заданного диапазона сегментов,
     * чтобы не перескакивать на встречную/финишную ветку петли.
     */
    fun projectOntoRoute(
        pt: GeoPoint,
        path: List<GeoPoint>,
        minSegmentIdx: Int = 0,
        maxSegmentLimit: Int = path.lastIndex
    ): RouteProjection {
        if (path.size < 2) {
            return RouteProjection(0, pt, 0.0, 0.0)
        }
        val cosLat = cos(Math.toRadians(pt.latitude)).coerceAtLeast(0.1)
        val startIdx = minSegmentIdx.coerceIn(0, (path.lastIndex - 1).coerceAtLeast(0))
        val limit = maxSegmentLimit.coerceIn(startIdx + 1, path.lastIndex)

        var bestSeg = startIdx
        var bestDist = Double.MAX_VALUE
        var bestProj = path[startIdx]

        for (i in startIdx until limit) {
            val p1 = path[i]
            val p2 = path[i + 1]
            val ax = (p1.longitude - pt.longitude) * 111320.0 * cosLat
            val ay = (p1.latitude - pt.latitude) * 110540.0
            val bx = (p2.longitude - pt.longitude) * 111320.0 * cosLat
            val by = (p2.latitude - pt.latitude) * 110540.0

            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq > 1e-6) {
                (-(ax * dx + ay * dy) / lenSq).coerceIn(0.0, 1.0)
            } else 0.0

            val px = ax + t * dx
            val py = ay + t * dy
            val d = hypot(px, py)
            if (d < bestDist) {
                bestDist = d
                bestSeg = i
                bestProj = GeoPoint(
                    p1.latitude + t * (p2.latitude - p1.latitude),
                    p1.longitude + t * (p2.longitude - p1.longitude)
                )
            }
        }

        var rem = bestProj.distanceToAsDouble(path[bestSeg + 1])
        for (i in (bestSeg + 1) until path.lastIndex) {
            rem += path[i].distanceToAsDouble(path[i + 1])
        }

        return RouteProjection(
            segmentIndex = bestSeg,
            projectedPoint = bestProj,
            offRouteMeters = bestDist,
            remainingMeters = rem
        )
    }

    fun distanceAlongPath(
        fromPoint: GeoPoint,
        fromSegmentIndex: Int,
        toShapeIndex: Int,
        path: List<GeoPoint>
    ): Double {
        if (path.size < 2 || toShapeIndex <= fromSegmentIndex) {
            return fromPoint.distanceToAsDouble(path.getOrElse(toShapeIndex.coerceIn(0, path.lastIndex)) { fromPoint })
        }
        val endIdx = toShapeIndex.coerceIn(0, path.lastIndex)
        var sum = fromPoint.distanceToAsDouble(path[fromSegmentIndex + 1])
        for (i in (fromSegmentIndex + 1) until endIdx) {
            sum += path[i].distanceToAsDouble(path[i + 1])
        }
        return sum
    }

    private fun mapValhallaTurnType(type: Int): TurnDirection {
        return when (type) {
            1, 2, 3 -> TurnDirection.START
            4, 5, 6 -> TurnDirection.FINISH
            7, 8 -> TurnDirection.STRAIGHT
            9 -> TurnDirection.SLIGHT_RIGHT
            10 -> TurnDirection.RIGHT
            11 -> TurnDirection.SHARP_RIGHT
            12, 13 -> TurnDirection.UTURN
            14 -> TurnDirection.SHARP_LEFT
            15 -> TurnDirection.LEFT
            16 -> TurnDirection.SLIGHT_LEFT
            26, 27 -> TurnDirection.ROUNDABOUT
            else -> TurnDirection.STRAIGHT
        }
    }

    private fun buildBeacons(path: List<GeoPoint>): List<RouteBeacon> {
        if (path.size < 10) return emptyList()
        val shuffled = beaconNames.shuffled()
        val idx1 = (path.size * 0.36).toInt().coerceIn(1, path.lastIndex - 1)
        val idx2 = (path.size * 0.70).toInt().coerceIn(idx1 + 1, path.lastIndex - 1)
        return listOf(
            RouteBeacon(id = 1, point = path[idx1], title = shuffled[0]),
            RouteBeacon(id = 2, point = path[idx2], title = shuffled[1])
        )
    }

    private fun buildMileposts(path: List<GeoPoint>, totalMeters: Double): List<RouteMilepost> {
        if (path.size < 3 || totalMeters < 750.0) return emptyList()
        val stepMeters = if (totalMeters > 5500.0) 1000.0 else 500.0
        val out = ArrayList<RouteMilepost>()
        var accum = 0.0
        var nextMark = stepMeters

        for (i in 1 until path.size) {
            val seg = path[i - 1].distanceToAsDouble(path[i])
            if (seg <= 0.0) continue
            while (accum + seg >= nextMark && nextMark < totalMeters - 220.0) {
                val frac = ((nextMark - accum) / seg).coerceIn(0.0, 1.0)
                val lat = path[i - 1].latitude + (path[i].latitude - path[i - 1].latitude) * frac
                val lon = path[i - 1].longitude + (path[i].longitude - path[i - 1].longitude) * frac
                val km = nextMark / 1000.0
                val label = if (km % 1.0 == 0.0) {
                    String.format(Locale.US, "%.0f км", km)
                } else {
                    String.format(Locale.US, "%.1f км", km)
                }
                out.add(RouteMilepost(GeoPoint(lat, lon), label, nextMark.toInt()))
                nextMark += stepMeters
            }
            accum += seg
        }
        return out
    }

    private fun buildDirectionArrows(path: List<GeoPoint>, totalMeters: Double): List<DirectionArrow> {
        if (path.size < 2) return emptyList()
        val count = when {
            totalMeters < 1800.0 -> 4
            totalMeters < 4500.0 -> 6
            else -> 8
        }
        val stepDist = (totalMeters / (count + 0.5)).coerceAtLeast(80.0)
        val out = ArrayList<DirectionArrow>(count)
        var accum = 0.0
        var nextTarget = (stepDist * 0.35).coerceAtLeast(60.0)

        for (i in 1 until path.size) {
            val p1 = path[i - 1]
            val p2 = path[i]
            val seg = p1.distanceToAsDouble(p2)
            if (seg < 2.0) continue
            if (accum + seg >= nextTarget && out.size < count) {
                val frac = ((nextTarget - accum) / seg).coerceIn(0.0, 1.0)
                val lat = p1.latitude + (p2.latitude - p1.latitude) * frac
                val lon = p1.longitude + (p2.longitude - p1.longitude) * frac
                val bearing = p1.bearingTo(p2).toFloat()
                out.add(DirectionArrow(GeoPoint(lat, lon), bearing))
                nextTarget += stepDist
            }
            accum += seg
        }
        return out
    }

    private fun queryValhallaPedestrian(
        waypoints: List<GeoPoint>,
        profile: RouteProfile,
        paceKmh: Double
    ): ValhallaResult? {
        return try {
            val url = URL("https://valhalla1.openstreetmap.de/route")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 9000
                readTimeout = 9000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "LoopWalk-Android/1.5")
            }

            val locationsJson = JSONArray()
            for (pt in waypoints) {
                locationsJson.put(JSONObject().apply {
                    put("lat", pt.latitude)
                    put("lon", pt.longitude)
                    put("radius", 30)
                })
            }

            val pedestrianOpts = JSONObject().apply {
                put("walking_speed", paceKmh.coerceIn(2.5, 8.0))
                when (profile) {
                    RouteProfile.SCENIC -> {
                        put("walkway_factor", 0.5)
                        put("sidewalk_factor", 0.9)
                        put("alley_factor", 3.0)
                        put("use_tracks", 0.85)
                        put("use_lit", 0.3)
                    }
                    RouteProfile.PAVED -> {
                        put("walkway_factor", 0.9)
                        put("sidewalk_factor", 0.55)
                        put("alley_factor", 3.5)
                        put("use_tracks", 0.05)
                        put("use_living_streets", 0.85)
                    }
                    RouteProfile.NIGHT_LIT -> {
                        put("walkway_factor", 0.8)
                        put("sidewalk_factor", 0.6)
                        put("alley_factor", 5.0)
                        put("use_tracks", 0.0)
                        put("use_lit", 1.0)
                    }
                }
            }

            val payload = JSONObject().apply {
                put("locations", locationsJson)
                put("costing", "pedestrian")
                put("costing_options", JSONObject().put("pedestrian", pedestrianOpts))
                put("directions_options", JSONObject().apply {
                    put("units", "kilometers")
                    put("language", "ru-RU")
                })
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null

            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val trip = JSONObject(responseText).getJSONObject("trip")
            val totalKm = trip.getJSONObject("summary").getDouble("length")
            val legs = trip.getJSONArray("legs")

            val fullPath = ArrayList<GeoPoint>()
            val maneuvers = ArrayList<RouteManeuver>()

            for (i in 0 until legs.length()) {
                val leg = legs.getJSONObject(i)
                val legStartIdx = fullPath.size
                val shape = leg.getString("shape")
                val legPoints = decodePolyline6(shape)
                fullPath.addAll(legPoints)

                val mArray = leg.optJSONArray("maneuvers")
                if (mArray != null && fullPath.isNotEmpty()) {
                    for (j in 0 until mArray.length()) {
                        val m = mArray.getJSONObject(j)
                        val instruction = m.optString("instruction", "").trim()
                        val mType = m.optInt("type", 8)
                        val beginIdx = (legStartIdx + m.optInt("begin_shape_index", 0))
                            .coerceIn(0, fullPath.lastIndex)
                        if (instruction.isNotEmpty()) {
                            maneuvers.add(
                                RouteManeuver(
                                    point = fullPath[beginIdx],
                                    instruction = instruction,
                                    distanceKm = m.optDouble("length", 0.0),
                                    shapeIndex = beginIdx,
                                    direction = mapValhallaTurnType(mType)
                                )
                            )
                        }
                    }
                }
            }

            ValhallaResult(fullPath, totalKm * 1000.0, maneuvers)
        } catch (_: Exception) {
            null
        }
    }

    private fun offsetPoint(origin: GeoPoint, distanceMeters: Double, bearingDeg: Double): GeoPoint {
        val earthRadius = 6371000.0
        val distRatio = distanceMeters / earthRadius
        val bearingRad = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(origin.latitude)
        val lon1 = Math.toRadians(origin.longitude)

        val lat2 = asin(sin(lat1) * cos(distRatio) + cos(lat1) * sin(distRatio) * cos(bearingRad))
        val lon2 = lon1 + atan2(
            sin(bearingRad) * sin(distRatio) * cos(lat1),
            cos(distRatio) - sin(lat1) * sin(lat2)
        )
        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun decodePolyline6(encoded: String): List<GeoPoint> {
        val poly = ArrayList<GeoPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            poly.add(GeoPoint(lat / 1e6, lng / 1e6))
        }
        return poly
    }
}
