package com.tvbox.kotlin.core.network.doh

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * 引导 DNS 实现，DoH 服务器自身主机名解析时返回预设 IP，避免循环依赖。
 */
internal class BootstrapDns(
    private val dnsHostname: String,
    private val dnsServers: List<InetAddress>
) : Dns {

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        if (dnsHostname != hostname) {
            throw UnknownHostException("BootstrapDns called for $hostname instead of $dnsHostname")
        }
        return dnsServers
    }
}
