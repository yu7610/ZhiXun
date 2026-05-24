package com.powerchina.zhixun.physicalkey

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.powerchina.zhixun.dashcam.VideoKeyReceiver

/**
 * 拦截录音键系统广播，并触发应用内开麦（部分 OEM 只发广播不发 KeyEvent）。
 */
class RecordKeyBlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!RecordKeyBroadcast.isRecordKeyAction(action)) return
        if (!PhysicalKeyInterceptor.isAppInForeground) return

        Log.i(
            VideoKeyReceiver.TAG,
            "拦截录音键广播 action=$action extras=${intent.extras}",
        )
        resultCode = Activity.RESULT_CANCELED
        if (isOrderedBroadcast) {
            abortBroadcast()
        }
        // 部分 OEM 只发 LONG_PRESS_RECORD_KEY；与 KeyEvent ACTION_UP 去重在 RecordKeyHandler
        if (action.contains("LONG_PRESS", ignoreCase = true)) {
            RecordKeyHandler.handleLongPress(context.applicationContext)
        } else {
            RecordKeyHandler.handleKeyUp(context.applicationContext)
        }
    }
}
