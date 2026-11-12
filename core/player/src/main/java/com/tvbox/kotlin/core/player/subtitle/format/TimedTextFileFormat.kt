package com.tvbox.kotlin.core.player.subtitle.format

import com.tvbox.kotlin.core.player.subtitle.exception.FatalParsingException
import com.tvbox.kotlin.core.player.subtitle.model.TimedTextObject
import java.io.IOException
import java.io.InputStream

/**
 * 字幕文件格式解析器接口。
 */
interface TimedTextFileFormat {

    /**
     * 解析字幕文件。
     *
     * @param fileName 文件名（用于推断扩展名与元信息）
     * @param is 字幕输入流
     * @return 解析后的 [TimedTextObject]
     */
    @Throws(IOException::class, FatalParsingException::class)
    fun parseFile(fileName: String, input: InputStream): TimedTextObject

    /**
     * 将 [TimedTextObject] 反向序列化为字幕文件内容。
     * @return String[]（每行一个元素）或 byte[]（STL 二进制）
     */
    fun toFile(tto: TimedTextObject): Any
}
