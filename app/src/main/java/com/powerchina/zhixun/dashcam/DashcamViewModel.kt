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
        private const val TAG = "DashcamVM"
        private const val FRAME_UPLOAD_INTERVAL_MS = 5_000L
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    private val _clips = MutableStateFlow<List<DashcamClip>>(emptyList())
    val clips: StateFlow<List<DashcamClip>> = _clips.asStateFlow()

    private val _photos = MutableStateFlow<List<DashcamPhoto>>(emptyList())
    val photos: StateFlow<List<DashcamPhoto>> = _photos.asStateFlow()

    private val _isPhotoUploading = MutableStateFlow(false)
    val isPhotoUploading: StateFlow<Boolean> = _isPhotoUploading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _isCompressing = MutableStateFlow(false)
    val isCompressing: StateFlow<Boolean> = _isCompressing.asStateFlow()

    private val _isAudioRecording = MutableStateFlow(false)
    val isAudioRecording: StateFlow<Boolean> = _isAudioRecording.asStateFlow()

    private val audioRecorder = DashcamAudioRecorder(app)

    private var cameraSession: DashcamCameraSession? = null
    private val recordingController: DashcamRecordingController?
        get() = cameraSession?.recordingController
    private var timerJob: Job? = null
    private var frameUploadJob: Job? = null
    private var compressJob: Job? = null
    private val frameCaptureInProgress = AtomicBoolean(false)
    private val photoCaptureInProgress = AtomicBoolean(false)
    private val recordingStartInProgress = AtomicBoolean(false)
    private var hasAutoStarted = false
    private var userStoppedRecording = false

    init {
        refreshClips()
        refreshPhotos()
        DashcamForeground.onBackground = {
            releaseCameraForBackground()
        }
    }

    private fun releaseCameraForBackground() {
        if (_isRecording.value) {
            Log.i(TAG, "执法仪进入后台，停止录像以释放相机")
            stopRecordingIfActive()
        }
        bindCameraSession(null)
    }

    fun bindCameraSession(session: DashcamCameraSession?) {
        cameraSession = session
        SharedCameraCapture.dashcamSession = session
        if (session != null) {
            McpCameraHolder.pauseForDashcam()
            if (_isRecording.value && !session.recordingController.isRecording) {
                Log.w(TAG, "相机重绑导致录像中断，重置状态")
                _isRecording.value = false
                recordingStartInProgress.set(false)
                stopTimer()
                stopFrameUploadLoop()
            }
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
        if (userStoppedRecording || _isAudioRecording.value) return
        if (_isRecording.value || controller.isRecording || recordingStartInProgress.get()) return
        if (!hasAutoStarted) hasAutoStarted = true
        startRecording(controller)
    }

    fun refreshClips() {
        _clips.value = DashcamRecordingStore.listClips(app)
    }

    fun refreshPhotos() {
        _photos.value = DashcamRecordingStore.listPhotos(app)
    }

    fun hasPhotosAfterRefresh(): Boolean {
        refreshPhotos()
        return _photos.value.isNotEmpty()
    }

    fun uploadPhoto(photo: DashcamPhoto) {
        if (_isPhotoUploading.value) return
        RecordingFrameTts.warmUp(getApplication())
        viewModelScope.launch {
            _isPhotoUploading.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var jpegBytes = XiaozhiPhotoUploader.compressJpegForUpload(photo.file)
                    if (jpegBytes.size > 180_000) {
                        jpegBytes = XiaozhiPhotoUploader.compressJpegForUpload(
                            photo.file,
                            maxWidth = 480,
                            quality = 65,
                        )
                    }
                    val deviceId = ConfigManager(getApplication()).loadConfig().macAddress
                    RecordingFrameUploader.uploadFrame(
                        context = app,
                        deviceId = deviceId,
                        jpegBytes = jpegBytes,
                        filename = photo.file.name,
                    ).getOrThrow()
                }
            }
            _isPhotoUploading.value = false
            result.onSuccess { raw ->
                val speakText = RecordingFrameUploader.parseSpeakText(raw)
                if (speakText != null) {
                    RecordingFrameTts.speak(getApplication(), speakText)
                    showMessage("上传成功：$speakText")
                } else {
                    showMessage("上传成功")
                }
            }.onFailure {
                showMessage(it.message ?: "上传失败")
            }
        }
    }

    fun takePhoto() {
        val session = cameraSession
        if (session == null) {
            showMessage("相机未就绪")
            return
        }
        if (!photoCaptureInProgress.compareAndSet(false, true)) {
            showMessage("拍照进行中，请稍候")
            return
        }
        val file = DashcamRecordingStore.createPhotoFile(app)
        session.takePicture(file) { result ->
            photoCaptureInProgress.set(false)
            result.onSuccess { saved ->
                refreshPhotos()
                showMessage("照片已保存：${saved.name}")
            }.onFailure {
                file.delete()
                showMessage(it.message ?: "拍照失败")
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun showMessage(text: String) {
        Log.i(TAG, "toast: $text")
        _message.value = text
    }

    fun toggleAudioRecording() {
        if (_isRecording.value) {
            showMessage("请先停止录像")
            return
        }
        if (_isAudioRecording.value) {
            stopAudioRecording()
            return
        }
        val file = DashcamRecordingStore.createAudioOutputFile(app)
        val started = audioRecorder.start(file)
        started.onSuccess {
            userStoppedRecording = true
            _isAudioRecording.value = true
        }.onFailure {
            file.delete()
            showMessage(it.message ?: "录音失败")
        }
    }

    private fun stopAudioRecording() {
        val result = audioRecorder.stop()
        _isAudioRecording.value = false
        result.onSuccess { file ->
            refreshClips()
            showMessage("录音已保存：${file.name}")
        }.onFailure {
            showMessage(it.message ?: "录音保存失败")
        }
    }

    fun toggleRecording() {
        if (_isAudioRecording.value) {
            showMessage("请先停止录音")
            return
        }
        val controller = recordingController
        if (controller == null) {
            showMessage("相机未就绪")
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
        if (_isAudioRecording.value) {
            showMessage("请先停止录音")
            return
        }
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
                showMessage("相机未就绪")
            } else {
                startRecording(controller)
            }
        }
    }

    private fun startRecording(controller: DashcamRecordingController) {
        if (_isAudioRecording.value) {
            showMessage("请先停止录音")
            return
        }
        if (controller.isRecording || _isRecording.value) return
        if (!recordingStartInProgress.compareAndSet(false, true)) return
        val request = runCatching {
            DashcamRecordingStore.createVideoOutputRequest(app)
        }.getOrElse { err ->
            recordingStartInProgress.set(false)
            Log.e(TAG, "创建录像输出失败", err)
            showMessage(err.message ?: "无法创建录像文件")
            return
        }
        val started = controller.startRecording(
            request = request,
            onStarted = {
                recordingStartInProgress.set(false)
                _isRecording.value = true
                _elapsedSeconds.value = 0
                startTimer()
                startFrameUploadLoop()
            },
            onError = { err ->
                recordingStartInProgress.set(false)
                _isRecording.value = false
                stopTimer()
                stopFrameUploadLoop()
                showMessage("录像失败: $err")
            },
        )
        if (!started) {
            recordingStartInProgress.set(false)
            showMessage("录像未启动：${request.displayName}")
        }
    }

    private fun stopRecording() {
        val controller = recordingController ?: return
        controller.stopRecording { result ->
            _isRecording.value = false
            stopTimer()
            stopFrameUploadLoop()
            result.onSuccess { output ->
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        DashcamRecordingStore.publishVideoOutput(app, output)
                    }
                    refreshClips()
                    scheduleCompressRecording(output)
                }
            }.onFailure {
                refreshClips()
                showMessage("录像停止失败: ${it.message ?: "未知错误"}")
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

    private fun scheduleCompressRecording(output: DashcamVideoOutput) {
        compressJob?.cancel()
        compressJob = viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) {
                DashcamRecordingStore.waitForVideoReady(app, output)
            }
            if (ready.isFailure) {
                Log.w(TAG, "录像未落盘 ${output.displayName}: ${ready.exceptionOrNull()?.message}")
                return@launch
            }
            val file = DashcamRecordingStore.resolveVideoFile(app, output)
            if (file == null || !file.exists() || file.length() == 0L) {
                refreshClips()
                val report = DashcamRecordingStore.buildVideoPublishReport(
                    app,
                    output,
                    readyBytes = ready.getOrNull(),
                )
                Log.w(TAG, "路径不可解析 ${output.displayName} | $report")
                return@launch
            }
            _isCompressing.value = true
            val result = DashcamVideoCompressor.compressToSdcard0(app, file, output.uri)
            _isCompressing.value = false
            refreshClips()
            result.onSuccess { compressed ->
                exportRecordingToGallery(compressed.sdcard0File)
                showMessage("保存成功")
            }.onFailure { err ->
                Log.w(DashcamVideoCompressor.TAG, "压缩失败，保留原片: ${file.name}", err)
                exportRecordingToGallery(file)
            }
        }
    }

    private suspend fun exportRecordingToGallery(file: File): File? =
        withContext(Dispatchers.IO) {
            DashcamGalleryExporter.exportToGallery(app, file).getOrNull()
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
        DashcamForeground.onBackground = null
        DashcamForeground.setActive(false)
        SharedCameraCapture.dashcamSession = null
        stopFrameUploadLoop()
        compressJob?.cancel()
        RecordingFrameTts.shutdown()
        if (_isAudioRecording.value) {
            audioRecorder.release()
            _isAudioRecording.value = false
        }
        if (_isRecording.value) {
            recordingController?.stopRecording { _ -> refreshClips() }
        }
        stopTimer()
        super.onCleared()
    }
}
