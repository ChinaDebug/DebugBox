package com.tvbox.kotlin.core.network

import okhttp3.Dns
import java.net.IDN
import java.net.InetAddress

/**
 * 域名 → IP 直连映射（配置 hosts 字段），绕过运营商 DNS 污染。
 * 部分源配置通过 hosts 字段把域名映射到真实 IP；由 ApiConfig 解析配置后写入 [update]。
 */
object HostDnsMap {

    @Volatile
    private var map: Map<String, String> = emptyMap()

    /**
     * 更新映射。key 同时保留原始写法与 IDN（punycode）写法，
     * 因为 OkHttp 传给 Dns.lookup 的 hostname 是 IDN 化后的 ascii 域名。
     */
    fun update(hosts: Map<String, String>) {
        if (hosts.isEmpty()) {
            map = emptyMap()
            return
        }
        val normalized = HashMap<String, String>(hosts.size * 2)
        hosts.forEach { (key, value) ->
            normalized[key] = value
            try {
                normalized[IDN.toASCII(key)] = value
            } catch (_: Throwable) {
            }
        }
        map = normalized
    }

    fun lookup(hostname: String): String? = map[hostname]
}

/**
 * 优先按 hosts 直连映射解析域名，未命中时回退到 [delegate]（DoH 或系统 DNS）。
 * 映射值为 IP 时直连，为域名时继续走 delegate 解析。
 */
class HostsDns(private val delegate: Dns) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val mapped = HostDnsMap.lookup(hostname)
        if (!mapped.isNullOrEmpty()) {
            return try {
                if (isIpAddress(mapped)) listOf(InetAddress.getByName(mapped))
                else delegate.lookup(mapped)
            } catch (_: Throwable) {
                delegate.lookup(hostname)
            }
        }
        return delegate.lookup(hostname)
    }

    private fun isIpAddress(value: String): Boolean {
        if (value.contains('.')) {
            val parts = value.split('.')
            return parts.size == 4 && parts.all { it.toIntOrNull() != null }
        }
        return value.contains(':')
    }
}
