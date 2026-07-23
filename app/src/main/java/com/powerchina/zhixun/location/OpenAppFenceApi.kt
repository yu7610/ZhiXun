package com.powerchina.zhixun.location

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class GenerateTokenResult(
    val code: Int?,
    val msg: String?,
    /** 接口返回的 token 字符串 */
    val data: String?,
    val raw: String,
)

/**
 * 围栏页：POST /openApp/generateToken
 */
object OpenAppFenceApi {

    private const val TAG = "OpenAppFence"
    const val BASE_URL = "https://111.231.8.58:18099"
    const val GENERATE_TOKEN_URL = "$BASE_URL/openApp/generateToken"
    private const val APP_ID = "0aff4d17e27b45b5"
    private const val APP_SECRET = "d3e1d6a6d8ee432b9b22c0856fcfbea0"

    @Volatile
    private var sharedClient: OkHttpClient? = null

    private val emptyJson = "{}".toRequestBody("application/json; charset=utf-8".toMediaType())

    fun httpClient(@Suppress("UNUSED_PARAMETER") context: Context): OkHttpClient {
        // 主机为自签证书 IP，暂不用 OkHttpClientFactory
        return sharedClient ?: synchronized(this) {
            sharedClient ?: buildTrustAllClient().also { sharedClient = it }
        }
    }

    private fun client(context: Context): OkHttpClient = httpClient(context)

    fun generateToken(context: Context): Result<GenerateTokenResult> = runCatching {
        val url = "$GENERATE_TOKEN_URL?appId=$APP_ID&appSecret=$APP_SECRET"
        Log.i(TAG, "POST $url")

        val request = Request.Builder()
            .url(url)
            .post(emptyJson)
            .header("Content-Type", "application/json")
            .build()

        client(context).newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            Log.i(TAG, "generateToken HTTP ${response.code} 响应: $raw")
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $raw")
            }
            val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            val code = json?.get("code")?.asInt
            val msg = json?.get("msg")?.takeIf { !it.isJsonNull }?.asString
            val data = json?.get("data")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            if (code != null && code != 200 && code != 0) {
                throw IllegalStateException("业务错误 code=$code: $raw")
            }
            GenerateTokenResult(code = code, msg = msg, data = data, raw = raw)
        }
    }.onFailure { e ->
        Log.w(TAG, "generateToken 失败", e)
    }

    @SuppressLint("CustomX509TrustManager")
    private fun buildTrustAllClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
