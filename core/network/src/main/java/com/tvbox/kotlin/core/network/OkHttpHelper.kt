package com.tvbox.kotlin.core.network

import android.content.Context
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.network.doh.DohUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.conscrypt.Conscrypt
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局 OkHttp 客户端封装。
 * Hilt 单例懒加载，提供同步 GET/POST、流式读取、文件下载与 DoH 支持。
 * 同步方法内部走 OkHttp 同步执行，禁止在主线程调用，调用方需自行切换 IO 线程。
 */
@Singleton
class OkHttpHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val dohUtil: DohUtil,
    private val sslCompat: SSLSocketFactoryCompat
) {
    init {
        initConscrypt()
    }

    private val httpCache: Cache by lazy {
        Cache(File(context.cacheDir, DIR_HTTP_CACHE), SIZE_HTTP_CACHE)
    }

    @Volatile
    private var defaultClient: OkHttpClient? = null

    @Volatile
    private var noRedirectClient: OkHttpClient? = null

    @Volatile
    private var checkApiClient: OkHttpClient? = null

    /** 内存级 Cookie Jar，跨请求复用会话；登出/切换账号时调用 [clearCookies] */
    val cookieJar: InMemoryCookieJar by lazy { InMemoryCookieJar() }

    /**
     * UA 轮换池：默认内置常见 UA，外部可通过 [setUaPool] 注入自定义列表。
     * 通过 [nextUserAgent] 取下一个 UA，未配置池时回落到默认 UA。
     */
    @Volatile
    private var uaPool: List<String> = DEFAULT_UA_POOL

    /** 配置 UA 轮换池，传入空列表则恢复默认池 */
    fun setUaPool(pool: List<String>) {
        uaPool = if (pool.isEmpty()) DEFAULT_UA_POOL else pool.distinct()
        uaIndex = 0
    }

    @Volatile
    private var uaIndex: Int = 0

    /** 取下一个轮换 UA；池中只有一个时直接返回 */
    @Synchronized
    fun nextUserAgent(): String {
        val pool = uaPool
        if (pool.size <= 1) return pool.first()
        val ua = pool[uaIndex % pool.size]
        uaIndex = (uaIndex + 1) % pool.size
        return ua
    }

    /** 清空全部 Cookie，用于登出/切换账号 */
    fun clearCookies() = cookieJar.clear()

    /** 清空指定 host 的 Cookie */
    fun clearCookiesForHost(host: String) = cookieJar.clearForHost(host)

    /** 默认全局 OkHttpClient，跟随重定向 */
    fun client(): OkHttpClient = synchronized(this) {
        defaultClient ?: buildClient(followRedirects = true).also { defaultClient = it }
    }

    /** 不跟随重定向的 OkHttpClient，用于抓取跳转地址 */
    fun noRedirectClient(): OkHttpClient = synchronized(this) {
        noRedirectClient ?: buildClient(followRedirects = false).also { noRedirectClient = it }
    }

    /** 短超时 OkHttpClient，用于订阅地址探活与更新检测 */
    fun checkApiClient(): OkHttpClient = synchronized(this) {
        checkApiClient ?: buildCheckClient().also { checkApiClient = it }
    }

    /** 重建全部客户端实例（DoH 等客户端级配置变更后调用，使新配置立即生效） */
    fun rebuildClients() {
        synchronized(this) {
            defaultClient = null
            noRedirectClient = null
            checkApiClient = null
        }
    }

    /** 同步 GET 请求，返回响应体字符串，异常返回空串 */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        executeString(buildGetRequest(url, headers))

    /** 同步 GET 请求，返回响应体原始字节，异常返回空数组（用于调用方自定义解码） */
    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        return try {
            client().newCall(buildGetRequest(url, headers)).execute().use { response ->
                response.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: Exception) {
            LOG.e(e)
            ByteArray(0)
        }
    }

    /** 同步 POST 请求，body 默认 JSON，异常返回空串 */
    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        executeString(buildPostRequest(url, body, headers))

    /** 同步获取响应流，调用方负责关闭返回的 InputStream */
    fun stream(url: String, headers: Map<String, String> = emptyMap()): InputStream {
        val request = buildGetRequest(url, headers)
        val response = client().newCall(request).execute()
        val body = response.body
        if (body == null) {
            response.close()
            return ByteArrayInputStream(ByteArray(0))
        }
        val raw = body.byteStream()
        return object : FilterInputStream(raw) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    response.close()
                }
            }
        }
    }

    /** 协程下载文件，progress 回调 0..1 进度，成功返回 true */
    suspend fun download(
        url: String,
        destFile: File,
        progress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val request = buildGetRequest(url, emptyMap())
        var response: Response? = null
        try {
            response = client().newCall(request).execute()
            if (!response.isSuccessful) return@withContext false
            val body = response.body ?: return@withContext false
            val total = body.contentLength()
            destFile.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(destFile).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) progress?.invoke(read.toFloat() / total.toFloat())
                    }
                    out.flush()
                }
            }
            true
        } catch (e: Exception) {
            LOG.e(e)
            false
        } finally {
            response?.close()
        }
    }

    /** 基于全局 client 创建新 builder，共享连接池，供上层（如播放器）自定义 */
    fun newBuilder(): OkHttpClient.Builder = client().newBuilder()

    private fun executeString(request: Request): String = try {
        client().newCall(request).execute().use { response ->
            response.body?.string() ?: ""
        }
    } catch (e: Exception) {
        LOG.e(e)
        ""
    }

    private fun buildGetRequest(url: String, headers: Map<String, String>): Request {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid url: $url")
        val builder = Request.Builder().url(httpUrl).get()
        applyHeaders(builder, headers)
        return builder.build()
    }

    private fun buildPostRequest(url: String, body: String, headers: Map<String, String>): Request {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid url: $url")
        val mediaType = resolveContentType(headers)
        val requestBody = body.toRequestBody(mediaType)
        val builder = Request.Builder().url(httpUrl).post(requestBody)
        // Content-Type 由 requestBody 自动设置，避免重复
        applyHeaders(builder, headers.filterKeys { !it.equals("Content-Type", ignoreCase = true) })
        return builder.build()
    }

    private fun resolveContentType(headers: Map<String, String>): MediaType {
        val raw = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value
        return raw?.toMediaTypeOrNull() ?: JSON
    }

    private fun applyHeaders(builder: Request.Builder, headers: Map<String, String>) {
        builder.header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> builder.header(k, v) }
    }

    private fun buildClient(followRedirects: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionSpecs(getConnectionSpecs())
            .retryOnConnectionFailure(true)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .dns(HostsDns(dohUtil.choose()))
            .cache(httpCache)
            .cookieJar(cookieJar)
        applySsl(builder)
        return builder.build()
    }

    private fun buildCheckClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionSpecs(getConnectionSpecs())
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .dns(HostsDns(dohUtil.choose()))
        applySsl(builder)
        return builder.build()
    }

    private fun applySsl(builder: OkHttpClient.Builder) {
        try {
            builder.sslSocketFactory(sslCompat, SSLSocketFactoryCompat.systemTrustManager())
        } catch (e: Throwable) {
            LOG.e(e)
        }
    }

    private fun getConnectionSpecs(): List<ConnectionSpec> = listOf(
        // 不硬编码 TLS 版本：内置 MODERN_TLS/COMPATIBLE_TLS 的 tlsVersions 含 TLSv1.1，
        // 在 Android 16+（provider 已移除 TLSv1.1）上 OkHttp fallback 时 setEnabledProtocols
        // 会抛 "protocol TLSv1.1 is not supported"；allEnabledTlsVersions 让 OkHttp 不覆盖
        // 协议列表，按设备实际支持的版本握手，兼容旧 TLS 源
        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .allEnabledTlsVersions()
            .allEnabledCipherSuites()
            .build(),
        ConnectionSpec.CLEARTEXT
    )

    /** 初始化 conscrypt 提供现代 TLS，失败不影响主流程 */
    private fun initConscrypt() {
        try {
            if (Security.getProvider(PROVIDER_CONSCRYPT) == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            }
        } catch (e: Throwable) {
            LOG.e(e)
        }
    }

    companion object {
        private const val DIR_HTTP_CACHE = "okhttp"
        private const val SIZE_HTTP_CACHE: Long = 50L * 1024L * 1024L
        private const val BUFFER_SIZE = 8 * 1024
        private const val PROVIDER_CONSCRYPT = "Conscrypt"
        private const val CHECK_TIMEOUT_MS = 5_000L

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val USER_AGENT = AppConstants.UA_DEFAULT
        private val CONNECT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(AppConstants.TIMEOUT_CONNECT.toLong())
        private val READ_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(AppConstants.TIMEOUT_READ.toLong())
        private val WRITE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(AppConstants.TIMEOUT_WRITE.toLong())

        /** 默认 UA 轮换池，涵盖常见移动/桌面 UA，外部可通过 [setUaPool] 替换 */
        private val DEFAULT_UA_POOL: List<String> = listOf(
            AppConstants.UA_DEFAULT,
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
    }
}
