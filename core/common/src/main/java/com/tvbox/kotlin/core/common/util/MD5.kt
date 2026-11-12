package com.tvbox.kotlin.core.common.util

import com.tvbox.kotlin.core.common.LOG
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/** MD5 摘要工具 */
object MD5 {

    /** 计算文件 MD5 */
    fun getFileMd5(f: File): String {
        val sb = StringBuilder()
        try {
            val md = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(4096)
            FileInputStream(f).use { fis ->
                var len = fis.read(buffer)
                while (len != -1) {
                    md.update(buffer, 0, len)
                    len = fis.read(buffer)
                }
            }
            val b = md.digest()
            for (value in b) {
                var d = value.toInt()
                if (d < 0) d = value.toInt() and 0xff
                if (d < 16) sb.append('0')
                sb.append(Integer.toHexString(d))
            }
        } catch (e: NoSuchAlgorithmException) {
            LOG.e(e)
        } catch (e: IOException) {
            LOG.e(e)
        }
        return sb.toString()
    }

    /** 生成 32 位 md5（每次创建局部实例，避免共享 MessageDigest 在多线程下互相污染） */
    fun string2MD5(inStr: String?): String? {
        if (inStr.isNullOrEmpty()) {
            return null
        }
        return try {
            val charArray = inStr.toCharArray()
            val byteArray = ByteArray(charArray.size)
            for (i in charArray.indices) byteArray[i] = charArray[i].code.toByte()
            val md5Bytes = MessageDigest.getInstance("MD5").digest(byteArray)
            val hexValue = StringBuilder()
            for (md5Byte in md5Bytes) {
                val `val` = md5Byte.toInt() and 0xff
                if (`val` < 16) hexValue.append('0')
                hexValue.append(Integer.toHexString(`val`))
            }
            hexValue.toString()
        } catch (_: NoSuchAlgorithmException) {
            null
        }
    }
}
