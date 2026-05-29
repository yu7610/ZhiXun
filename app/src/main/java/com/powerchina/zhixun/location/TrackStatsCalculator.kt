package com.powerchina.zhixun.location

/**
 * 轨迹统计：过滤 GPS 漂移，按真实移动距离累计里程，按低速/静止累计停留。
 */
object TrackStatsCalculator {

    /** 至少移动该距离才记录新轨迹点 */
    const val MIN_RECORD_DISTANCE_M = 5.0

    /** 累计里程时忽略小于该值的段（噪声） */
    private const val MIN_MILEAGE_SEGMENT_M = 3.0

    /** 低于该速度视为停留/静止（m/s） */
    private const val STAY_SPEED_MPS = 0.5

    data class Session(
        var mileageM: Double = 0.0,
        var stayMs: Long = 0L,
        var pointCount: Int = 0,
        var lastLat: Double? = null,
        var lastLng: Double? = null,
        var lastTimestampMs: Long = 0L,
    ) {
        fun toStats(
            nowMs: Long = System.currentTimeMillis(),
            includeTailStay: Boolean = false,
        ): DailyTrackStats {
            val tailStayMs = if (includeTailStay) {
                computeTailStayMs(lastLat, lastLng, lastTimestampMs, nowMs)
            } else {
                0L
            }
            return DailyTrackStats(
                mileageKm = mileageM / 1000.0,
                stayMinutes = ((stayMs + tailStayMs) / 60_000L).toInt(),
                pointCount = pointCount,
            )
        }
    }

    fun ingestSample(
        session: Session,
        latitude: Double,
        longitude: Double,
        speedMps: Float,
        timestampMs: Long,
    ): Boolean {
        val movedEnough = if (session.lastLat != null && session.lastLng != null) {
            distanceM(session.lastLat!!, session.lastLng!!, latitude, longitude) >= MIN_RECORD_DISTANCE_M
        } else {
            true
        }

        if (session.lastLat != null && session.lastLng != null && session.lastTimestampMs > 0L) {
            val dt = (timestampMs - session.lastTimestampMs).coerceAtLeast(0L)
            if (dt > 0L) {
                val dist = distanceM(session.lastLat!!, session.lastLng!!, latitude, longitude)
                if (dist >= MIN_MILEAGE_SEGMENT_M) {
                    session.mileageM += dist
                }
                if (isStaySegment(dist, dt, speedMps)) {
                    session.stayMs += dt
                }
            }
        }

        session.lastLat = latitude
        session.lastLng = longitude
        session.lastTimestampMs = timestampMs

        if (movedEnough) {
            session.pointCount += 1
        } else if (session.pointCount == 0) {
            session.pointCount = 1
        }
        return movedEnough
    }

    fun replaySessionFromPoints(points: List<TrackPoint>): Session {
        if (points.isEmpty()) return Session()
        var mileageM = 0.0
        var stayMs = 0L
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val dt = (curr.timestampMs - prev.timestampMs).coerceAtLeast(0L)
            if (dt <= 0L) continue
            val dist = distanceM(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
            if (dist >= MIN_MILEAGE_SEGMENT_M) {
                mileageM += dist
            }
            if (isStaySegment(dist, dt, curr.speedMps)) {
                stayMs += dt
            }
        }
        val last = points.last()
        return Session(
            mileageM = mileageM,
            stayMs = stayMs,
            pointCount = points.size,
            lastLat = last.latitude,
            lastLng = last.longitude,
            lastTimestampMs = last.timestampMs,
        )
    }

    fun rebuildFromPoints(
        points: List<TrackPoint>,
        nowMs: Long = System.currentTimeMillis(),
    ): DailyTrackStats {
        if (points.isEmpty()) return DailyTrackStats(0.0, 0, 0)
        val session = Session()
        points.forEachIndexed { index, point ->
            val prev = points.getOrNull(index - 1)
            if (prev != null) {
                val dt = (point.timestampMs - prev.timestampMs).coerceAtLeast(0L)
                if (dt > 0L) {
                    val dist = distanceM(prev.latitude, prev.longitude, point.latitude, point.longitude)
                    if (dist >= MIN_MILEAGE_SEGMENT_M) {
                        session.mileageM += dist
                    }
                    if (isStaySegment(dist, dt, point.speedMps)) {
                        session.stayMs += dt
                    }
                }
            }
            session.lastLat = point.latitude
            session.lastLng = point.longitude
            session.lastTimestampMs = point.timestampMs
        }
        session.pointCount = points.size
        return session.toStats(nowMs, includeTailStay = true)
    }

    private fun computeTailStayMs(
        lastLat: Double?,
        lastLng: Double?,
        lastTimestampMs: Long,
        nowMs: Long,
    ): Long {
        if (lastLat == null || lastLng == null || lastTimestampMs <= 0L) return 0L
        val tail = (nowMs - lastTimestampMs).coerceAtLeast(0L)
        if (tail <= 0L) return 0L
        // 尾段默认视为仍在当前位置停留（尚未产生新轨迹点）
        return tail
    }

    private fun isStaySegment(distanceM: Double, durationMs: Long, speedMps: Float): Boolean {
        if (durationMs <= 0L) return false
        val impliedSpeed = distanceM / (durationMs / 1000.0)
        return speedMps < STAY_SPEED_MPS && impliedSpeed < STAY_SPEED_MPS
    }

    private fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0].toDouble()
    }
}
