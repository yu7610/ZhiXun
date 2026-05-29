package com.powerchina.zhixun.location

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.powerchina.zhixun.BuildConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class LocationViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LocationUiState())
    val uiState: StateFlow<LocationUiState> = _uiState.asStateFlow()

    private val livePoints = mutableListOf<TrackPoint>()
    private var locationTracker: RealtimeLocationTracker? = null
    private var statsTickerJob: Job? = null
    private var persistJob: Job? = null
    private var trackingStarted = false

    private var lastPersistedCount = 0

    init {
        BaiduSdkInitializer.ensureInitialized(application)
        if (BuildConfig.BAIDU_MAP_AK.isBlank()) {
            _uiState.update { it.copy(errorMessage = "请在 local.properties 配置 baiduMapAk") }
        } else {
            preloadTodayTrack()
        }
    }

    fun onPermissionsGranted() {
        if (trackingStarted) return
        trackingStarted = true
        startLocationUpdates()
        startStatsTicker()
        startPersistTicker()
    }

    fun selectTab(tab: LocationTab) {
        _uiState.update { it.copy(tab = tab) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun preloadTodayTrack() {
        viewModelScope.launch {
            livePoints.clear()
            livePoints.addAll(LocationTrackRepository.loadTodayPoints(getApplication()))
            lastPersistedCount = livePoints.size
            publishTrackState()
        }
    }

    private fun startLocationUpdates() {
        val app = getApplication<Application>()
        locationTracker?.stop()
        locationTracker = RealtimeLocationTracker(app.applicationContext) { sample ->
            handleSample(sample)
        }.also { it.start() }
        Log.i(TAG, "实时定位追踪已启动")
    }

    private fun handleSample(sample: LiveLocationSample) {
        val now = System.currentTimeMillis()
        val last = livePoints.lastOrNull()
        val speedMps = LocationTrackRepository.deriveSpeedMps(
            previous = last,
            latitude = sample.latitude,
            longitude = sample.longitude,
            timestampMs = now,
            reportedSpeedMps = sample.speedMps,
        )
        val movedEnough = last == null ||
            distanceM(last.latitude, last.longitude, sample.latitude, sample.longitude) >= 0.5
        val intervalReached = last == null || now - last.timestampMs >= 900L

        if (last != null && !intervalReached && !movedEnough) {
            livePoints[livePoints.lastIndex] = last.copy(
                altitude = sample.altitude,
                speedMps = speedMps,
                timestampMs = now,
            )
        } else {
            livePoints.add(
                TrackPoint(
                    latitude = sample.latitude,
                    longitude = sample.longitude,
                    altitude = sample.altitude,
                    speedMps = speedMps,
                    timestampMs = now,
                ),
            )
        }

        publishLiveState(sample.latitude, sample.longitude, sample.altitude, speedMps)
    }

    private fun publishLiveState(
        lat: Double,
        lng: Double,
        altitude: Double,
        speedMps: Float,
    ) {
        val stats = LocationTrackRepository.computeStats(livePoints, System.currentTimeMillis())
        val start = livePoints.firstOrNull()
        val fenceLat = _uiState.value.fenceCenterLat ?: start?.latitude
        val fenceLng = _uiState.value.fenceCenterLng ?: start?.longitude
        val inFence = if (fenceLat != null && fenceLng != null) {
            distanceM(lat, lng, fenceLat, fenceLng) <= _uiState.value.fenceRadiusM
        } else {
            false
        }

        _uiState.update {
            it.copy(
                locationReady = true,
                currentLat = lat,
                currentLng = lng,
                coordinateText = LocationTrackRepository.formatCoordinate(lat, lng),
                speedText = LocationTrackRepository.formatSpeedMps(speedMps),
                altitudeText = formatAltitude(altitude),
                trackPoints = livePoints.toList(),
                startPoint = start,
                todayStats = stats,
                fenceCenterLat = fenceLat,
                fenceCenterLng = fenceLng,
                riskActive = inFence,
                riskTitle = if (inFence) "风险告警" else "安全",
                riskMessage = if (inFence) {
                    "已进入1#施工洞工区\n可联动风险库提示潜在风险及管控措施"
                } else {
                    "当前不在1#施工洞工区"
                },
            )
        }
    }

    private fun startStatsTicker() {
        statsTickerJob?.cancel()
        statsTickerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                if (livePoints.isEmpty()) continue
                val stats = LocationTrackRepository.computeStats(
                    livePoints,
                    System.currentTimeMillis(),
                )
                _uiState.update { state ->
                    if (state.todayStats == stats) state else state.copy(todayStats = stats)
                }
            }
        }
    }

    private fun startPersistTicker() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                if (livePoints.size <= lastPersistedCount) continue
                LocationTrackRepository.saveTodayPoints(
                    getApplication(),
                    livePoints.toList(),
                )
                lastPersistedCount = livePoints.size
            }
        }
    }

    private suspend fun publishTrackState() {
        val app = getApplication<Application>()
        val stats = LocationTrackRepository.computeStats(livePoints, System.currentTimeMillis())
        val start = livePoints.firstOrNull()
        val last = livePoints.lastOrNull()
        _uiState.update {
            it.copy(
                trackPoints = livePoints.toList(),
                startPoint = start,
                todayStats = stats,
                history = LocationTrackRepository.loadHistory(app),
                fenceCenterLat = start?.latitude,
                fenceCenterLng = start?.longitude,
                currentLat = last?.latitude,
                currentLng = last?.longitude,
                coordinateText = last?.let { p ->
                    LocationTrackRepository.formatCoordinate(p.latitude, p.longitude)
                } ?: "--",
                speedText = last?.let { p -> LocationTrackRepository.formatSpeedMps(p.speedMps) } ?: "--",
                altitudeText = last?.let { p -> formatAltitude(p.altitude) } ?: "--",
                locationReady = last != null,
            )
        }
    }

    private fun formatAltitude(altitude: Double): String {
        return if (altitude.isFinite() && altitude != 0.0) {
            "${altitude.roundToInt()}m"
        } else {
            "--"
        }
    }

    private fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0].toDouble()
    }

    override fun onCleared() {
        statsTickerJob?.cancel()
        persistJob?.cancel()
        locationTracker?.stop()
        locationTracker = null
        if (livePoints.isNotEmpty()) {
            LocationTrackRepository.saveTodayPoints(getApplication(), livePoints.toList())
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "LocationViewModel"
    }
}
