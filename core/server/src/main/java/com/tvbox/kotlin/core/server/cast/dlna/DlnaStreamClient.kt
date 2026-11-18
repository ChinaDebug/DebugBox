package com.tvbox.kotlin.core.server.cast.dlna

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.fourthline.cling.model.message.StreamRequestMessage
import org.fourthline.cling.model.message.StreamResponseMessage
import org.fourthline.cling.model.message.UpnpHeaders
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.transport.spi.AbstractStreamClient
import org.fourthline.cling.transport.spi.AbstractStreamClientConfiguration
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * cling 的 OkHttp 流客户端。
 *
 * cling 默认客户端依赖 Apache HttpClient，Android 上不可用；
 * 此处以 OkHttp 承接 SOAP 请求与响应体解析。
 */
open class DlnaStreamClient(private val configuration: Configuration) :
    AbstractStreamClient<DlnaStreamClient.Configuration, Call>() {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(configuration.timeoutSeconds + 5L, TimeUnit.SECONDS)
        .readTimeout(configuration.timeoutSeconds + 5L, TimeUnit.SECONDS)
        .build()

    override fun getConfiguration(): Configuration = configuration

    override fun createRequest(requestMessage: StreamRequestMessage): Call {
        val method = requestMessage.operation.httpMethodName
        val builder = Request.Builder()
            .url(requestMessage.operation.uri.toString())
            .method(method, buildRequestBody(requestMessage, method))
        for ((key, values) in requestMessage.headers.entries) {
            if (key == null || values == null) continue
            for (value in values) {
                if (value != null) builder.addHeader(key, value)
            }
        }
        if (requestMessage.headers["user-agent"] == null) {
            builder.header(
                "User-Agent",
                configuration.getUserAgentValue(
                    requestMessage.udaMajorVersion,
                    requestMessage.udaMinorVersion
                )
            )
        }
        return httpClient.newCall(builder.build())
    }

    private fun buildRequestBody(requestMessage: StreamRequestMessage, method: String): RequestBody? {
        if (requestMessage.hasBody()) {
            val bytes = requestMessage.bodyBytes
            if (bytes != null && bytes.isNotEmpty()) {
                val contentTypes = requestMessage.headers["content-type"]
                val mediaType = contentTypes?.firstOrNull()?.toMediaTypeOrNull()
                return bytes.toRequestBody(mediaType)
            }
        }
        return if (requiresBody(method)) ByteArray(0).toRequestBody(null) else null
    }

    private fun requiresBody(method: String): Boolean =
        "POST" == method || "NOTIFY" == method || "PUT" == method

    override fun createCallable(
        requestMessage: StreamRequestMessage,
        call: Call
    ): Callable<StreamResponseMessage> = Callable {
        val response = call.execute()
        try {
            val responseMessage = StreamResponseMessage(UpnpResponse(response.code, response.message))
            val upnpHeaders = UpnpHeaders()
            for (name in response.headers.names()) {
                for (value in response.headers(name)) {
                    upnpHeaders.add(name, value)
                }
            }
            responseMessage.setHeaders(upnpHeaders)
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.isNotEmpty()) responseMessage.setBodyCharacters(bytes)
            responseMessage
        } finally {
            response.close()
        }
    }

    override fun abort(call: Call) {
        call.cancel()
    }

    override fun logExecutionException(t: Throwable): Boolean = false

    override fun stop() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    open class Configuration(executorService: ExecutorService) :
        AbstractStreamClientConfiguration(executorService)
}
