package com.powerchina.zhixun.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 应用级定位引导：在 Application 完成百度 SDK 初始化，并在有权限时预热定位客户端。
 */
object LocationBootstrap {

    private const val TAG = BaiduLocationReporter.TAG

    fun initialize(application: Application) {
        BaiduSdkInitializer.ensureInitialized(application)
    }

    fun startLocationIfPermitted(context: Context) {
        if (!hasLocationPermission(context)) {
            Log.i(TAG, "定位预热跳过：无权限")
            return
        }
        val app = context.applicationContext as Application
        if (BaiduSdkInitializer.resolveApiKey(app).isBlank()) {
            Log.w(TAG, "定位预热跳过：AK 为空")
            return
        }
        if (!BaiduSdkInitializer.isReady()) {
            BaiduSdkInitializer.ensureInitialized(app)
        }
        BaiduLocationReporter.start(app)
        LocationReportCoordinator.ensureStarted(app)
        Log.i(TAG, "定位预热已启动")
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
