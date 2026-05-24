package com.powerchina.zhixun.xiaozhi

/**
 * 小智 MCP initialize 下发的视觉分析端点（HTTP multipart）。
 */
data class XiaozhiVisionConfig(
    val url: String,
    val token: String,
    val deviceId: String,
    val clientId: String,
)

object XiaozhiVisionRegistry {

    @Volatile
    private var config: XiaozhiVisionConfig? = null

    fun update(config: XiaozhiVisionConfig) {
        this.config = config
    }

    fun get(): XiaozhiVisionConfig? = config

    fun clear() {
        config = null
    }
}
