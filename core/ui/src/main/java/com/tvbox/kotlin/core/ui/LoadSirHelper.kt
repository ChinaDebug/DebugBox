package com.tvbox.kotlin.core.ui

import android.view.View
import com.kingja.loadsir.callback.Callback
import com.kingja.loadsir.core.LoadService
import com.kingja.loadsir.core.LoadSir

/**
 * LoadSir 加载/空/错误态集成辅助。
 *
 * 业务接入示例：
 * ```kotlin
 * val service = LoadSirHelper.register(rootView, callbacks = listOf(
 *     LoadingCallback(),
 *     EmptyCallback(),
 *     ErrorCallback()
 * )) { viewModel.retry() }
 * service.showCallback(LoadingCallback::class.java)
 * service.showSuccess()
 * ```
 *
 * 注：LoadingCallback / EmptyCallback / ErrorCallback 需业务方继承 [Callback] 自定义实现，
 * 关联布局放在调用方模块的 res/layout 下。
 */
object LoadSirHelper {

    /**
     * 注册 LoadSir 到目标 View，返回 [LoadService] 用于状态切换。
     *
     * @param target 待替换的目标 View
     * @param callbacks 业务自定义的状态回调列表（loading/empty/error 等）
     * @param onRetry 重试回调，由各 Callback 内部点击事件触发
     */
    fun register(
        target: View,
        callbacks: List<Callback>,
        onRetry: (() -> Unit)? = null
    ): LoadService<*> {
        val builder = LoadSir.beginBuilder()
        callbacks.forEach { builder.addCallback(it) }
        builder.commit()
        val listener = if (onRetry != null) {
            Callback.OnReloadListener { onRetry.invoke() }
        } else {
            null
        }
        return LoadSir.getDefault().register(target, listener)
    }
}
