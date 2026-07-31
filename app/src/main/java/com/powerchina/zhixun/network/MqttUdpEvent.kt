package com.powerchina.zhixun.network

/**
 * MQTT + UDP 会话事件（控制面 MQTT / 音频面 UDP）。
 */
sealed class MqttUdpEvent {
    object Connected : MqttUdpEvent()
    object Disconnected : MqttUdpEvent()
    data class TextMessage(val message: String) : MqttUdpEvent()
    data class BinaryMessage(val data: ByteArray) : MqttUdpEvent()
    data class Error(val error: String) : MqttUdpEvent()
    object HelloReceived : MqttUdpEvent()
    data class MCPMessage(val message: String) : MqttUdpEvent()
}
