package com.tvbox.kotlin.core.network.doh

import android.content.Context
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.network.SSLSocketFactoryCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DoH 提供商选择与 Dns 实例构建。
 * 根据 [AppPreferences] 中的 [AppConstants.DOH_URL] 选择腾讯/阿里/360/Google/AdGuard/Quad9。
 * 选中关闭时返回系统 DNS。
 */
@Singleton
class DohUtil @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val sslCompat: SSLSocketFactoryCompat
) {

    @Volatile
    private var cachedType: Int = Int.MIN_VALUE

    @Volatile
    private var cachedDns: Dns? = null

    /** 返回当前配置对应的 Dns，相同配置复用缓存实例 */
    fun choose(): Dns {
        val type = preferences.getInt(AppConstants.DOH_URL, 0)
        val cached = cachedDns
        if (cached != null && cachedType == type) return cached
        // DoH 属增强能力：缓存目录不可用、地址异常等构建失败时退回系统 DNS，
        // 否则异常会冒泡到 OkHttpClient.Builder.dns()，导致整个网络栈无法创建
        val dns = runCatching { buildDns(type) }
            .onFailure { com.tvbox.kotlin.core.common.LOG.e(it) }
            .getOrDefault(Dns.SYSTEM)
        cachedDns = dns
        cachedType = type
        return dns
    }

    private fun buildDns(type: Int): Dns {
        if (type == TYPE_OFF) return Dns.SYSTEM
        val url = getDohUrl(type)
        if (url.isEmpty()) return Dns.SYSTEM
        val httpUrl = url.toHttpUrlOrNull() ?: return Dns.SYSTEM
        val dohClient = buildDohClient()
        return DnsOverHttps.Builder()
            .client(dohClient)
            .url(httpUrl)
            .includeIPv6(true)
            .post(false)
            .resolvePrivateAddresses(false)
            .resolvePublicAddresses(true)
            .build()
    }

    /** 构建 DoH 请求专用 client，带缓存与 TLS 兜底 */
    private fun buildDohClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectionSpecs(getConnectionSpecs())
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(TIMEOUT_DOH, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_DOH, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT_DOH, TimeUnit.MILLISECONDS)
        // 缓存目录异常时降级为无缓存，避免 DoH client 构建失败
        runCatching { Cache(File(context.cacheDir, DIR_DOH_CACHE), SIZE_DOH_CACHE) }
            .onFailure { com.tvbox.kotlin.core.common.LOG.e(it) }
            .getOrNull()
            ?.let { builder.cache(it) }
        applySsl(builder)
        return builder.build()
    }

    private fun applySsl(builder: OkHttpClient.Builder) {
        try {
            builder.sslSocketFactory(sslCompat, sslCompat.trustManager())
        } catch (e: Throwable) {
            // 兜底失败时退回系统默认 SSL 配置
            com.tvbox.kotlin.core.common.LOG.e(e)
        }
    }

    private fun getConnectionSpecs(): List<ConnectionSpec> = listOf(
        // 同 OkHttpHelper：不硬编码 TLS 版本，避免 Android 16+ 上 fallback 到
        // 含 TLSv1.1 的内置 spec 时抛 "protocol TLSv1.1 is not supported"
        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .allEnabledTlsVersions()
            .allEnabledCipherSuites()
            .build(),
        ConnectionSpec.CLEARTEXT
    )

    companion object {
        const val TYPE_OFF: Int = 0
        const val TYPE_TENCENT: Int = 1
        const val TYPE_ALI: Int = 2
        const val TYPE_360: Int = 3
        const val TYPE_GOOGLE: Int = 4
        const val TYPE_ADGUARD: Int = 5
        const val TYPE_QUAD9: Int = 6

        // DoH 仅作加速：被运营商拦截或不可达时要在数秒内失败并回退系统 DNS，
        // 过长的超时会让订阅拉取首包等待十几秒，表现为“拉取订阅失败/卡住”
        private const val TIMEOUT_DOH: Long = 5_000L
        private const val SIZE_DOH_CACHE: Long = 10L * 1024L * 1024L
        private const val DIR_DOH_CACHE = "dohcache"

        /** 根据 DoH 类型返回对应服务地址，关闭或未知返回空串 */
        fun getDohUrl(type: Int): String = when (type) {
            TYPE_TENCENT -> "https://doh.pub/dns-query"
            TYPE_ALI -> "https://dns.alidns.com/dns-query"
            TYPE_360 -> "https://doh.360.cn/dns-query"
            TYPE_GOOGLE -> "https://dns.google/dns-query"
            TYPE_ADGUARD -> "https://dns.adguard.com/dns-query"
            TYPE_QUAD9 -> "https://dns.quad9.net/dns-query"
            else -> ""
        }
    }
}
