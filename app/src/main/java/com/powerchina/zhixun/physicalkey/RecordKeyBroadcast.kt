package com.powerchina.zhixun.physicalkey

/**
 * 执法仪「录音键」相关系统广播（OEM 常用 action，用于阻断系统默认弹窗/跳转）。
 */
object RecordKeyBroadcast {

    val MANIFEST_ACTIONS = listOf(
        "android.intent.action.PRESS_RECORD_KEY",
        "android.intent.action.LONG_PRESS_RECORD_KEY",
        "intent.action.PRESS_RECORD_KEY",
        "intent.action.LONG_PRESS_RECORD_KEY",
        "com.android.intent.action.PRESS_RECORD_KEY",
        "com.android.intent.action.LONG_PRESS_RECORD_KEY",
        "android.intent.action.PRESS_AUDIO_KEY",
        "android.intent.action.LONG_PRESS_AUDIO_KEY",
        "intent.action.PRESS_AUDIO_KEY",
        "intent.action.LONG_PRESS_AUDIO_KEY",
        "android.intent.action.RECORD_KEY_DOWN",
        "android.intent.action.RECORD_KEY_UP",
        "com.yulong.action.RECORD_KEY",
        "com.yulong.action.PRESS_RECORD_KEY",
    )

    fun isRecordKeyAction(action: String?): Boolean {
        if (action.isNullOrBlank()) return false
        if (action in MANIFEST_ACTIONS) return true
        val upper = action.uppercase()
        if (upper.contains("RECORD") &&
            (upper.contains("KEY") || upper.contains("PRESS") || upper.contains("BUTTON"))
        ) {
            return true
        }
        if (upper.contains("AUDIO") && upper.contains("KEY") && upper.contains("PRESS")) {
            return true
        }
        return false
    }
}
