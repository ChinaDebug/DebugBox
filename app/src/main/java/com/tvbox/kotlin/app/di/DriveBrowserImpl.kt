package com.tvbox.kotlin.app.di

import com.google.gson.JsonObject
import com.tvbox.kotlin.core.common.bean.DriveFolderFile
import com.tvbox.kotlin.core.common.util.StorageDriveType
import com.tvbox.kotlin.core.network.OkHttpHelper
import com.tvbox.kotlin.core.ui.DriveBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Gson JsonObject 兼容扩展：键不存在或为 null 时返回 null */
private fun JsonObject.optStringOrNull(key: String): String? {
    if (!has(key) || get(key).isJsonNull) return null
    return try { get(key).asString } catch (_: Throwable) { null }
}

/**
 * 网盘文件浏览实现。
 *
 * - LOCAL：通过 [File] API 浏览本地目录；
 * - WEBDAV：通过 PROPFIND 请求浏览 WebDAV 目录；
 * - ALISTWEB：通过 Alist API 浏览文件列表。
 */
@Singleton
class DriveBrowserImpl @Inject constructor(
    private val okHttpHelper: OkHttpHelper
) : DriveBrowser {

    private companion object {
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
<D:prop>
<D:displayname/>
<D:resourcetype/>
<D:getcontentlength/>
<D:getlastmodified/>
</D:prop>
</D:propfind>"""
    }

    override suspend fun listFiles(
        drive: DriveFolderFile,
        path: DriveFolderFile?
    ): List<DriveFolderFile> = withContext(Dispatchers.IO) {
        val type = drive.getDriveType() ?: return@withContext emptyList()
        when (type) {
            StorageDriveType.TYPE.LOCAL -> listLocalFiles(drive, path)
            StorageDriveType.TYPE.WEBDAV -> listWebDavFiles(drive, path)
            StorageDriveType.TYPE.ALISTWEB -> listAlistFiles(drive, path)
        }
    }

    /** 浏览本地目录 */
    private fun listLocalFiles(
        drive: DriveFolderFile,
        path: DriveFolderFile?
    ): List<DriveFolderFile> {
        val config = drive.getConfig() ?: return emptyList()
        val rootPath = config.optStringOrNull("url") ?: return emptyList()
        val currentPath = if (path != null) {
            "$rootPath/${path.getAccessingPathStr()}${path.name}"
        } else {
            rootPath
        }
        val dir = File(currentPath)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()?.map { file ->
            DriveFolderFile(
                parent = path ?: drive,
                name = file.name,
                version = 0,
                isFile = file.isFile,
                fileType = file.extension,
                lastModifiedDate = file.lastModified()
            ).apply {
                fileUrl = file.absolutePath
            }
        } ?: emptyList()
    }

    /** 浏览 WebDAV 目录（PROPFIND） */
    private fun listWebDavFiles(
        drive: DriveFolderFile,
        path: DriveFolderFile?
    ): List<DriveFolderFile> {
        val config = drive.getConfig() ?: return emptyList()
        val rootUrl = webDavRootUrl(config) ?: return emptyList()
        val cred = drive.getWebDAVBase64Credential()
        val currentPath = if (path != null) {
            buildPath(rootUrl, path)
        } else {
            rootUrl
        }

        return try {
            val response = propFind(currentPath, cred)
            parseWebDavResponse(response, path ?: drive, currentPath)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** WebDAV 根地址：配置的 url 拼接初始路径，统一以 / 结尾（目录请求的规范形式） */
    private fun webDavRootUrl(config: JsonObject): String? {
        val base = config.optStringOrNull("url")?.removeSuffix("/") ?: return null
        val initPath = config.optStringOrNull("initPath")?.trim('/').orEmpty()
        return if (initPath.isEmpty()) "$base/" else "$base/$initPath/"
    }

    /** 发送 WebDAV PROPFIND 请求 */
    private fun propFind(url: String, credential: String?): String {
        val builder = okhttp3.Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
        if (credential != null) {
            builder.header("Authorization", "Basic $credential")
        }
        return try {
            okHttpHelper.client().newCall(builder.build()).execute().use { response ->
                response.body?.string() ?: ""
            }
        } catch (_: Throwable) {
            ""
        }
    }

    /** 浏览 Alist 目录 */
    private fun listAlistFiles(
        drive: DriveFolderFile,
        path: DriveFolderFile?
    ): List<DriveFolderFile> {
        val config = drive.getConfig() ?: return emptyList()
        val baseUrl = config.optStringOrNull("url")?.removeSuffix("/") ?: return emptyList()
        val currentPath = alistPath(config, path)

        return try {
            val apiUrl = "$baseUrl/api/fs/list"
            val body = JSONObject().apply {
                put("path", currentPath)
                put("page", 1)
                put("per_page", 0)
                put("refresh", false)
            }
            val response = okHttpHelper.post(apiUrl, body.toString(), mapOf("Content-Type" to "application/json"))
            parseAlistResponse(response, path ?: drive)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 构建 WebDAV 请求路径（rootUrl 已带尾部 /，目录请求同样以 / 结尾避免服务端 301） */
    private fun buildPath(rootUrl: String, path: DriveFolderFile): String {
        val pathStr = path.getAccessingPathStr() + (path.name ?: "")
        return "$rootUrl$pathStr/"
    }

    /** 构建 Alist 目录路径（含配置的初始路径） */
    private fun alistPath(config: JsonObject, path: DriveFolderFile?): String {
        val initPath = config.optStringOrNull("initPath")?.trim('/').orEmpty()
        val subPath = path?.let { (it.getAccessingPathStr() + (it.name ?: "")).trim('/') }.orEmpty()
        val parts = listOf(initPath, subPath).filter { it.isNotEmpty() }
        return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
    }

    /** 解析 WebDAV PROPFIND 响应 */
    private fun parseWebDavResponse(
        response: String,
        parent: DriveFolderFile,
        currentUrl: String
    ): List<DriveFolderFile> {
        val result = ArrayList<DriveFolderFile>()
        val hrefRegex = Regex("<d:href>([^<]+)</d:href>", RegexOption.IGNORE_CASE)
        val collectionRegex = Regex("<d:collection", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("<d:displayname>([^<]+)</d:displayname>", RegexOption.IGNORE_CASE)

        val currentPath = normalizeWebDavPath(currentUrl)
        val entries = response.split("<d:response>", "<D:response>")
        for (entry in entries) {
            if (entry.isEmpty()) continue
            val hrefMatch = hrefRegex.find(entry) ?: continue
            val href = hrefMatch.groupValues[1]
            // Depth:1 响应含当前目录自身，跳过以避免重复条目
            if (normalizeWebDavPath(href) == currentPath) continue
            val nameMatch = nameRegex.find(entry)
            val name = nameMatch?.groupValues?.getOrNull(1) ?: href
                .substringAfterLast("/").ifEmpty { continue }
            val isCollection = collectionRegex.containsMatchIn(entry)
            val file = DriveFolderFile(
                parent = parent,
                name = name,
                version = 0,
                isFile = !isCollection,
                fileType = name.substringAfterLast(".", ""),
                lastModifiedDate = null
            )
            if (!isCollection) file.fileUrl = resolveWebDavUrl(currentUrl, href)
            result.add(file)
        }
        return result
    }

    /** 归一化 WebDAV href / URL 为解码后的路径，用于识别当前目录自身 */
    private fun normalizeWebDavPath(raw: String): String {
        var path = raw
        if (path.contains("://")) path = path.substringAfter("://").substringAfter('/', "")
        path = path.substringBefore('?').substringBefore('#')
        path = runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        return "/" + path.trim('/')
    }

    /** 由响应 href 还原完整文件地址（href 可能是绝对路径或完整 URL） */
    private fun resolveWebDavUrl(currentUrl: String, href: String): String {
        if (href.startsWith("http://", ignoreCase = true) || href.startsWith("https://", ignoreCase = true)) {
            return href
        }
        return runCatching {
            val base = java.net.URI(if (currentUrl.endsWith("/")) currentUrl else "$currentUrl/")
            base.resolve(href).toString()
        }.getOrDefault(href)
    }

    /** 解析 Alist API 响应 */
    private fun parseAlistResponse(
        response: String,
        parent: DriveFolderFile
    ): List<DriveFolderFile> {
        val result = ArrayList<DriveFolderFile>()
        val json = try { JSONObject(response) } catch (_: Throwable) { return emptyList() }
        val data = json.optJSONObject("data") ?: return emptyList()
        val content = data.optJSONArray("content") ?: return emptyList()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val isDir = item.optBoolean("is_dir", false)
            val file = DriveFolderFile(
                parent = parent,
                name = name,
                version = 0,
                isFile = !isDir,
                fileType = name.substringAfterLast(".", ""),
                lastModifiedDate = item.optLong("modified")
            )
            file.fileUrl = item.optString("raw_url").ifEmpty { item.optString("url") }
            result.add(file)
        }
        return result
    }
}
