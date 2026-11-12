package com.tvbox.kotlin.core.common.bean

/** 单个直播频道 */
class LiveChannelItem {
    /** 频道索引号 */
    var channelIndex: Int = 0

    /** 频道号 */
    var channelNum: Int = 0

    /** 频道名称 */
    var channelName: String? = null

    /** 频道自带台标地址（m3u 源 #EXTINF 的 tvg-logo / JSON 源 logo 字段），优先于订阅地址 logo 字段 */
    var tvgLogo: String? = null

    var channelSourceNames: ArrayList<String>? = null

    var channelUrls: ArrayList<String>? = null
        set(value) {
            field = value
            sourceNum = value?.size ?: 0
        }

    var sourceIndex: Int = 0
    var sourceNum: Int = 0
    var include_back: Boolean = false

    fun preSource() {
        sourceIndex--
        if (sourceIndex < 0) sourceIndex = sourceNum - 1
    }

    fun nextSource() {
        sourceIndex++
        if (sourceIndex == sourceNum) sourceIndex = 0
    }

    fun getUrl(): String? = channelUrls?.getOrNull(sourceIndex)

    fun getSourceName(): String? = channelSourceNames?.getOrNull(sourceIndex)

    /** 浅拷贝：换源复用同一对象时 StateFlow 引用相等不发射，拷贝新实例用于强制发射刷新 UI */
    fun copy(): LiveChannelItem = LiveChannelItem().apply {
        channelIndex = this@LiveChannelItem.channelIndex
        channelNum = this@LiveChannelItem.channelNum
        channelName = this@LiveChannelItem.channelName
        tvgLogo = this@LiveChannelItem.tvgLogo
        channelSourceNames = this@LiveChannelItem.channelSourceNames
        channelUrls = this@LiveChannelItem.channelUrls
        sourceIndex = this@LiveChannelItem.sourceIndex
        sourceNum = this@LiveChannelItem.sourceNum
        include_back = this@LiveChannelItem.include_back
    }
}
