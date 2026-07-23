package com.powerchina.zhixun.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.baidu.location.BDLocation
import com.powerchina.zhixun.data.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 进入定位页后绑定连续定位回调，约每 6 秒上报 receiveLocation。
 */
object LocationReportCoordinator {

    private const val TAG = BaiduLocationReporter.TAG
    /** 略小于 6s，避免回调稍早被当成重复上报 */
    private const val MIN_REPORT_INTERVAL_MS = 4_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var uploadListener: ((BDLocation) -> Unit)? = null
    private var started = false
    @Volatile
    private var lastReportTimeMs: Long = 0L
    private val reportLock = Any()

    private val _riskUpdates = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 1)
    val riskUpdates: SharedFlow<String> = _riskUpdates.asSharedFlow()

    fun ensureStarted(context: Context) {
        if (!hasLocationPermission(context)) {
            Log.i(TAG, "无定位权限，跳过上报")
            return
        }
        if (!started) {
            started = true
            lastReportTimeMs = 0L
            val appContext = context.applicationContext
            val listener: (BDLocation) -> Unit = { location ->
                scope.launch {
                    uploadLocation(appContext, location)
                }
            }
            uploadListener = listener
            BaiduLocationReporter.addListener(listener)
            Log.i(TAG, "定位上报已绑定，间隔约 ${BaiduLocationReporter.SCAN_INTERVAL_MS}ms")
        }
        BaiduLocationReporter.start(context.applicationContext)
    }

    fun stop() {
        uploadListener?.let { BaiduLocationReporter.removeListener(it) }
        uploadListener = null
        started = false
        lastReportTimeMs = 0L
    }

    private fun uploadLocation(context: Context, location: BDLocation) {
        if (!hasLocationPermission(context)) return
        if (!BaiduLocationReporter.isValid(location)) {
            Log.w(TAG, "跳过无效定位上报 type=${location.locType}")
            return
        }
        val now = System.currentTimeMillis()
        synchronized(reportLock) {
            if (now - lastReportTimeMs < MIN_REPORT_INTERVAL_MS) {
                Log.i(TAG, "跳过重复上报 interval=${now - lastReportTimeMs}ms")
                return
            }
            lastReportTimeMs = now
        }
        val terCode = ConfigManager(context).loadConfig().macAddress
        LocationReportUploader.report(
            context = context,
            latitude = location.latitude,
            longitude = location.longitude,
            terCode = terCode,
        ).onSuccess { result ->
            val data = result.data.orEmpty().trim()
            if (data.isEmpty()) {
                Log.i(TAG, "风险描述 data 为空，隐藏风险告警")
            } else {
                Log.i(TAG, "更新风险描述: $data")
            }
            _riskUpdates.tryEmit(data)
        }
    }

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }
}
