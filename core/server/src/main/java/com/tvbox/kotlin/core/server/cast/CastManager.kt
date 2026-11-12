package com.tvbox.kotlin.core.server.cast

import android.content.Context
import com.tvbox.kotlin.core.server.cast.discovery.DeviceDiscovery
import com.tvbox.kotlin.core.server.cast.model.CastData
import com.tvbox.kotlin.core.server.cast.model.CastDevice
import com.tvbox.kotlin.core.server.cast.model.CastMessage
import com.tvbox.kotlin.core.server.cast.server.CastServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 投屏管理器。
 *
 * 统一管理投屏的发送与接收：内部持有 [CastServer] 监听远端请求，
 * 通过 [DeviceDiscovery] 扫描局域网设备，对外暴露状态流 [castState]。
 *
 * 进程内单例，通过 [getInstance] 获取。
 */
class CastManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val castServer: CastServer
    private val deviceDiscovery: DeviceDiscovery

    // 当前投屏状态
    private val _castState = MutableStateFlow<CastState>(CastState.Idle)
    val castState: StateFlow<CastState> = _castState.asStateFlow()

    // 当前连接的设备
    @Volatile
    private var currentDevice: CastDevice? = null

    // 投屏监听器
    @Volatile
    private var castListener: CastListener? = null

    init {
        castServer = CastServer(appContext, object : CastServer.CastServerListener {
            override fun onPlayRequested(data: CastData?) {
                castListener?.onReceivePlay(data)
            }

            override fun onPauseRequested() {
                castListener?.onReceivePause()
            }

            override fun onResumeRequested() {
                castListener?.onReceiveResume()
            }

            override fun onStopRequested() {
                _castState.value = CastState.Idle
                castListener?.onReceiveStop()
            }

            override fun onSeekRequested(position: Long) {
                castListener?.onReceiveSeek(position)
            }

            override fun onStatusRequested(): CastData {
                return castListener?.onStatusRequested() ?: CastData()
            }
        })
        deviceDiscovery = DeviceDiscovery(appContext)
    }

    /** 启动投屏服务（接收端） */
    fun startCastServer(): Boolean = castServer.startServer()

    /** 停止投屏服务 */
    fun stopCastServer() {
        castServer.stopServer()
    }

    /** 开始搜索设备（发送端） */
    fun startDeviceDiscovery(listener: DeviceDiscovery.DeviceDiscoveryListener) {
        deviceDiscovery.startDiscovery(listener)
    }

    /** 停止搜索设备 */
    fun stopDeviceDiscovery() {
        deviceDiscovery.stopDiscovery()
    }

    /** 获取已发现的设备列表 */
    fun getDiscoveredDevices(): List<CastDevice> = deviceDiscovery.getDiscoveredDevices()

    /** 投屏到指定设备 */
    fun castToDevice(device: CastDevice, data: CastData, callback: ((Boolean) -> Unit)? = null) {
        currentDevice = device
        _castState.value = CastState.Connecting(device)
        val message = CastMessage(type = CastMessage.MessageType.PLAY, data = data)
        castServer.sendMessage(device, message) { success ->
            if (success) {
                _castState.value = CastState.Connected(device, data)
            } else {
                _castState.value = CastState.Error("投屏失败")
                currentDevice = null
            }
            callback?.invoke(success)
        }
    }

    /** 控制播放 */
    fun controlPlay(action: ControlAction, callback: ((Boolean) -> Unit)? = null) {
        val device = currentDevice ?: run {
            callback?.invoke(false)
            return
        }
        val messageType = when (action) {
            is ControlAction.Pause -> CastMessage.MessageType.PAUSE
            is ControlAction.Resume -> CastMessage.MessageType.RESUME
            is ControlAction.Stop -> CastMessage.MessageType.STOP
            is ControlAction.Seek -> CastMessage.MessageType.SEEK
        }
        val data = when (action) {
            is ControlAction.Seek -> CastData(position = action.position)
            else -> null
        }
        val message = CastMessage(type = messageType, data = data)
        castServer.sendMessage(device, message, callback)
    }

    /** 断开投屏连接 */
    fun disconnect() {
        currentDevice?.let { device ->
            val message = CastMessage(type = CastMessage.MessageType.STOP)
            castServer.sendMessage(device, message)
        }
        currentDevice = null
        _castState.value = CastState.Idle
    }

    /** 设置投屏监听器 */
    fun setCastListener(listener: CastListener) {
        this.castListener = listener
    }

    /** 释放资源 */
    fun release() {
        disconnect()
        castServer.release()
        deviceDiscovery.release()
        castListener = null
    }

    /** 投屏状态 */
    sealed class CastState {
        /** 空闲 */
        object Idle : CastState()

        /** 连接中 */
        data class Connecting(val device: CastDevice) : CastState()

        /** 已连接 */
        data class Connected(val device: CastDevice, val data: CastData) : CastState()

        /** 错误 */
        data class Error(val message: String) : CastState()
    }

    /** 控制动作 */
    sealed class ControlAction {
        /** 暂停 */
        object Pause : ControlAction()

        /** 继续 */
        object Resume : ControlAction()

        /** 停止 */
        object Stop : ControlAction()

        /** 跳转到指定位置 */
        data class Seek(val position: Long) : ControlAction()
    }

    /** 投屏监听器 */
    interface CastListener {
        fun onReceivePlay(data: CastData?)
        fun onReceivePause()
        fun onReceiveResume()
        fun onReceiveStop()
        fun onReceiveSeek(position: Long)
        fun onStatusRequested(): CastData
    }

    companion object {
        @Volatile
        private var instance: CastManager? = null

        @JvmStatic
        fun getInstance(context: Context): CastManager {
            return instance ?: synchronized(this) {
                instance ?: CastManager(context).also { instance = it }
            }
        }

        @JvmStatic
        fun releaseInstance() {
            instance?.release()
            instance = null
        }
    }
}
