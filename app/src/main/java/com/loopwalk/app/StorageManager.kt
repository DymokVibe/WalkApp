package com.loopwalk.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.random.Random

class StorageManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("loopwalk_local_db", Context.MODE_PRIVATE)

    fun loadProfile(): UserProfile {
        val today = LocalDate.now().toEpochDay()
        var streak = prefs.getInt("streak_days", 0)
        var shields = prefs.getInt("cryo_shields", 1)
        val lastDay = prefs.getLong("last_walk_day", -1L)

        if (lastDay != -1L && today - lastDay > 1L) {
            val missedDays = today - lastDay - 1L
            if (missedDays == 1L && shields > 0) {
                shields -= 1
                prefs.edit()
                    .putInt("cryo_shields", shields)
                    .putLong("last_walk_day", today - 1L)
                    .apply()
            } else {
                streak = 0
                prefs.edit().putInt("streak_days", 0).apply()
            }
        }

        return UserProfile(
            totalXp = prefs.getInt("total_xp", 0),
            streakDays = streak,
            cryoShields = shields,
            lastWalkEpochDay = prefs.getLong("last_walk_day", -1L),
            totalWalks = prefs.getInt("total_walks", 0),
            totalMeters = prefs.getFloat("total_meters", 0f).toDouble(),
            totalSeconds = prefs.getLong("total_seconds", 0L),
            totalCapturedAreaSqM = prefs.getFloat("total_area_sqm", 0f).toDouble(),
            totalBeaconsCollected = prefs.getInt("total_beacons", 0),
            exploredSectors = prefs.getStringSet("explored_sectors", emptySet()) ?: emptySet(),
            capturedPolygons = loadPolygons(),
            walkHistory = loadHistory(),
            heightCm = prefs.getInt("height_cm", 178),
            paceKmh = prefs.getFloat("pace_kmh", 4.8f).toDouble(),
            hapticNavEnabled = prefs.getBoolean("haptic_nav", true),
            showMileposts = prefs.getBoolean("show_mileposts", true)
        )
    }

    fun updateSettings(
        heightCm: Int,
        paceKmh: Double,
        hapticNav: Boolean,
        showMileposts: Boolean
    ): UserProfile {
        prefs.edit()
            .putInt("height_cm", heightCm.coerceIn(130, 220))
            .putFloat("pace_kmh", paceKmh.coerceIn(2.5, 8.0).toFloat())
            .putBoolean("haptic_nav", hapticNav)
            .putBoolean("show_mileposts", showMileposts)
            .apply()
        return loadProfile()
    }

    fun getTodayMutator(): DailyMutator {
        val today = LocalDate.now().toEpochDay()
        val mutators = listOf(
            DailyMutator("NIGHT", "[ТЕМНЫЙ РЕЖИМ]", "Завершить круг после 19:00 (+120 XP)", 120),
            DailyMutator("SCOUT", "[ПЕРВООТКРЫВАТЕЛЬ]", "Открыть 4+ новых сектора карты (+150 XP)", 150),
            DailyMutator("SNIPER", "[ТОЧНЫЙ РАСЧЁТ]", "Вернуться с отклонением < 2 мин от цели (+140 XP)", 140),
            DailyMutator("PACE", "[ФОРСАЖ]", "Пройти круг быстрее расчётного времени (+110 XP)", 110),
            DailyMutator("SIGNAL", "[ПЕРЕХВАТЧИК]", "Собрать все маяки на маршруте (+130 XP)", 130)
        )
        return mutators[Random(today).nextInt(mutators.size)]
    }

    fun sectorKey(point: GeoPoint): String {
        val latIdx = (point.latitude * 1000).roundToInt()
        val lonIdx = (point.longitude * 1000).roundToInt()
        return "$latIdx:$lonIdx"
    }

    fun sectorPolygon(key: String): List<GeoPoint> {
        val parts = key.split(":")
        val lat = parts[0].toInt() / 1000.0
        val lon = parts[1].toInt() / 1000.0
        val d = 0.0005
        return listOf(
            GeoPoint(lat - d, lon - d),
            GeoPoint(lat + d, lon - d),
            GeoPoint(lat + d, lon + d),
            GeoPoint(lat - d, lon + d)
        )
    }

    fun circlePolygon(center: GeoPoint, radiusMeters: Double, steps: Int = 28): List<GeoPoint> {
        val latConv = 110540.0
        val lonConv = 111320.0 * cos(Math.toRadians(center.latitude)).coerceAtLeast(0.1)
        val pts = ArrayList<GeoPoint>(steps)
        for (i in 0 until steps) {
            val angle = 2.0 * Math.PI * i / steps
            val dLat = (radiusMeters * kotlin.math.sin(angle)) / latConv
            val dLon = (radiusMeters * kotlin.math.cos(angle)) / lonConv
            pts.add(GeoPoint(center.latitude + dLat, center.longitude + dLon))
        }
        return pts
    }

    fun calculatePolygonAreaSqM(points: List<GeoPoint>): Double {
        if (points.size < 3) return 0.0
        val lat0 = points[0].latitude
        val lon0 = points[0].longitude
        val cosLat = cos(Math.toRadians(lat0))
        var sum = 0.0
        for (i in points.indices) {
            val p1 = points[i]
            val p2 = points[(i + 1) % points.size]
            val x1 = (p1.longitude - lon0) * 111320.0 * cosLat
            val y1 = (p1.latitude - lat0) * 110540.0
            val x2 = (p2.longitude - lon0) * 111320.0 * cosLat
            val y2 = (p2.latitude - lat0) * 110540.0
            sum += (x1 * y2 - x2 * y1)
        }
        return abs(sum) * 0.5
    }

    private fun simplifyTrail(points: List<GeoPoint>, minStepMeters: Double = 18.0): List<GeoPoint> {
        if (points.size <= 4) return points
        val out = ArrayList<GeoPoint>()
        out.add(points.first())
        var last = points.first()
        for (i in 1 until points.size - 1) {
            if (last.distanceToAsDouble(points[i]) >= minStepMeters) {
                out.add(points[i])
                last = points[i]
            }
        }
        out.add(points.last())
        return out
    }

    fun completeWalk(
        walkedMeters: Double,
        elapsedSeconds: Long,
        targetSeconds: Long,
        steps: Int,
        trail: List<GeoPoint>,
        newSectors: Set<String>,
        beaconsCollected: Int,
        totalBeacons: Int,
        returnedHome: Boolean
    ): Pair<UserProfile, Int> {
        val current = loadProfile()
        val today = LocalDate.now().toEpochDay()
        val countsForStreak = walkedMeters >= 800.0 || elapsedSeconds >= 600L

        var newStreak = current.streakDays
        var newShields = current.cryoShields
        var lastDay = current.lastWalkEpochDay

        if (countsForStreak && lastDay != today) {
            newStreak = if (lastDay == today - 1L) current.streakDays + 1 else 1
            lastDay = today
            if (newStreak % 7 == 0) {
                newShields += 1
            }
        }

        val simplified = simplifyTrail(trail)
        val areaSqM = if (returnedHome && simplified.size >= 4 && walkedMeters >= 400.0) {
            calculatePolygonAreaSqM(simplified)
        } else 0.0

        val updatedPolygons = if (areaSqM >= 500.0) {
            (current.capturedPolygons + listOf(simplified)).takeLast(50)
        } else current.capturedPolygons

        val mutator = getTodayMutator()
        val mutatorCompleted = when (mutator.id) {
            "NIGHT" -> LocalTime.now().hour >= 19
            "SCOUT" -> newSectors.size >= 4
            "SNIPER" -> abs(elapsedSeconds - targetSeconds) <= 120L && walkedMeters >= 600.0
            "PACE" -> elapsedSeconds <= targetSeconds && walkedMeters >= 800.0
            "SIGNAL" -> totalBeacons > 0 && beaconsCollected >= totalBeacons
            else -> false
        }

        val areaBonusXp = (areaSqM / 250.0).toInt().coerceAtMost(150)
        val baseXp = (walkedMeters / 10.0).toInt() +
            (newSectors.size * 25) +
            (beaconsCollected * 35) +
            areaBonusXp +
            (if (returnedHome) 60 else 0) +
            (if (mutatorCompleted) mutator.bonusXp else 0)

        val earnedXp = (baseXp * current.streakMultiplier).roundToInt().coerceAtLeast(15)
        val mergedSectors = current.exploredSectors + newSectors

        val newRecord = WalkRecord(
            timestampMs = System.currentTimeMillis(),
            distanceMeters = walkedMeters,
            elapsedSeconds = elapsedSeconds,
            steps = steps,
            earnedXp = earnedXp,
            capturedAreaSqM = areaSqM,
            newSectorsCount = newSectors.size,
            beaconsCollected = beaconsCollected
        )
        val updatedHistory = (listOf(newRecord) + current.walkHistory).take(50)

        prefs.edit()
            .putInt("total_xp", current.totalXp + earnedXp)
            .putInt("streak_days", newStreak)
            .putInt("cryo_shields", newShields)
            .putLong("last_walk_day", lastDay)
            .putInt("total_walks", current.totalWalks + 1)
            .putFloat("total_meters", (current.totalMeters + walkedMeters).toFloat())
            .putLong("total_seconds", current.totalSeconds + elapsedSeconds)
            .putFloat("total_area_sqm", (current.totalCapturedAreaSqM + areaSqM).toFloat())
            .putInt("total_beacons", current.totalBeaconsCollected + beaconsCollected)
            .putStringSet("explored_sectors", mergedSectors)
            .putString("captured_polygons_json", serializePolygons(updatedPolygons))
            .putString("walk_history_json", serializeHistory(updatedHistory))
            .apply()

        return Pair(loadProfile(), earnedXp)
    }

    private fun serializePolygons(polygons: List<List<GeoPoint>>): String {
        val arr = JSONArray()
        for (poly in polygons) {
            val pArr = JSONArray()
            for (pt in poly) {
                pArr.put(JSONArray().put(pt.latitude).put(pt.longitude))
            }
            arr.put(pArr)
        }
        return arr.toString()
    }

    private fun loadPolygons(): List<List<GeoPoint>> {
        val raw = prefs.getString("captured_polygons_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = ArrayList<List<GeoPoint>>()
            for (i in 0 until arr.length()) {
                val pArr = arr.getJSONArray(i)
                val pts = ArrayList<GeoPoint>()
                for (j in 0 until pArr.length()) {
                    val pair = pArr.getJSONArray(j)
                    pts.add(GeoPoint(pair.getDouble(0), pair.getDouble(1)))
                }
                if (pts.size >= 3) result.add(pts)
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeHistory(records: List<WalkRecord>): String {
        val arr = JSONArray()
        for (r in records) {
            arr.put(JSONObject().apply {
                put("ts", r.timestampMs)
                put("dist", r.distanceMeters)
                put("sec", r.elapsedSeconds)
                put("steps", r.steps)
                put("xp", r.earnedXp)
                put("area", r.capturedAreaSqM)
                put("sectors", r.newSectorsCount)
                put("beacons", r.beaconsCollected)
            })
        }
        return arr.toString()
    }

    private fun loadHistory(): List<WalkRecord> {
        val raw = prefs.getString("walk_history_json", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = ArrayList<WalkRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    WalkRecord(
                        timestampMs = o.optLong("ts", 0L),
                        distanceMeters = o.optDouble("dist", 0.0),
                        elapsedSeconds = o.optLong("sec", 0L),
                        steps = o.optInt("steps", 0),
                        earnedXp = o.optInt("xp", 0),
                        capturedAreaSqM = o.optDouble("area", 0.0),
                        newSectorsCount = o.optInt("sectors", 0),
                        beaconsCollected = o.optInt("beacons", 0)
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun exportGpxFile(route: LoopRoute): File {
        val dir = File(context.cacheDir, "gpx").apply { mkdirs() }
        val file = File(dir, "loopwalk_${System.currentTimeMillis()}.gpx")
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"LoopWalk\">\n")
        for (b in route.beacons) {
            sb.append(
                String.format(
                    Locale.US,
                    "<wpt lat=\"%.6f\" lon=\"%.6f\"><name>%s</name></wpt>\n",
                    b.point.latitude, b.point.longitude, b.title
                )
            )
        }
        sb.append("<trk><name>LoopWalk Circle</name><trkseg>\n")
        for (pt in route.points) {
            sb.append(String.format(Locale.US, "<trkpt lat=\"%.6f\" lon=\"%.6f\" />\n", pt.latitude, pt.longitude))
        }
        sb.append("</trkseg></trk>\n</gpx>")
        file.writeText(sb.toString())
        return file
    }
}
