package com.powerchina.zhixun.dashcam

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.powerchina.zhixun.xiaozhi.XiaozhiAppEvents
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DashcamViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext

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
    private var hasAutoStarted = false
    private var userStoppedRecording = false

    init {
        refreshClips()
    }

    fun bindCameraSession(session: DashcamCameraSession?) {
        cameraSession = session
        SharedCameraCapture.dashcamSession = session
        if (session != null) {
            tryAutoStartRecording()
        }
    }

    fun takePhoto() {
        capturePhoto { result ->
            result.onSuccess {
                _message.value = "照片已保存：${it.name}"
            }.onFailure {
                _message.value = it.message ?: "拍照失败"
            }
        }
    }

    /** 拍照并在聊天界面展示、上传小智（不跳转页面） */
    fun takePhotoAndShareToChat() {
        capturePhoto { result ->
            result.onSuccess { file ->
                _message.value = "照片已保存：${file.name}"
                XiaozhiAppEvents.sharePhotoToChat(file)
            }.onFailure { err ->
                _message.value = err.message ?: "拍照失败"
            }
        }
    }

    private fun capturePhoto(onResult: (Result<File>) -> Unit) {
        val session = cameraSession
        if (session == null) {
            onResult(Result.failure(IllegalStateException("相机未就绪")))
            return
        }
        val file = DashcamRecordingStore.createPhotoFile(app)
        session.takePicture(file, onResult)
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
            },
            onError = { err ->
                _isRecording.value = false
                stopTimer()
                _message.value = err
            },
        )
    }

    private fun stopRecording() {
        val controller = recordingController ?: return
        controller.stopRecording { result ->
            _isRecording.value = false
            stopTimer()
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

    override fun onCleared() {
        SharedCameraCapture.dashcamSession = null
        if (_isRecording.value) {
            recordingController?.stopRecording { _ -> refreshClips() }
        }
        stopTimer()
        super.onCleared()
    }
}
