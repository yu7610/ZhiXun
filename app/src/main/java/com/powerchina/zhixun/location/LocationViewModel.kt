package com.powerchina.zhixun.location

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.baidu.location.BDLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        if (LocationBootstrap.hasLocationPermission(application)) {
            startTracking()
        }
    }

    fun onPermissionsGranted() {
        LocationBootstrap.startLocationIfPermitted(getApplication())
        startTracking()
    }

    fun selectTab(tab: LocationTab) {
        _uiState.update { it.copy(tab = tab) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun startTracking() {
        if (trackingStarted) return
        trackingStarted = true
        BaiduLocationReporter.addListener(mapListener)
        LocationReportCoordinator.ensureStarted(getApplication())
        BaiduLocationReporter.requestLocate("进入定位页")
        altitudeWatcher = GpsAltitudeWatcher(getApplication()) { altitudeM ->
            lastAltitudeM = altitudeM
            _uiState.update { state ->
                state.copy(altitudeText = LocationAltitudeHelper.format(altitudeM))
            }
        }.also { it.start() }
        Log.i(TAG, "定位页已启动百度连续定位")
    }

    private fun stopTracking() {
        if (!trackingStarted) return
        trackingStarted = false
        altitudeWatcher?.stop()
        altitudeWatcher = null
        BaiduLocationReporter.removeListener(mapListener)
        LocationReportCoordinator.stop()
        Log.i(TAG, "定位页已停止百度连续定位")
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
        val fenceLat = _uiState.value.fenceCenterLat ?: location.latitude
        val fenceLng = _uiState.value.fenceCenterLng ?: location.longitude
        val inFence = distanceM(
            location.latitude,
            location.longitude,
            fenceLat,
            fenceLng,
        ) <= _uiState.value.fenceRadiusM

        _uiState.update { state ->
            val trackPoints = state.trackPoints + point
            state.copy(
                locationReady = true,
                currentLat = location.latitude,
                currentLng = location.longitude,
                coordinateText = LocationTrackRepository.formatCoordinate(
                    location.latitude,
                    location.longitude,
                ),
                speedText = LocationTrackRepository.formatSpeedMps(speedMps),
                altitudeText = LocationAltitudeHelper.format(lastAltitudeM),
                trackPoints = trackPoints,
                startPoint = state.startPoint ?: point,
                fenceCenterLat = state.fenceCenterLat ?: location.latitude,
                fenceCenterLng = state.fenceCenterLng ?: location.longitude,
                riskActive = inFence,
                riskTitle = if (inFence) "风险告警" else "安全",
                riskMessage = if (inFence) {
                    "已进入1#施工洞工区\n可联动风险库提示潜在风险及管控措施"
                } else {
                    "当前不在1#施工洞工区"
                },
            )
        }
        if (lastAltitudeM == null) {
            fetchTerrainElevation(location.latitude, location.longitude)
        }
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

    private fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0].toDouble()
    }

    override fun onCleared() {
        stopTracking()
        super.onCleared()
    }

    companion object {
        private const val TAG = "LocationViewModel"
    }
}
