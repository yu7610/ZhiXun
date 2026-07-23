package com.powerchina.zhixun.physicalkey

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.powerchina.zhixun.dashcam.VideoKeyReceiver

/**
 * 拦截录音键系统广播，并触发应用内开麦（部分 OEM 只发广播不发 KeyEvent）。
 *
 * 无论前台/后台都 abort，避免同键拉起系统录音 App 抢麦导致对话开麦失败。
 * 长按广播仅拦截、不处理；短按抬起才走应用内对话。
 */
class RecordKeyBlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!RecordKeyBroadcast.isRecordKeyAction(action)) return

        // 先阻断系统默认录音
        resultCode = Activity.RESULT_CANCELED
        if (isOrderedBroadcast) {
            abortBroadcast()
        }

        Log.i(
            VideoKeyReceiver.TAG,
            "拦截录音键广播 action=$action foreground=${PhysicalKeyInterceptor.isAppInForeground} " +
                "extras=${intent.extras}",
        )

        if (action.contains("LONG_PRESS", ignoreCase = true)) {
            // 长按：只拦截，不触发对话（并抑制随后 KEY_UP）
            RecordKeyHandler.markLongPressIntercepted()
            return
        }
        RecordKeyHandler.handleKeyUp(context.applicationContext)
    }
}
