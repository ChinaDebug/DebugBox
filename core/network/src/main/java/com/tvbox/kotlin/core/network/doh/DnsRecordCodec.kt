package com.tvbox.kotlin.core.network.doh

import java.io.EOFException
import java.net.InetAddress
import java.net.UnknownHostException
import okio.Buffer
import okio.ByteString
import okio.utf8Size

/**
 * DNS 报文编解码，仅覆盖 DoH 所需的最小查询编码与应答解析。
 */
internal object DnsRecordCodec {

    private const val SERVFAIL: Byte = 2
    private const val NXDOMAIN: Byte = 3
    private const val TYPE_PTR: Int = 0x000c

    const val TYPE_A: Int = 0x0001
    const val TYPE_AAAA: Int = 0x001c

    /** 编码 DNS 查询报文 */
    fun encodeQuery(host: String, type: Int): ByteString {
        val buf = Buffer()
        buf.writeShort(0) // 查询 ID
        buf.writeShort(256) // 标志，启用递归
        buf.writeShort(1) // 问题数
        buf.writeShort(0) // 应答数
        buf.writeShort(0) // 授权记录数
        buf.writeShort(0) // 附加记录数

        val nameBuf = Buffer()
        val labels = host.split(".")
        for (label in labels) {
            val utf8ByteCount = label.utf8Size()
            if (utf8ByteCount != label.length.toLong()) {
                throw IllegalArgumentException("non-ascii hostname: $host")
            }
            nameBuf.writeByte(utf8ByteCount.toInt())
            nameBuf.writeUtf8(label)
        }
        nameBuf.writeByte(0) // 终止符

        nameBuf.copyTo(buf, 0, nameBuf.size)
        buf.writeShort(type)
        buf.writeShort(1) // CLASS_IN

        return buf.readByteString()
    }

    /** 解码 DNS 应答中的地址记录 */
    @Throws(Exception::class)
    fun decodeAnswers(hostname: String, byteString: ByteString): List<InetAddress> {
        val result = ArrayList<InetAddress>()
        val buf = Buffer()
        buf.write(byteString)
        buf.readShort() // 查询 ID

        val flags = buf.readShort().toInt() and 0xffff
        if (flags shr 15 == 0) {
            throw IllegalArgumentException("not a response")
        }

        val responseCode = (flags and 0xf).toByte()
        if (responseCode == NXDOMAIN) {
            throw UnknownHostException("$hostname: NXDOMAIN")
        } else if (responseCode == SERVFAIL) {
            throw UnknownHostException("$hostname: SERVFAIL")
        }

        val questionCount = buf.readShort().toInt() and 0xffff
        val answerCount = buf.readShort().toInt() and 0xffff
        buf.readShort() // 授权记录数
        buf.readShort() // 附加记录数

        for (i in 0 until questionCount) {
            skipName(buf)
            buf.readShort() // type
            buf.readShort() // class
        }

        for (i in 0 until answerCount) {
            skipName(buf)
            val type = buf.readShort().toInt() and 0xffff
            buf.readShort() // class
            buf.readInt() // ttl
            val length = buf.readShort().toInt() and 0xffff

            if (type == TYPE_A || type == TYPE_AAAA) {
                val bytes = ByteArray(length)
                buf.read(bytes)
                result.add(InetAddress.getByAddress(bytes))
            } else {
                buf.skip(length.toLong())
            }
        }
        return result
    }

    @Throws(EOFException::class)
    private fun skipName(buf: Buffer) {
        // 0-63 字节为标签长度，首位置 1 表示压缩指针
        var length = buf.readByte().toInt()
        if (length < 0) {
            buf.skip(1)
        } else {
            while (length > 0) {
                buf.skip(length.toLong())
                length = buf.readByte().toInt()
            }
        }
    }
}
