package com.github.tvbox.osc.cast.model

import java.net.InetAddress

/**
 * 投屏设备数据类
 */
data class CastDevice(
    val name: String,
    val hostAddress: String,
    val port: Int = DEFAULT_CAST_PORT,
    val deviceType: DeviceType = DeviceType.UNKNOWN,
    val lastSeen: Long = System.currentTimeMillis()
) {
    enum class DeviceType {
        TVBOX,      // 本应用TVBox
        DLNA,       // DLNA设备
        AIRPLAY,    // AirPlay设备
        CHROMECAST, // Chromecast设备
        UNKNOWN     // 未知类型
    }

    companion object {
        const val DEFAULT_CAST_PORT = 9979
        const val DEVICE_TYPE_TVBOX = "tvbox"
        const val DEVICE_TYPE_DLNA = "dlna"
        const val DEVICE_TYPE_AIRPLAY = "airplay"
        const val DEVICE_TYPE_CHROMECAST = "chromecast"
    }

    /**
     * 生成设备唯一标识
     */
    fun getDeviceId(): String {
        return "$hostAddress:$port"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CastDevice) return false
        return hostAddress == other.hostAddress && port == other.port
    }

    override fun hashCode(): Int {
        return 31 * hostAddress.hashCode() + port
    }
}
