package com.tvbox.kotlin.core.server

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream

/**
 * 静态资源请求处理器。
 *
 * RawRequestProcess.java 直接通过 R.raw.* 资源 ID 加载，
 * server 模块不持有 app/ui 资源，改由 [ServerResources] 注入资源流。
 *
 * @param resources 静态资源提供者
 */
class RawRequestProcess(private val resources: ServerResources) : RequestProcess {

    override fun isRequest(session: NanoHTTPD.IHTTPSession, fileName: String): Boolean {
        // 仅处理 GET 且能命中静态资源的请求
        return session.method == NanoHTTPD.Method.GET && resources.find(fileName) != null
    }

    override fun doResponse(
        session: NanoHTTPD.IHTTPSession,
        fileName: String,
        params: Map<String, String>,
        files: Map<String, String>
    ): NanoHTTPD.Response {
        val resource = resources.find(fileName)
            ?: return RemoteServer.createPlainTextResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "Not Found: $fileName"
            )
        var stream: InputStream? = null
        return try {
            stream = resource.openStream()
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "${resource.mimeType}; charset=utf-8",
                stream,
                stream.available().toLong()
            )
        } catch (e: IOException) {
            stream?.close()
            RemoteServer.createPlainTextResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "SERVER INTERNAL ERROR: IOException: ${e.message}"
            )
        }
    }
}
