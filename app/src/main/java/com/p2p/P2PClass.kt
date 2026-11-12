package com.p2p

import java.io.File

/**
 * 荐片 P2P 组件（libp2p.so）native 绑定。
 * 构造时启动本地 HTTP 服务（doxstarthttpd），通过 [port] 暴露端口，供播放器拉取 xgplay 资源。
 *
 * 注意：包名/类名/native 方法名必须与 libp2p.so 的 JNI 符号完全匹配。
 */
class P2PClass(cardPath: String) {

    var path: String? = null

    init {
        path = "$cardPath/jpali"
        File(path!!).apply { if (!exists()) mkdirs() }
        port = doxstarthttpd(SEED, cardPath.toByteArray())
    }

    companion object {
        /** 本地 HTTP 服务端口，由 doxstarthttpd 返回 */
        @Volatile
        var port = 8087

        private val SEED = "TEST3E63BAAECDAA79BEAA91853490A69F08".toByteArray()

        init {
            System.loadLibrary("p2p")
        }
    }

    // ==================== native 方法（JNI 绑定 libp2p.so）====================
    private external fun doxstarthttpd(bArr: ByteArray, bArr2: ByteArray): Int
    private external fun doxstart(bArr: ByteArray): Int
    private external fun doxdownload(bArr: ByteArray): Int
    private external fun doxterminate(): Int
    private external fun dosetupload(i: Int): Int
    private external fun doxadd(bArr: ByteArray): Int
    private external fun doxcheck(bArr: ByteArray): Int
    private external fun doxdel(bArr: ByteArray): Int
    private external fun doxdelall(): Int
    private external fun doxpause(bArr: ByteArray): Int
    private external fun doxsave(): Int
    private external fun doxendhttpd(): Int
    private external fun doxgetVersion(): String
    private external fun doxgethostbynamehook(str: String): String
    private external fun doxgetlocalAddress(): String
    private external fun doxgettaskstat(i: Int): String
    private external fun doxsetduration(i: Int): Int
    private external fun doxSetP2PPauseUpdate(i: Int)
    private external fun getdownsize(i: Int): Long
    private external fun getfilesize(i: Int): Long
    private external fun getlocalfilesize(bArr: ByteArray): Long
    private external fun getpercent(): Int
    private external fun getspeed(i: Int): Long
    private external fun XGFilmOpenFile(bArr: ByteArray): Long
    private external fun XGFilmCloseFile(j: Long)
    private external fun XGFilmReadFile(j: Long, j2: Long, i: Int, bArr: ByteArray): Int

    // 公开包装
    fun p2Pdoxstart(bArr: ByteArray): Int = doxstart(bArr)

    fun p2Pdoxdownload(bArr: ByteArray): Int = doxdownload(bArr)

    fun p2Pdoxterminate(): Int = doxterminate()

    fun p2Pdosetupload(i: Int): Int = dosetupload(i)

    fun p2Pdoxcheck(bArr: ByteArray): Int = doxcheck(bArr)

    fun p2Pdoxadd(bArr: ByteArray): Int = doxadd(bArr)

    fun p2Pdoxpause(bArr: ByteArray): Int = doxpause(bArr)

    fun p2Pdoxdel(bArr: ByteArray): Int = doxdel(bArr)

    fun p2PdoxdelAll(): Int = doxdelall()

    fun p2Pgetspeed(i: Int): Long = getspeed(i)

    fun p2Pgetdownsize(i: Int): Long = getdownsize(i)

    fun p2Pgetfilesize(i: Int): Long = getfilesize(i)

    fun p2Pgetpercent(): Int = getpercent()

    fun p2Pgetlocalfilesize(bArr: ByteArray): Long = getlocalfilesize(bArr)

    fun p2Pdosetduration(i: Int): Long = doxsetduration(i).toLong()

    fun getServiceAddress(): String = doxgethostbynamehook("xx0.github.com")

    fun p2Pdoxstarthttpd(bArr: ByteArray, bArr2: ByteArray): Int = doxstarthttpd(bArr, bArr2)

    fun p2Pdoxsave(): Int = doxsave()

    fun p2Pdoxendhttpd(): Int = doxendhttpd()

    fun getVersion(): String = doxgetVersion()

    fun xGFilmOpenFile(bArr: ByteArray): Long = XGFilmOpenFile(bArr)

    fun xGFilmCloseFile(j: Long) = XGFilmCloseFile(j)

    fun xGFilmReadFile(j: Long, j2: Long, i: Int, bArr: ByteArray): Int = XGFilmReadFile(j, j2, i, bArr)

    fun setP2PPauseUpdate(i: Int) = doxSetP2PPauseUpdate(i)

    fun getTouPingUrl(): String = doxgetlocalAddress()

    fun p2Pdoxgettaskstat(i: Int): String = doxgettaskstat(i)
}
