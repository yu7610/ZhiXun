package com.powerchina.zhixun.ui

import android.app.Activity
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.powerchina.zhixun.R
import com.powerchina.zhixun.data.Message
import com.powerchina.zhixun.data.MessageRole
import com.powerchina.zhixun.viewmodel.ConversationState
import com.powerchina.zhixun.viewmodel.ConversationViewModel
import com.powerchina.zhixun.xiaozhi.XiaozhiAppEvents
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService
import java.io.File
import kotlinx.coroutines.launch

private val AuraBgTop = Color(0xFFF8F9FF)
private val AuraBgBottom = Color(0xFFFBF5FF)
private val AuraPrimary = Color(0xFF674BB5)
private val AuraSecondary = Color(0xFF64A8FE)
private val AuraTertiary = Color(0xFFF170B4)
private val AuraText = Color(0xFF121C2A)
private val AuraSubText = Color(0xFF6E6A78)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ConversationScreen(
    onNavigateToSettings: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: ConversationViewModel = viewModel(),
) {
    val appContext = LocalContext.current
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    val permissionsState = rememberMultiplePermissionsState(
        permissions = buildList {
            add(android.Manifest.permission.CAMERA)
            add(android.Manifest.permission.RECORD_AUDIO)
            add(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )

    val errorMessage by viewModel.errorMessage.collectAsState()
    val showActivationDialog by viewModel.showActivationDialog.collectAsState()
    val activationCode by viewModel.activationCode.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val conversationState by viewModel.state.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val isStandbyReady by viewModel.isStandbyReady.collectAsState()
    val isAwaitingReconnect by viewModel.isAwaitingReconnect.collectAsState()
    val isSessionConnecting by viewModel.isSessionConnecting.collectAsState()
    val isWakeGreetingPlaying by viewModel.isWakeGreetingPlaying.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setConversationUiActive(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setConversationUiActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setConversationUiActive(false)
        }
    }

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            XiaozhiWakeForegroundService.ensureStarted(appContext)
            viewModel.onConversationScreenReady()
        } else {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    MainConversationContent(
        messages = messages,
        conversationState = conversationState,
        isConnected = isConnected,
        isStandbyReady = isStandbyReady,
        isAwaitingReconnect = isAwaitingReconnect,
        isSessionConnecting = isSessionConnecting,
        isWakeGreetingPlaying = isWakeGreetingPlaying,
        onShowSettings = onNavigateToSettings,
        onBack = onBack,
        showActivationDialog = showActivationDialog,
        activationCode = activationCode,
        errorMessage = errorMessage,
        viewModel = viewModel,
    )
}

@Composable
private fun MainConversationContent(
    messages: List<Message>,
    conversationState: ConversationState,
    isConnected: Boolean,
    isStandbyReady: Boolean,
    isAwaitingReconnect: Boolean,
    isSessionConnecting: Boolean,
    isWakeGreetingPlaying: Boolean,
    onShowSettings: () -> Unit,
    onBack: (() -> Unit)?,
    showActivationDialog: Boolean,
    activationCode: String?,
    errorMessage: String?,
    viewModel: ConversationViewModel,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val featureComingText = stringResource(R.string.dashcam_feature_coming)
    val onFeatureComing: () -> Unit = {
        scope.launch { snackbarHostState.showSnackbar(featureComingText) }
    }

    LaunchedEffect(errorMessage) {
        val message = errorMessage?.trim().orEmpty()
        if (message.isNotBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentWindowInsets = WindowInsets.ime,
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopBar(
                conversationState = conversationState,
                isConnected = isConnected,
                isStandbyReady = isStandbyReady,
                isAwaitingReconnect = isAwaitingReconnect,
                isSessionConnecting = isSessionConnecting,
                isWakeGreetingPlaying = isWakeGreetingPlaying,
                onShowSettings = onShowSettings,
                onLocationClick = onFeatureComing,
                onScreenRecordClick = onFeatureComing,
                onBack = onBack,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(AuraBgTop, AuraBgBottom),
                    ),
                )
                .padding(padding),
        ) {
            AuroraDecor(
                modifier = Modifier
                    .matchParentSize()
                    .zIndex(0f),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .padding(horizontal = 24.dp),
            ) {
                MessageList(
                    messages = messages,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
    }

    if (showActivationDialog && activationCode != null) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    text = "设备激活",
                    color = AuraText,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(text = "激活码：", color = AuraSubText)
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF3EEFF)
                    ) {
                        Text(
                            text = activationCode,
                            modifier = Modifier.padding(16.dp),
                            color = AuraPrimary,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.onActivationConfirmed() },
                    colors = ButtonDefaults.buttonColors(containerColor = AuraPrimary)
                ) {
                    Text("我已激活", color = Color.White)
                }
            },
            containerColor = Color(0xFFFDFBFF)
        )
    }
}

