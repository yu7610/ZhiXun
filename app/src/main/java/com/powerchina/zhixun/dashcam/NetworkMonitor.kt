package com.powerchina.zhixun.dashcam

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 网络可达性监听：用于离线录像队列在恢复联网后自动上传。
 */
object NetworkMonitor {

    private const val TAG = "DashcamVideoUpload"

    fun interface Listener {
        fun onNetworkAvailable()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val registered = AtomicBoolean(false)

    @Volatile
    private var lastOnline: Boolean? = null

    fun isOnline(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun ensureRegistered(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                maybeNotifyOnline(app)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                maybeNotifyOnline(app)
            }

            override fun onLost(network: Network) {
                lastOnline = false
            }
        }
        runCatching {
            cm.registerNetworkCallback(request, callback)
            lastOnline = isOnline(app)
            Log.i(TAG, "NetworkMonitor 已注册 online=$lastOnline")
        }.onFailure {
            registered.set(false)
            Log.w(TAG, "NetworkMonitor 注册失败", it)
        }
    }

    private fun maybeNotifyOnline(context: Context) {
        val online = isOnline(context)
        val prev = lastOnline
        lastOnline = online
        if (online && prev != true) {
            Log.i(TAG, "网络已恢复，通知 ${listeners.size} 个监听者")
            listeners.forEach { runCatching { it.onNetworkAvailable() } }
        }
    }
}
