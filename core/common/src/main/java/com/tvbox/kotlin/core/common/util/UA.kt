package com.tvbox.kotlin.core.common.util

import android.content.Context
import com.tvbox.kotlin.core.common.LOG
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Random

/** User-Agent 工具 */
object UA {

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36"

    /** 从 assets 的 ua.db 中随机读取一条 UA */
    @JvmStatic
    fun random(context: Context? = null): String {
        val ctx = context ?: return DEFAULT_UA
        return try {
            val fis: InputStream = ctx.assets.open("ua.db")
            val dis = DataInputStream(fis)
            val len = dis.readInt()
            val random = Random().nextInt(len)
            dis.skipBytes(random * 4)
            val offset = dis.readInt()
            dis.skipBytes((len - 1 - random) * 4 + offset)
            dis.readUTF()
        } catch (e: IOException) {
            LOG.e(e)
            DEFAULT_UA
        }
    }
}
