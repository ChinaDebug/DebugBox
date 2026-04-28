package com.github.tvbox.osc.cast.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.github.tvbox.osc.cast.model.CastDevice
import com.github.tvbox.osc.cast.model.DeviceInfo
import com.github.tvbox.osc.util.LocalIPAddress
import com.github.tvbox.osc.util.LOG
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * 设备发现管理器
 * 使用协程进行异步设备扫描
 */
class DeviceDiscovery(private val context: Context) : CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val discoveredDevices = mutableSetOf<CastDevice>()
    private var discoveryJob: Job? = null
    private var listener: DeviceDiscoveryListener? = null

    /**
     * 获取本机IP地址，用于过滤
     */
    private fun getLocalIp(): String {
        return LocalIPAddress.getLocalIPAddress(context)
    }

    /**
     * 开始设备发现
     */
    fun startDiscovery(listener: DeviceDiscoveryListener) {
        this.listener = listener
        discoveryJob?.cancel()
        discoveredDevices.clear()

        discoveryJob = launch {
            scanNetworkDevices()
        }
    }

    /**
     * 停止设备发现
     */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        listener = null
    }

    /**
     * 扫描网络设备
     */
    private suspend fun scanNetworkDevices() {
        val localIp = getLocalIp()
        val subnet = localIp.substring(0, localIp.lastIndexOf('.') + 1)

        LOG.d("DeviceDiscovery", "开始扫描网络，本机IP: $localIp，网段: $subnet")

        try {
            // 设置整体扫描超时时间为15秒
            withTimeout(15000) {
                // 创建并行扫描任务，分批执行避免同时创建过多协程
                val batchSize = 50
                val ipRange = (1..254).filter { "$subnet$it" != localIp }

                ipRange.chunked(batchSize).forEach { batch ->
                    val scanJobs = batch.map { i ->
                        async {
                            val ip = "$subnet$i"
                            scanDevice(ip)
                        }
                    }
                    // 等待每批扫描完成
                    scanJobs.awaitAll()
                }
            }

            LOG.d("DeviceDiscovery", "设备扫描完成，发现 ${discoveredDevices.size} 个设备")
            withContext(Dispatchers.Main) {
                listener?.onDiscoveryComplete()
            }
        } catch (e: TimeoutCancellationException) {
            LOG.d("DeviceDiscovery", "设备扫描超时，发现 ${discoveredDevices.size} 个设备")
            withContext(Dispatchers.Main) {
                listener?.onDiscoveryComplete()
            }
        } catch (e: Exception) {
            LOG.e("DeviceDiscovery", "设备扫描出错: ${e.message}")
            withContext(Dispatchers.Main) {
                listener?.onDiscoveryError(e.message ?: "扫描出错")
            }
        }
    }

    /**
     * 扫描单个IP
     */
    private suspend fun scanDevice(ip: String) {
        try {
            val request = Request.Builder()
                .url("http://$ip:${CastDevice.DEFAULT_CAST_PORT}/device/info")
                .get()
                .build()

            val response = withTimeoutOrNull(2000) {
                client.newCall(request).execute()
            }

            if (response?.isSuccessful == true) {
                val body = response.body()?.string()
                if (!body.isNullOrEmpty()) {
                    val deviceInfo = DeviceInfo.fromJson(body)
                    deviceInfo?.let { info ->
                        val device = CastDevice(
                            name = info.name,
                            hostAddress = ip,
                            port = info.port,
                            deviceType = parseDeviceType(info.type)
                        )

                        // 再次确认不是本机
                        if (ip != getLocalIp()) {
                            discoveredDevices.add(device)
                            withContext(Dispatchers.Main) {
                                listener?.onDeviceFound(device)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略连接失败的设备
        }
    }

    /**
     * 解析设备类型
     */
    private fun parseDeviceType(type: String): CastDevice.DeviceType {
        return when (type.lowercase()) {
            CastDevice.DEVICE_TYPE_TVBOX -> CastDevice.DeviceType.TVBOX
            CastDevice.DEVICE_TYPE_DLNA -> CastDevice.DeviceType.DLNA
            CastDevice.DEVICE_TYPE_AIRPLAY -> CastDevice.DeviceType.AIRPLAY
            CastDevice.DEVICE_TYPE_CHROMECAST -> CastDevice.DeviceType.CHROMECAST
            else -> CastDevice.DeviceType.UNKNOWN
        }
    }

    /**
     * 获取已发现的设备列表
     */
    fun getDiscoveredDevices(): List<CastDevice> {
        return discoveredDevices.toList()
    }

    /**
     * 释放资源
     */
    fun release() {
        stopDiscovery()
        job.cancel()
    }

    interface DeviceDiscoveryListener {
        fun onDeviceFound(device: CastDevice)
        fun onDiscoveryComplete()
        fun onDiscoveryError(error: String)
    }
}
