package com.tvbox.kotlin.core.network

import android.content.Context
import com.tvbox.kotlin.core.common.LOG
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 系统默认 SSLSocketFactory 的兼容封装。
 * 按 socket 能力启用 TLS 协议与安全密码套件，兼容旧版服务器；
 * 证书校验沿用系统 TrustManager 并追加内置公信根，不做放宽。
 */
@Singleton
class SSLSocketFactoryCompat @Inject constructor(
    @ApplicationContext private val context: Context
) : SSLSocketFactory() {

    private val delegate: SSLSocketFactory = SSLContext.getDefault().socketFactory

    /** 系统 CA + 内置 ISRG 根的校验器；构建失败回退系统校验器 */
    private val mergedTrustManager: X509TrustManager by lazy { buildTrustManager() }

    /** 供 OkHttpClient.Builder.sslSocketFactory 配套使用的校验器 */
    fun trustManager(): X509TrustManager = mergedTrustManager

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

    /** 排除不可作为实际加密套件启用的项：SCSV 信令值、NULL 加密、匿名与导出级，以及 RC4/3DES/DES/MD5 弱套件 */
    private fun isSuiteSafeToEnable(suite: String): Boolean {
        val name = suite.uppercase(java.util.Locale.ROOT)
        return !name.endsWith("_SCSV") &&
            !name.contains("_NULL_") && !name.endsWith("_NULL") &&
            !name.contains("_ANON_") && !name.contains("_EXPORT_") &&
            !name.contains("_RC4_") && !name.contains("_3DES_") &&
            !name.contains("_DES_") && !name.contains("_MD5")
    }

    /**
     * 复制系统内置 CA 后追加 ISRG Root X1/X2：Android 7.x 的信任库早于
     * Let's Encrypt 新根，订阅源用其短链证书时会直接校验失败。
     * 只提升系统锚与这两张公信根，用户安装的 CA 不纳入，主机名校验仍由系统完成。
     */
    private fun buildTrustManager(): X509TrustManager {
        val fallback = systemTrustManager()
        return try {
            val merged = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            val systemStore = KeyStore.getInstance(ANDROID_CA_STORE).apply { load(null, null) }
            var systemAnchors = 0
            for (alias in systemStore.aliases()) {
                if (!alias.startsWith(SYSTEM_ALIAS_PREFIX)) continue
                val cert = systemStore.getCertificate(alias) ?: continue
                merged.setCertificateEntry(alias, cert)
                systemAnchors++
            }
            // 系统锚为空说明该设备形态异常，直接回退，避免只剩内置根导致其它站点全失败
            if (systemAnchors == 0) return fallback
            val factory = CertificateFactory.getInstance("X.509")
            for (resId in BUNDLED_ROOT_RES) {
                val cert = context.resources.openRawResource(resId).use {
                    factory.generateCertificate(it) as X509Certificate
                }
                merged.setCertificateEntry("bundled_$resId", cert)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(merged)
            tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull() ?: fallback
        } catch (e: Throwable) {
            LOG.e(e)
            fallback
        }
    }

    companion object {
        private const val ANDROID_CA_STORE = "AndroidCAStore"
        private const val SYSTEM_ALIAS_PREFIX = "system:"
        private val BUNDLED_ROOT_RES = intArrayOf(R.raw.isrg_root_x1, R.raw.isrg_root_x2)

        /** 获取系统默认 X509TrustManager，用于 OkHttpClient.Builder.sslSocketFactory 配套 */
        fun systemTrustManager(): X509TrustManager {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
