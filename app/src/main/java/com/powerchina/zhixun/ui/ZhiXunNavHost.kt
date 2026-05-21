package com.powerchina.zhixun.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.powerchina.zhixun.data.ConfigManager
import com.powerchina.zhixun.ui.theme.YTheme
import com.powerchina.zhixun.viewmodel.ConversationViewModel
import com.powerchina.zhixun.xiaozhi.XiaozhiAppEvents
import com.powerchina.zhixun.xiaozhi.XiaozhiSessionManager

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
            XiaozhiAppEvents.requests.collect { req ->
                val cfg = configManager.loadConfig()
                if (cfg.otaUrl.isBlank() && cfg.websocketUrl.isBlank()) {
                    navController.navigate(AppRoutes.Settings)
                } else {
                    conversationViewModel.updateConfig(cfg)
                    sessionManager.ensureConnected()
                    if (req.fromVoiceWake) {
                        conversationViewModel.onVoiceWakeDetected()
                    }
                    if (navController.currentDestination?.route != AppRoutes.Conversation) {
                        navController.navigate(AppRoutes.Conversation) {
                            popUpTo(AppRoutes.Conversation) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
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
