package com.powerchina.zhixun.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 应用级：只做百度 SDK 初始化。
 * 连续定位与 6s 上报由定位页（[LocationViewModel]）进入时启动、离开时停止。
 */
object LocationBootstrap {

    private const val TAG = BaiduLocationReporter.TAG

    fun initialize(application: Application) {
        BaiduSdkInitializer.ensureInitialized(application)
    }

    /**
     * 进入定位页时调用：有权限则启动连续定位并绑定上报。
     */
    fun startLocationIfPermitted(context: Context) {
        if (!hasLocationPermission(context)) {
            Log.i(TAG, "定位启动跳过：无权限")
            return
        }
        val app = context.applicationContext as Application
        if (BaiduSdkInitializer.resolveApiKey(app).isBlank()) {
            Log.w(TAG, "定位启动跳过：AK 为空")
            return
        }
        if (!BaiduSdkInitializer.isReady()) {
            BaiduSdkInitializer.ensureInitialized(app)
        }
        BaiduLocationReporter.start(app)
        LocationReportCoordinator.ensureStarted(app)
        Log.i(TAG, "定位页连续定位已启动 interval=${BaiduLocationReporter.SCAN_INTERVAL_MS}ms")
    }

    fun hasLocationPermission(context: Context): Boolean {
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
