package com.powerchina.zhixun.location

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.baidu.location.BDLocation
import com.powerchina.zhixun.data.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocationViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    private var trackingStarted = false
    private var lastAltitudeM: Double? = null
    private var lastTerrainFetchKey: String? = null
    private var altitudeWatcher: GpsAltitudeWatcher? = null
    private val mapListener: (BDLocation) -> Unit = { location ->
        publishLocation(location)
    }

    init {
        val manifestAk = BaiduSdkInitializer.resolveApiKey(application)
        when {
            manifestAk.isBlank() -> {
                _uiState.update {
                    it.copy(
                        errorMessage = "百度地图 AK 未生效：请确认 local.properties / gradle.properties 中已配置 baiduMapAk，并重新编译安装",
                    )
                }
            }
            !BaiduSdkInitializer.isReady() -> {
                _uiState.update {
                    it.copy(errorMessage = BaiduSdkInitializer.lastError() ?: "百度地图 SDK 未就绪")
                }
            }
        }
        viewModelScope.launch {
            LocationReportCoordinator.riskUpdates.collect { data ->
                applyRiskFromApi(data)
            }
        }
        if (LocationBootstrap.hasLocationPermission(application)) {
            startTracking()
        }
    }

    fun onPermissionsGranted() {
        startTracking()
    }

    fun selectTab(tab: LocationTab) {
        _uiState.update { it.copy(tab = tab) }
        if (tab == LocationTab.FENCE) {
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val terCode = ConfigManager(getApplication()).loadConfig().macAddress
                    GeofenceApi.fetchForDevice(getApplication(), terCode)
                }
                result
                    .onSuccess { applyFencesFromApi(it.fences) }
                    .onFailure { Log.w(TAG, "围栏请求失败", it) }
            }
        }
    }

    private fun applyFencesFromApi(fences: List<FenceArea>) {
        Log.i(TAG, "应用围栏数据 size=${fences.size}")
        _uiState.update { state ->
            val first = fences.firstOrNull()?.points?.firstOrNull()
            state.copy(
                fences = fences,
                fenceCenterLat = first?.latitude ?: state.fenceCenterLat,
                fenceCenterLng = first?.longitude ?: state.fenceCenterLng,
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun startTracking() {
        if (trackingStarted) return
        trackingStarted = true
        // 先挂监听再 start，避免首点回调丢失
        BaiduLocationReporter.addListener(mapListener)
        LocationReportCoordinator.ensureStarted(getApplication())
        altitudeWatcher = GpsAltitudeWatcher(getApplication()) { altitudeM ->
            lastAltitudeM = altitudeM
            _uiState.update { state ->
                state.copy(altitudeText = LocationAltitudeHelper.format(altitudeM))
            }
        }.also { it.start() }
        Log.i(TAG, "定位页已启动定位脉冲 interval=${BaiduLocationReporter.SCAN_INTERVAL_MS}ms")
    }

    private fun stopTracking() {
        if (!trackingStarted) return
        trackingStarted = false
        altitudeWatcher?.stop()
        altitudeWatcher = null
        // 先停上报监听，再移地图监听；最后一个 listener 会触发 BaiduLocationReporter.stop()
        LocationReportCoordinator.stop()
        BaiduLocationReporter.removeListener(mapListener)
        Log.i(TAG, "离开定位页，已停止定位与上报")
    }

    private fun publishLocation(location: BDLocation) {
        val speedMps = when {
            location.hasSpeed() && location.speed > 0f -> location.speed / 3.6f
            else -> 0f
        }
        lastAltitudeM = LocationAltitudeHelper.resolve(
            context = getApplication(),
            location = location,
            cached = lastAltitudeM,
        )
        val point = TrackPoint(location.latitude, location.longitude)

        _uiState.update { state ->
            val lastTrack = state.trackPoints.lastOrNull()
            val trackPoints = if (LocationQualityFilter.shouldAppendTrack(
                    lastTrack,
                    location.latitude,
                    location.longitude,
                )
            ) {
                val next = state.trackPoints + point
                // 防止长时间停留轨迹无限增长
                if (next.size > MAX_TRACK_POINTS) {
                    next.takeLast(MAX_TRACK_POINTS)
                } else {
                    next
                }
            } else {
                state.trackPoints
            }
            state.copy(
                currentLat = location.latitude,
                currentLng = location.longitude,
                coordinateText = LocationTrackRepository.formatCoordinateWithAccuracy(
                    lat = location.latitude,
                    lng = location.longitude,
                    isGnss = LocationQualityFilter.isGnssFix(location),
                    radiusM = location.radius,
                ),
                speedText = LocationTrackRepository.formatSpeedMps(speedMps),
                altitudeText = LocationAltitudeHelper.format(lastAltitudeM),
                trackPoints = trackPoints,
                startPoint = state.startPoint ?: point,
                // 已有接口围栏时不再用当前位置覆盖圆心
                fenceCenterLat = if (state.fences.isNotEmpty()) {
                    state.fenceCenterLat
                } else {
                    state.fenceCenterLat ?: location.latitude
                },
                fenceCenterLng = if (state.fences.isNotEmpty()) {
                    state.fenceCenterLng
                } else {
                    state.fenceCenterLng ?: location.longitude
                },
            )
        }
        if (lastAltitudeM == null) {
            fetchTerrainElevation(location.latitude, location.longitude)
        }
    }

    /** 风险告警文案以 receiveLocation 接口 data 为准；data 为空则隐藏 */
    private fun applyRiskFromApi(data: String) {
        val message = data.trim()
        if (message.isEmpty()) {
            _uiState.update { state ->
                state.copy(
                    riskActive = false,
                    riskTitle = "风险告警",
                    riskMessage = "",
                )
            }
            return
        }
        val active = isRiskActive(message)
        _uiState.update { state ->
            state.copy(
                riskActive = active,
                riskTitle = if (active) "风险告警" else "安全",
                riskMessage = message,
            )
        }
    }

    private fun isRiskActive(data: String): Boolean {
        if (data.contains("未进入") || data.contains("不在") || data.contains("未在")) {
            return false
        }
        return data.contains("隐患") ||
            data.contains("已进入") ||
            data.contains("风险")
    }

    private fun fetchTerrainElevation(latitude: Double, longitude: Double) {
        val key = "%.4f,%.4f".format(latitude, longitude)
        if (key == lastTerrainFetchKey) return
        lastTerrainFetchKey = key
        viewModelScope.launch {
            TerrainElevationFetcher.fetch(latitude, longitude)
                .onSuccess { elevationM ->
                    lastAltitudeM = elevationM
                    _uiState.update { state ->
                        state.copy(altitudeText = LocationAltitudeHelper.format(elevationM))
                    }
                }
        }
    }

    override fun onCleared() {
        stopTracking()
        super.onCleared()
    }

    companion object {
        private const val TAG = "LocationViewModel"
        private const val MAX_TRACK_POINTS = 2_000
    }
}
