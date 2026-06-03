package com.powerchina.zhixun.physicalkey

import com.powerchina.zhixun.dashcam.DashcamVideoKeyEvents
import com.powerchina.zhixun.dashcam.VideoKeyHandler
import com.powerchina.zhixun.dashcam.VideoKeyReceiver

/** 执法仪拍照键系统广播（Manifest + 动态注册共用）。 */
object PhotoKeyBroadcast {

    val MANIFEST_ACTIONS = listOf(
        VideoKeyReceiver.ACTION_PRESS_PIC_KEY_DOWN,
        VideoKeyReceiver.ACTION_PRESS_PIC_KEY,
        VideoKeyReceiver.ACTION_PRESS_VIDEO_KEY,
        VideoKeyReceiver.ACTION_CAMERA_BUTTON,
        VideoKeyReceiver.ACTION_PRESS_CAMERA_KEY,
        "android.intent.action.CAMERA_SNAPSHOT",
        "intent.action.PRESS_VIDEO_KEY",
        "intent.action.PRESS_CAMERA_KEY",
        "com.android.intent.action.PRESS_VIDEO_KEY",
        "com.android.intent.action.PRESS_CAMERA_KEY",
        "com.android.camera.action.CAMERA_BUTTON",
        "com.yulong.action.PRESS_PHOTO_KEY",
        "com.yulong.action.PHOTO_KEY",
        "com.yulong.android.action.PRESS_PHOTO_KEY",
        "com.yulong.android.action.PHOTO_KEY",
        "com.yulong.action.CAMERA_KEY",
        "com.yulong.android.action.CAMERA_KEY",
    )

    fun isPhotoKeyAction(action: String?): Boolean {
        if (action.isNullOrBlank()) return false
        if (action in MANIFEST_ACTIONS) return true
        if (VideoKeyHandler.resolveKeyAction(action) == DashcamVideoKeyEvents.KeyAction.PHOTO) {
            return true
        }
        val upper = action.uppercase()
        if (upper.contains("LONG_PRESS")) return false
        if (upper.contains("PIC_KEY")) return true
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
