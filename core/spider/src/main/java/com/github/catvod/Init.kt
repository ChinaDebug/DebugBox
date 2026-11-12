package com.github.catvod

import android.content.Context
import java.lang.ref.WeakReference

/**
 * catvod 初始化入口，持有应用 Context。
 * jar 内爬虫通过 [context] 获取主进程上下文，由 Application 启动时调用 [set] 注入。
 * 保留 com.github.catvod 包名以兼容旧 jar 爬虫对 com.github.catvod.Init 的反射调用。
 */
object Init {

    @Volatile
    private var contextRef: WeakReference<Context>? = null

    @JvmStatic
    fun set(context: Context?) {
        contextRef = if (context != null) WeakReference(context) else null
    }

    @JvmStatic
    fun context(): Context? = contextRef?.get()
}
