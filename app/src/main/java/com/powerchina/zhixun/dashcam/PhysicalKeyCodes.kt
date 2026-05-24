package com.powerchina.zhixun.dashcam

import android.view.KeyEvent

/**
 * 执法仪物理键 keyCode 映射。
 */
object PhysicalKeyCodes {
    /** 拍照键（KEYCODE_F12） */
    const val PHOTO = KeyEvent.KEYCODE_F12 // 142

    /** 录像键 */
    const val RECORD = 136

    /** 系统录音键（OEM 上 keyCodeToString 可能显示 KEYCODE_F8，但值为 138） */
    const val SYSTEM_BLOCK = 138

    /** 同一按键 scanCode（日志：keyCode=138 scanCode=66） */
    const val SYSTEM_BLOCK_SCAN_CODE = 66

    fun isRecordKey(keyCode: Int, scanCode: Int = 0): Boolean {
        if (keyCode == SYSTEM_BLOCK) return true
        if (scanCode == SYSTEM_BLOCK_SCAN_CODE) return true
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && scanCode == SYSTEM_BLOCK_SCAN_CODE) return true
        return false
    }

    fun actionForKeyCode(keyCode: Int): DashcamVideoKeyEvents.KeyAction? {
        return when (keyCode) {
            PHOTO -> DashcamVideoKeyEvents.KeyAction.PHOTO
            RECORD -> DashcamVideoKeyEvents.KeyAction.RECORD
            else -> null
        }
    }

    fun isMappedKey(keyCode: Int): Boolean = actionForKeyCode(keyCode) != null

    fun isBlockedKey(keyCode: Int): Boolean = isRecordKey(keyCode)
}
