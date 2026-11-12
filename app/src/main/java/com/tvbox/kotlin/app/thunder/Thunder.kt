package com.tvbox.kotlin.app.thunder

import android.util.Base64
import com.tvbox.kotlin.app.TvBoxApp
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.UiResources
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 下载类协议解析入口（magnet / thunder:// / ftp）。
 *
 * 底层由开源 BT 引擎实现（[BtEngine] + [BtStreamServer]）：磁力经本地 HTTP 流边下边播，
 * 无迅雷版权拦截；thunder:// 解密后 http 直链直接播、magnet 走 BT 引擎；ed2k 与 ftp 直链不支持。
 * 公开接口沿用旧迅雷封装，供播放解析链调用。
 */
object Thunder {

    private var cacheRoot = ""
    private var threadPool: ExecutorService? = null
    private val streamServer = BtStreamServer(BtEngine)

    /** 下载任务回调；[status] 的 code<0 表示失败并携带文案 */
    interface ThunderCallback {
        fun status(code: Int, info: String)
        fun play(url: String)
    }

    fun isFtp(url: String): Boolean = url.lowercase().startsWith("ftp://")

    private fun isMagnet(url: String): Boolean = url.lowercase().startsWith("magnet:")

    private fun isThunderUrl(url: String): Boolean = url.lowercase().startsWith("thunder://")

    /** 是否下载类协议（ed2k 已随迅雷移除） */
    fun isNetworkDownloadTask(url: String): Boolean =
        isMagnet(url) || isThunderUrl(url) || isFtp(url)

    /**
     * 解析下载类地址，成功后回调本地 HTTP 播放地址。
     * @return true 表示地址已接管；false 表示非下载类协议
     */
    fun play(url: String, callback: ThunderCallback): Boolean {
        when {
            isMagnet(url) -> {
                playMagnet(url, callback)
                return true
            }
            isThunderUrl(url) -> {
                val decoded = runCatching { decodeThunderUrl(url) }.getOrNull()
                when {
                    decoded.isNullOrEmpty() || (!decoded.startsWith("http", true) && !decoded.startsWith("magnet:", true)) ->
                        callback.status(-1, UiResources.string(uiR.string.thunder_invalid_link))
                    decoded.startsWith("http", true) -> callback.play(decoded)
                    else -> playMagnet(decoded, callback)
                }
                return true
            }
            isFtp(url) -> {
                // 荐片 xgplay 的 ftp（含 gbl.114s）已被协议链 Jianpian 分支截获，此处为普通 ftp 直链
                callback.status(-1, UiResources.string(uiR.string.bt_ftp_unsupported))
                return true
            }
        }
        return false
    }

    /** 磁力解析：任务就绪后回调本地 HTTP 播放地址 */
    private fun playMagnet(magnet: String, callback: ThunderCallback) {
        if (threadPool == null) {
            cacheRoot = TvBoxApp.instance.cacheDir.absolutePath + File.separator + "bt"
            threadPool = Executors.newSingleThreadExecutor()
        }
        threadPool!!.execute {
            try {
                val task = BtEngine.startMagnet(magnet, File(cacheRoot))
                streamServer.ensureStarted()
                callback.play(streamServer.playUrl())
            } catch (e: BtEngine.BtException) {
                val msg = when (e.kind) {
                    BtEngine.ErrorKind.META_TIMEOUT -> UiResources.string(uiR.string.thunder_parse_timeout)
                    BtEngine.ErrorKind.NO_VIDEO_FILE -> UiResources.string(uiR.string.bt_no_video_file)
                    BtEngine.ErrorKind.OTHER -> e.message ?: UiResources.string(uiR.string.thunder_download_error)
                }
                callback.status(-1, msg)
            } catch (e: Throwable) {
                LOG.e("Thunder", "磁力解析失败", e)
                callback.status(-1, UiResources.string(uiR.string.thunder_download_error))
            }
        }
    }

    /** 停止当前 BT 任务（保留已下载缓存供同源复用，缓存整体清理由"设置-清理缓存"兜底） */
    fun stop() {
        BtEngine.stopCurrent()
    }

    /** thunder:// 链接解密：Base64 解码后去除首尾 AA/ZZ 包裹 */
    private fun decodeThunderUrl(url: String): String {
        val raw = String(Base64.decode(url.removePrefix("thunder://"), Base64.DEFAULT), Charsets.UTF_8)
        var inner = raw
        if (inner.startsWith("AA")) inner = inner.substring(2)
        if (inner.endsWith("ZZ")) inner = inner.dropLast(2)
        return inner
    }
}
