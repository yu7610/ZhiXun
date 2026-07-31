# 小智 MQTT + UDP 协议说明（ZhiXun 实现）

本应用与小智云通信已切换为 **MQTT 控制 + UDP 音频**，不再使用 WebSocket。

官方协议：https://github.com/78/xiaozhi-esp32/blob/main/docs/mqtt-udp_zh.md

## 核心类

| 类 | 职责 |
|---|---|
| `MqttUdpManager` | MQTT 连接、hello/listen/abort/mcp；UDP AES-CTR Opus |
| `MqttUdpEvent` | 会话事件（Connected / Text / Binary / MCP…） |
| `XiaozhiSessionManager.mqttManager` | 应用层会话入口 |
| `OtaService` | HTTP OTA，读取 `mqtt` 字段下发连接参数 |

## 连接流程

```
OTA → 取得 mqtt{endpoint,client_id,username,password,publish_topic,…}
    → MqttUdpManager.connect(MqttConfig)
    → MQTT hello → 服务端下发 UDP 参数 → UDP 打洞
    → Connected
```

控制消息走 MQTT publish；音频上下行走加密 UDP。
