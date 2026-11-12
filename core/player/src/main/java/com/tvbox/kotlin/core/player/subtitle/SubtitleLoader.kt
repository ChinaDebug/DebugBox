package com.tvbox.kotlin.core.player.subtitle

import android.net.Uri
import android.text.TextUtils
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.player.subtitle.exception.FatalParsingException
import com.tvbox.kotlin.core.player.subtitle.format.FormatASS
import com.tvbox.kotlin.core.player.subtitle.format.FormatSRT
import com.tvbox.kotlin.core.player.subtitle.format.FormatVTT
import com.tvbox.kotlin.core.player.subtitle.format.TimedTextFileFormat
import com.tvbox.kotlin.core.player.subtitle.model.TimedTextObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 字幕加载器。支持本地文件与 HTTP/HTTPS 远程字幕，自动识别 BOM 与字符编码，
 * 按扩展名选择 srt/ass/vtt 解析器，未识别扩展名时依次尝试全部解析器。
 *
 * 使用协程在 IO 调度器执行。
 */
object SubtitleLoader {

    private const val TAG = "SubtitleLoader"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.54 Safari/537.36"

    /** 挂起加载字幕，内部在 IO 线程执行，失败返回 null */
    suspend fun loadSubtitle(path: String?): SubtitleLoadSuccessResult? {
        if (path.isNullOrEmpty()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                if (path.startsWith("http://") || path.startsWith("https://")) {
                    loadFromRemote(path)
                } else {
                    loadFromLocal(path)
                }
            }.onFailure { LOG.e(TAG, "加载字幕失败: $path", it) }.getOrNull()
        }
    }

    @Throws(IOException::class, FatalParsingException::class)
    private fun loadFromRemote(remotePath: String): SubtitleLoadSuccessResult? {
        val url = remotePath.split("#").first()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", AppConstants.UA_DESKTOP)
            val referer = when {
                remotePath.contains("alicloud") || remotePath.contains("aliyundrive") ->
                    "https://www.aliyundrive.com/"
                remotePath.contains("assrt.net") -> "https://secure.assrt.net/"
                else -> ""
            }
            if (referer.isNotEmpty()) setRequestProperty("Referer", referer)
        }
        try {
            val bytes = conn.inputStream.use { it.readBytes() }
            val content = decodeBytes(bytes)
            var fileName = parseFileNameFromHeader(conn.getHeaderField("content-disposition"))
            if (fileName.isEmpty()) {
                val uriPath = Uri.parse(remotePath).path
                fileName = uriPath ?: remotePath
            }
            if (!fileName.contains(".") && remotePath.contains("#")) {
                fileName = runCatching { URLDecoder.decode(remotePath.split("#")[1], "UTF-8") }
                    .getOrElse { fileName }
            }
            return buildResult(content, fileName, remotePath)
        } finally {
            conn.disconnect()
        }
    }

    @Throws(IOException::class, FatalParsingException::class)
    private fun loadFromLocal(localPath: String): SubtitleLoadSuccessResult? {
        val file = File(localPath)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val content = decodeBytes(bytes)
        return buildResult(content, file.path, localPath)
    }

    private fun buildResult(content: String, filePath: String, subtitlePath: String): SubtitleLoadSuccessResult {
        val result = SubtitleLoadSuccessResult()
        result.timedTextObject = loadAndParse(content, filePath)
        result.fileName = filePath.substringAfterLast("/")
        result.content = content
        result.subtitlePath = subtitlePath
        return result
    }

    /** 按扩展名选择解析器，未命中则依次尝试 srt/ass/vtt */
    @Throws(IOException::class, FatalParsingException::class)
    private fun loadAndParse(content: String, filePath: String): TimedTextObject? {
        val fileName = filePath.substringAfterLast("/")
        val ext = fileName.substringAfterLast(".", "").lowercase()
        val reader = BufferedReader(StringReader(content))
        val format = when (ext) {
            "srt" -> FormatSRT()
            "ass", "ssa" -> FormatASS()
            "vtt" -> FormatVTT()
            else -> null
        }
        if (format != null) {
            return format.parseFile(fileName, reader.toInputStream())
        }
        // 未知扩展名，依次尝试
        val candidates: Array<TimedTextFileFormat> = arrayOf(FormatSRT(), FormatASS(), FormatVTT())
        for (candidate in candidates) {
            val stream = BufferedReader(StringReader(content)).toInputStream()
            runCatching { return candidate.parseFile(fileName, stream) }
        }
        return null
    }

    /** 解析 content-disposition 中的文件名 */
    private fun parseFileNameFromHeader(contentDisposition: String?): String {
        if (contentDisposition.isNullOrEmpty()) return ""
        val parts = contentDisposition.split(";")
        if (parts.size < 2) return ""
        var info = parts[1].trim()
        return when {
            info.startsWith("filename=") -> {
                info = info.removePrefix("filename=").replace("\"", "").trim()
                runCatching { URLDecoder.decode(info, "UTF-8") }.getOrElse { info }
            }
            info.startsWith("filename*=") -> {
                info = info.substringAfterLast("''")
                runCatching { URLDecoder.decode(info, "UTF-8") }.getOrElse { info }
            }
            else -> ""
        }
    }

    /**
     * 解码字节数组：优先按 BOM 判断，无 BOM 时尝试 UTF-8 严格解码，失败回退 GBK，
     * 兼容中文字幕常见编码。
     */
    private fun decodeBytes(bytes: ByteArray): String {
        // UTF-8 BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        // UTF-16LE BOM
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        // UTF-16BE BOM
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        // 无 BOM：先尝试 UTF-8 严格解码
        if (isValidUtf8(bytes)) {
            return String(bytes, StandardCharsets.UTF_8)
        }
        return runCatching { String(bytes, Charset.forName("GBK")) }
            .getOrElse { String(bytes, StandardCharsets.UTF_8) }
    }

    /** 严格 UTF-8 合法性校验，非法字节则视为非 UTF-8 */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        return runCatching {
            val decoder = StandardCharsets.UTF_8.newDecoder()
            java.nio.ByteBuffer.wrap(bytes).let { decoder.decode(it) }
            true
        }.getOrElse { false }
    }

    private fun BufferedReader.toInputStream(): java.io.InputStream {
        val text = this.readText()
        return java.io.ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))
    }
}
