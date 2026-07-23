package com.powerchina.zhixun.location

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * 应用级：只做百度 SDK 初始化。
 * 定位脉冲与上报由定位页（[LocationViewModel]）进入时启动、离开时停止。
 */
object LocationBootstrap {

    fun initialize(application: Application) {
        BaiduSdkInitializer.ensureInitialized(application)
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
