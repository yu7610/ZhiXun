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

enum class PhotoFollowUpMode {
    Capture,
    VoiceNote,
}

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

    private val _photoFollowUpMode = MutableStateFlow(PhotoFollowUpMode.Capture)
    val photoFollowUpMode: StateFlow<PhotoFollowUpMode> = _photoFollowUpMode.asStateFlow()

    private val _pendingPhoto = MutableStateFlow<DashcamPhoto?>(null)
    val pendingPhoto: StateFlow<DashcamPhoto?> = _pendingPhoto.asStateFlow()

    private val _isVoiceHolding = MutableStateFlow(false)
    val isVoiceHolding: StateFlow<Boolean> = _isVoiceHolding.asStateFlow()

    private val _voiceOverlayText = MutableStateFlow<String?>(null)
    val voiceOverlayText: StateFlow<String?> = _voiceOverlayText.asStateFlow()

    private val _isVoiceTranscribing = MutableStateFlow(false)
    val isVoiceTranscribing: StateFlow<Boolean> = _isVoiceTranscribing.asStateFlow()

    private val _showUploadConfirm = MutableStateFlow(false)
    val showUploadConfirm: StateFlow<Boolean> = _showUploadConfirm.asStateFlow()

    private val _pendingVoiceText = MutableStateFlow<String?>(null)
    val pendingVoiceText: StateFlow<String?> = _pendingVoiceText.asStateFlow()

    private val _asrUnavailable = MutableStateFlow(false)
    val asrUnavailable: StateFlow<Boolean> = _asrUnavailable.asStateFlow()

    private val _useLocalVoiceAsr = MutableStateFlow(false)
    val useLocalVoiceAsr: StateFlow<Boolean> = _useLocalVoiceAsr.asStateFlow()

    private val audioRecorder = DashcamAudioRecorder(app)
    private var voiceNoteFile: File? = null
    private var localVoiceRecognizer: DashcamLocalVoiceRecognizer? = null

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
    private var pendingPhotoAfterStop = false

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
        if (_photoFollowUpMode.value == PhotoFollowUpMode.VoiceNote) return
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

    fun uploadPhoto(
        photo: DashcamPhoto,
        voiceNote: String? = null,
        onSuccess: (() -> Unit)? = null,
    ) {
        if (_isPhotoUploading.value) return
        viewModelScope.launch {
            _isPhotoUploading.value = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val markText = voiceNote?.trim().orEmpty()
                    require(markText.isNotBlank()) { "请先补充语音说明" }
                    val terCode = ConfigManager(getApplication()).loadConfig().macAddress
                    DashcamMarkedImgUploader.upload(
                        context = app,
                        photoFile = photo.file,
                        markText = markText,
                        terCode = terCode,
                    ).getOrThrow()
                }
            }
            _isPhotoUploading.value = false
            result.onSuccess {
                onSuccess?.invoke()
                showMessage("提交成功")
            }.onFailure {
                showMessage(it.message ?: "上传失败")
            }
        }
    }

    fun takePhoto() {
        if (_photoFollowUpMode.value == PhotoFollowUpMode.VoiceNote) return
        if (_isRecording.value) {
            pendingPhotoAfterStop = true
            stopRecording(markUserStopped = false)
            return
        }
        capturePhotoInternal()
    }

    fun onVoiceNotePressStart() {
        if (_photoFollowUpMode.value != PhotoFollowUpMode.VoiceNote) return
        if (_isVoiceHolding.value || _isVoiceTranscribing.value) return
        _voiceOverlayText.value = null
        _pendingVoiceText.value = null
        _showUploadConfirm.value = false
        _asrUnavailable.value = false
        if (_useLocalVoiceAsr.value) {
            Log.i(DashcamAsrUploader.TAG, "按住说话 -> 本机识别")
            val recognizer = ensureLocalVoiceRecognizer()
            _isVoiceHolding.value = true
            if (!recognizer.startListening()) _isVoiceHolding.value = false
            return
        }
        DashcamAsrUploader.resetReachabilityCache()
        val file = DashcamRecordingStore.createVoiceNoteFile(app)
        Log.i(DashcamAsrUploader.TAG, "按住说话 -> 录音 path=${file.absolutePath}")
        audioRecorder.start(file).onSuccess {
            voiceNoteFile = file
            _isVoiceHolding.value = true
        }.onFailure {
            Log.e(DashcamAsrUploader.TAG, "录音启动失败", it)
            file.delete()
            showMessage(it.message ?: "录音失败")
        }
    }

    fun onVoiceNotePressEnd() {
        if (!_isVoiceHolding.value) return
        _isVoiceHolding.value = false
        if (_useLocalVoiceAsr.value) {
            Log.i(DashcamAsrUploader.TAG, "松开 -> 本机识别结束")
            _isVoiceTranscribing.value = true
            localVoiceRecognizer?.stopListening()
            return
        }
        Log.i(DashcamAsrUploader.TAG, "松开 -> 停止录音，调用云端 ASR")
        _isVoiceTranscribing.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val m4aFile = audioRecorder.stop().getOrThrow()
                    voiceNoteFile = null
                    refreshClips()
                    Log.i(
                        DashcamAsrUploader.TAG,
                        "录音已保存 path=${m4aFile.absolutePath} size=${m4aFile.length()}B，开始调用 ASR",
                    )
                    DashcamAsrUploader.transcribe(app, m4aFile).getOrThrow()
                }
            }
            _isVoiceTranscribing.value = false
            result.onSuccess { text ->
                Log.i(DashcamAsrUploader.TAG, "ASR 成功: $text")
                _voiceOverlayText.value = text
                _pendingVoiceText.value = text
                _showUploadConfirm.value = true
            }.onFailure { error ->
                voiceNoteFile = null
                if (DashcamAsrUploader.isConnectionError(error)) {
                    Log.w(DashcamAsrUploader.TAG, "云端 ASR 连接失败，降级本机识别", error)
                    _useLocalVoiceAsr.value = true
                    showMessage("云端识别暂不可用，请再按住麦克风说话")
                } else {
                    Log.e(DashcamAsrUploader.TAG, "ASR 失败", error)
                    showMessage(DashcamAsrUploader.friendlyMessage(error))
                }
            }
        }
    }

    fun confirmPendingUpload() {
        val photo = _pendingPhoto.value ?: return
        uploadPhoto(photo, _pendingVoiceText.value) {
            resetPhotoFollowUp(resumeRecording = false)
            userStoppedRecording = true
        }
    }

    fun cancelPendingUpload() {
        resetPhotoFollowUp(resumeRecording = false)
        userStoppedRecording = true
        showMessage("已取消上传")
    }

    private fun ensureLocalVoiceRecognizer(): DashcamLocalVoiceRecognizer {
        return localVoiceRecognizer ?: DashcamLocalVoiceRecognizer(
            context = app,
            onPartial = { partial -> _voiceOverlayText.value = partial },
            onFinal = { text ->
                _isVoiceTranscribing.value = false
                _voiceOverlayText.value = text
                _pendingVoiceText.value = text
                _showUploadConfirm.value = true
            },
            onError = { message ->
                _isVoiceTranscribing.value = false
                showMessage(message)
            },
        ).also { localVoiceRecognizer = it }
    }

    private fun resetPhotoFollowUp(resumeRecording: Boolean) {
        localVoiceRecognizer?.cancel()
        if (_isVoiceHolding.value || audioRecorder.isRecording) {
            audioRecorder.release()
            voiceNoteFile?.delete()
            voiceNoteFile = null
        }
        _photoFollowUpMode.value = PhotoFollowUpMode.Capture
        _pendingPhoto.value = null
        _pendingVoiceText.value = null
        _voiceOverlayText.value = null
        _isVoiceHolding.value = false
        _isVoiceTranscribing.value = false
        _showUploadConfirm.value = false
        _asrUnavailable.value = false
        _useLocalVoiceAsr.value = false
        if (resumeRecording) {
            userStoppedRecording = false
            tryAutoStartRecording()
        }
    }

    private fun capturePhotoInternal() {
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
                val photo = _photos.value.firstOrNull { it.file.absolutePath == saved.absolutePath }
                    ?: DashcamPhoto(
                        file = saved,
                        displayName = saved.name,
                        sizeBytes = saved.length(),
                        lastModifiedMs = saved.lastModified(),
                    )
                _pendingPhoto.value = photo
                _photoFollowUpMode.value = PhotoFollowUpMode.VoiceNote
                _useLocalVoiceAsr.value = false
                DashcamAsrUploader.resetReachabilityCache()
                Log.i(DashcamAsrUploader.TAG, "拍照完成，进入语音说明流程 ${saved.name}")
                showMessage("照片已保存，请补充语音说明")
            }.onFailure {
                file.delete()
                showMessage(it.message ?: "拍照失败")
                userStoppedRecording = false
                tryAutoStartRecording()
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
        Log.i(DashcamAudioRecorder.TAG, "独立录音 -> path=${file.absolutePath}")
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
        result.onSuccess { m4aFile ->
            refreshClips()
            Log.i(DashcamAudioRecorder.TAG, "独立录音已保存 path=${m4aFile.absolutePath}")
            showMessage("录音已保存：${m4aFile.name}")
        }.onFailure {
            showMessage(it.message ?: "录音保存失败")
        }
    }

    fun toggleRecording() {
        if (_isAudioRecording.value) {
            showMessage("请先停止录音")
            return
        }
        if (_photoFollowUpMode.value == PhotoFollowUpMode.VoiceNote) {
            resetPhotoFollowUp(resumeRecording = false)
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

    private fun stopRecording(markUserStopped: Boolean = true) {
        val controller = recordingController ?: run {
            if (pendingPhotoAfterStop) {
                pendingPhotoAfterStop = false
                capturePhotoInternal()
            }
            return
        }
        if (markUserStopped) userStoppedRecording = true
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
                    if (pendingPhotoAfterStop) {
                        pendingPhotoAfterStop = false
                        capturePhotoInternal()
                    }
                }
            }.onFailure {
                refreshClips()
                showMessage("录像停止失败: ${it.message ?: "未知错误"}")
                if (pendingPhotoAfterStop) {
                    pendingPhotoAfterStop = false
                    capturePhotoInternal()
                }
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
        localVoiceRecognizer?.destroy()
        localVoiceRecognizer = null
        if (_isVoiceHolding.value || audioRecorder.isRecording) {
            audioRecorder.release()
            voiceNoteFile?.delete()
            voiceNoteFile = null
        }
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
