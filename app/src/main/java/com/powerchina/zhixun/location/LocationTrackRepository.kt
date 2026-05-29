package com.powerchina.zhixun.location

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object LocationTrackRepository {

    private const val PREFS = "location_track"
    private const val KEY_TODAY_POINTS = "today_points"
    private const val KEY_TODAY_DATE = "today_date"
    private const val KEY_HISTORY = "history"
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val labelFormat = SimpleDateFormat("MM月dd日", Locale.CHINA)

    fun loadTodayPoints(context: Context): List<TrackPoint> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = dateFormat.format(Date())
        val savedDate = prefs.getString(KEY_TODAY_DATE, null)
        if (savedDate != today) {
            archiveYesterdayIfNeeded(context, prefs, savedDate)
            prefs.edit()
                .putString(KEY_TODAY_DATE, today)
                .putString(KEY_TODAY_POINTS, "[]")
                .apply()
            return emptyList()
        }
        val json = prefs.getString(KEY_TODAY_POINTS, "[]") ?: "[]"
        val type = object : TypeToken<List<TrackPoint>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun appendPoint(context: Context, point: TrackPoint): List<TrackPoint> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val points = loadTodayPoints(context).toMutableList()
        points.add(point)
        saveTodayPoints(context, points)
        return points
    }

    fun saveTodayPoints(context: Context, points: List<TrackPoint>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_TODAY_DATE, dateFormat.format(Date()))
            .putString(KEY_TODAY_POINTS, gson.toJson(points))
            .apply()
    }

    fun loadHistory(context: Context): List<DailyTrackHistory> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        val type = object : TypeToken<List<DailyTrackHistory>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun computeStats(
        points: List<TrackPoint>,
        nowMs: Long = System.currentTimeMillis(),
    ): DailyTrackStats = TrackStatsCalculator.rebuildFromPoints(points, nowMs)

    private fun archiveYesterdayIfNeeded(
        context: Context,
        prefs: android.content.SharedPreferences,
        savedDate: String?,
    ) {
        if (savedDate.isNullOrBlank()) return
        val json = prefs.getString(KEY_TODAY_POINTS, "[]") ?: "[]"
        val type = object : TypeToken<List<TrackPoint>>() {}.type
        val points: List<TrackPoint> = gson.fromJson(json, type) ?: emptyList()
        if (points.isEmpty()) return
        val stats = computeStats(points)
        val history = loadHistory(context).toMutableList()
        history.removeAll { it.dateLabel == savedDate }
        history.add(
            0,
            DailyTrackHistory(
                dateLabel = runCatching { labelFormat.format(dateFormat.parse(savedDate)!!) }
                    .getOrDefault(savedDate),
                mileageKm = stats.mileageKm,
                stayMinutes = stats.stayMinutes,
                pointCount = stats.pointCount,
            ),
        )
        prefs.edit().putString(KEY_HISTORY, gson.toJson(history.take(30))).apply()
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return 2 * r * asin(sqrt(a))
    }

    fun formatCoordinate(lat: Double, lng: Double): String {
        return "${"%.4f".format(Locale.CHINA, lat)} ${"%.4f".format(Locale.CHINA, lng)}"
    }

    fun formatSpeedMps(speedMps: Float): String {
        val value = (speedMps * 10).roundToInt() / 10.0
        return "${value}m/s"
    }

    fun deriveSpeedMps(
        previous: TrackPoint?,
        latitude: Double,
        longitude: Double,
        timestampMs: Long,
        reportedSpeedMps: Float,
    ): Float {
        if (reportedSpeedMps > 0f) return reportedSpeedMps
        if (previous == null) return 0f
        val elapsedSec = (timestampMs - previous.timestampMs) / 1000.0
        if (elapsedSec <= 0.0) return 0f
        val distanceM = haversineM(previous.latitude, previous.longitude, latitude, longitude)
        return (distanceM / elapsedSec).toFloat().coerceAtLeast(0f)
    }

    fun todayLabel(): String = labelFormat.format(Date())
}
