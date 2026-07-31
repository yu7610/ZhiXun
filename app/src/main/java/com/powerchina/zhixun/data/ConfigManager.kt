package com.powerchina.zhixun.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 配置管理器，负责配置的存储和读取
 */
class ConfigManager(private val context: Context) {
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val PREFS_NAME = "xiaozhi_config"
        private const val KEY_CONFIG = "config"
    }
    
    /**
     * 保存配置
     */
    fun saveConfig(config: XiaozhiConfig) {
        val configJson = gson.toJson(config)
        sharedPreferences.edit()
            .putString(KEY_CONFIG, configJson)
            .apply()
    }
    
    /**
     * 读取配置
     *
     * 注意：Gson 不会应用 Kotlin 默认值，旧版本地配置缺少 `mqtt` 字段时会得到 null，
     * 必须在这里兜底，避免运行时 NPE。
     */
    fun loadConfig(): XiaozhiConfig {
        val configJson = sharedPreferences.getString(KEY_CONFIG, null)
        return if (configJson != null) {
            try {
                normalize(gson.fromJson(configJson, XiaozhiConfig::class.java))
            } catch (e: Exception) {
                XiaozhiConfig.createDefault()
            }
        } else {
            XiaozhiConfig.createDefault()
        }
    }

    private fun normalize(config: XiaozhiConfig?): XiaozhiConfig {
        if (config == null) return XiaozhiConfig.createDefault()
        // Gson 反射写入可使非空类型实际为 null
        @Suppress("SENSELESS_COMPARISON")
        val rawMqtt = if (config.mqtt == null) MqttConfig() else config.mqtt
        val mqtt = rawMqtt.copy(
            subscribeTopic = rawMqtt.subscribeTopic.trim().let {
                if (it.equals("null", ignoreCase = true)) "" else it
            },
        )
        @Suppress("SENSELESS_COMPARISON")
        val mcpServers = if (config.mcpServers == null) emptyList() else config.mcpServers
        return config.copy(mqtt = mqtt, mcpServers = mcpServers)
    }
    
    /**
     * 检查配置是否完整
     */
    fun isConfigComplete(config: XiaozhiConfig): Boolean {
        return config.name.isNotBlank() &&
               (config.otaUrl.isNotBlank() || config.mqtt.isReady()) &&
               config.macAddress.isNotBlank()
    }
    
    /**
     * 获取缺失的配置项
     */
    fun getMissingFields(config: XiaozhiConfig): List<String> {
        val missingFields = mutableListOf<String>()
        
        if (config.name.isBlank()) missingFields.add("设备名称")
        if (config.otaUrl.isBlank() && !config.mqtt.isReady()) missingFields.add("OTA地址或MQTT配置(至少填一个)")
        if (config.macAddress.isBlank()) missingFields.add("MAC地址")
        
        return missingFields
    }
}