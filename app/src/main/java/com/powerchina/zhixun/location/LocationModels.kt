package com.powerchina.zhixun.location

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speedMps: Float,
    val timestampMs: Long,
)

data class DailyTrackStats(
    val mileageKm: Double,
    val stayMinutes: Int,
    val pointCount: Int,
)

data class DailyTrackHistory(
    val dateLabel: String,
    val mileageKm: Double,
    val stayMinutes: Int,
    val pointCount: Int,
)

data class LocationUiState(
    val tab: LocationTab = LocationTab.LOCATE,
    val coordinateText: String = "--",
    val speedText: String = "--",
    val altitudeText: String = "--",
    val riskTitle: String = "风险告警",
    val riskMessage: String = "未进入工区",
    val riskActive: Boolean = false,
    val todayStats: DailyTrackStats = DailyTrackStats(0.0, 0, 0),
    val history: List<DailyTrackHistory> = emptyList(),
    val trackPoints: List<TrackPoint> = emptyList(),
    val startPoint: TrackPoint? = null,
    val currentLat: Double? = null,
    val currentLng: Double? = null,
    val fenceCenterLat: Double? = null,
    val fenceCenterLng: Double? = null,
    val fenceRadiusM: Double = 120.0,
    val locationReady: Boolean = false,
    val errorMessage: String? = null,
)

enum class LocationTab {
    LOCATE,
    FENCE,
    TRACK,
    HISTORY,
}
