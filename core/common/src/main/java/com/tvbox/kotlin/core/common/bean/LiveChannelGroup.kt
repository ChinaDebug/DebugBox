package com.tvbox.kotlin.core.common.bean

/** 直播频道分组 */
class LiveChannelGroup {
    /** 分组索引号 */
    var groupIndex: Int = 0

    /** 分组名称 */
    var groupName: String? = null

    /** 分组密码 */
    var groupPassword: String? = null

    /** 分组级回看配置（FongMi 直播源 catchup 字段），频道未单独配置时继承 */
    var catchup: LiveCatchup? = null

    private var liveChannelItems: ArrayList<LiveChannelItem>? = null

    fun getLiveChannels(): ArrayList<LiveChannelItem>? = liveChannelItems

    fun setLiveChannels(liveChannelItems: ArrayList<LiveChannelItem>?) {
        this.liveChannelItems = liveChannelItems
    }
}
