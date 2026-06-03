package com.powerchina.zhixun.physicalkey

import com.powerchina.zhixun.dashcam.DashcamVideoKeyEvents
import com.powerchina.zhixun.dashcam.VideoKeyHandler
import com.powerchina.zhixun.dashcam.VideoKeyReceiver

/**
 * 执法仪「拍照键」相关系统广播（OEM 常用 action，用于阻断系统相机/快拍）。
 */
object PhotoKeyBroadcast {

    val MANIFEST_ACTIONS = listOf(
        VideoKeyReceiver.ACTION_PRESS_VIDEO_KEY,
        VideoKeyReceiver.ACTION_CAMERA_BUTTON,
        VideoKeyReceiver.ACTION_PRESS_CAMERA_KEY,
        "intent.action.PRESS_VIDEO_KEY",
        "intent.action.PRESS_CAMERA_KEY",
        "com.android.intent.action.PRESS_VIDEO_KEY",
        "com.android.intent.action.PRESS_CAMERA_KEY",
        "com.android.camera.action.CAMERA_BUTTON",
        "android.intent.action.CAMERA_SNAPSHOT",
        "com.yulong.action.PRESS_PHOTO_KEY",
        "com.yulong.action.PHOTO_KEY",
    )

    fun isPhotoKeyAction(action: String?): Boolean {
        if (action.isNullOrBlank()) return false
        if (action in MANIFEST_ACTIONS) return true
        if (VideoKeyHandler.resolveKeyAction(action) == DashcamVideoKeyEvents.KeyAction.PHOTO) {
            return true
        }
        val upper = action.uppercase()
        if (upper.contains("LONG_PRESS")) return false
        if (upper.contains("PHOTO") || upper.contains("SNAPSHOT")) return true
        if (upper.contains("VIDEO") && upper.contains("PRESS")) return true
        if (upper.contains("CAMERA") &&
            (upper.contains("BUTTON") || upper.contains("PRESS") || upper.contains("KEY"))
        ) {
            return true
        }
        return false
    }
}
