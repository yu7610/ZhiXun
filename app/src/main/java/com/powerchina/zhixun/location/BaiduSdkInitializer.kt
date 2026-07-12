package com.powerchina.zhixun.location

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import com.baidu.location.LocationClient
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer

object BaiduSdkInitializer {

    private const val TAG = "BaiduSdk"
    private const val META_API_KEY = "com.baidu.lbsapi.API_KEY"
    @Volatile
    private var initialized = false
    @Volatile
    private var initError: String? = null

    fun isReady(): Boolean = initialized

    fun lastError(): String? = initError

    fun resolveApiKey(application: Application): String {
        return runCatching {
            val appInfo = application.packageManager.getApplicationInfo(
                application.packageName,
                PackageManager.GET_META_DATA,
            )
            appInfo.metaData?.getString(META_API_KEY).orEmpty().trim()
        }.getOrDefault("")
    }

    fun ensureInitialized(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                LocationClient.setAgreePrivacy(true)
                SDKInitializer.setAgreePrivacy(application, true)
                SDKInitializer.initialize(application)
                SDKInitializer.setCoordType(CoordType.BD09LL)
                initialized = true
                initError = null
                Log.i(TAG, "百度地图 SDK 初始化成功")
            } catch (e: Exception) {
                initError = e.message ?: "百度地图 SDK 初始化失败"
                Log.e(TAG, "百度地图 SDK 初始化失败", e)
            }
        }
    }
}
