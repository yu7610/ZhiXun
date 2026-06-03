package com.powerchina.zhixun.dashcam

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.powerchina.zhixun.data.ConfigManager
import com.powerchina.zhixun.xiaozhi.XiaozhiPhotoUploader
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class DashcamViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext

    private companion object {
        private const val FRAME_UPLOAD_INTERVAL_MS = 5_000L
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    private val _clips = MutableStateFlow<List<DashcamClip>>(emptyList())
    val clips: StateFlow<List<DashcamClip>> = _clips.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var cameraSession: DashcamCameraSession? = null
    private val recordingController: DashcamRecordingController?
        get() = cameraSession?.recordingController
    private var timerJob: Job? = null
    private var frameUploadJob: Job? = null
    private val frameCaptureInProgress = AtomicBoolean(false)
    private var hasAutoStarted = false
    private var userStoppedRecording = false

    init {
        refreshClips()
    }

    fun bindCameraSession(session: DashcamCameraSession?) {
        cameraSession = session
        SharedCameraCapture.dashcamSession = session
        if (session != null) {
            McpCameraHolder.pauseForDashcam()
            tryAutoStartRecording()
        }
    }

    fun stopRecordingIfActive() {
        if (_isRecording.value) {
            userStoppedRecording = true
            stopRecording()
        }
    }

    fun tryAutoStartRecording() {
        val controller = recordingController ?: return
        if (userStoppedRecording || _isRecording.value) return
        if (!hasAutoStarted) hasAutoStarted = true
        startRecording(controller)
    }

    fun ensureRecordingContinues() {
        if (userStoppedRecording || _isRecording.value) return
        tryAutoStartRecording()
    }

    fun refreshClips() {
        _clips.value = DashcamRecordingStore.listClips(app)
    }

    fun clearMessage() {
        _message.value = null
    }

    fun toggleRecording() {
        val controller = recordingController
        if (controller == null) {
            _message.value = "相机未就绪"
            return
        }
        if (_isRecording.value) {
            userStoppedRecording = true
            stopRecording()
        } else {
            userStoppedRecording = false
            startRecording(controller)
        }
    }

    /** 物理录像键：keyCode=136 切换录像 */
    fun onVideoKey(action: DashcamVideoKeyEvents.KeyAction) {
        if (action != DashcamVideoKeyEvents.KeyAction.RECORD) return
        Log.i(
            VideoKeyReceiver.TAG,
            "onVideoKey: action=$action, isRecording=${_isRecording.value}, " +
                "cameraReady=${recordingController != null}",
        )
        if (_isRecording.value) {
            Log.d(VideoKeyReceiver.TAG, "长按 -> 停止录像")
            userStoppedRecording = true
            stopRecording()
        } else {
            Log.d(VideoKeyReceiver.TAG, "长按 -> 开始录像")
            userStoppedRecording = false
            val controller = recordingController
            if (controller == null) {
                Log.w(VideoKeyReceiver.TAG, "长按失败: 相机未就绪")
                _message.value = "相机未就绪"
            } else {
                startRecording(controller)
            }
        }
    }

    private fun startRecording(controller: DashcamRecordingController) {
        val file = DashcamRecordingStore.createOutputFile(app)
        controller.startRecording(
            outputFile = file,
            onStarted = {
                _isRecording.value = true
                _elapsedSeconds.value = 0
                startTimer()
                startFrameUploadLoop()
            },
            onError = { err ->
                _isRecording.value = false
                stopTimer()
                stopFrameUploadLoop()
                _message.value = err
            },
        )
    }

    private fun stopRecording() {
        val controller = recordingController ?: return
        controller.stopRecording { result ->
            _isRecording.value = false
            stopTimer()
            stopFrameUploadLoop()
            result.onSuccess { file ->
                refreshClips()
                _message.value = "已保存：${file.name}"
            }.onFailure {
                refreshClips()
                _message.value = it.message ?: "录像已停止"
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive && _isRecording.value) {
                delay(1000)
                _elapsedSeconds.value += 1
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun startFrameUploadLoop() {
        stopFrameUploadLoop()
        RecordingFrameTts.resetDedupe()
        RecordingFrameTts.warmUp(getApplication())
        frameUploadJob = viewModelScope.launch {
            Log.i(RecordingFrameUploader.TAG, "录屏帧上传已启动，间隔 ${FRAME_UPLOAD_INTERVAL_MS}ms")
            while (isActive && _isRecording.value) {
                delay(FRAME_UPLOAD_INTERVAL_MS)
                if (!_isRecording.value) break
                captureAndUploadFrame()
            }
        }
    }

    private fun stopFrameUploadLoop() {
        frameUploadJob?.cancel()
        frameUploadJob = null
    }

    private suspend fun captureAndUploadFrame() {
        val session = cameraSession
        if (session == null) {
            Log.w(RecordingFrameUploader.TAG, "相机未就绪，跳过本帧")
            return
        }
        if (!frameCaptureInProgress.compareAndSet(false, true)) {
            Log.d(RecordingFrameUploader.TAG, "上一帧尚未完成，跳过")
            return
        }
        val frameFile = File(app.cacheDir, "rec_frame_${System.currentTimeMillis()}.jpg")
        try {
            val captured = suspendCaptureFrame(session, frameFile)
            if (captured == null) {
                Log.w(RecordingFrameUploader.TAG, "抓帧失败")
                frameFile.delete()
                return
            }
            withContext(Dispatchers.IO) {
                val jpegBytes = XiaozhiPhotoUploader.compressJpegForUpload(captured)
                val deviceId = ConfigManager(getApplication()).loadConfig().macAddress
                val upload = RecordingFrameUploader.uploadFrame(
                    context = app,
                    deviceId = deviceId,
                    jpegBytes = jpegBytes,
                    filename = captured.name,
                )
                val speakText = upload.getOrNull()?.let { RecordingFrameUploader.parseSpeakText(it) }
                if (speakText != null) {
                    withContext(Dispatchers.Main) {
                        RecordingFrameTts.speak(getApplication(), speakText)
                    }
                }
            }
        } finally {
            frameFile.delete()
            frameCaptureInProgress.set(false)
        }
    }

    private suspend fun suspendCaptureFrame(
        session: DashcamCameraSession,
        outputFile: File,
    ): File? = suspendCancellableCoroutine { cont ->
        session.takePicture(outputFile) { result ->
            if (cont.isActive) {
                cont.resume(result.getOrNull())
            }
        }
    }

    override fun onCleared() {
        SharedCameraCapture.dashcamSession = null
        stopFrameUploadLoop()
        RecordingFrameTts.shutdown()
        if (_isRecording.value) {
            recordingController?.stopRecording { _ -> refreshClips() }
        }
        stopTimer()
        super.onCleared()
    }
}