@Composable
private fun TopBar(
    conversationState: ConversationState,
    isConnected: Boolean,
    isStandbyReady: Boolean,
    isAwaitingReconnect: Boolean,
    isSessionConnecting: Boolean,
    isWakeGreetingPlaying: Boolean,
    onShowSettings: () -> Unit,
    onLocationClick: () -> Unit,
    onScreenRecordClick: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val statusText = statusLabel(
        state = conversationState,
        isConnected = isConnected,
        isStandbyReady = isStandbyReady,
        isAwaitingReconnect = isAwaitingReconnect,
        isSessionConnecting = isSessionConnecting,
        isWakeGreetingPlaying = isWakeGreetingPlaying,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "返回",
                        tint = AuraPrimary,
                    )
                }
            }
            IconButton(onClick = onShowSettings) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "设置",
                    tint = AuraPrimary,
                )
            }
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onLocationClick) {
                Icon(
                    imageVector = Icons.Outlined.LocationOn,
                    contentDescription = stringResource(R.string.feature_location),
                    tint = AuraPrimary,
                )
            }
            IconButton(onClick = onScreenRecordClick) {
                Icon(
                    imageVector = Icons.Outlined.ScreenShare,
                    contentDescription = stringResource(R.string.feature_screen_record),
                    tint = AuraPrimary,
                )
            }
        }

        if (statusText.isNotEmpty()) {
            Text(
                text = statusText,
                color = AuraPrimary,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun statusLabel(
    state: ConversationState,
    isConnected: Boolean,
    isStandbyReady: Boolean,
    isAwaitingReconnect: Boolean,
    isSessionConnecting: Boolean,
    isWakeGreetingPlaying: Boolean,
): String {
    if (isWakeGreetingPlaying && state == ConversationState.LISTENING) {
        return stringResource(R.string.status_speaking)
    }
    return when (state) {
        ConversationState.LISTENING -> stringResource(R.string.status_listening)
        ConversationState.PROCESSING -> stringResource(R.string.status_processing)
        ConversationState.SPEAKING -> stringResource(R.string.status_speaking)
        ConversationState.CONNECTING -> stringResource(R.string.status_connecting)
        ConversationState.IDLE -> when {
            isAwaitingReconnect || isSessionConnecting -> stringResource(R.string.status_connecting)
            !isConnected -> stringResource(R.string.status_disconnected)
            isStandbyReady -> stringResource(R.string.status_standby)
            else -> stringResource(R.string.status_connecting)
        }
    }
}

@Composable
private fun AuroraDecor(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(420.dp)
                .align(Alignment.TopCenter)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            AuraPrimary.copy(alpha = 0.16f),
                            AuraSecondary.copy(alpha = 0.10f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        Box(
            modifier = Modifier
                .size(540.dp)
                .align(Alignment.Center)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            AuraPrimary.copy(alpha = 0.14f),
                            AuraTertiary.copy(alpha = 0.09f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageList(
    messages: List<Message>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // 自动滚动到底部：当消息数量、内容或图片变化时触发
    val lastMessageContent = messages.lastOrNull()?.let { "${it.content}:${it.imagePath}" }
    LaunchedEffect(messages.size, lastMessageContent) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    CompositionLocalProvider(
        LocalOverscrollConfiguration provides null
    ) {
        LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                ChatBubble(message = message)
            }
        }
    }
}

@Composable
private fun ChatBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val backgroundColor = if (isUser) AuraPrimary else Color.White
    val contentColor = if (isUser) Color.White else AuraText
    val textShape = if (isUser) {
        RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp)
    } else {
        RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)
    }
    val imageShape = if (isUser) {
        RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
    } else {
        RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    }
    val imageBitmap = remember(message.imagePath) {
        message.imagePath?.let { path ->
            if (File(path).exists()) {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            } else {
                null
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        imageBitmap?.let { bitmap ->
            Surface(
                color = Color.White,
                shape = imageShape,
                shadowElevation = 2.dp,
                modifier = Modifier.widthIn(max = 220.dp),
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "照片",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(imageShape),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        if (message.content.isNotBlank()) {
            Surface(
                color = backgroundColor,
                shape = textShape,
                shadowElevation = 2.dp,
                modifier = Modifier.widthIn(max = 280.dp),
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = contentColor,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            }
        }
    }
}
