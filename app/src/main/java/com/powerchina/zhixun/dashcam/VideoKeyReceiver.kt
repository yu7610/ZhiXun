package com.powerchina.zhixun.dashcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.powerchina.zhixun.physicalkey.PhysicalKeyInterceptor

/**
 * 监听机身物理录像键广播（Manifest + 动态注册）。拍照广播由 [com.powerchina.zhixun.physicalkey.PhotoKeyBlockReceiver] 处理。
 */
class VideoKeyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val keyAction = VideoKeyHandler.resolveKeyAction(action) ?: return
        if (keyAction != DashcamVideoKeyEvents.KeyAction.RECORD) return
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
        const val ACTION_CAMERA_BUTTON = "android.intent.action.CAMERA_BUTTON"
        const val ACTION_PRESS_CAMERA_KEY = "android.intent.action.PRESS_CAMERA_KEY"
        const val ACTION_LONG_PRESS_CAMERA_KEY = "android.intent.action.LONG_PRESS_CAMERA_KEY"
        /** PhoneWindowManager.keyBroadcastCamera 实测（scanCode=88 / F12） */
        const val ACTION_PRESS_PIC_KEY_DOWN = "android.intent.action.PRESS_PIC_KEY_DOWN"
        const val ACTION_PRESS_PIC_KEY = "android.intent.action.PRESS_PIC_KEY"
    }
}
