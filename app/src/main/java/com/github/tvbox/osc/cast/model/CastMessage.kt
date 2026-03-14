package com.github.tvbox.osc.cast.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * 投屏消息数据类
 */
data class CastMessage(
    @SerializedName("type")
    val type: MessageType,

    @SerializedName("data")
    val data: CastData? = null,

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @SerializedName("from")
    val from: String = "",

    @SerializedName("to")
    val to: String = ""
) {
    enum class MessageType {
        PLAY,           // 播放
        PAUSE,          // 暂停
        RESUME,         // 继续
        STOP,           // 停止
        SEEK,           // 跳转
        UPDATE_PROGRESS,// 更新进度
        GET_STATUS,     // 获取状态
        STATUS_RESPONSE,// 状态响应
        HEARTBEAT,      // 心跳
        DEVICE_INFO     // 设备信息
    }

    fun toJson(): String {
        return Gson().toJson(this)
    }

    companion object {
        fun fromJson(json: String): CastMessage? {
            return try {
                Gson().fromJson(json, CastMessage::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * 投屏数据
 */
data class CastData(
    @SerializedName("url")
    val url: String? = null,

    @SerializedName("title")
    val title: String? = null,

    @SerializedName("position")
    val position: Long = 0,

    @SerializedName("duration")
    val duration: Long = 0,

    @SerializedName("isPlaying")
    val isPlaying: Boolean = false,

    @SerializedName("headers")
    val headers: Map<String, String>? = null,

    @SerializedName("episode")
    val episode: Int = 0,

    @SerializedName("episodeName")
    val episodeName: String? = null,

    @SerializedName("playerType")
    val playerType: Int = 2,  // 播放器类型：0=系统, 1=IJK, 2=Exo，默认Exo

    @SerializedName("danmakuData")
    val danmakuData: String? = null,  // 弹幕XML内容

    @SerializedName("hasDanmaku")
    val hasDanmaku: Boolean = false   // 是否有弹幕
)

/**
 * 设备信息
 */
data class DeviceInfo(
    @SerializedName("name")
    val name: String,

    @SerializedName("type")
    val type: String,

    @SerializedName("version")
    val version: String,

    @SerializedName("port")
    val port: Int = CastDevice.DEFAULT_CAST_PORT
) {
    fun toJson(): String {
        return Gson().toJson(this)
    }

    companion object {
        fun fromJson(json: String): DeviceInfo? {
            return try {
                Gson().fromJson(json, DeviceInfo::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
}
