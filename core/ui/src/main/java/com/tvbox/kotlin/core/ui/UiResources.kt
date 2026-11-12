package com.tvbox.kotlin.core.ui

import android.content.Context

/**
 * core/ui 模块的全局 Context 入口。
 * 在 Application 启动时通过 [install] 注册，供 ViewModel 等无法注入 Context 的场景读取字符串资源。
 */
object UiResources {

    @Volatile
    private var appContext: Context? = null

    /** 注册 Application Context（Application 启动时调用一次） */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** 读取字符串资源，未初始化时抛出明确错误 */
    fun string(id: Int): String =
        (appContext ?: error("UiResources 未初始化，请先在 Application 中调用 install")).getString(id)
}
