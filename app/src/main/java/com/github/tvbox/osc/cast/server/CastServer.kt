package com.github.tvbox.osc.cast.server

import android.content.Context
import com.github.tvbox.osc.cast.model.CastData
import com.github.tvbox.osc.cast.model.CastDevice
import com.github.tvbox.osc.cast.model.CastMessage
import com.github.tvbox.osc.cast.model.DeviceInfo
import com.github.tvbox.osc.util.LOG
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * 投屏服务端
 * 接收来自其他设备的投屏请求
 */
class CastServer(
    private val context: Context,
    private val listener: CastServerListener
) : NanoHTTPD(CastDevice.DEFAULT_CAST_PORT), CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var isRunning = false

    /**
     * 启动投屏服务
     */
    fun startServer(): Boolean {
        return try {
            if (!isRunning) {
                start()
                isRunning = true
                LOG.d("CastServer", "投屏服务启动成功，端口: ${CastDevice.DEFAULT_CAST_PORT}")
                true
            } else {
                true
            }
        } catch (e: Exception) {
            LOG.e("CastServer", "投屏服务启动失败: ${e.message}")
            false
        }
    }

    /**
     * 停止投屏服务
     */
    fun stopServer() {
        if (isRunning) {
            stop()
            isRunning = false
            job.cancel()
            LOG.d("CastServer", "投屏服务已停止")
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return when {
            uri == "/device/info" && method == Method.GET -> {
                handleDeviceInfo()
            }
            uri == "/cast/play" && method == Method.POST -> {
                handlePlay(session)
            }
            uri == "/cast/control" && method == Method.POST -> {
                handleControl(session)
            }
            uri == "/cast/status" && method == Method.GET -> {
                handleGetStatus()
            }
            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }
    }

    /**
     * 处理设备信息请求
     */
    private fun handleDeviceInfo(): Response {
        val deviceInfo = DeviceInfo(
            name = android.os.Build.MODEL,
            type = CastDevice.DEVICE_TYPE_TVBOX,
            version = "1.0",
            port = CastDevice.DEFAULT_CAST_PORT
        )
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            deviceInfo.toJson()
        )
    }

    /**
     * 处理播放请求
     */
    private fun handlePlay(session: IHTTPSession): Response {
        return try {
            val body = readBody(session)
            LOG.d("CastServer", "收到播放请求，body: $body")
            val message = CastMessage.fromJson(body)

            if (message?.type == CastMessage.MessageType.PLAY) {
                LOG.d("CastServer", "消息类型正确，data: ${message.data}")
                launch(Dispatchers.Main) {
                    try {
                        LOG.d("CastServer", "在主线程调用listener")
                        listener.onPlayRequested(message.data)
                        LOG.d("CastServer", "listener调用完成")
                    } catch (e: Exception) {
                        LOG.e("CastServer", "调用listener失败: ${e.message}")
                        e.printStackTrace()
                    }
                }
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
            } else {
                LOG.e("CastServer", "消息类型错误: ${message?.type}")
                newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid message")
            }
        } catch (e: Exception) {
            LOG.e("CastServer", "处理播放请求失败: ${e.message}")
            e.printStackTrace()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    /**
     * 处理控制请求
     */
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
                    CastMessage.MessageType.GET_STATUS -> {
                        // 状态请求，由调用方处理
                    }
                    else -> {}
                }
            }
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    /**
     * 处理获取状态请求
     */
    private fun handleGetStatus(): Response {
        val status = listener.onStatusRequested()
        val message = CastMessage(
            type = CastMessage.MessageType.STATUS_RESPONSE,
            data = status
        )
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            message.toJson()
        )
    }

    /**
     * 读取请求体
     */
    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }

    /**
     * 发送消息到指定设备
     */
    fun sendMessage(device: CastDevice, message: CastMessage, callback: ((Boolean) -> Unit)? = null) {
        launch {
            var success = false
            try {
                val url = when (message.type) {
                    CastMessage.MessageType.PLAY -> "http://${device.hostAddress}:${device.port}/cast/play"
                    else -> "http://${device.hostAddress}:${device.port}/cast/control"
                }

                val mediaType = MediaType.parse("application/json; charset=utf-8")
                val requestBody = RequestBody.create(mediaType, message.toJson())

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                success = response.isSuccessful
                response.close()
            } catch (e: Exception) {
                LOG.e("CastServer", "发送消息失败: ${e.message}")
            }
            withContext(Dispatchers.Main) {
                callback?.invoke(success)
            }
        }
    }

    interface CastServerListener {
        fun onPlayRequested(data: CastData?)
        fun onPauseRequested()
        fun onResumeRequested()
        fun onStopRequested()
        fun onSeekRequested(position: Long)
        fun onStatusRequested(): CastData
    }
}
