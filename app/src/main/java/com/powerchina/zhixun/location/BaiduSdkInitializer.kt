package com.powerchina.zhixun.location

import android.app.Application
import android.util.Log
import com.baidu.location.LocationClient
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer

object BaiduSdkInitializer {

    private const val TAG = "BaiduSdk"
    @Volatile
    private var initialized = false

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
                Log.i(TAG, "百度地图 SDK 初始化成功")
            } catch (e: Exception) {
                Log.e(TAG, "百度地图 SDK 初始化失败", e)
            }
        }
    }
}
