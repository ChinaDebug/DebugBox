package com.tvbox.kotlin.core.server.cast.model

/**
 * 投屏设备数据类。
 *
 * 描述被发现的投屏接收端信息，以 `hostAddress:port` 作为唯一标识。
 */
data class CastDevice(
    val name: String,
    val hostAddress: String,
    val port: Int = DEFAULT_CAST_PORT,
    val deviceType: DeviceType = DeviceType.UNKNOWN,
    val lastSeen: Long = System.currentTimeMillis()
) {

    /** 设备类型枚举 */
    enum class DeviceType {
        /** 本应用 TVBox */
        TVBOX,

        /** DLNA 设备 */
        DLNA,

        /** AirPlay 设备 */
        AIRPLAY,

        /** Chromecast 设备 */
        CHROMECAST,

        /** 未知类型 */
        UNKNOWN
    }

    /** 生成设备唯一标识 */
    fun getDeviceId(): String = "$hostAddress:$port"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CastDevice) return false
        return hostAddress == other.hostAddress && port == other.port
    }

    override fun hashCode(): Int = 31 * hostAddress.hashCode() + port

    companion object {
        const val DEFAULT_CAST_PORT = 9979
        const val DEVICE_TYPE_TVBOX = "tvbox"
        const val DEVICE_TYPE_DLNA = "dlna"
        const val DEVICE_TYPE_AIRPLAY = "airplay"
        const val DEVICE_TYPE_CHROMECAST = "chromecast"
    }
}
