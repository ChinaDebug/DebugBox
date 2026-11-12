package com.tvbox.kotlin.core.server

import android.content.Context
import android.os.Environment
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.event.ServerEvent
import com.tvbox.kotlin.core.common.util.FileUtils
import com.tvbox.kotlin.core.common.util.LocalIPAddress
import com.tvbox.kotlin.core.common.util.StoragePermissionUtils
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 本地 HTTP 服务，基于 NanoHTTPD 提供手机推送、投屏控制、文件代理、DNS 转发等接口。
 *
 * 路由概览：
 * - GET  /、/index.html、/style.css、/jquery.js、/script.js、/favicon.ico 静态资源
 * - GET  /media                              当前播放媒体信息
 * - GET  /proxy?do=xxx                       本地爬虫代理
 * - GET  /proxy?go=xxx                       通用代理
 * - GET  /file/<path>                        文件浏览与下载
 * - GET  /dns-query?name=xxx                 DNS over HTTPS 转发
 * - GET  /m3u8                               m3u8 内容
 * - GET  /api/remote/version                 版本信息
 * - GET  /api/updateUrl?url=xxx              推送地址更新
 * - GET  /dash/<data>                        DASH 数据
 * - POST /action                             按键与输入（search/api/live/epg/push/mirror）
 * - POST /upload、/newFolder、/delFolder、/delFile 文件管理
 *
 * @param port    监听端口
 * @param context 上下文
 * @param host    外部依赖聚合
 */
