package com.powerchina.zhixun.dashcam

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
        if (session != null) {
            tryAutoStartRecording()
        }
    }

    fun takePhoto() {
        val session = cameraSession
        if (session == null) {
            _message.value = "相机未就绪"
            return
        }
        if (_isRecording.value) {
            _message.value = "录像中无法拍照"
            return
        }
        val file = DashcamRecordingStore.createPhotoFile(app)
        session.takePicture(file) { result ->
            result.onSuccess {
                _message.value = "照片已保存：${it.name}"
            }.onFailure {
                _message.value = it.message ?: "拍照失败"
            }
        }
    }

    fun stopRecordingIfActive() {
        if (_isRecording.value) {
            userStoppedRecording = true
            stopRecording()
        }
    }

    /** 进入应用后相机就绪即自动开始录像，并保持录制 */
    fun tryAutoStartRecording() {
        val controller = recordingController ?: return
        if (userStoppedRecording || _isRecording.value) return
        if (!hasAutoStarted) hasAutoStarted = true
        startRecording(controller)
    }

    /** 相机重新绑定时自动续录 */
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

    /** 物理录像键：短按切换录像；长按在未录制时开始录制，录制中则停止 */
    fun onVideoKey(action: DashcamVideoKeyEvents.KeyAction) {
        Log.i(
            VideoKeyReceiver.TAG,
            "onVideoKey: action=$action, isRecording=${_isRecording.value}, " +
                "cameraReady=${recordingController != null}",
        )
        when (action) {
            DashcamVideoKeyEvents.KeyAction.PRESS -> {
                Log.d(VideoKeyReceiver.TAG, "短按 -> toggleRecording")
                toggleRecording()
            }
            DashcamVideoKeyEvents.KeyAction.LONG_PRESS -> {
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
        if (_isRecording.value) {
            recordingController?.stopRecording { _ -> refreshClips() }
        }
        stopTimer()
        super.onCleared()
    }
}
