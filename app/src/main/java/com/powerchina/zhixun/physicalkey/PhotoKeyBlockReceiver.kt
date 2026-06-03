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
            VideoKeyReceiver.TAG,
            "拦截拍照广播 action=$action extras=${intent.extras}",
        )
        resultCode = Activity.RESULT_CANCELED
        if (isOrderedBroadcast) {
            abortBroadcast()
        }
        VideoKeyHandler.requestServerPhoto(context.applicationContext)
    }
}
