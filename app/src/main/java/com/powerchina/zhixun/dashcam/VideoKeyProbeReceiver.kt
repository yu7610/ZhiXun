package com.powerchina.zhixun.dashcam

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.powerchina.zhixun.physicalkey.PhotoKeyBroadcast
import com.powerchina.zhixun.physicalkey.PhysicalKeyInterceptor
import com.powerchina.zhixun.physicalkey.RecordKeyBroadcast

/**
 * 探测与录像/拍照/录音相关的系统广播；优先拦截并阻断系统默认行为。
 */
class VideoKeyProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!action.contains("VIDEO", ignoreCase = true) &&
            !action.contains("RECORD", ignoreCase = true) &&
            !action.contains("CAMERA", ignoreCase = true) &&
            !action.contains("PHOTO", ignoreCase = true) &&
            !action.contains("AUDIO", ignoreCase = true) &&
            !action.contains("SNAPSHOT", ignoreCase = true)
        ) {
            return
        }
        Log.w(
            VideoKeyReceiver.TAG,
            "【探测广播】action=$action package=${intent.`package`} " +
                "component=${intent.component} extras=${intent.extras}",
        )
        when {
            PhotoKeyBroadcast.isPhotoKeyAction(action) -> {
                Log.i(VideoKeyReceiver.TAG, "【探测广播】拦截拍照 action=$action")
                resultCode = Activity.RESULT_CANCELED
                if (isOrderedBroadcast) {
                    abortBroadcast()
                }
                VideoKeyHandler.requestServerPhoto(context.applicationContext)
            }
            PhysicalKeyInterceptor.isAppInForeground &&
                RecordKeyBroadcast.isRecordKeyAction(action) -> {
                Log.i(VideoKeyReceiver.TAG, "【探测广播】拦截录音键 action=$action")
                resultCode = Activity.RESULT_CANCELED
                if (isOrderedBroadcast) {
                    abortBroadcast()
                }
            }
        }
    }
}
