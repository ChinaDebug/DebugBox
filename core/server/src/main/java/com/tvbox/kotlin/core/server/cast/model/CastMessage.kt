package com.tvbox.kotlin.core.server.cast.model

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * 投屏消息数据类。
 *
 * 用于发送端与接收端之间的协议消息封装，JSON 序列化基于 Gson。
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

    /** 消息类型 */
    enum class MessageType {
        /** 播放 */
        PLAY,

        /** 暂停 */
        PAUSE,

        /** 继续 */
        RESUME,

        /** 停止 */
        STOP,

        /** 跳转 */
        SEEK,

        /** 更新进度 */
        UPDATE_PROGRESS,

        /** 获取状态 */
        GET_STATUS,

        /** 状态响应 */
        STATUS_RESPONSE,

        /** 心跳 */
        HEARTBEAT,

        /** 设备信息 */
        DEVICE_INFO
    }

    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): CastMessage? = try {
            Gson().fromJson(json, CastMessage::class.java)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 投屏数据。
 *
 * 由 CastData 数据类迁移，承载播放媒体地址、标题、进度、弹幕等信息。
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

    /** 播放器类型：0=系统, 2=Exo，默认 Exo */
    @SerializedName("playerType")
    val playerType: Int = 2,

    /** 弹幕 XML 内容 */
    @SerializedName("danmakuData")
    val danmakuData: String? = null,

    /** 是否有弹幕 */
    @SerializedName("hasDanmaku")
    val hasDanmaku: Boolean = false
)

/**
 * 设备信息。
 *
 * 用于接收端对外暴露自身标识与端口。
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
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): DeviceInfo? = try {
            Gson().fromJson(json, DeviceInfo::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
