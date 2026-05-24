package com.powerchina.zhixun.xiaozhi

import android.util.Log

/**
 * 语音全流程诊断日志（唤醒 / 对话 / 退下 / 待机）。
 *
 * 推荐过滤：
 * ```
 * adb logcat -s VoiceFlow ConversationViewModel WakeSTT WakeService SessionEndReply
 * ```
 */
object VoiceFlowLog {

    const val TAG = "VoiceFlow"

    /** 当前节点 + 完整上下文快照 */
    fun snapshot(event: String, detail: String) {
        Log.i(TAG, "▶ $event | $detail")
    }

    /** 状态迁移 */
    fun transition(event: String, from: String, to: String, reason: String, context: String = "") {
        val ctx = if (context.isBlank()) "" else " | $context"
        Log.i(TAG, "⇄ $event | $from → $to | $reason$ctx")
    }

    /** 允许/拒绝决策（TTS、STT、开麦等） */
    fun decision(event: String, action: String, allowed: Boolean, reason: String) {
        val mark = if (allowed) "✓" else "✗"
        Log.i(TAG, "$mark $event | $action | ${if (allowed) "允许" else "拒绝"} | $reason")
    }

    fun step(event: String, detail: String) {
        Log.d(TAG, "· $event | $detail")
    }

    fun warn(event: String, detail: String) {
        Log.w(TAG, "⚠ $event | $detail")
    }

    fun error(event: String, detail: String) {
        Log.e(TAG, "✖ $event | $detail")
    }
}
