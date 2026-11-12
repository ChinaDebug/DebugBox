package com.tvbox.kotlin.core.server.cast.server

import android.content.Context
import android.os.Build
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.server.cast.model.CastData
import com.tvbox.kotlin.core.server.cast.model.CastDevice
import com.tvbox.kotlin.core.server.cast.model.CastMessage
import com.tvbox.kotlin.core.server.cast.model.DeviceInfo
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * 投屏服务端。
 *
 * 基于 NanoHTTPD 接收来自其他设备的投屏与控制请求，
 * 并通过 OkHttp 向远端设备发送投屏消息。
 *
 * 路由：
 * - GET  /device/info    返回本机设备信息
 * - POST /cast/play      投屏播放请求
 * - POST /cast/control   播放控制（暂停/继续/停止/跳转）
 * - GET  /cast/status    获取接收端播放状态
 */
class CastServer(
    context: Context,
    private val listener: CastServerListener
) : NanoHTTPD(CastDevice.DEFAULT_CAST_PORT), CoroutineScope {

    private val appContext = context.applicationContext

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var isRunning = false

    /** 启动投屏服务 */
    fun startServer(): Boolean {
        return try {
            if (!isRunning) {
                start()
                isRunning = true
            }
            true
        } catch (e: Exception) {
            LOG.e(TAG, "投屏服务启动失败: ${e.message}")
            false
        }
    }

    /** 停止投屏服务，保留 job 不取消以便重启 */
    fun stopServer() {
        if (isRunning) {
            stop()
            isRunning = false
        }
    }

    /** 释放资源，彻底取消协程 */
    fun release() {
        stopServer()
        job.cancel()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: ""
        val method = session.method
        return when {
            uri == "/device/info" && method == Method.GET -> handleDeviceInfo()
            uri == "/cast/play" && method == Method.POST -> handlePlay(session)
            uri == "/cast/control" && method == Method.POST -> handleControl(session)
            uri == "/cast/status" && method == Method.GET -> handleGetStatus()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    /** 处理设备信息请求 */
    private fun handleDeviceInfo(): Response {
        val deviceInfo = DeviceInfo(
            name = deviceName(),
            type = CastDevice.DEVICE_TYPE_TVBOX,
            version = "1.0",
            port = CastDevice.DEFAULT_CAST_PORT
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", deviceInfo.toJson())
    }

    /** 投屏设备名：优先读取设置页配置的投屏设备名，缺省使用系统型号 */
    private fun deviceName(): String {
        return try {
            AppPreferences.getShared(appContext)
                .getString(AppConstants.CAST_DEVICE_NAME, Build.MODEL)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        } catch (t: Throwable) {
            Build.MODEL
        }
    }

    /** 处理播放请求 */
    private fun handlePlay(session: IHTTPSession): Response {
        return try {
            val body = readBody(session)
            val message = CastMessage.fromJson(body)
            if (message?.type == CastMessage.MessageType.PLAY) {
                launch(Dispatchers.Main) {
                    try {
                        listener.onPlayRequested(message.data)
                    } catch (e: Exception) {
                        LOG.e(TAG, "调用listener失败: ${e.message}")
                    }
                }
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
            } else {
                newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid message")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    /** 处理控制请求 */
    private fun handleControl(session: IHTTPSession): Response {
        return try {
            val body = readBody(session)
            val message = CastMessage.fromJson(body)
            launch(Dispatchers.Main) {
                when (message?.type) {
                    CastMessage.MessageType.PAUSE -> listener.onPauseRequested()
                    CastMessage.MessageType.RESUME -> listener.onResumeRequested()
                    CastMessage.MessageType.STOP -> listener.onStopRequested()
                    CastMessage.MessageType.SEEK -> listener.onSeekRequested(message.data?.position ?: 0)
                    else -> {}
                }
            }
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    /** 处理获取状态请求 */
    private fun handleGetStatus(): Response {
        val status = listener.onStatusRequested()
        val message = CastMessage(
            type = CastMessage.MessageType.STATUS_RESPONSE,
            data = status
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json", message.toJson())
    }

    /** 读取请求体 */
    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }

    /** 发送消息到指定设备 */
    fun sendMessage(device: CastDevice, message: CastMessage, callback: ((Boolean) -> Unit)? = null) {
        launch {
            var success = false
            try {
                val url = when (message.type) {
                    CastMessage.MessageType.PLAY ->
                        "http://${device.hostAddress}:${device.port}/cast/play"
                    else ->
                        "http://${device.hostAddress}:${device.port}/cast/control"
                }
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val requestBody = message.toJson().toRequestBody(mediaType)
                val request = Request.Builder().url(url).post(requestBody).build()
                client.newCall(request).execute().use { response ->
                    success = response.isSuccessful
                }
            } catch (e: Exception) {
                LOG.e(TAG, "发送消息失败: ${e.message}")
            }
            withContext(Dispatchers.Main) {
                callback?.invoke(success)
            }
        }
    }

    /** 投屏服务端监听器 */
    interface CastServerListener {
        fun onPlayRequested(data: CastData?)
        fun onPauseRequested()
        fun onResumeRequested()
        fun onStopRequested()
        fun onSeekRequested(position: Long)
        fun onStatusRequested(): CastData
    }

    companion object {
        private const val TAG = "CastServer"
    }
}
