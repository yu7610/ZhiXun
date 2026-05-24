package com.powerchina.zhixun.dashcam

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.powerchina.zhixun.physicalkey.PhysicalKeyInterceptor
import com.powerchina.zhixun.physicalkey.RecordKeyBroadcast

/**
 * 探测与录像/录音相关的系统广播；前台时拦截录音键广播，避免系统弹窗。
 */
class VideoKeyProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!action.contains("VIDEO", ignoreCase = true) &&
            !action.contains("RECORD", ignoreCase = true) &&
            !action.contains("CAMERA", ignoreCase = true) &&
            !action.contains("AUDIO", ignoreCase = true)
        ) {
            return
        }
        Log.w(
            VideoKeyReceiver.TAG,
            "【探测广播】action=$action package=${intent.`package`} " +
                "component=${intent.component} extras=${intent.extras}",
        )
        if (!PhysicalKeyInterceptor.isAppInForeground) return
        if (!RecordKeyBroadcast.isRecordKeyAction(action)) return

        Log.i(VideoKeyReceiver.TAG, "【探测广播】拦截录音键 action=$action")
        resultCode = Activity.RESULT_CANCELED
        if (isOrderedBroadcast) {
            abortBroadcast()
        }
    }
}
