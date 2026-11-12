package com.github.catvod.net

import android.annotation.SuppressLint
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * 兼容旧 TLS 的 SSLSocketFactory（信任全部证书），供 jar 爬虫与 catvod.net.OkHttp 共用。
 *
 * 协议与密码套件按 socket 实例的 supportedProtocols / supportedCipherSuites 启用，
 * 不做静态硬编码：部分套件（如 TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256）在
 * Android 16+ 已被 provider 移除，setEnabledCipherSuites 会抛
 * "cipherSuite ... is not supported"。
 */
class SSLCompat : SSLSocketFactory() {

    private val factory: SSLSocketFactory

    init {
        factory = try {
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<X509TrustManager>(TM), null)
            // HttpsURLConnection 调用路径复用本工厂，与 OkHttp 保持一致的 TLS 兼容策略
            HttpsURLConnection.setDefaultSSLSocketFactory(this@SSLCompat)
            HttpsURLConnection.setDefaultHostnameVerifier(VERIFIER)
            context.socketFactory
        } catch (_: Throwable) {
            SSLSocketFactory.getDefault() as SSLSocketFactory
        }
    }

    @Throws(IOException::class)
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        val ssl = factory.createSocket(s, host, port, autoClose)
        if (ssl is SSLSocket) upgradeTLS(ssl)
        return ssl
    }

    @Throws(IOException::class)
    override fun createSocket(host: String, port: Int): Socket {
        val ssl = factory.createSocket(host, port)
        if (ssl is SSLSocket) upgradeTLS(ssl)
        return ssl
    }

    @Throws(IOException::class)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val ssl = factory.createSocket(host, port, localHost, localPort)
        if (ssl is SSLSocket) upgradeTLS(ssl)
        return ssl
    }

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress, port: Int): Socket {
        val ssl = factory.createSocket(host, port)
        if (ssl is SSLSocket) upgradeTLS(ssl)
        return ssl
    }

    @Throws(IOException::class)
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        val ssl = factory.createSocket(address, port, localAddress, localPort)
        if (ssl is SSLSocket) upgradeTLS(ssl)
        return ssl
    }

    override fun getDefaultCipherSuites(): Array<String> = factory.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = factory.supportedCipherSuites

    /**
     * 按 socket 实例能力启用 TLS 协议与密码套件。
     * 取值限定于 supported 列表，避免 setEnabled* 抛“不支持”；异常时保留平台默认。
     */
    private fun upgradeTLS(ssl: SSLSocket) {
        try {
            val protocols = ssl.supportedProtocols.filterNot { it.contains("SSL", ignoreCase = true) }
            if (protocols.isNotEmpty()) ssl.enabledProtocols = protocols.toTypedArray()
        } catch (_: Throwable) {
            // 保持平台默认协议
        }
        try {
            val suites = LinkedHashSet(ssl.enabledCipherSuites.asList())
            for (suite in ssl.supportedCipherSuites) {
                if (!isSuiteSafeToEnable(suite)) continue
                suites.add(suite)
            }
            if (suites.isNotEmpty()) ssl.enabledCipherSuites = suites.toTypedArray()
        } catch (_: Throwable) {
            // 保持平台默认套件
        }
    }

    /** 排除不能作为实际加密套件启用的项：SCSV 信令值、NULL 加密、匿名与导出级套件 */
    private fun isSuiteSafeToEnable(suite: String): Boolean {
        val name = suite.uppercase(Locale.ROOT)
        return !name.endsWith("_SCSV") &&
            !name.contains("_NULL_") && !name.endsWith("_NULL") &&
            !name.contains("_ANON_") && !name.contains("_EXPORT_")
    }

    companion object {
        @JvmField
        val VERIFIER: HostnameVerifier = HostnameVerifier { _, _ -> true }

        @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
        @JvmField
        val TM: X509TrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}
