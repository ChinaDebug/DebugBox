package com.tvbox.kotlin.core.server.cast.discovery

import android.content.Context
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.util.LocalIPAddress
import com.tvbox.kotlin.core.server.cast.model.CastDevice
import com.tvbox.kotlin.core.server.cast.model.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * 设备发现管理器。
 *
 * 使用协程扫描局域网内 1~254 的 IP，通过 GET /device/info 识别投屏接收端。
 * 扫描分批并行（每批 50 个），整体超时 15 秒。
 */
class DeviceDiscovery(private val context: Context) : CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val discoveredDevices = LinkedHashSet<CastDevice>()
    private var discoveryJob: Job? = null

    @Volatile
    private var listener: DeviceDiscoveryListener? = null

    /** 获取本机 IP 地址，用于过滤自身 */
    private fun getLocalIp(): String = LocalIPAddress.getLocalIPAddress(context)

    /** 开始设备发现 */
    fun startDiscovery(listener: DeviceDiscoveryListener) {
        this.listener = listener
        discoveryJob?.cancel()
        synchronized(discoveredDevices) { discoveredDevices.clear() }

        discoveryJob = launch {
            scanNetworkDevices()
        }
    }

    /** 停止设备发现 */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        listener = null
    }

    /** 扫描网络设备 */
    private suspend fun scanNetworkDevices() {
        val localIp = getLocalIp()
        val lastDot = localIp.lastIndexOf('.')
        if (lastDot < 0) {
            withContext(Dispatchers.Main) {
                listener?.onDiscoveryError("本机 IP 非法: $localIp")
            }
            return
        }
        val subnet = localIp.substring(0, lastDot + 1)

        try {
            // 整体扫描超时 15 秒
            withTimeout(15000) {
                val batchSize = 50
                val ipRange = (1..254).filter { "$subnet$it" != localIp }
                ipRange.chunked(batchSize).forEach { batch ->
                    val scanJobs = batch.map { i ->
                        async { scanDevice("$subnet$i") }
                    }
                    scanJobs.awaitAll()
                }
            }
            withContext(Dispatchers.Main) {
                listener?.onDiscoveryComplete()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            withContext(Dispatchers.Main) {
                listener?.onDiscoveryComplete()
            }
        } catch (e: Exception) {
            LOG.e(TAG, "设备扫描出错: ${e.message}")
            withContext(Dispatchers.Main) {
                listener?.onDiscoveryError(e.message ?: "扫描出错")
            }
        }
    }

    /** 扫描单个 IP */
    private suspend fun scanDevice(ip: String) {
        try {
            val request = Request.Builder()
                .url("http://$ip:${CastDevice.DEFAULT_CAST_PORT}/device/info")
                .get()
                .build()
            val response = withTimeoutOrNull(2000) {
                client.newCall(request).execute()
            } ?: return
            response.use { resp ->
                if (!resp.isSuccessful) return
                val body = resp.body?.string() ?: return
                val info = DeviceInfo.fromJson(body) ?: return
                // 再次确认不是本机
                if (ip == getLocalIp()) return
                val device = CastDevice(
                    name = info.name,
                    hostAddress = ip,
                    port = info.port,
                    deviceType = parseDeviceType(info.type)
                )
                synchronized(discoveredDevices) {
                    if (discoveredDevices.add(device)) {
                        launch(Dispatchers.Main) {
                            listener?.onDeviceFound(device)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 忽略连接失败的设备
        }
    }

    /** 解析设备类型 */
    private fun parseDeviceType(type: String): CastDevice.DeviceType {
        return when (type.lowercase()) {
            CastDevice.DEVICE_TYPE_TVBOX -> CastDevice.DeviceType.TVBOX
            CastDevice.DEVICE_TYPE_DLNA -> CastDevice.DeviceType.DLNA
            CastDevice.DEVICE_TYPE_AIRPLAY -> CastDevice.DeviceType.AIRPLAY
            CastDevice.DEVICE_TYPE_CHROMECAST -> CastDevice.DeviceType.CHROMECAST
            else -> CastDevice.DeviceType.UNKNOWN
        }
    }

    /** 获取已发现的设备列表 */
    fun getDiscoveredDevices(): List<CastDevice> =
        synchronized(discoveredDevices) { discoveredDevices.toList() }

    /** 释放资源 */
    fun release() {
        stopDiscovery()
        job.cancel()
    }

    /** 设备发现监听器 */
    interface DeviceDiscoveryListener {
        fun onDeviceFound(device: CastDevice)
        fun onDiscoveryComplete()
        fun onDiscoveryError(error: String)
    }

    companion object {
        private const val TAG = "DeviceDiscovery"
    }
}
