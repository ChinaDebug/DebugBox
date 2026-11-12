package com.tvbox.kotlin.core.common

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 基于 SharedFlow 的全局事件总线。
 * 事件类型通过泛型区分，订阅方按类型过滤。
 */
object EventHub {

    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    fun post(event: AppEvent) {
        _events.tryEmit(event)
    }

    fun post(event: () -> AppEvent) {
        _events.tryEmit(event())
    }
}

/** 所有事件的基类型 */
interface AppEvent {
    val timestamp: Long
}

/** 携带类型的简单事件，用于解耦通用刷新/状态通知 */
data class TypedEvent(val type: String, val payload: Any? = null) : AppEvent {
    override val timestamp: Long = System.currentTimeMillis()
}
