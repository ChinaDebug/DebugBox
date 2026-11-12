package com.tvbox.kotlin.core.server

import com.tvbox.kotlin.core.common.util.UrlUtils
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * 按键与输入请求处理器。
 *
 * 接收远端推送的搜索词、接口地址、媒体地址等，通过 [DataReceiver] 转发到业务层。
 *
 * @param remoteServer 持有 [DataReceiver] 的本地服务实例
 * @param host 外部依赖（源查找等）
 */
class InputRequestProcess(
    private val remoteServer: RemoteServer,
    private val host: ServerHost
) : RequestProcess {

    override fun isRequest(session: NanoHTTPD.IHTTPSession, fileName: String): Boolean {
        // 爬虫异步生成弹幕后以 GET 方式回调 /action?do=refresh，故 GET/POST 均需受理
        return (session.method == NanoHTTPD.Method.POST || session.method == NanoHTTPD.Method.GET) &&
            fileName == "/action"
    }

    override fun doResponse(
        session: NanoHTTPD.IHTTPSession,
        fileName: String,
        params: Map<String, String>,
        files: Map<String, String>
    ): NanoHTTPD.Response {
        val dataReceiver = remoteServer.getDataReceiver()
        if (fileName == "/action" && params["do"] != null && dataReceiver != null) {
            when (params["do"]) {
                "search" -> {
                    dataReceiver.onTextReceived(params["word"]?.trim())
                }
                "api" -> {
                    dataReceiver.onApiReceived(params["url"]?.trim())
                }
                "live" -> {
                    dataReceiver.onLiveReceived(params["url"]?.trim())
                }
                "epg" -> {
                    dataReceiver.onEpgReceived(params["url"]?.trim())
                }
                "push" -> {
                    return handlePush(params, dataReceiver)
                }
                "mirror" -> {
                    return handleMirror(params, dataReceiver)
                }
                // 爬虫异步生成弹幕后的刷新回调：type=danmaku 时 path 为可直接加载的弹幕地址
                "refresh" -> {
                    if (params["type"] == "danmaku") dataReceiver.onDanmuReceived(params["path"]?.trim())
                }
                // 订阅设置界面"提交全部并重载"：统一保存全部地址并重载
                "submit_all" -> {
                    dataReceiver.onSubmitAll(
                        params["api"]?.trim(),
                        params["live"]?.trim(),
                        params["epg"]?.trim()
                    )
                }
            }
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "ok")
        }
        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Error 404, file not found.")
    }

    /** 处理推送地址 */
    private fun handlePush(params: Map<String, String>, dataReceiver: DataReceiver): NanoHTTPD.Response {
        val rawUrl = params["url"]
        if (rawUrl.isNullOrBlank()) {
            val errResult = JSONObject()
            errResult.put("status", "error")
            errResult.put("message", "推送地址不能为空")
            return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.BAD_REQUEST, errResult.toString())
        }
        val pushUrl = rawUrl.trim()
        dataReceiver.onPushReceived(pushUrl)

        val isDirectPlayUrl = UrlUtils.isDirectPlayUrl(pushUrl)
        val hasPushAgent = host.hasPushAgent()
        val pushType: String
        val message: String
        when {
            isDirectPlayUrl -> {
                pushType = "direct"
                message = "已推送，直链地址将直接播放"
            }
            hasPushAgent -> {
                pushType = "detail"
                message = "已推送给 push_agent 源解析"
            }
            else -> {
                pushType = "unsupported"
                message = "检测到当前站点中未配置含 push_agent 的源，无法推送该地址"
            }
        }

        val result = JSONObject()
        result.put("status", "ok")
        result.put("hasPushAgent", hasPushAgent)
        result.put("pushType", pushType)
        result.put("message", message)
        return RemoteServer.createJSONResponse(NanoHTTPD.Response.Status.OK, result.toString())
    }

    /** 处理镜像推送 */
    private fun handleMirror(params: Map<String, String>, dataReceiver: DataReceiver): NanoHTTPD.Response {
        val id = params["id"] ?: return RemoteServer.createPlainTextResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST, "missing_params"
        )
        val sourceKey = params["sourceKey"] ?: return RemoteServer.createPlainTextResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST, "missing_params"
        )
        if (!host.hasSource(sourceKey.trim())) {
            return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "source_not_found")
        }
        dataReceiver.onMirrorReceived(id.trim(), sourceKey.trim())
        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.OK, "mirrored")
    }
}
