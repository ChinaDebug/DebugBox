package com.tvbox.kotlin.core.common.util

import android.content.Context
import android.text.TextUtils
import com.tvbox.kotlin.core.common.LOG
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** 文件与缓存工具 */
object FileUtils {

    @JvmStatic
    @Throws(IOException::class)
    fun copyFile(source: File, dest: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(1024)
                var length = input.read(buffer)
                while (length > 0) {
                    output.write(buffer, 0, length)
                    length = input.read(buffer)
                }
            }
        }
    }

    @JvmStatic
    fun recursiveDelete(file: File?) {
        try {
            if (file == null || !file.exists()) return
            if (file.isDirectory) {
                file.listFiles()?.forEach { recursiveDelete(it) }
            }
            file.delete()
        } catch (e: Exception) {
            LOG.e(e)
        }
    }

    @JvmStatic
    fun getExternalCachePath(context: Context): String =
        context.externalCacheDir?.absolutePath ?: context.cacheDir.absolutePath

    @JvmStatic
    fun getFileNameWithoutExt(filePath: String?): String {
        if (TextUtils.isEmpty(filePath)) return ""
        var fileName = filePath!!
        val p = fileName.lastIndexOf(File.separatorChar)
        if (p != -1) fileName = fileName.substring(p + 1)
        val dot = fileName.indexOf('.')
        if (dot != -1) fileName = fileName.substring(0, dot)
        return fileName
    }

    @JvmStatic
    fun isWeekAgo(file: File): Boolean {
        val oneWeekMillis = 15L * 24 * 60 * 60 * 1000
        val timeDiff = System.currentTimeMillis() - file.lastModified()
        return timeDiff > oneWeekMillis
    }
}
