package com.tvbox.kotlin.feature.push.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.tvbox.kotlin.core.server.ControlManager
import com.tvbox.kotlin.core.ui.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 推屏页 ViewModel。
 *
 * 职责：拉取本地服务地址（[refresh]），供推屏页展示二维码与地址。
 * 服务未启动时地址为空，由界面层处理空态提示。
 */
@HiltViewModel
class PushViewModel @Inject constructor(
    private val controlManager: ControlManager,
    savedStateHandle: SavedStateHandle
) : BaseViewModel() {

    /** 局域网推屏地址 */
    private val _address = MutableStateFlow("")
    val address: StateFlow<String> = _address.asStateFlow()

    /** 服务是否运行 */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    init {
        refresh()
    }

    /** 重新拉取本地服务地址，服务未启动时返回空字符串 */
    fun refresh() {
        val running = controlManager.isServerRunning()
        _running.value = running
        _address.value = if (running) controlManager.getAddress(false) else ""
    }
}
