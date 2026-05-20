package com.powerchina.zhixun.dashcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.powerchina.zhixun.physicalkey.PhysicalKeyInterceptor

/**
 * 监听机身物理录像键广播（Manifest + 动态注册）。
 */
class VideoKeyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!PhysicalKeyInterceptor.isAppInForeground) return
        if (PhysicalKeyInterceptor.dispatchVideoBroadcast(context, intent)) {
            if (isOrderedBroadcast) {
                abortBroadcast()
            }
        }
    }

    companion object {
        const val TAG = "ZhiXunVideoKey"

        const val ACTION_PRESS_VIDEO_KEY = "android.intent.action.PRESS_VIDEO_KEY"
        const val ACTION_LONG_PRESS_VIDEO_KEY = "android.intent.action.LONG_PRESS_VIDEO_KEY"
    }
}
