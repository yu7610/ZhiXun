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
    private var preloadFinished = false
    private var lastPersistedCount = 0
    private var previousSample: LiveLocationSample? = null
    private var latestSample: LiveLocationSample? = null
    private val trackSession = TrackStatsCalculator.Session()

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
            val saved = LocationTrackRepository.loadTodayPoints(getApplication())
            synchronized(livePoints) {
                if (livePoints.isEmpty()) {
                    livePoints.addAll(saved)
                    lastPersistedCount = livePoints.size
                    rebuildSessionFromPoints(saved)
                }
            }
            preloadFinished = true
            if (!trackingStarted) {
                publishTrackState(includeLiveMetrics = true)
            }
        }
    }

    private fun rebuildSessionFromPoints(points: List<TrackPoint>) {
        val replayed = TrackStatsCalculator.replaySessionFromPoints(points)
        trackSession.mileageM = replayed.mileageM
        trackSession.stayMs = replayed.stayMs
        trackSession.pointCount = replayed.pointCount
        trackSession.lastLat = replayed.lastLat
        trackSession.lastLng = replayed.lastLng
        trackSession.lastTimestampMs = replayed.lastTimestampMs
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
        val speedMps = resolveSpeedMps(previousSample, sample, now)
        val resolved = sample.copy(speedMps = speedMps, timestampMs = now)
        previousSample = latestSample
        latestSample = resolved

        val recordNewPoint = TrackStatsCalculator.ingestSample(
            session = trackSession,
            latitude = resolved.latitude,
            longitude = resolved.longitude,
            speedMps = speedMps,
            timestampMs = now,
        )

        synchronized(livePoints) {
            val last = livePoints.lastOrNull()
            if (last == null) {
                livePoints.add(toTrackPoint(resolved))
            } else if (recordNewPoint) {
                livePoints.add(toTrackPoint(resolved))
            } else {
                livePoints[livePoints.lastIndex] = last.copy(
                    altitude = pickAltitude(resolved.altitude, last.altitude),
                    speedMps = speedMps,
                    timestampMs = now,
                )
            }
        }

        publishLiveState(resolved)
    }

    private fun toTrackPoint(sample: LiveLocationSample): TrackPoint {
        return TrackPoint(
            latitude = sample.latitude,
            longitude = sample.longitude,
            altitude = pickAltitude(sample.altitude, Double.NaN),
            speedMps = sample.speedMps,
            timestampMs = sample.timestampMs,
        )
    }

    private fun resolveSpeedMps(
        previous: LiveLocationSample?,
        current: LiveLocationSample,
        nowMs: Long,
    ): Float {
        if (current.speedMps > 0.05f) return current.speedMps
        if (previous == null) return 0f
        val elapsedSec = (nowMs - previous.timestampMs) / 1000.0
        if (elapsedSec <= 0.2) return 0f
        val distanceM = distanceM(
            previous.latitude,
            previous.longitude,
            current.latitude,
            current.longitude,
        )
        return (distanceM / elapsedSec).toFloat().coerceAtLeast(0f)
    }

    private fun pickAltitude(current: Double, previous: Double): Double {
        return when {
            current.isFinite() && current != 0.0 -> current
            previous.isFinite() && previous != 0.0 -> previous
            else -> 0.0
        }
    }

    private fun publishLiveState(sample: LiveLocationSample) {
        val pointsSnapshot = synchronized(livePoints) { livePoints.toList() }
        val stats = trackSession.toStats(
            nowMs = System.currentTimeMillis(),
            includeTailStay = sample.speedMps < 0.5f,
        )
        val start = pointsSnapshot.firstOrNull()
        val fenceLat = _uiState.value.fenceCenterLat ?: start?.latitude
        val fenceLng = _uiState.value.fenceCenterLng ?: start?.longitude
        val inFence = if (fenceLat != null && fenceLng != null) {
            distanceM(sample.latitude, sample.longitude, fenceLat, fenceLng) <= _uiState.value.fenceRadiusM
        } else {
            false
        }

        _uiState.update {
            it.copy(
                locationReady = true,
                currentLat = sample.latitude,
                currentLng = sample.longitude,
                coordinateText = LocationTrackRepository.formatCoordinate(sample.latitude, sample.longitude),
                speedText = LocationTrackRepository.formatSpeedMps(sample.speedMps),
                altitudeText = formatAltitude(sample.altitude),
                trackPoints = pointsSnapshot,
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
                val sample = latestSample ?: continue
                val speedMps = resolveSpeedMps(previousSample, sample, System.currentTimeMillis())
                val pointsSnapshot = synchronized(livePoints) { livePoints.toList() }
                if (pointsSnapshot.isEmpty()) continue
                val stats = trackSession.toStats(
                    nowMs = System.currentTimeMillis(),
                    includeTailStay = speedMps < 0.5f,
                )
                _uiState.update { state ->
                    state.copy(
                        speedText = LocationTrackRepository.formatSpeedMps(speedMps),
                        todayStats = stats,
                    )
                }
            }
        }
    }

    private fun startPersistTicker() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                val count = synchronized(livePoints) { livePoints.size }
                if (count <= lastPersistedCount) continue
                LocationTrackRepository.saveTodayPoints(
                    getApplication(),
                    synchronized(livePoints) { livePoints.toList() },
                )
                lastPersistedCount = count
            }
        }
    }

    private suspend fun publishTrackState(includeLiveMetrics: Boolean) {
        val app = getApplication<Application>()
        val pointsSnapshot = synchronized(livePoints) { livePoints.toList() }
        val stats = if (trackingStarted) {
            val speed = latestSample?.speedMps ?: 0f
            trackSession.toStats(
                nowMs = System.currentTimeMillis(),
                includeTailStay = speed < 0.5f,
            )
        } else {
            LocationTrackRepository.computeStats(pointsSnapshot, System.currentTimeMillis())
        }
        val start = pointsSnapshot.firstOrNull()
        val last = pointsSnapshot.lastOrNull()
        _uiState.update { state ->
            state.copy(
                trackPoints = pointsSnapshot,
                startPoint = start,
                todayStats = stats,
                history = LocationTrackRepository.loadHistory(app),
                fenceCenterLat = start?.latitude,
                fenceCenterLng = start?.longitude,
                currentLat = if (includeLiveMetrics) last?.latitude else state.currentLat,
                currentLng = if (includeLiveMetrics) last?.longitude else state.currentLng,
                coordinateText = if (includeLiveMetrics) {
                    last?.let { LocationTrackRepository.formatCoordinate(it.latitude, it.longitude) } ?: "--"
                } else {
                    state.coordinateText
                },
                speedText = if (includeLiveMetrics) {
                    last?.let { LocationTrackRepository.formatSpeedMps(it.speedMps) } ?: "--"
                } else {
                    state.speedText
                },
                altitudeText = if (includeLiveMetrics) {
                    last?.let { formatAltitude(it.altitude) } ?: "--"
                } else {
                    state.altitudeText
                },
                locationReady = if (includeLiveMetrics) last != null else state.locationReady,
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
        val pointsSnapshot = synchronized(livePoints) { livePoints.toList() }
        if (pointsSnapshot.isNotEmpty()) {
            LocationTrackRepository.saveTodayPoints(getApplication(), pointsSnapshot)
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "LocationViewModel"
    }
}
