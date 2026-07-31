package com.powerchina.zhixun.network

import android.annotation.SuppressLint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OTA设备上报请求数据类
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class DeviceReportRequest(
    @SerialName("application")
    val application: Application,

    @SerialName("board")
    val board: BoardInfo
) {

    @Serializable
    data class Application(
        @SerialName("version")
        val version: String,

        @SerialName("elf_sha256")
        val elfSha256: String
    )

    @Serializable
    data class BoardInfo(
        @SerialName("type")
        val type: String,

        @SerialName("name")
        val name: String? = null,

        @SerialName("ssid")
        val ssid: String,

        @SerialName("rssi")
        val rssi: Int,

        @SerialName("channel")
        val channel: Int,

        @SerialName("ip")
        val ip: String,

        @SerialName("mac")
        val mac: String
    )
}

/**
 * OTA响应数据类
 */
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class OtaResponse(
    @SerialName("server_time")
    val serverTime: ServerTime? = null,

    @SerialName("activation")
    val activation: Activation? = null,

    @SerialName("firmware")
    val firmware: Firmware? = null,

    /** 旧协议字段：解析兼容，连接层已改用 [mqtt] */
    @SerialName("websocket")
    val websocket: OtaWebsocket? = null,

    @SerialName("mqtt")
    val mqtt: Mqtt? = null,
) {
    @Serializable
    data class ServerTime(
        @SerialName("timestamp")
        val timestamp: Long,

        @SerialName("timeZone")
        val timeZone: String? = null,

        @SerialName("timezone_offset")
        val timezoneOffset: Int = 0
    )

    @Serializable
    data class Activation(
        @SerialName("code")
        val code: String,

        @SerialName("message")
        val message: String,

        @SerialName("challenge")
        val challenge: String
    )

    @Serializable
    data class Firmware(
        @SerialName("version")
        val version: String,

        @SerialName("url")
        val url: String
    )

    @Serializable
    data class OtaWebsocket(
        @SerialName("url")
        val url: String = "",

        @SerialName("token")
        val token: String? = null,
    )

    /**
     * MQTT 连接参数（OTA 下发），对应 xiaozhi mqtt 协议配置。
     * 示例：
     * ```
     * "mqtt": {
     *   "endpoint": "192.168.0.7:1883",
     *   "client_id": "GID_default@@@11_22_33_44_55_66@@@uuid",
     *   "username": "...",
     *   "password": "...",
     *   "publish_topic": "device-server",
     *   "subscribe_topic": "devices/p2p/11_22_33_44_55_66"
     * }
     * ```
     */
    @Serializable
    data class Mqtt(
        @SerialName("endpoint")
        val endpoint: String,

        @SerialName("client_id")
        val clientId: String,

        @SerialName("username")
        val username: String = "",

        @SerialName("password")
        val password: String = "",

        @SerialName("publish_topic")
        val publishTopic: String = "",

        @SerialName("subscribe_topic")
        val subscribeTopic: String = "",

        @SerialName("keepalive")
        val keepalive: Int = 240,
    )
}
