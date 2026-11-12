package com.tvbox.kotlin.core.server.cast

import android.content.Context
import com.tvbox.kotlin.core.server.cast.discovery.DeviceDiscovery
import com.tvbox.kotlin.core.server.cast.model.CastData
import com.tvbox.kotlin.core.server.cast.model.CastDevice
import com.tvbox.kotlin.core.common.LOG

/**
 * 投屏桥接类。
 *
 * 为上层提供投屏功能的统一访问入口，内部委托 [CastManager]，
 * 将 Kotlin 函数式回调适配为 Java 友好的接口回调。
 */
class CastBridge private constructor(context: Context) {

    private val castManager: CastManager = CastManager.getInstance(context)

    /** 启动投屏服务（接收端） */
    fun startCastServer(): Boolean = castManager.startCastServer()

    /** 停止投屏服务 */
    fun stopCastServer() {
        castManager.stopCastServer()
    }

    /** 开始搜索设备 */
    fun startDeviceDiscovery(callback: DeviceDiscoveryCallback?) {
        castManager.startDeviceDiscovery(object : DeviceDiscovery.DeviceDiscoveryListener {
            override fun onDeviceFound(device: CastDevice) {
                callback?.onDeviceFound(device)
            }

            override fun onDiscoveryComplete() {
                callback?.onDiscoveryComplete()
            }

            override fun onDiscoveryError(error: String) {
                callback?.onDiscoveryError(error)
            }
        })
    }

    /** 停止搜索设备 */
    fun stopDeviceDiscovery() {
        castManager.stopDeviceDiscovery()
    }

    /** 获取已发现的设备列表 */
    fun getDiscoveredDevices(): List<CastDevice> = castManager.getDiscoveredDevices()

    /** 投屏到指定设备 */
    fun castToDevice(device: CastDevice, data: CastData, callback: CastCallback?) {
        castManager.castToDevice(device, data) { success ->
            callback?.onResult(success)
        }
    }

    /** 暂停播放 */
    fun pauseCast(callback: CastCallback?) {
        castManager.controlPlay(CastManager.ControlAction.Pause) { success ->
            callback?.onResult(success)
        }
    }

    /** 继续播放 */
    fun resumeCast(callback: CastCallback?) {
        castManager.controlPlay(CastManager.ControlAction.Resume) { success ->
            callback?.onResult(success)
        }
    }

    /** 停止播放 */
    fun stopCast(callback: CastCallback?) {
        castManager.controlPlay(CastManager.ControlAction.Stop) { success ->
            callback?.onResult(success)
        }
    }

    /** 跳转播放位置 */
    fun seekCast(position: Long, callback: CastCallback?) {
        castManager.controlPlay(CastManager.ControlAction.Seek(position)) { success ->
            callback?.onResult(success)
        }
    }

    /** 断开投屏连接 */
    fun disconnect() {
        castManager.disconnect()
    }

    /** 设置投屏监听器 */
    fun setCastListener(listener: CastListener?) {
        castManager.setCastListener(object : CastManager.CastListener {
            override fun onReceivePlay(data: CastData?) {
                try {
                    listener?.onReceivePlay(data)
                } catch (e: Exception) {
                    LOG.e(TAG, "onReceivePlay error: ${e.message}", e)
                }
            }

            override fun onReceivePause() {
                try {
                    listener?.onReceivePause()
                } catch (e: Exception) {
                    LOG.e(TAG, "onReceivePause error: ${e.message}", e)
                }
            }

            override fun onReceiveResume() {
                try {
                    listener?.onReceiveResume()
                } catch (e: Exception) {
                    LOG.e(TAG, "onReceiveResume error: ${e.message}", e)
                }
            }

            override fun onReceiveStop() {
                try {
                    listener?.onReceiveStop()
                } catch (e: Exception) {
                    LOG.e(TAG, "onReceiveStop error: ${e.message}", e)
                }
            }

            override fun onReceiveSeek(position: Long) {
                try {
                    listener?.onReceiveSeek(position)
                } catch (e: Exception) {
                    LOG.e(TAG, "onReceiveSeek error: ${e.message}", e)
                }
            }

            override fun onStatusRequested(): CastData {
                return try {
                    listener?.onStatusRequested() ?: CastData()
                } catch (e: Exception) {
                    LOG.e(TAG, "onStatusRequested error: ${e.message}", e)
                    CastData()
                }
            }
        })
    }

    /** 设备发现回调接口 */
    interface DeviceDiscoveryCallback {
        fun onDeviceFound(device: CastDevice)
        fun onDiscoveryComplete()
        fun onDiscoveryError(error: String)
    }

    /** 投屏操作回调接口 */
    interface CastCallback {
        fun onResult(success: Boolean)
    }

    /** 投屏接收监听器接口 */
    interface CastListener {
        fun onReceivePlay(data: CastData?)
        fun onReceivePause()
        fun onReceiveResume()
        fun onReceiveStop()
        fun onReceiveSeek(position: Long)
        fun onStatusRequested(): CastData
    }

    companion object {
        private const val TAG = "CastBridge"

        @Volatile
        private var instance: CastBridge? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): CastBridge {
            if (instance == null) {
                instance = CastBridge(context.applicationContext)
            }
            return instance!!
        }

        /** 释放资源 */
        @JvmStatic
        fun release() {
            CastManager.releaseInstance()
            instance = null
        }
    }
}
