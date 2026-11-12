package com.github.catvod

import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.util.LocalIPAddress

/**
 * 主进程代理端口占位类。
 * jar 内爬虫通过此类获取本地服务端口与代理 URL，
 * JarLoader 加载 jar 时同步端口，避免端口变更后 jar 内仍持有旧端口导致请求失败。
 * 保留 com.github.catvod 包名以兼容旧 jar 爬虫。
 */
object Proxy {

    @Volatile
    private var port: Int = AppConstants.LOCAL_SERVER_PORT

    @JvmStatic
    fun set(port: Int) {
        Proxy.port = port
    }

    @JvmStatic
    fun getPort(): Int = if (port > 0) port else AppConstants.LOCAL_SERVER_PORT

    /** 获取代理 URL，local=true 返回 127.0.0.1 地址便于 jar 内自访问 */
    @JvmStatic
    fun getUrl(local: Boolean): String =
        "http://" + (if (local) "127.0.0.1" else getIp()) + ":" + getPort() + "/proxy"

    private fun getIp(): String = try {
        LocalIPAddress.getLocalIPAddress(Init.context())
    } catch (_: Throwable) {
        "127.0.0.1"
    }
}
