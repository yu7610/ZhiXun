package com.powerchina.zhixun.dashcam

import android.content.Context
import java.io.File

/**
 * MCP 拍照入口：执法仪会话优先，否则 [McpCameraHolder] 后台快门。
 */
object QuickPhotoCapture {

    fun preWarm(context: Context) {
        McpCameraHolder.ensureAcquired(context)
    }

    fun releasePreWarm() {
        McpCameraHolder.releasePreWarm()
    }

    fun forceReset() {
        McpCameraHolder.forceReset()
    }

    fun capture(context: Context, onResult: (Result<File>) -> Unit) {
        McpCameraHolder.capture(context, onResult)
    }
}
