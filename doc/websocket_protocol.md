# 小智 WebSocket 协议说明（ZhiXun 实现）

本文档描述 `com.powerchina.zhixun` 中与服务器通信的 WebSocket 协议，与 zhixun2 / 官方设备端约定一致。

## 1. 模块职责

| 模块 | 职责 |
|------|------|
| `WebSocketManager` | 连接管理、握手、文本/二进制发送、自动重连 |
| `ConversationViewModel` | 业务状态机：解析 STT/TTS/LLM，驱动录音与播放 |
| `EnhancedAudioManager` | 麦克风采集 Opus 编码上行；下行 Opus 解码播放 |
| `OtaService` | HTTP OTA 获取 `websocket.url` 与激活信息 |

## 2. 连接管理

### 2.1 建立连接

```
OTA（可选）→ 取得 wss URL → WebSocketManager.connect(url, deviceId, token)
```

请求头（`WebSocketManager.connect`）：

| Header | 值 |
|--------|-----|
| `Authorization` | `Bearer {token}` |
| `Protocol-Version` | `1` |
| `Device-Id` | 设备 MAC |
| `Client-Id` | 与 Device-Id 相同（MAC） |

实现：`app/src/main/java/com/powerchina/zhixun/network/WebSocketManager.kt`

### 2.2 断开与重连

- **手动断开**：`disconnect()`，关闭码 1000，禁用自动重连，发出 `Disconnected` 事件
- **异常断开**：`onFailure` / `onClosed` → 发出 `Disconnected` → 延迟 2s 自动重连（若未手动断开）
- **就绪判断**：`isConnected()` = TCP 已连接且握手完成

## 3. 握手（Hello）

### 3.1 客户端 → 服务器（连接成功后立即发送）

```json
{
  "type": "hello",
  "version": 1,
  "transport": "websocket",
  "audio_params": {
    "format": "opus",
    "sample_rate": 16000,
    "channels": 1,
    "frame_duration": 60
  }
}
```

### 3.2 服务器 → 客户端

```json
{
  "type": "hello",
  "transport": "websocket",
  "session_id": "..."
}
```

- 校验 `transport == "websocket"`
- 保存 `session_id`，标记 `isHandshakeComplete = true`
- 事件顺序：`HelloReceived` → `Connected`
- **超时**：15 秒内未收到有效 hello → `Error("握手超时")` 并断开

## 4. 文本消息（客户端 → 服务器）

均携带 `session_id`（握手后）。

| type | state / 字段 | 方法 |
|------|----------------|------|
| `listen` | `start` + `mode`: `auto`/`manual` | `sendStartListening(mode)` |
| `listen` | `stop` | `sendStopListening()` |
| `listen` | `detect` + `text` + `source` | `sendWakeWordDetected(text)` / `sendTextRequest` |
| `abort` | `reason` | `sendAbort(reason)` |

示例（开始聆听）：

```json
{
  "session_id": "xxx",
  "type": "listen",
  "state": "start",
  "mode": "auto"
}
```

## 5. 文本消息（服务器 → 客户端）

由 `ConversationViewModel.handleTextMessage` 处理：

| type | 说明 |
|------|------|
| `stt` | 语音识别结果，更新用户消息，进入 PROCESSING |
| `llm` | 表情/文本，更新助手消息 |
| `tts` | `start` / `stop` / `sentence_start` / `sentence_end`，控制 SPEAKING 与多轮 |
| `mcp` | 经 `WebSocketManager` 转发为 `MCPMessage` 事件 |

## 6. 音频收发

### 6.1 上行（设备 → 服务器）

- 格式：**Opus**，16 kHz，单声道，60 ms 帧
- 路径：`AudioRecord` → `OpusEncoder` → `AudioEvent.AudioData` → `sendBinaryMessage`
- 条件：仅当 `ConversationState.LISTENING` 时发送
- 要求：握手完成（`isHandshakeComplete`）

### 6.2 下行（服务器 → 设备）

- WebSocket **二进制帧** → `BinaryMessage` → `audioManager.playAudio`
- 解码：`OpusDecoder` → `AudioTrack` 播放
- **仅在对话态播放**（`SPEAKING` / `LISTENING` / `PROCESSING`）；`IDLE` 收到的二进制帧视为 abort 后迟回，丢弃
- **唤醒问候窗口**（detect 后约 15s）：允许在 `IDLE`/`LISTENING` 播放问候 TTS，不切 `SPEAKING`、不打断开麦

### 6.3 Listen 会话续期

- 服务端 listen 会话约 **30s** 超时
- 待机唤醒（`ServerWakeDetector`）与对话聆听（`ConversationViewModel`）均每 **12s** 发送 `listen/stop` + `listen/start` 续期

## 7. 典型对话流程

```
connect → hello 握手 → Connected
  → sendStartListening("auto") → LISTENING → 二进制上行
  → stt → PROCESSING
  → tts start → SPEAKING → 二进制下行播放
  → tts stop → 自动模式 startNextRound() 或 IDLE
disconnect / 错误 → Disconnected（对话中自动重连并恢复开麦，不进入待机）
```

**断线恢复（对话中）**：`LISTENING`/`PROCESSING`/`SPEAKING` + 自动模式 → `CONNECTING` + `pendingAutoStart`，重连后 `initializeAudio()` 恢复聆听。

## 8. 与 OTA 的关系

- OTA：`Client-Id` = UUID，`Device-Id` = MAC（`OtaService`）
- WebSocket：`Device-Id` / `Client-Id` 均为 MAC（与 zhixun2 一致）
- 若配置中仅填 OTA、WSS 为空：先 OTA 取 `websocket.url` 再连接

## 9. 相关源码

- `network/WebSocketManager.kt` — 协议传输层
- `viewmodel/ConversationViewModel.kt` — 会话状态与消息分发
- `audio/EnhancedAudioManager.kt` — 音频采集与播放
- `audio/utils/OpusEncoder.kt` / `OpusDecoder.kt` — 编解码
