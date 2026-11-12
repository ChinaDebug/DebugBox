package com.tvbox.kotlin.app.thunder

import android.net.Uri
import com.p2p.P2PClass
import com.tvbox.kotlin.app.TvBoxApp
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.util.LocalIPAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset

/** 荐片 P2P 交互使用 GBK 编码（getBytes("GBK")） */
private val GBK = Charset.forName("GBK")

/**
 * 荐片（xgplay）协议封装。
 * 通过 P2P 组件（libp2p.so）把 xg:// 资源转成本地 HTTP 地址供播放器拉流。
 */
object Jianpian {

    /** 是否为荐片地址：tvbox-xg: 前缀，或含 gbl.114s 的 ftp 地址 */
    fun isJpUrl(url: String): Boolean =
        url.startsWith("tvbox-xg:") || (Thunder.isFtp(url) && url.contains("gbl.114s"))

    /**
     * 解码荐片地址：xg:// 转 ftp:// 后交由 P2P 组件下载，返回本地 HTTP 播放地址。
     */
    fun jpUrlDec(url: String): String {
        val p2p = TvBoxApp.getP2p() ?: return ""
        return try {
            val decode = URLDecoder.decode(url, "UTF-8")
            val split = decode.split("|")
            var replace = split[0].replace("xg://", "ftp://")
            if (replace.contains("xgplay://")) {
                replace = split[0].replace("xgplay://", "ftp://")
            }
            // 切换资源前先暂停/删除旧任务，避免 P2P 缓存堆积
            if (TvBoxApp.burl.isNotEmpty()) {
                p2p.p2Pdoxpause(TvBoxApp.burl.toByteArray(GBK))
                p2p.p2Pdoxdel(TvBoxApp.burl.toByteArray(GBK))
            }
            TvBoxApp.burl = replace
            p2p.p2Pdoxstart(replace.toByteArray(GBK))
            p2p.p2Pdoxadd(replace.toByteArray(GBK))
            val lastPath = Uri.parse(replace).lastPathSegment
            "http://" + LocalIPAddress.getIP(TvBoxApp.instance) + ":" + P2PClass.port + "/" +
                URLEncoder.encode(lastPath ?: "", "GBK")
        } catch (e: Exception) {
            e.localizedMessage ?: ""
        }
    }

    /** 停止当前荐片任务并清理缓存标记 */
    fun finish() {
        val current = TvBoxApp.burl
        if (current.isEmpty()) return
        val p2p = TvBoxApp.getP2p()
        if (p2p != null) {
            try {
                p2p.p2Pdoxpause(current.toByteArray(GBK))
                p2p.p2Pdoxdel(current.toByteArray(GBK))
                TvBoxApp.burl = ""
            } catch (e: Throwable) {
                LOG.e(e)
            }
        }
    }
}
