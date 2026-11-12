package com.tvbox.kotlin.core.network

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统默认 SSLSocketFactory 的兼容封装。
 * 按 socket 能力启用 TLS 协议与安全密码套件，兼容旧版服务器；
 * 证书校验沿用系统 TrustManager，不做放宽。
 */
@Singleton
class SSLSocketFactoryCompat @Inject constructor() : SSLSocketFactory() {

    private val delegate: SSLSocketFactory = SSLContext.getDefault().socketFactory

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    @Throws(IOException::class)
    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
        patch(delegate.createSocket(s, host, port, autoClose))

    @Throws(IOException::class, UnknownHostException::class)
    override fun createSocket(host: String?, port: Int): Socket =
        patch(delegate.createSocket(host, port))

    @Throws(IOException::class, UnknownHostException::class)
    override fun createSocket(
        host: String?, port: Int, localHost: InetAddress?, localPort: Int
    ): Socket = patch(delegate.createSocket(host, port, localHost, localPort))

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress?, port: Int): Socket =
        patch(delegate.createSocket(host, port))

    @Throws(IOException::class)
    override fun createSocket(
        address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int
    ): Socket = patch(delegate.createSocket(address, port, localAddress, localPort))

    private fun patch(s: Socket): Socket {
        if (s is SSLSocket) {
            // 按 socket 能力启用协议，覆盖 TLS1.0~1.3
            try {
                val enabled = s.supportedProtocols.filterNot { it.contains("SSL", ignoreCase = true) }
                if (enabled.isNotEmpty()) s.enabledProtocols = enabled.toTypedArray()
            } catch (_: Throwable) {
                // 保持平台默认协议
            }
            // 密码套件取自当前 socket 的 supported 列表：硬编码套件在 Android 16+ 已移除，
            // setEnabledCipherSuites 会抛 "cipherSuite ... is not supported"
            try {
                val suites = LinkedHashSet(s.enabledCipherSuites.asList())
                for (suite in s.supportedCipherSuites) {
                    if (!isSuiteSafeToEnable(suite)) continue
                    suites.add(suite)
                }
                if (suites.isNotEmpty()) s.enabledCipherSuites = suites.toTypedArray()
            } catch (_: Throwable) {
                // 保持平台默认套件
            }
        }
        return s
    }

    /** 排除不可作为实际加密套件启用的项：SCSV 信令值、NULL 加密、匿名与导出级套件 */
    private fun isSuiteSafeToEnable(suite: String): Boolean {
        val name = suite.uppercase(java.util.Locale.ROOT)
        return !name.endsWith("_SCSV") &&
            !name.contains("_NULL_") && !name.endsWith("_NULL") &&
            !name.contains("_ANON_") && !name.contains("_EXPORT_")
    }

    companion object {
        /** 获取系统默认 X509TrustManager，用于 OkHttpClient.Builder.sslSocketFactory 配套 */
        fun systemTrustManager(): X509TrustManager {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
