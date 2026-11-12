package com.tvbox.kotlin.core.common.event

import com.tvbox.kotlin.core.common.AppEvent

/** 刷新事件，用于通知界面刷新数据 */
data class RefreshEvent(val type: Int, val obj: Any? = null) : AppEvent {
    override val timestamp: Long = System.currentTimeMillis()

    companion object {
        const val TYPE_APP_REFRESH = 0
        const val TYPE_HOME = 1
        const val TYPE_DETAIL = 2
        const val TYPE_HISTORY = 3
        const val TYPE_COLLECT = 4
        const val TYPE_LIVE = 5
        const val TYPE_SETTING = 6
        const val TYPE_SEARCH = 7
        const val TYPE_HOME_REC_CHANGE = 8
    }
}

/** 服务端事件 */
data class ServerEvent(val action: String, val data: String? = null) : AppEvent {
    override val timestamp: Long = System.currentTimeMillis()
}
