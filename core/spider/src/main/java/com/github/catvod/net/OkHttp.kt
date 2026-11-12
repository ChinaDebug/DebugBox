package com.github.catvod.net

import com.github.catvod.utils.Util
import okhttp3.Cache
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

/**
 * 爬虫专用 OkHttp 封装，jar 内爬虫通过 com.github.catvod.net.OkHttp 调用。
 * 保留静态方法签名以兼容旧 jar 爬虫。
 */
object OkHttp {

    private const val TIMEOUT = 30 * 1000L
    private const val CACHE_SIZE = 100L * 1024L * 1024L

    private const val HEADER_AUTHORIZATION = "Authorization"

    @Volatile
    private var dns: Dns? = null

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var selector: ProxySelector? = null

    private fun dns(): Dns = dns ?: Dns.SYSTEM

    /** 配置 DoH，当前实现回落到系统 DNS，保留签名兼容 jar 内调用 */
    fun setDoh(doh: com.tvbox.kotlin.core.common.bean.Doh) {
        // DoH 由 :core:network 统一管理，此处保留方法签名兼容旧 jar
        dns = null
        client = null
    }

    fun setProxy(proxy: String) {
        java.net.ProxySelector.setDefault(selector())
        selector().setProxy(proxy)
        client = null
    }

    @JvmStatic
    fun selector(): ProxySelector {
        if (selector != null) return selector!!
        selector = ProxySelector()
        return selector!!
    }

    @JvmStatic
    fun client(): OkHttpClient {
        if (client != null) return client!!
        client = getBuilder().build()
        return client!!
    }

    @JvmStatic
    fun client(timeout: Int): OkHttpClient {
        return client().newBuilder()
            .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    @JvmStatic
    fun noRedirect(timeout: Int): OkHttpClient {
        return client().newBuilder()
            .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @JvmStatic
    fun client(redirect: Boolean, timeout: Int): OkHttpClient {
        return if (redirect) client(timeout) else noRedirect(timeout)
    }

    @JvmStatic
    fun string(url: String): String = try {
        if (url.startsWith("http")) newCall(url).execute().body?.string() ?: ""
        else ""
    } catch (_: Exception) {
        ""
    }

    @JvmStatic
    fun newCall(url: String): Call {
        val uri = android.net.Uri.parse(url)
        if (uri.userInfo != null) {
            return newCall(url, Headers.headersOf(HEADER_AUTHORIZATION, Util.basic(uri)))
        }
        return client().newCall(Request.Builder().url(url).build())
    }

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String): Call =
        client.newCall(Request.Builder().url(url).build())

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String, headers: Headers): Call =
        client.newCall(Request.Builder().url(url).headers(headers).build())

    @JvmStatic
    fun newCall(url: String, headers: Headers): Call =
        client().newCall(Request.Builder().url(url).headers(headers).build())

    @JvmStatic
    fun newCall(url: String, headers: Headers, params: androidx.collection.ArrayMap<String, String>): Call =
        client().newCall(Request.Builder().url(buildUrl(url, params)).headers(headers).build())

    @JvmStatic
    fun newCall(url: String, headers: Headers, body: RequestBody): Call =
        client().newCall(Request.Builder().url(url).headers(headers).post(body).build())

    @JvmStatic
    fun newCall(client: OkHttpClient, url: String, body: RequestBody): Call =
        client.newCall(Request.Builder().url(url).post(body).build())

    @JvmStatic
    fun toBody(params: androidx.collection.ArrayMap<String, String>): FormBody {
        val body = FormBody.Builder()
        for ((key, value) in params.entries) body.add(key, value)
        return body.build()
    }

    private fun buildUrl(url: String, params: androidx.collection.ArrayMap<String, String>): HttpUrl {
        val builder = url.toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("invalid url: $url")
        for ((key, value) in params.entries) builder.addQueryParameter(key, value)
        return builder.build()
    }

    private fun getBuilder(): OkHttpClient.Builder {
        // 兼容旧版 TLS 源：默认 ConnectionSpec（MODERN_TLS）仅允许 TLSv1.2+，
        // 部分订阅源服务器只支持 TLSv1.1，握手会报 "protocol TLSv1.1 is not supported"；
        // 显式放行设备支持的全部 TLS 版本与密码套件（结合 SSLCompat 自定义 SSLSocketFactory）。
        // 必须同时放行 CLEARTEXT：手动设置 connectionSpecs 后若不包含 CLEARTEXT，
        // OkHttp 会对 http:// 明文请求直接抛 "CLEARTEXT communication not enabled for client"，
        // 导致 jar 内爬虫请求 http 明文 ext 链接（json 订阅源）时站点无法加载
        val specs = listOf(
            ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .allEnabledTlsVersions()
                .allEnabledCipherSuites()
                .build(),
            ConnectionSpec.CLEARTEXT
        )
        val builder = OkHttpClient.Builder()
            .addInterceptor(OkhttpInterceptor())
            .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .dns(dns())
            .hostnameVerifier(SSLCompat.VERIFIER)
            .sslSocketFactory(SSLCompat(), SSLCompat.TM)
            .connectionSpecs(specs)
        builder.proxySelector(selector())
        return builder
    }
}
