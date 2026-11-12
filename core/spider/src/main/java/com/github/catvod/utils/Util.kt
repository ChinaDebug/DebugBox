package com.github.catvod.utils

import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import com.github.catvod.Init
import java.io.File
import java.io.FileInputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Formatter

/** 通用工具，jar 内爬虫通过 com.github.catvod.utils.Util 调用 */
object Util {

    @JvmField
    val UNITS = arrayOf("bytes", "KB", "MB", "GB", "TB")

    private const val HEADER_USER_AGENT = "User-Agent"
    private const val HEADER_REFERER = "Referer"
    private const val HEADER_COOKIE = "Cookie"

    @JvmStatic
    fun getDeviceId(): String = try {
        Settings.Secure.getString(Init.context()!!.contentResolver, Settings.Secure.ANDROID_ID)
    } catch (_: Exception) {
        ""
    }

    @JvmStatic
    fun getDeviceName(): String {
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        return if (model.startsWith(manufacturer)) model else "$manufacturer $model"
    }

    @JvmStatic
    fun base64(ext: String): String = base64(ext.toByteArray())

    @JvmStatic
    fun base64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.DEFAULT or Base64.NO_WRAP)

    @JvmStatic
    fun substring(text: String?): String = substring(text, 1)

    @JvmStatic
    fun substring(text: String?, num: Int): String {
        if (text != null && text.length > num) return text.substring(0, text.length - num)
        return text ?: ""
    }

    @JvmStatic
    fun scheme(url: String?): String = if (url == null) "" else scheme(Uri.parse(url))

    @JvmStatic
    fun scheme(uri: Uri): String {
        val scheme = uri.scheme
        return scheme?.lowercase()?.trim() ?: ""
    }

    @JvmStatic
    fun host(url: String?): String = if (url == null) "" else host(Uri.parse(url))

    @JvmStatic
    fun host(uri: Uri): String {
        val host = uri.host
        return host?.lowercase()?.trim() ?: ""
    }

    @JvmStatic
    fun path(uri: Uri): String {
        val path = uri.path
        return path?.trim() ?: ""
    }

    @JvmStatic
    fun basic(uri: Uri): String = "Basic " + base64(uri.userInfo ?: "")

    @JvmStatic
    fun md5(src: String?): String {
        return try {
            if (TextUtils.isEmpty(src)) return ""
            val digest = MessageDigest.getInstance("MD5")
            val bytes = digest.digest(src!!.toByteArray())
            val no = BigInteger(1, bytes)
            val sb = StringBuilder(no.toString(16))
            while (sb.length < 32) sb.insert(0, "0")
            sb.toString().lowercase()
        } catch (_: NoSuchAlgorithmException) {
            ""
        }
    }

    @JvmStatic
    fun md5(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val byteArray = ByteArray(1024)
                var count: Int
                while (fis.read(byteArray).also { count = it } != -1) {
                    digest.update(byteArray, 0, count)
                }
            }
            val sb = StringBuilder()
            for (b in digest.digest()) {
                sb.append(Integer.toString((b.toInt() and 0xff) + 0x100, 16).substring(1))
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

    @JvmStatic
    fun equals(name: String, md5: String): Boolean = md5(Path.jar(name)).equals(md5, ignoreCase = true)

    @JvmStatic
    fun containOrMatch(text: String, regex: String): Boolean = try {
        val pattern = Regex(regex)
        text.contains(pattern) || text.matches(pattern)
    } catch (_: Exception) {
        false
    }

    @JvmStatic
    fun format(format: SimpleDateFormat, src: String): Long = try {
        format.parse(src)?.time ?: 0
    } catch (_: Exception) {
        0L
    }

    /** 格式化播放时间为 mm:ss，替代 media3 Util.getStringForTime */
    @JvmStatic
    fun format(builder: StringBuilder, formatter: Formatter, timeMs: Long): String {
        return try {
            val totalSeconds = (timeMs / 1000).toInt()
            val seconds = totalSeconds % 60
            val minutes = totalSeconds / 60 % 60
            val hours = totalSeconds / 3600
            builder.setLength(0)
            if (hours > 0) formatter.format("%d:%02d:%02d", hours, minutes, seconds)
            else formatter.format("%02d:%02d", minutes, seconds)
            formatter.toString()
        } catch (_: Exception) {
            ""
        }
    }

    @JvmStatic
    fun fixUrl(url: String): String = url.trim().replace("\\", "")

    @JvmStatic
    fun fixHeader(key: String): String = when {
        key.equals(HEADER_USER_AGENT, ignoreCase = true) -> HEADER_USER_AGENT
        key.equals(HEADER_REFERER, ignoreCase = true) -> HEADER_REFERER
        key.equals(HEADER_COOKIE, ignoreCase = true) -> HEADER_COOKIE
        else -> key
    }

    @JvmStatic
    fun size(size: Long): String {
        if (size <= 0) return ""
        val group = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return "[" + DecimalFormat("###0.#").format(size / Math.pow(1024.0, group.toDouble())) + " " + UNITS[group] + "] "
    }
}
