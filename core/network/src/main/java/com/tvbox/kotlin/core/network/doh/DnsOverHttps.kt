package com.tvbox.kotlin.core.network.doh

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.ByteString
import java.util.logging.Level
import java.util.logging.Logger

/**
 * DNS over HTTPS 实现，遵循 draft-ietf-doh-dns-over-https-13。
 * 当 [url] 为 null 时退化为系统 DNS。
 */
class DnsOverHttps private constructor(builder: Builder) : Dns {

    @Volatile
    var url: HttpUrl?
        private set

    private val includeIPv6: Boolean = builder.includeIPv6
    private val post: Boolean = builder.post
    private val resolvePrivateAddresses: Boolean = builder.resolvePrivateAddresses
    private val resolvePublicAddresses: Boolean = builder.resolvePublicAddresses
    private val client: OkHttpClient

    init {
        val sourceClient = builder.client ?: throw NullPointerException("client not set")
        this.url = builder.url
        this.client = sourceClient.newBuilder().dns(buildBootstrapClient(builder)).build()
    }

    /** 动态切换 DoH 服务地址，传 null 关闭 DoH */
    fun setUrl(newUrl: HttpUrl?) {
        this.url = newUrl
    }

    private fun buildBootstrapClient(builder: Builder): Dns {
        val hosts = builder.bootstrapDnsHosts
        return if (hosts != null) {
            BootstrapDns(builder.url?.host ?: "", hosts)
        } else {
            builder.systemDns
        }
    }

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        // DoH 关闭时直接走系统 DNS
        val currentUrl = url ?: return Dns.SYSTEM.lookup(hostname)

