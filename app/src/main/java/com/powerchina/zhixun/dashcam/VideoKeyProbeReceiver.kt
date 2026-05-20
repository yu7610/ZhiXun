package com.powerchina.zhixun.dashcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 探测与录像相关的系统广播 action（便于在 Logcat 中确认 OEM 实际 action）。
 */
class VideoKeyProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!action.contains("VIDEO", ignoreCase = true) &&
            !action.contains("RECORD", ignoreCase = true) &&
            !action.contains("CAMERA", ignoreCase = true)
        ) {
            return
        }
        Log.w(
            VideoKeyReceiver.TAG,
            "【探测广播】action=$action package=${intent.`package`} " +
                "component=${intent.component} extras=${intent.extras}",
        )
    }
}
