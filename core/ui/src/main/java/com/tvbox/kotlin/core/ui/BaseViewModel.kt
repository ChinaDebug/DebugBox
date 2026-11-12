package com.tvbox.kotlin.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * ViewModel 基类，提供统一的异常处理与全局消息分发。
 */
abstract class BaseViewModel : ViewModel() {

    /** 向界面分发的错误信息 */
    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val error: SharedFlow<String> = _error.asSharedFlow()

    /** 向界面分发的提示信息 */
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    protected val handler = CoroutineExceptionHandler { _, t ->
        val msg = t.message ?: UiResources.string(com.tvbox.kotlin.core.ui.R.string.unknown_error)
        viewModelScope.launch { _error.emit(msg) }
    }

    /** 在 IO 线程执行耗时任务，异常统一交给 handler；返回 Job 供调用方取消 */
    protected fun launchIO(block: suspend () -> Unit): Job {
        return viewModelScope.launch(Dispatchers.IO + handler) { block() }
    }

    protected fun toast(msg: String) {
        viewModelScope.launch { _toast.emit(msg) }
    }

    protected fun error(msg: String) {
        viewModelScope.launch { _error.emit(msg) }
    }
}