class RemoteServer(
    private val context: Context,
    port: Int,
    private val host: ServerHost
) : NanoHTTPD(port) {

    @Volatile
    private var isStarted = false

    private var dataReceiver: DataReceiver? = null

    private val getRequestList = ArrayList<RequestProcess>()
    private val postRequestList = ArrayList<RequestProcess>()

    /** 当前播放媒体标题（GET /media 返回） */
    @Volatile
    var vodName: String? = null

    /** 当前播放媒体艺术家（GET /media 返回） */
    @Volatile
    var artist: String? = null

    /** m3u8 内容（GET /m3u8 返回） */
    @Volatile
    var m3u8Content: String? = null

    init {
        getRequestList.add(RawRequestProcess(host.resources))
        // 爬虫异步生成弹幕后以 GET 回调 /action?do=refresh，需同时注册到 GET 通道
        getRequestList.add(InputRequestProcess(this, host))
        postRequestList.add(InputRequestProcess(this, host))
    }

    @Throws(IOException::class)
    override fun start(timeout: Int, daemon: Boolean) {
        isStarted = true
        super.start(timeout, daemon)
        EventHub.post(ServerEvent(SERVER_SUCCESS))
    }

    override fun stop() {
        super.stop()
        isStarted = false
    }

    override fun serve(session: IHTTPSession): Response {
        EventHub.post(ServerEvent(SERVER_CONNECTION))
        val rawUri = session.uri ?: ""
        if (rawUri.isEmpty()) {
            return defaultIndexResponse(session)
        }
        var fileName = rawUri.trim()
        val queryIndex = fileName.indexOf('?')
        if (queryIndex >= 0) {
            fileName = fileName.substring(0, queryIndex)
        }
        return when (session.method) {
            Method.GET -> handleGet(session, fileName)
            Method.POST -> handlePost(session, fileName)
            else -> defaultIndexResponse(session)
        }
    }

    /** 处理 GET 请求 */
    private fun handleGet(session: IHTTPSession, fileName: String): Response {
        // 静态资源优先匹配
        for (process in getRequestList) {
            if (process.isRequest(session, fileName)) {
                return process.doResponse(session, fileName, toSingleValueMap(session.parameters), emptyMap())
            }
        }
        when {
            fileName == "/media" -> {
                val jsonObject = JSONObject()
                jsonObject.put("title", vodName)
                jsonObject.put("artist", artist)
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, jsonObject.toString())
            }
            fileName == "/proxy" -> {
                val params = toSingleValueMap(session.parameters).toMutableMap()
                params.putAll(session.headers)
                params["request-headers"] = Gson().toJson(session.headers)
                if (params.containsKey("do")) {
                    return getProxy(host.proxyLocal(params))
                }
                if (params.containsKey("go")) {
                    return getProxy(host.proxy(params))
                }
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Proxy error")
            }
            fileName.startsWith("/file/") -> {
                return handleFile(fileName)
            }
            fileName == "/dns-query" -> {
                val name = toSingleValueMap(session.parameters)["name"]
                val rs = try {
                    host.resolveDns(name)
                } catch (t: Throwable) {
                    ByteArray(0)
                }
                return newFixedLengthResponse(Response.Status.OK, "application/dns-message", ByteArrayInputStream(rs), rs.size.toLong())
            }
            fileName == "/m3u8" -> {
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, m3u8Content ?: "")
            }
            fileName == "/api/remote/version" || fileName == "/index.html" -> {
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "hello")
            }
            fileName == "/api/updateUrl" -> {
                val url = toSingleValueMap(session.parameters)["url"]
                if (!url.isNullOrEmpty()) {
                    dataReceiver?.onPushReceived(url)
                }
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
            }
            fileName.startsWith("/dash/") -> {
                return handleDash()
            }
            else -> return defaultIndexResponse(session)
        }
    }

    /** 处理 POST 请求 */
    private fun handlePost(session: IHTTPSession, fileName: String): Response {
        val files = HashMap<String, String>()
        try {
            // NanoHTTPD 底层 headers 为可变 Map，此处显式转换以支持写入
            val headers = session.headers as MutableMap<String, String>
            val contentType = headers["content-type"]
            if (contentType != null) {
                // 修正 multipart 表单中文乱码
                if (contentType.lowercase().contains("multipart/form-data") &&
                    !contentType.lowercase().contains("charset=")
                ) {
                    val matcher = Regex("[ |\\t]*(boundary[ |\\t]*=[ |\\t]*=['|\"]?[^\"'^;^,]*['|\"]?)", RegexOption.IGNORE_CASE)
                        .find(contentType)
                    val boundary = matcher?.groupValues?.get(1)
                    if (boundary != null) {
                        headers["content-type"] = "multipart/form-data; charset=utf-8; $boundary"
                    }
                }
            }
            session.parseBody(files)
        } catch (ioExc: IOException) {
            return createPlainTextResponse(Response.Status.INTERNAL_ERROR, "SERVER INTERNAL ERROR: IOException: ${ioExc.message}")
        } catch (rex: ResponseException) {
            return createPlainTextResponse(rex.status, rex.message ?: "")
        }
        val postParams = toSingleValueMap(session.parameters)
        // 自定义处理器优先匹配
        for (process in postRequestList) {
            if (process.isRequest(session, fileName)) {
                return process.doResponse(session, fileName, postParams, files)
            }
        }
        return try {
            handleFileManagement(fileName, postParams, files)
        } catch (t: Throwable) {
            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        }
    }

    /** 文件管理路由（/upload、/newFolder、/delFolder、/delFile） */
    private fun handleFileManagement(fileName: String, params: Map<String, String>, files: Map<String, String>): Response {
        val needStorage = fileName == "/upload" || fileName == "/newFolder" ||
            fileName == "/delFolder" || fileName == "/delFile"
        if (needStorage && !hasStoragePermission()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Storage permission not granted")
        }
        val root = Environment.getExternalStorageDirectory().absolutePath
        when (fileName) {
            "/upload" -> {
                val path = params["path"].orEmpty()
                for ((k, tmpFile) in files) {
                    if (k.startsWith("files-")) {
                        val fn = params[k].orEmpty()
                        val tmp = File(tmpFile)
                        val file = File("$root/$path/$fn")
                        if (file.exists()) file.delete()
                        if (tmp.exists()) {
                            if (fn.lowercase().endsWith(".zip")) {
                                unzip(tmp, "$root/$path")
                            } else {
                                FileUtils.copyFile(tmp, file)
                            }
                        }
                        if (tmp.exists()) tmp.delete()
                    }
                }
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
            }
            "/newFolder" -> {
                val path = params["path"].orEmpty()
                val name = params["name"].orEmpty()
                val file = File("$root/$path/$name")
                if (!file.exists()) {
                    file.mkdirs()
                    val flag = File("$root/$path/$name/.tvbox_folder")
                    if (!flag.exists()) flag.createNewFile()
                }
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
            }
            "/delFolder" -> {
                val path = params["path"].orEmpty()
                val file = File("$root/$path")
                if (file.exists()) FileUtils.recursiveDelete(file)
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
            }
            "/delFile" -> {
                val path = params["path"].orEmpty()
                val file = File("$root/$path")
                if (file.exists()) file.delete()
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
            }
            else -> {
                return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
            }
        }
    }

    /** 处理 /file/<path> 路由 */
    private fun handleFile(fileName: String): Response {
        if (!hasStoragePermission()) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Storage permission not granted")
        }
        return try {
            val f = fileName.substring(6)
            val root = Environment.getExternalStorageDirectory().absolutePath
            val file = File("$root/$f")
            if (!file.exists()) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "File $file not found!")
            }
            if (file.isFile) {
                newChunkedResponse(Response.Status.OK, "application/octet-stream", FileInputStream(file))
            } else {
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, fileList(root, f))
            }
        } catch (t: Throwable) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, t.message ?: "error")
        }
    }

    /** 处理 /dash/<data> 路由 */
    private fun handleDash(): Response {
        val dashData = host.dashData()
        if (dashData.isNullOrEmpty()) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "DASH data not available")
        }
        return try {
            val data = String(Base64.decode(dashData, Base64.DEFAULT or Base64.NO_WRAP), Charsets.UTF_8)
            newFixedLengthResponse(Response.Status.OK, "application/dash+xml", data)
        } catch (t: Throwable) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to decode DASH data")
        }
    }

    /** 构造代理响应 */
    private fun getProxy(rs: Array<Any?>?): Response {
        return try {
            if (rs == null || rs.size < 3) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Proxy error: rs is null or empty")
            }
            val first = rs[0]
            if (first is Response) return first
            if ("error" == first) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, rs[1]?.toString() ?: "Proxy error")
            }
            val code = (first as? Int) ?: 500
            val mime = rs[1] as? String ?: MIME_PLAINTEXT
            val stream = rs[2] as? InputStream
            val response = newChunkedResponse(Response.Status.lookup(code), mime, stream)
            if (rs.size >= 4 && rs[3] is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                val mapHeader = rs[3] as Map<String, String>
                for ((key, value) in mapHeader) {
                    response.addHeader(key, value)
                }
            }
            response
        } catch (t: Throwable) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "500")
        }
    }

    /** 默认返回首页（/），由 RawRequestProcess 通过 ServerResources 解析 */
    private fun defaultIndexResponse(session: IHTTPSession): Response =
        getRequestList.first().doResponse(session, "/", emptyMap(), emptyMap())

    fun setDataReceiver(receiver: DataReceiver?) {
        dataReceiver = receiver
    }

    fun getDataReceiver(): DataReceiver? = dataReceiver

    fun isStarting(): Boolean = isStarted

    /** 对外暴露的本机服务地址 */
    fun getServerAddress(): String {
        val ipAddress = LocalIPAddress.getLocalIPAddress(context)
        return "http://$ipAddress:${getListeningPort()}/"
    }

    /** 本机回环地址（供 jar 内爬虫使用） */
    fun getLoadAddress(): String = "http://127.0.0.1:${getListeningPort()}/"

    /** 检查存储权限：Android 11+ 需 MANAGE_EXTERNAL_STORAGE，低版本用 WRITE_EXTERNAL_STORAGE */
    private fun hasStoragePermission(): Boolean = StoragePermissionUtils.hasManageExternalStorage(context)

    /** 文件列表 JSON */
    private fun fileList(root: String, path: String): String {
        val file = File("$root/$path")
        val list = file.listFiles()
        val info = JsonObject()
        info.addProperty("remote", getServerAddress().replace("http://", "clan://"))
        info.addProperty("del", 0)
        if (path.isEmpty()) {
            info.addProperty("parent", ".")
        } else {
            info.addProperty("parent", file.parentFile?.absolutePath?.replace("$root/", "")?.replace(root, "") ?: "")
        }
        if (list == null || list.isEmpty()) {
            info.add("files", JsonArray())
            return info.toString()
        }
        list.sortWith { o1, o2 ->
            if (o1.isDirectory && o2.isFile) -1
            else if (o1.isFile && o2.isDirectory) 1
            else o1.name.compareTo(o2.name)
        }
        val result = JsonArray()
        for (f in list) {
            if (f.name.startsWith(".")) {
                if (f.name == ".tvbox_folder") info.addProperty("del", 1)
                continue
            }
            val fileObj = JsonObject()
            fileObj.addProperty("name", f.name)
            fileObj.addProperty("path", f.absolutePath.replace("$root/", ""))
            fileObj.addProperty("time", fileTime(f.lastModified(), "yyyy/MM/dd aHH:mm:ss"))
            fileObj.addProperty("dir", if (f.isDirectory) 1 else 0)
            result.add(fileObj)
        }
        info.add("files", result)
        return info.toString()
    }

    private fun fileTime(time: Long, fmt: String): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = time
        val date: Date = calendar.time
        return SimpleDateFormat(fmt, Locale.getDefault()).format(date)
    }

    /** 解压 zip 到目标目录，目录创建 .tvbox_folder 标记 */
    @Throws(Throwable::class)
    private fun unzip(zipFilePath: File, destDirectory: String) {
        val destDir = File(destDirectory)
        if (!destDir.exists()) destDir.mkdirs()
        ZipFile(zipFilePath).use { zip ->
            val iter = zip.entries()
            while (iter.hasMoreElements()) {
                val entry: ZipEntry = iter.nextElement()
                val filePath = destDirectory + File.separator + entry.name
                if (!entry.isDirectory) {
                    zip.getInputStream(entry).use { input -> extractFile(input, filePath) }
                } else {
                    val dir = File(filePath)
                    if (!dir.exists()) dir.mkdirs()
                    val flag = File(dir, ".tvbox_folder")
                    if (!flag.exists()) flag.createNewFile()
                }
            }
        }
    }

    @Throws(Throwable::class)
    private fun extractFile(inputStream: InputStream, destFilePath: String) {
        val dst = File(destFilePath)
        if (dst.exists()) dst.delete()
        BufferedOutputStream(FileOutputStream(destFilePath)).use { bos ->
            val bytesIn = ByteArray(2048)
            var len = inputStream.read(bytesIn)
            while (len > 0) {
                bos.write(bytesIn, 0, len)
                len = inputStream.read(bytesIn)
            }
        }
    }

    /**
     * 将 NanoHTTPD getParameters() 返回的多值参数映射转换为单值映射，
     * 保持与旧 getParms() 行为一致。
     */
    private fun toSingleValueMap(parameters: Map<String, List<String>>): Map<String, String> {
        val result = HashMap<String, String>()
        for ((key, values) in parameters) {
            if (values.isNotEmpty()) {
                result[key] = values[0]
            }
        }
        return result
    }

    companion object {
        const val SERVER_SUCCESS = "server_success"
        const val SERVER_CONNECTION = "server_connection"
        const val SERVER_SEARCH = "server_search"

        @JvmStatic
        fun createPlainTextResponse(status: Response.IStatus, text: String): Response =
            NanoHTTPD.newFixedLengthResponse(status, MIME_PLAINTEXT, text)

        @JvmStatic
        fun createJSONResponse(status: Response.IStatus, text: String): Response =
            NanoHTTPD.newFixedLengthResponse(status, "application/json", text)
    }
}
