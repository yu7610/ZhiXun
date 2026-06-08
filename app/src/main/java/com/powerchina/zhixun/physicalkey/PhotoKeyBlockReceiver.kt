package com.powerchina.zhixun.physicalkey

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.powerchina.zhixun.dashcam.VideoKeyHandler
import com.powerchina.zhixun.dashcam.VideoKeyReceiver

/**
 * 最高优先级拦截拍照键系统广播，阻止系统相机/快拍，并转给应用内「发服务器 → MCP 拍照」流程。
 */
class PhotoKeyBlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!PhotoKeyBroadcast.isPhotoKeyAction(action)) return

        Log.i(
            PhotoKeyLog.TAG,
            "拦截拍照广播 action=$action extras=${intent.extras}",
        )
        resultCode = Activity.RESULT_CANCELED
        if (isOrderedBroadcast) {
            abortBroadcast()
        }
        // 相机预热改到 MCP take_photo 前，避免与上一轮会话争用相机导致主线程 ANR
        // PRESS_PIC_KEY（抬起）仅阻断系统相机；拍照在 DOWN 广播或 KeyEvent 已触发
        if (action != VideoKeyReceiver.ACTION_PRESS_PIC_KEY) {
            VideoKeyHandler.requestServerPhoto(context.applicationContext)
        }
    }
}
