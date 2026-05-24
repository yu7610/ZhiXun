package com.powerchina.zhixun.physicalkey

import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.powerchina.zhixun.dashcam.VideoKeyReceiver

/**
 * 动态注册录音键广播（部分 OEM 不发 KeyEvent，只发隐式广播）。
 */
object RecordKeyRegistrar {

    private var registered = false
    private val receiver = RecordKeyBlockReceiver()

    fun register(context: Context) {
        if (registered) return
        val appContext = context.applicationContext
        val filter = IntentFilter().apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            RecordKeyBroadcast.MANIFEST_ACTIONS.forEach { addAction(it) }
            addCategory(android.content.Intent.CATEGORY_DEFAULT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        registered = true
        Log.i(VideoKeyReceiver.TAG, "动态注册录音键广播拦截 (${RecordKeyBroadcast.MANIFEST_ACTIONS.size} actions)")
    }
}
