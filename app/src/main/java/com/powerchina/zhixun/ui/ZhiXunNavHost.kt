package com.powerchina.zhixun.ui

import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.powerchina.zhixun.R
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.powerchina.zhixun.data.ConfigManager
import com.powerchina.zhixun.ui.theme.YTheme
import com.powerchina.zhixun.viewmodel.ConversationViewModel
import com.powerchina.zhixun.xiaozhi.XiaozhiAppEvents
import com.powerchina.zhixun.xiaozhi.XiaozhiSessionManager

private const val TAG = "ZhiXunNavHost"
private const val EXIT_CONFIRM_MS = 3_000L

object AppRoutes {
    const val Conversation = "conversation"
    const val Settings = "settings"
}

@Composable
fun ZhiXunNavHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val configManager = remember { ConfigManager(context) }
    val sessionManager = remember {
        XiaozhiSessionManager.getInstance(context.applicationContext as android.app.Application)
    }

    YTheme(darkTheme = true) {
        val navController = rememberNavController()
        val conversationViewModel: ConversationViewModel = viewModel()
        val startDestination = remember {
            val cfg = configManager.loadConfig()
            if (cfg.otaUrl.isBlank() && cfg.websocketUrl.isBlank()) {
                AppRoutes.Settings
            } else {
                AppRoutes.Conversation
            }
        }

        LaunchedEffect(Unit) {
            XiaozhiAppEvents.photoKeyRequests.collect {
                Log.i(TAG, "收到物理拍照键请求")
                val cfg = configManager.loadConfig()
                if (cfg.otaUrl.isBlank() && cfg.websocketUrl.isBlank()) {
                    Log.w(TAG, "未配置，无法发送拍照指令")
                    return@collect
                }
                conversationViewModel.updateConfig(cfg)
                sessionManager.ensureConnected()
                if (navController.currentDestination?.route != AppRoutes.Conversation) {
                    navController.navigate(AppRoutes.Conversation) {
                        popUpTo(AppRoutes.Conversation) { inclusive = true }
                        launchSingleTop = true
                    }
                }
                conversationViewModel.onPhotoKeyPressed()
            }
        }

        LaunchedEffect(Unit) {
            XiaozhiAppEvents.requests.collect { req ->
                Log.i(
                    TAG,
                    "收到打开对话请求 wake=${req.fromVoiceWake} autoConnect=${req.autoConnect} " +
                        "voiceKey=${req.startVoiceOnConnect}",
                )
                val cfg = configManager.loadConfig()
                if (cfg.otaUrl.isBlank() && cfg.websocketUrl.isBlank()) {
                    Log.w(TAG, "未配置，跳转设置页")
                    navController.navigate(AppRoutes.Settings)
                } else {
                    when {
                        req.startVoiceOnConnect -> {
                            sessionManager.ensureConnected()
                            conversationViewModel.onRecordKeyPressed()
                        }
                        req.fromVoiceWake -> {
                            conversationViewModel.updateConfig(cfg)
                            sessionManager.ensureConnected()
                            conversationViewModel.onVoiceWakeDetected()
                        }
                        req.autoConnect -> sessionManager.ensureConnected()
                    }
                    if (navController.currentDestination?.route != AppRoutes.Conversation) {
                        Log.d(TAG, "导航到对话页")
                        navController.navigate(AppRoutes.Conversation) {
                            popUpTo(AppRoutes.Conversation) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else {
                        Log.d(TAG, "已在对话页")
                    }
                }
            }
        }

        val exitHint = stringResource(R.string.press_back_again_to_exit)
        var lastBackPressAt by remember { mutableLongStateOf(0L) }
        BackHandler {
            if (navController.previousBackStackEntry != null) {
                navController.popBackStack()
                return@BackHandler
            }
            val now = System.currentTimeMillis()
            if (now - lastBackPressAt < EXIT_CONFIRM_MS) {
                (context as? ComponentActivity)?.finish()
            } else {
                lastBackPressAt = now
                Toast.makeText(context, exitHint, Toast.LENGTH_SHORT).show()
            }
        }

        Surface(modifier = modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
            ) {
                composable(AppRoutes.Conversation) {
                    ConversationScreen(
                        onNavigateToSettings = {
                            navController.navigate(AppRoutes.Settings)
                        },
                        viewModel = conversationViewModel,
                    )
                }
                composable(AppRoutes.Settings) {
                    var editedConfig = remember { configManager.loadConfig() }
                    SettingsScreen(
                        config = editedConfig,
                        onConfigChange = { newConfig ->
                            configManager.saveConfig(newConfig)
                            editedConfig = newConfig
                            conversationViewModel.updateConfig(newConfig)
                            sessionManager.ensureConnected()
                            if (newConfig.otaUrl.isNotBlank() || newConfig.websocketUrl.isNotBlank()) {
                                if (navController.previousBackStackEntry != null) {
                                    navController.popBackStack()
                                } else {
                                    navController.navigate(AppRoutes.Conversation) {
                                        popUpTo(AppRoutes.Settings) { inclusive = true }
                                    }
                                }
                            }
                        },
                        onBack = {
                            val cfg = configManager.loadConfig()
                            if (cfg.otaUrl.isNotBlank() || cfg.websocketUrl.isNotBlank()) {
                                if (navController.previousBackStackEntry != null) {
                                    navController.popBackStack()
                                } else {
                                    navController.navigate(AppRoutes.Conversation) {
                                        popUpTo(AppRoutes.Settings) { inclusive = true }
                                    }
                                }
                            } else {
                                navController.popBackStack()
                            }
                        },
                    )
                }
            }
        }
    }
}
