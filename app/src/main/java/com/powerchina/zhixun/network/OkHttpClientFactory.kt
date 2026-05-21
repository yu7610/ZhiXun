package com.powerchina.zhixun.network

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.powerchina.zhixun.R
import okhttp3.OkHttpClient
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.net.Socket
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedTrustManager

/**
 * 使用内置 CA/站点证书校验，不调用系统 TrustManager（避免与 domain-config 冲突）。
 */
object OkHttpClientFactory {

    private const val TAG = "OkHttpClientFactory"

    private val embeddedCertResIds = listOf(
        R.raw.digicert_global_root_g2,
        R.raw.geotrust_g2_intermediate,
        R.raw.api_tenclass_net
    )

    private val trustedHostSuffixes = listOf(
        "tenclass.net",
        "xiaozhi.me"
    )

    fun create(
        context: Context,
        connectTimeoutSec: Long = 15,
        readTimeoutSec: Long = 0,
        writeTimeoutSec: Long = 15,
        /** WebSocket 长连接建议 0：小智服务端 idle 时可能不回 pong */
        pingIntervalSec: Long = 0,
    ): OkHttpClient {
        val anchors = loadEmbeddedTrustAnchors(context.applicationContext)
        val trustManager = EmbeddedCertTrustManager(anchors)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
            .pingInterval(pingIntervalSec, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    private fun loadEmbeddedTrustAnchors(context: Context): Set<TrustAnchor> {
        val cf = CertificateFactory.getInstance("X.509")
        val anchors = mutableSetOf<TrustAnchor>()
        for (resId in embeddedCertResIds) {
            context.resources.openRawResource(resId).use { stream ->
                val cert = cf.generateCertificate(stream) as X509Certificate
                anchors.add(TrustAnchor(cert, null))
            }
        }
        Log.d(TAG, "已加载内置信任锚点 ${anchors.size} 个")
        return anchors
    }

    @SuppressLint("CustomX509TrustManager")
    private class EmbeddedCertTrustManager(
        private val anchors: Set<TrustAnchor>
    ) : X509ExtendedTrustManager() {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            // 客户端证书不校验
        }

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket,
        ) {
            // 客户端证书不校验
        }

        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine,
        ) {
            // 客户端证书不校验
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            verifyServerChain(chain, null)
        }

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            socket: Socket,
        ) {
            verifyServerChain(chain, socket.inetAddress?.hostName)
        }

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
            engine: SSLEngine,
        ) {
            verifyServerChain(chain, engine.peerHost)
        }

        private fun verifyServerChain(chain: Array<out X509Certificate>?, host: String?) {
            if (chain.isNullOrEmpty()) {
                throw CertificateException("服务器证书链为空")
            }
            val certs = chain.map { it as X509Certificate }.toTypedArray()
            val fullChain = expandChainIfNeeded(certs, anchors)

            try {
                validateWithPkix(fullChain, anchors)
                return
            } catch (e: Exception) {
                Log.w(TAG, "PKIX 校验失败(host=$host): ${e.message}")
            }

            if (isPinnedKnownHost(fullChain, anchors, host)) {
                Log.d(TAG, "使用内置站点证书放行: $host")
                return
            }

            throw CertificateException(
                "SSL 证书校验失败，请检查系统时间、网络代理/VPN，或更新应用"
            )
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return anchors.map { it.trustedCert }.toTypedArray()
        }
    }

    private fun expandChainIfNeeded(
        chain: Array<X509Certificate>,
        anchors: Set<TrustAnchor>
    ): Array<X509Certificate> {
        if (chain.size >= 2) return chain
        val leaf = chain[0]
        val issuer = anchors.map { it.trustedCert }
            .find { it.subjectX500Principal == leaf.issuerX500Principal }
        return if (issuer != null) arrayOf(leaf, issuer) else chain
    }

    private fun isPinnedKnownHost(
        chain: Array<X509Certificate>,
        anchors: Set<TrustAnchor>,
        host: String?
    ): Boolean {
        val leaf = chain.firstOrNull() ?: return false
        val hostOk = host.isNullOrBlank() || trustedHostSuffixes.any { suffix ->
            host.equals(suffix, ignoreCase = true) || host.endsWith(".$suffix", ignoreCase = true)
        }
        if (!hostOk) {
            val subject = leaf.subjectX500Principal.name
            if (trustedHostSuffixes.none { subject.contains(it, ignoreCase = true) }) {
                return false
            }
        }
        return anchors.any { anchor ->
            val trusted = anchor.trustedCert
            trusted.subjectX500Principal == leaf.subjectX500Principal &&
                trusted.serialNumber == leaf.serialNumber
        }
    }

    private fun validateWithPkix(chain: Array<X509Certificate>, anchors: Set<TrustAnchor>) {
        val certPath = CertificateFactory.getInstance("X.509").generateCertPath(chain.toList())
        val params = PKIXParameters(anchors).apply { isRevocationEnabled = false }
        CertPathValidator.getInstance("PKIX").validate(certPath, params)
    }
}
