package com.loopwalk.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
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
        userProfile: UserProfile
    ): LoopRoute = withContext(Dispatchers.IO) {
        val baseBearing = Random.nextDouble(0.0, 360.0)
        val direction = if (Random.nextBoolean()) 1.0 else -1.0
        var legRadiusMeters = targetMeters / 4.25

        var bestResult: ValhallaResult? = null

        for (attempt in 0 until 3) {
            val wp1 = offsetPoint(start, legRadiusMeters, baseBearing + 35.0 * direction)
            val wp2 = offsetPoint(start, legRadiusMeters, baseBearing - 35.0 * direction)
            val waypoints = listOf(start, wp1, wp2, start)

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
            LoopRoute(
                points = bestResult.points,
                distanceMeters = bestResult.distanceMeters,
                estimatedMinutes = minutes,
                estimatedSteps = steps,
                profile = routeProfile,
                maneuvers = bestResult.maneuvers,
                beacons = beacons,
                isOfflineFallback = false
            )
        } else {
            val r = targetMeters / 3.15
            val wp1 = offsetPoint(start, r, baseBearing + 35.0 * direction)
            val wp2 = offsetPoint(start, r, baseBearing - 35.0 * direction)
            val fallbackPoints = listOf(start, wp1, wp2, start)
            val minutes = ((targetMeters / 1000.0) / userProfile.paceKmh * 60.0)
                .toInt().coerceAtLeast(1)
            val steps = (targetMeters / userProfile.strideMeters).toInt()
            val beacons = listOf(
                RouteBeacon(1, wp1, beaconNames[0]),
                RouteBeacon(2, wp2, beaconNames[1])
            )
            LoopRoute(
                points = fallbackPoints,
                distanceMeters = targetMeters,
                estimatedMinutes = minutes,
                estimatedSteps = steps,
                profile = routeProfile,
                maneuvers = emptyList(),
                beacons = beacons,
                isOfflineFallback = true
            )
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
                setRequestProperty("User-Agent", "LoopWalk-Android/1.1")
            }

            val locationsJson = JSONArray()
            for (pt in waypoints) {
                locationsJson.put(JSONObject().apply {
                    put("lat", pt.latitude)
                    put("lon", pt.longitude)
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
                        val beginIdx = (legStartIdx + m.optInt("begin_shape_index", 0))
                            .coerceIn(0, fullPath.lastIndex)
                        if (instruction.isNotEmpty()) {
                            maneuvers.add(
                                RouteManeuver(
                                    point = fullPath[beginIdx],
                                    instruction = instruction,
                                    distanceKm = m.optDouble("length", 0.0)
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
