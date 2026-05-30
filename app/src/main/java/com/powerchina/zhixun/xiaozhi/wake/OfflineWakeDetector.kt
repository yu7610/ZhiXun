package com.powerchina.zhixun.xiaozhi.wake

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.powerchina.zhixun.xiaozhi.VoiceFlowLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 离线语音唤醒：本地用 sherpa-onnx KWS 检测「你好，智询」，待机时不连服务器、不上传音频。
 * 命中后回调 onWakeDetected，由 XiaozhiWakeCoordinator 连接服务器并进入对话。
 *
 * 与 ServerWakeDetector 的关键区别：离线检测时服务端尚未听到唤醒词，
 * 因此不设置 serverGreetingTtsPending —— 由对话握手阶段补发 listen/detect 让服务端问候。
 */
class OfflineWakeDetector(
    context: Context,
    private val onWakeDetected: () -> Unit,
) : WakeListener {

    companion object {
        private const val TAG = "WakeKWS"
        private const val SAMPLE_RATE = 16_000
        private const val MODEL_DIR = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01"
        private const val KEYWORDS_ASSET = "keywords-zhixun.txt"
        private const val DEBOUNCE_MS = 2_000L

        /** 模型/库是否可用（缺 so 或加载失败时降级到 ServerWakeDetector） */
        fun isAvailable(): Boolean = runCatching {
            // 触发 KeywordSpotter 类加载（含 System.loadLibrary）
            Class.forName("com.k2fsa.sherpa.onnx.KeywordSpotter")
            true
        }.getOrDefault(false)
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var active = false
    override val isActive: Boolean get() = active
    override val isStreaming: Boolean get() = active

    private var loopJob: Job? = null
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastWakeAtMs = 0L

    override fun start() {
        if (active) {
            Log.d(TAG, "已在运行，跳过 start")
            return
        }
        if (!ensureSpotter()) {
            Log.e(TAG, "KWS 初始化失败")
            return
        }
        active = true
        acquireWakeLock()
        Log.i(TAG, "启动离线唤醒，关键词=${WakePhraseMatcher.WAKE_PHRASE}")
        VoiceFlowLog.snapshot("wakeKWS.start", "keyword=${WakePhraseMatcher.WAKE_PHRASE}")
        loopJob?.cancel()
        loopJob = scope.launch { detectLoop() }
    }

    override fun pause() {
        if (!active) return
        Log.d(TAG, "暂停离线唤醒")
        VoiceFlowLog.step("wakeKWS.pause", "")
        active = false
        loopJob?.cancel()
        loopJob = null
        stopAudio()
    }

    override fun stop() {
        Log.d(TAG, "停止离线唤醒")
        active = false
        loopJob?.cancel()
        loopJob = null
        stopAudio()
        releaseWakeLock()
        try {
            stream?.release()
        } catch (_: Exception) {
        }
        stream = null
        try {
            spotter?.release()
        } catch (_: Exception) {
        }
        spotter = null
    }

    private fun ensureSpotter(): Boolean {
        if (spotter != null) return true
        return try {
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        decoder = "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                        joiner = "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    ),
                    tokens = "$MODEL_DIR/tokens.txt",
                    modelType = "zipformer2",
                    numThreads = 1,
                    provider = "cpu",
                ),
                keywordsFile = KEYWORDS_ASSET,
                keywordsScore = 2.0f,
                keywordsThreshold = 0.25f,
            )
            spotter = KeywordSpotter(assetManager = appContext.assets, config = config)
            Log.i(TAG, "KWS 模型加载完成")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "KWS 模型/库加载失败", e)
            spotter = null
            false
        }
    }

    private suspend fun detectLoop() {
        val ks = spotter ?: return
        if (!createAudioRecord()) {
            Log.e(TAG, "AudioRecord 创建失败")
            active = false
            return
        }
        val record = audioRecord ?: return
        val st = ks.createStream()
        stream = st
        val bufferSize = SAMPLE_RATE / 10 // 100ms
        val buffer = ShortArray(bufferSize)
        try {
            record.startRecording()
            Log.i(TAG, "离线唤醒采集开始")
            VoiceFlowLog.snapshot("wakeKWS.listening", "")
            while (active && scope.isActive) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { buffer[it] / 32768.0f }
                st.acceptWaveform(samples, SAMPLE_RATE)
                while (ks.isReady(st)) {
                    ks.decode(st)
                }
                val result = ks.getResult(st)
                if (result.keyword.isNotBlank()) {
                    ks.reset(st)
                    val now = System.currentTimeMillis()
                    if (now - lastWakeAtMs < DEBOUNCE_MS) continue
                    lastWakeAtMs = now
                    Log.i(TAG, "★ 离线命中唤醒词: ${result.keyword}")
                    VoiceFlowLog.snapshot("wakeKWS.hit", "keyword=${result.keyword}")
                    onWakeDetected()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "离线唤醒采集异常", e)
        } finally {
            try {
                st.release()
            } catch (_: Exception) {
            }
            if (stream === st) stream = null
        }
    }

    private fun createAudioRecord(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
        for (source in sources) {
            try {
                val record = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 2,
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = record
                    Log.i(TAG, "AudioRecord 就绪 source=$source")
                    return true
                }
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord source=$source 失败: ${e.message}")
            }
        }
        return false
    }

    private fun stopAudio() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZhiXun:OfflineWake").apply {
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }
}
