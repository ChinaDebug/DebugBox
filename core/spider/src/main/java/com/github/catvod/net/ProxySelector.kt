package com.github.catvod.net

import com.github.catvod.utils.Util
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.SocketAddress
import java.net.URI

/** 爬虫专用代理选择器，按 host 白名单匹配走代理，其余直连 */
class ProxySelector : java.net.ProxySelector() {

    private var hosts: List<String>? = null
    private var proxy: Proxy? = null

    fun setHosts(hosts: List<String>?) {
        this.hosts = hosts
    }

    fun setProxy(proxy: String?) {
        this.proxy = getProxy(proxy)
    }

    override fun select(uri: URI): List<Proxy> {
        if (proxy == null || hosts == null || hosts!!.isEmpty() ||
            uri.host == null || "127.0.0.1" == uri.host
        ) {
            return listOf(Proxy.NO_PROXY)
        }
        for (host in hosts!!) {
            if (Util.containOrMatch(uri.host, host)) return listOf(proxy!!)
        }
        return listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI, socketAddress: SocketAddress, e: IOException) {
    }

    private fun getProxy(proxy: String?): Proxy {
        if (proxy == null) return Proxy.NO_PROXY
        val uri = android.net.Uri.parse(proxy)
        val userInfo = uri.userInfo
        if (userInfo != null && userInfo.contains(":")) setAuthenticator(userInfo)
        val scheme = uri.scheme
        val host = uri.host
        val port = uri.port
        if (scheme == null || host == null || port <= 0) return Proxy.NO_PROXY
        return when {
            scheme.startsWith("http") ->
                Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))
            scheme.startsWith("socks") ->
                Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port))
            else -> Proxy.NO_PROXY
        }
    }

    private fun setAuthenticator(userInfo: String) {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                val parts = userInfo.split(":")
                return if (parts.size >= 2) {
                    PasswordAuthentication(parts[0], parts[1].toCharArray())
                } else {
                    PasswordAuthentication(userInfo, CharArray(0))
                }
            }
        })
    }
}
