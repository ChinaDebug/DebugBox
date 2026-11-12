package com.tvbox.kotlin.core.common.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Build
import com.tvbox.kotlin.core.common.LOG
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/**
 * 本机局域网 IPv4 获取工具。
 * 地址仅供同一局域网内的设备访问，因此只返回私网段（RFC1918）地址，
 * 公网地址与运营商共享地址（100.64/10）一律不采用。
 */
object LocalIPAddress {

    /** VPN/隧道类接口名特征，其网段对外不可达，须排除 */
    private val VIRTUAL_IFACE_KEYWORDS = listOf(
        "tun", "ppp", "tap", "dummy", "vti", "ip_vti", "vpn", "ipsec", "softap"
    )

    @JvmOverloads
    fun getLocalIPAddress(context: Context? = null): String {
        context?.let { getActiveNetworkIPv4(it) }?.takeIf { it.isNotBlank() }?.let { return it }
        val ip = getLocalIPAddressRaw()
        return if (ip == "127.0.0.1") getLocalIPAddressByInterface() else ip
    }

    @JvmOverloads
    fun getIP(context: Context? = null): String = getLocalIPAddress(context)

    /**
     * 取当前活跃网络上的私网 IPv4。activeNetwork 即系统正在联网的链路，
     * 优先命中它可避免多网卡/热点/VPN 并存时按枚举顺序取错网段。
     */
    @SuppressLint("MissingPermission")
    private fun getActiveNetworkIPv4(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val network = cm.activeNetwork ?: return null
            val lp: LinkProperties = cm.getLinkProperties(network) ?: return null
            lp.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { isLanAddress(it) }
                ?.hostAddress
        } catch (e: Throwable) {
            LOG.e("LocalIPAddress", e)
            null
        }
    }

    /** 是否局域网可访问的私网 IPv4（RFC1918 网段） */
    private fun isLanAddress(addr: Inet4Address): Boolean =
        !addr.isLoopbackAddress && !addr.isAnyLocalAddress && addr.isSiteLocalAddress

    private fun isValidInterface(ni: NetworkInterface): Boolean {
        if (!ni.isUp || ni.isLoopback || ni.isVirtual) return false
        val name = ni.name.lowercase()
        return VIRTUAL_IFACE_KEYWORDS.none { name.contains(it) }
    }

    /** 取接口上第一个私网 IPv4 */
    private fun firstLanIPv4(ni: NetworkInterface): String? {
        val addrs = ni.inetAddresses
        while (addrs.hasMoreElements()) {
            val addr = addrs.nextElement()
            if (addr is Inet4Address && isLanAddress(addr)) return addr.hostAddress
        }
        return null
    }

    private fun getLocalIPAddressByInterface(): String {
        try {
            val highPriority = listOf("wlan", "eth", "en", "rmnet", "bond")
            var best: String? = null
            var bestPrio = -1
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (ni in interfaces) {
                if (!isValidInterface(ni)) continue
                val prio = highPriority.indexOfFirst { ni.name.lowercase().contains(it) }
                val usable = firstLanIPv4(ni) ?: continue
                if (prio >= 0 && (bestPrio < 0 || prio < bestPrio)) {
                    bestPrio = prio
                    best = usable
                } else if (prio < 0 && best == null) {
                    best = usable
                }
            }
            return best ?: "127.0.0.1"
        } catch (e: SocketException) {
            LOG.e(e)
        }
        return "127.0.0.1"
    }

    @JvmStatic
    fun getLocalIPAddressRaw(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!isValidInterface(intf)) continue
                val addr = firstLanIPv4(intf) ?: continue
                return addr
            }
        } catch (e: SocketException) {
            LOG.e("LocalIPAddress", e)
        }
        return "127.0.0.1"
    }
}