        if (!resolvePrivateAddresses || !resolvePublicAddresses) {
            val privateHost = isPrivateHost(hostname)
            if (privateHost && !resolvePrivateAddresses) {
                throw UnknownHostException("private hosts not resolved")
            }
            if (!privateHost && !resolvePublicAddresses) {
                throw UnknownHostException("public hosts not resolved")
            }
        }
        return lookupHttps(hostname)
    }

    /** 同步查询 A 与 AAAA 记录，返回拼接后的原始应答字节，供外层缓存使用 */
    @Throws(Throwable::class)
    fun lookupHttpsForwardSync(hostname: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(executeRequestsSync(hostname, DnsRecordCodec.TYPE_A))
        out.write(executeRequestsSync(hostname, DnsRecordCodec.TYPE_AAAA))
        return out.toByteArray()
    }

    @Throws(UnknownHostException::class)
    private fun lookupHttps(hostname: String): List<InetAddress> {
        val networkRequests = ArrayList<Call>(2)
        val failures = ArrayList<Exception>(2)
        val results = ArrayList<InetAddress>(5)

        buildRequest(hostname, networkRequests, results, failures, DnsRecordCodec.TYPE_A)
        if (includeIPv6) {
            buildRequest(hostname, networkRequests, results, failures, DnsRecordCodec.TYPE_AAAA)
        }
        executeRequests(hostname, networkRequests, results, failures)

        // 查询失败或为空时回退系统 DNS，避免阻塞业务
        return if (results.isNotEmpty()) results else Dns.SYSTEM.lookup(hostname)
    }

    private fun buildRequest(
        hostname: String,
        networkRequests: MutableList<Call>,
        results: MutableList<InetAddress>,
        failures: MutableList<Exception>,
        type: Int
    ) {
        val request = buildRequest(hostname, type)
        val cacheResponse = getCacheOnlyResponse(request)
        if (cacheResponse != null) {
            processResponse(cacheResponse, hostname, results, failures)
        } else {
            networkRequests.add(client.newCall(request))
        }
    }

    @Throws(IOException::class)
    private fun executeRequestsSync(hostname: String, type: Int): ByteArray {
        val request = buildRequest(hostname, type)
        var response = getCacheOnlyResponse(request)
        if (response == null) {
            response = client.newCall(request).execute()
        }
        return response.use { it.body?.bytes() ?: ByteArray(0) }
    }

    private fun executeRequests(
        hostname: String,
        networkRequests: List<Call>,
        responses: MutableList<InetAddress>,
        failures: MutableList<Exception>
    ) {
        val latch = CountDownLatch(networkRequests.size)
        for (call in networkRequests) {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    synchronized(failures) { failures.add(e) }
                    latch.countDown()
                }

                override fun onResponse(call: Call, response: Response) {
                    processResponse(response, hostname, responses, failures)
                    latch.countDown()
                }
            })
        }
        try {
            latch.await()
        } catch (e: InterruptedException) {
            failures.add(e)
            Thread.currentThread().interrupt()
        }
    }

    private fun processResponse(
        response: Response,
        hostname: String,
        results: MutableList<InetAddress>,
        failures: MutableList<Exception>
    ) {
        try {
            val addresses = readResponse(hostname, response)
            synchronized(results) { results.addAll(addresses) }
        } catch (e: Exception) {
            synchronized(failures) { failures.add(e) }
        }
    }

    private fun getCacheOnlyResponse(request: Request): Response? {
        if (post || client.cache == null) return null
        return try {
            val cacheRequest = request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build()
            val cacheResponse = client.newCall(cacheRequest).execute()
            if (cacheResponse.code != 504) cacheResponse else null
        } catch (_: IOException) {
            // 缓存失败回退网络
            null
        }
    }

    @Throws(Exception::class)
    private fun readResponse(hostname: String, response: Response): List<InetAddress> {
        if (response.cacheResponse == null && response.protocol != Protocol.HTTP_2) {
            Logger.getLogger(DnsOverHttps::class.java.name)
                .log(Level.WARNING, "Incorrect protocol: ${response.protocol}")
        }
        try {
            if (!response.isSuccessful) {
                throw IOException("response: ${response.code} ${response.message}")
            }
            val body: ResponseBody = response.body ?: throw IOException("response body null")
            if (body.contentLength() > MAX_RESPONSE_SIZE) {
                throw IOException(
                    "response size exceeds limit ($MAX_RESPONSE_SIZE bytes): ${body.contentLength()} bytes"
                )
            }
            val responseBytes = body.source().readByteString()
            return DnsRecordCodec.decodeAnswers(hostname, responseBytes)
        } finally {
            response.close()
        }
    }

    private fun buildRequest(hostname: String, type: Int): Request {
        val safeUrl = url ?: throw IllegalStateException("DoH url not set")
        val requestBuilder = Request.Builder().header("Accept", DNS_MESSAGE.toString())
        val query = DnsRecordCodec.encodeQuery(hostname, type)
        return if (post) {
            requestBuilder.url(safeUrl).post(query.toByteArray().toRequestBody(DNS_MESSAGE)).build()
        } else {
            val encoded = query.base64Url().replace("=", "")
            val requestUrl = safeUrl.newBuilder().addQueryParameter("dns", encoded).build()
            requestBuilder.url(requestUrl).build()
        }
    }

    class Builder {
        internal var client: OkHttpClient? = null
        internal var url: HttpUrl? = null
        internal var includeIPv6: Boolean = true
        internal var post: Boolean = false
        internal var systemDns: Dns = Dns.SYSTEM
        internal var bootstrapDnsHosts: List<InetAddress>? = null
        internal var resolvePrivateAddresses: Boolean = false
        internal var resolvePublicAddresses: Boolean = true

        fun client(client: OkHttpClient): Builder = apply { this.client = client }
        fun url(url: HttpUrl?): Builder = apply { this.url = url }
        fun includeIPv6(includeIPv6: Boolean): Builder = apply { this.includeIPv6 = includeIPv6 }
        fun post(post: Boolean): Builder = apply { this.post = post }
        fun resolvePrivateAddresses(resolve: Boolean): Builder = apply { this.resolvePrivateAddresses = resolve }
        fun resolvePublicAddresses(resolve: Boolean): Builder = apply { this.resolvePublicAddresses = resolve }
        fun systemDns(systemDns: Dns): Builder = apply { this.systemDns = systemDns }
        fun bootstrapDnsHosts(bootstrapDnsHosts: List<InetAddress>?): Builder = apply {
            this.bootstrapDnsHosts = bootstrapDnsHosts
        }

        fun bootstrapDnsHosts(vararg bootstrapDnsHosts: InetAddress): Builder =
            bootstrapDnsHosts(bootstrapDnsHosts.toList())

        fun build(): DnsOverHttps = DnsOverHttps(this)
    }

    companion object {
        val DNS_MESSAGE = "application/dns-message".toMediaType()
        const val MAX_RESPONSE_SIZE: Long = 64L * 1024L

        /** 判断主机是否为私有地址，依据顶级私有域名是否可解析 */
        internal fun isPrivateHost(host: String): Boolean {
            val url = "http://$host".toHttpUrlOrNull() ?: return true
            return url.topPrivateDomain() == null
        }
    }
}
