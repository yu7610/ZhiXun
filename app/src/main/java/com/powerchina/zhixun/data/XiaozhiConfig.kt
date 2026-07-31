package com.powerchina.zhixun.data

/**
 * 小智配置数据类
 */
data class XiaozhiConfig(
    val id: String,
    val name: String,
    val otaUrl: String,
    val mqtt: MqttConfig = MqttConfig(),
    val macAddress: String,
    val uuid: String,
    val token: String,
    val mcpEnabled: Boolean = false,
    val mcpServers: List<McpServer> = emptyList()
) {
    companion object {
        /**
         * 创建默认配置
         */
        fun createDefault(): XiaozhiConfig {
            return XiaozhiConfig(
                id = "default",
                name = "Android",
                otaUrl = "",
                mqtt = MqttConfig(),
                macAddress = "",
                uuid = generateRandomUuid(),
                token = "test-token",
                mcpEnabled = false,
                mcpServers = listOf(
                    McpServer("示例MCP服务器", "https://example.com/mcp", false)
                )
            )
        }

        /**
         * 生成随机UUID
         */
        fun generateRandomUuid(): String {
            return java.util.UUID.randomUUID().toString()
        }
    }
}

/**
 * MQTT 连接配置（由 OTA 接口 mqtt 字段下发并持久化）
 *
 * 官方示例：
 * ```
 * "mqtt": {
 *   "endpoint": "mqtt.xiaozhi.me",
 *   "client_id": "GID_test@@@8d_1a_7b_c0_7c_69@@@uuid",
 *   "username": "...",
 *   "password": "...",
 *   "publish_topic": "device-server",
 *   "subscribe_topic": "null"
 * }
 * ```
 * `subscribe_topic` 为 `"null"` 表示网关 P2P 模式，无需客户端主动订阅。
 */
data class MqttConfig(
    val endpoint: String = "",
    val clientId: String = "",
    val username: String = "",
    val password: String = "",
    val publishTopic: String = "",
    val subscribeTopic: String = "",
    val keepalive: Int = 240,
) {
    fun isReady(): Boolean =
        endpoint.isNotBlank() && clientId.isNotBlank() && publishTopic.isNotBlank()

    /** 有效订阅主题；空 / "null" 视为不需要订阅（官方 mqtt.xiaozhi.me P2P） */
    fun effectiveSubscribeTopic(): String? {
        val topic = subscribeTopic.trim()
        if (topic.isEmpty() || topic.equals("null", ignoreCase = true)) return null
        return topic
    }

    companion object {
        fun fromOta(
            endpoint: String,
            clientId: String,
            username: String,
            password: String,
            publishTopic: String,
            subscribeTopic: String,
            keepalive: Int = 240,
        ): MqttConfig {
            val sub = subscribeTopic.trim().let {
                if (it.equals("null", ignoreCase = true)) "" else it
            }
            return MqttConfig(
                endpoint = endpoint.trim(),
                clientId = clientId.trim(),
                username = username,
                password = password,
                publishTopic = publishTopic.trim(),
                subscribeTopic = sub,
                keepalive = if (keepalive > 0) keepalive else 240,
            )
        }
    }
}

/**
 * MCP服务器配置
 */
data class McpServer(
    val name: String,
    val url: String,
    val enabled: Boolean = true
)
