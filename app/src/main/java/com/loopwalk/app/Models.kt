package com.loopwalk.app

import org.osmdroid.util.GeoPoint

enum class InputMode(val label: String, val unit: String) {
    TIME("ВРЕМЯ", "мин"),
    DISTANCE("ДИСТАНЦИЯ", "км"),
    STEPS("ШАГИ", "шаг.")
}

enum class RouteProfile(val label: String) {
    SCENIC("ПАРК / ТРОПЫ"),
    PAVED("УЛИЦЫ"),
    NIGHT_LIT("ФОНАРИ")
}

data class RouteManeuver(
    val point: GeoPoint,
    val instruction: String,
    val distanceKm: Double
)

data class RouteBeacon(
    val id: Int,
    val point: GeoPoint,
    val title: String,
    val xpReward: Int = 35,
    val collected: Boolean = false
)

data class RouteMilepost(
    val point: GeoPoint,
    val label: String,
    val metersFromStart: Int
)

data class DirectionArrow(
    val point: GeoPoint,
    val bearingDeg: Float
)

data class LoopRoute(
    val points: List<GeoPoint>,
    val distanceMeters: Double,
    val estimatedMinutes: Int,
    val estimatedSteps: Int,
    val profile: RouteProfile,
    val maneuvers: List<RouteManeuver> = emptyList(),
    val beacons: List<RouteBeacon> = emptyList(),
    val mileposts: List<RouteMilepost> = emptyList(),
    val arrows: List<DirectionArrow> = emptyList(),
    val isOfflineFallback: Boolean = false
)

data class DailyMutator(
    val id: String,
    val title: String,
    val description: String,
    val bonusXp: Int
)

data class WalkRecord(
    val timestampMs: Long,
    val distanceMeters: Double,
    val elapsedSeconds: Long,
    val steps: Int,
    val earnedXp: Int,
    val capturedAreaSqM: Double,
    val newSectorsCount: Int,
    val beaconsCollected: Int
)

data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val progressRatio: Float,
    val unlocked: Boolean
)

data class UserProfile(
    val totalXp: Int = 0,
    val streakDays: Int = 0,
    val cryoShields: Int = 1,
    val lastWalkEpochDay: Long = -1L,
    val totalWalks: Int = 0,
    val totalMeters: Double = 0.0,
    val totalSeconds: Long = 0L,
    val totalCapturedAreaSqM: Double = 0.0,
    val totalBeaconsCollected: Int = 0,
    val exploredSectors: Set<String> = emptySet(),
    val capturedPolygons: List<List<GeoPoint>> = emptyList(),
    val walkHistory: List<WalkRecord> = emptyList(),
    val heightCm: Int = 178,
    val paceKmh: Double = 4.8,
    val hapticNavEnabled: Boolean = true,
    val showMileposts: Boolean = true
) {
    val strideMeters: Double
        get() = (heightCm * 0.415) / 100.0

    val level: Int
        get() = (totalXp / 500) + 1

    val xpInCurrentLevel: Int
        get() = totalXp % 500

    val rankTitle: String
        get() = when {
            level < 3 -> "ДОМОСЕД"
            level < 6 -> "РАЗВЕДЧИК ПЕРЕУЛКОВ"
            level < 10 -> "ПЕШЕХОДНЫЙ СТАЛКЕР"
            level < 15 -> "КАРТОГРАФ ПУСТОШЕЙ"
            level < 25 -> "ХРОНО-СТРАННИК"
            else -> "МАСТЕР ПЕТЛИ"
        }

    val streakMultiplier: Double
        get() = 1.0 + (streakDays.coerceAtMost(10) * 0.1)

    val achievements: List<Achievement>
        get() = listOf(
            Achievement(
                "FIRST_LOOP",
                "ПЕРВЫЙ КОНТУР",
                "Завершить первую прогулку",
                (totalWalks / 1f).coerceIn(0f, 1f),
                totalWalks >= 1
            ),
            Achievement(
                "STREAK_7",
                "НЕДЕЛЯ В РИТМЕ",
                "Достичь серии из 7 дней подряд",
                (streakDays / 7f).coerceIn(0f, 1f),
                streakDays >= 7
            ),
            Achievement(
                "SECTORS_50",
                "КАРТОГРАФ РАЙОНА",
                "Открыть 50 секторов Тумана войны",
                (exploredSectors.size / 50f).coerceIn(0f, 1f),
                exploredSectors.size >= 50
            ),
            Achievement(
                "AREA_1HA",
                "ТЕРРИТОРИАЛЬНЫЙ ЗАХВАТ",
                "Замкнуть контуры площадью 10 000+ м²",
                (totalCapturedAreaSqM / 10000.0).toFloat().coerceIn(0f, 1f),
                totalCapturedAreaSqM >= 10000.0
            ),
            Achievement(
                "BEACONS_10",
                "ПЕРЕХВАТЧИК СИГНАЛОВ",
                "Расшифровать 10 сигнальных маяков",
                (totalBeaconsCollected / 10f).coerceIn(0f, 1f),
                totalBeaconsCollected >= 10
            ),
            Achievement(
                "DIST_25KM",
                "ПЕШИЙ МАРАФОН",
                "Пройти суммарно 25 км",
                (totalMeters / 25000.0).toFloat().coerceIn(0f, 1f),
                totalMeters >= 25000.0
            )
        )
}
