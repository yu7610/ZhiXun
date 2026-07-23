package com.powerchina.zhixun.location

data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
)

/** 接口返回的围栏（当前支持多边形） */
data class FenceArea(
    val id: String,
    val name: String,
    val points: List<TrackPoint>,
)

data class LocationUiState(
    val tab: LocationTab = LocationTab.LOCATE,
    val coordinateText: String = "--",
    val speedText: String = "--",
    val altitudeText: String = "--",
    val riskTitle: String = "风险告警",
    /** receiveLocation 的 data；为空时不展示风险告警卡片 */
    val riskMessage: String = "",
    val riskActive: Boolean = false,
    val trackPoints: List<TrackPoint> = emptyList(),
    val startPoint: TrackPoint? = null,
    val currentLat: Double? = null,
    val currentLng: Double? = null,
    val fenceCenterLat: Double? = null,
    val fenceCenterLng: Double? = null,
    val fenceRadiusM: Double = 120.0,
    /** 围栏 Tab：来自 geofences/byDevices */
    val fences: List<FenceArea> = emptyList(),
    val errorMessage: String? = null,
)

enum class LocationTab {
    LOCATE,
    FENCE,
    TRACK,
}
