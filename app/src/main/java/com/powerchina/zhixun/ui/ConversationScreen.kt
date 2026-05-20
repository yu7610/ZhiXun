package com.powerchina.zhixun.ui

import android.app.Activity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.toArgb
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
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
    )

    val errorMessage by viewModel.errorMessage.collectAsState()
    val showActivationDialog by viewModel.showActivationDialog.collectAsState()
    val activationCode by viewModel.activationCode.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val conversationState by viewModel.state.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()

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
            viewModel.initializeAudio()
            viewModel.connect()
        } else {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    MainConversationContent(
        messages = messages,
        conversationState = conversationState,
        isConnected = isConnected,
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

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    color = Color(0xFFBA1A1A),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(2f)
                        .padding(bottom = 24.dp, start = 24.dp, end = 24.dp),
                    textAlign = TextAlign.Center,
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
    onShowSettings: () -> Unit,
    onLocationClick: () -> Unit,
    onScreenRecordClick: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val statusText = statusLabel(conversationState, isConnected)

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
private fun statusLabel(state: ConversationState, isConnected: Boolean): String {
    if (!isConnected) {
        return when (state) {
            ConversationState.CONNECTING -> stringResource(R.string.status_connecting)
            else -> stringResource(R.string.status_disconnected)
        }
    }
    return when (state) {
        ConversationState.CONNECTING -> stringResource(R.string.status_connecting)
        ConversationState.LISTENING -> stringResource(R.string.status_listening)
        ConversationState.PROCESSING -> stringResource(R.string.status_processing)
        ConversationState.SPEAKING -> stringResource(R.string.status_speaking)
        ConversationState.IDLE -> stringResource(R.string.status_standby)
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

    // 自动滚动到底部：当消息数量变化或最后一条消息内容变化时触发
    val lastMessageContent = messages.lastOrNull()?.content
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
    val shape = if (isUser) {
        RoundedCornerShape(20.dp, 4.dp, 20.dp, 20.dp)
    } else {
        RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = backgroundColor,
            shape = shape,
            shadowElevation = 2.dp,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                color = contentColor,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }
    }
}
