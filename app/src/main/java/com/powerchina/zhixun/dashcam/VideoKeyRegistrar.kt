package com.powerchina.zhixun.dashcam

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
/**
 * 应用进程内动态注册录像键广播（部分 OEM 仅发给动态 Receiver）。
 */
object VideoKeyRegistrar {

    private var registered = false
    private val receiver = VideoKeyReceiver()

    fun register(context: Context) {
        if (registered) return
        val appContext = context.applicationContext
        val filter = IntentFilter().apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            addAction(VideoKeyReceiver.ACTION_PRESS_VIDEO_KEY)
            addAction(VideoKeyReceiver.ACTION_LONG_PRESS_VIDEO_KEY)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        registered = true
        Log.i(VideoKeyReceiver.TAG, "动态注册录像键广播完成 (API ${Build.VERSION.SDK_INT})")
    }

    fun unregister(context: Context) {
        if (!registered) return
        try {
            context.applicationContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(VideoKeyReceiver.TAG, "注销动态广播失败", e)
        }
        registered = false
        Log.i(VideoKeyReceiver.TAG, "已注销动态录像键广播")
    }
}
