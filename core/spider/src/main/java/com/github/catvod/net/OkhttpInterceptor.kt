package com.github.catvod.net

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.IOException
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** deflate 内容解码拦截器，jar 内爬虫请求遇到 deflate 响应时自动解压 */
class OkhttpInterceptor : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val encoding = response.header("Content-Encoding")
        val body = response.body
        if (body == null || encoding == null || encoding != "deflate") return response
        val inflaterStream = InflaterInputStream(body.byteStream(), Inflater(true))
        return response.newBuilder()
            .headers(response.headers)
            .body(object : ResponseBody() {
                override fun contentType(): MediaType? = body.contentType()
                override fun contentLength(): Long = body.contentLength()
                override fun source(): BufferedSource = inflaterStream.source().buffer()
            })
            .build()
    }
}
