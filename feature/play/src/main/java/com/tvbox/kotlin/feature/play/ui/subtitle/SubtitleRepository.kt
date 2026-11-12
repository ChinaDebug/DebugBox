package com.tvbox.kotlin.feature.play.ui.subtitle

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.network.OkHttpHelper
import com.tvbox.kotlin.core.ui.R as uiR
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.URLDecoder
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * 在线字幕抓取仓库（assrt.net）。
 *
 * 能力与 [SearchSubtitleDialog] 配套：
 * - [searchResult]：搜索页抓取压缩包列表（支持分页）；
 * - [getSearchResultSubtitleUrls]：进入压缩包详情页抓取内部字幕文件；
 * - [getSubtitleUrl]：解析真实下载地址（跟随重定向取 location）。
 * 复用全局 [OkHttpHelper]（DoH DNS + Conscrypt SSL + Cookie 与缓存），
 * 避免自建裸客户端在部分网络环境下 DNS/HTTPS 握手失败导致搜不到字幕。
 * 所有回调均切回主线程执行。
 */
@Singleton
class SubtitleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpHelper: OkHttpHelper
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前生效搜索域名：主域名访问失败时切换为备用域名，后续详情/下载请求跟随生效域名，避免主站失效后链路全部落空 */
    @Volatile
    private var activeHost: String = ASSRT_HOST

    /** 当前生效域名的基础地址（拼接详情页/下载地址用） */
    private val activeBase: String get() = "https://$activeHost"

    private fun post(callback: () -> Unit) {
        mainHandler.post { callback() }
    }

    private fun htmlRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .get()
            .addHeader("User-Agent", AppConstants.UA_DESKTOP)
            .addHeader("Referer", "$activeBase/")
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
    }

    /** 搜索结果总页数缓存，超出后不再请求后续页 */
    private var pagesTotal = -1

    /** 搜索字幕（分页），回调在主线程执行 */
    fun searchResult(title: String, page: Int, callback: (SubtitleData) -> Unit) {
        if (pagesTotal > 0 && page > pagesTotal) {
            post { callback(SubtitleData().apply { isNew = page <= 1; isZip = true; subtitleList = emptyList() }) }
            return
        }
        if (page == 1) pagesTotal = -1
        searchByHost(activeHost, title, page, callback, retried = false)
    }

    /** 指定 host 发起搜索，失败时自动切换到备用域名重试一次并记录生效域名 */
    private fun searchByHost(host: String, title: String, page: Int, callback: (SubtitleData) -> Unit, retried: Boolean) {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(host)
            .addPathSegments("sub/")
            .addQueryParameter("searchword", title)
            .addQueryParameter("sort", "rank")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("no_redir", "1")
            .build()
        okHttpHelper.client().newCall(htmlRequest(url.toString())).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!retried) {
                    // 主域名不可达：切换备用域名重试，并记录生效域名供后续详情/下载请求使用
                    switchToFallback()
                    searchByHost(ASSRT_HOST_FALLBACK, title, page, callback, retried = true)
                } else {
                    post { callback(SubtitleData().apply { isNew = page <= 1; isZip = true; subtitleList = null }) }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                // 主域名返回非成功状态码（403/5xx 等）同样视为不可用，切换到备用域名重试
                if (!response.isSuccessful) {
                    response.close()
                    if (!retried) {
                        switchToFallback()
                        searchByHost(ASSRT_HOST_FALLBACK, title, page, callback, retried = true)
                    } else {
                        post { callback(SubtitleData().apply { isNew = page <= 1; isZip = true; subtitleList = null }) }
                    }
                    return
                }
                try {
                    val content = response.body?.string() ?: ""
                    val doc = Jsoup.parse(content)
                    val data = mutableListOf<SubtitleBean>()
                    val items = doc.select(".resultcard .sublist_box_title a.introtitle")
                    for (item in items) {
                        val title = item.attr("title")
                        val href = item.attr("href")
                        if (href.isEmpty()) continue
                        data.add(SubtitleBean().apply {
                            name = title
                            this.url = if (href.startsWith("http")) href else "$activeBase$href"
                            isZip = true
                        })
                    }
                    // 解析分页总数，供后续翻页提前结束
                    val pages = doc.select(".pagelinkcard a")
                    val lastPage = pages.lastOrNull()?.text().orEmpty()
                    val ps = lastPage.split("/", limit = 2)
                    if (ps.size == 2 && ps[1].trim().toIntOrNull() != null) {
                        pagesTotal = ps[1].trim().toInt()
                    }
                    post { callback(SubtitleData().apply { isNew = page <= 1; isZip = true; subtitleList = data }) }
                } catch (e: Throwable) {
                    if (!retried) {
                        switchToFallback()
                        searchByHost(ASSRT_HOST_FALLBACK, title, page, callback, retried = true)
                    } else {
                        post { callback(SubtitleData().apply { isNew = page <= 1; isZip = true; subtitleList = null }) }
                    }
                }
            }
        })
    }

    /** 切换并记录备用域名，后续详情页/下载请求跟随该域名，避免主站失效后链路全部落空 */
    private fun switchToFallback() {
        activeHost = ASSRT_HOST_FALLBACK
    }

    private val regexShooterFileOnclick = Pattern.compile("onthefly\\(\"(\\d+)\",\"(\\d+)\",\"([\\s\\S]*)\"\\)")

    /** 进入压缩包详情页抓取内部字幕文件列表，回调在主线程执行 */
    fun getSearchResultSubtitleUrls(subtitle: SubtitleBean, callback: (SubtitleData) -> Unit) {
        requestDetailPage(subtitle.url, callback, retried = false)
    }

    /** 请求压缩包详情页，失败时把 URL 主机替换为备用域名重试一次（主站失效后链路仍可用） */
    private fun requestDetailPage(pageUrl: String, callback: (SubtitleData) -> Unit, retried: Boolean) {
        okHttpHelper.client().newCall(htmlRequest(pageUrl)).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                retryWithFallbackUrl(pageUrl, retried) {
                    post { callback(SubtitleData().apply { isNew = true; isZip = false; subtitleList = null }) }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                // 非成功状态码（403/5xx 等）同样视为主站不可用，切换备用域名重试
                if (!response.isSuccessful) {
                    response.close()
                    retryWithFallbackUrl(pageUrl, retried) {
                        post { callback(SubtitleData().apply { isNew = true; isZip = false; subtitleList = null }) }
                    }
                    return
                }
                try {
                    val content = response.body?.string() ?: ""
                    val doc = Jsoup.parse(content)
                    val data = mutableListOf<SubtitleBean>()
                    val items = doc.select("#detail-filelist .waves-effect")
                    if (items.isNotEmpty()) {
                        // 压缩包内的字幕文件
                        for (item in items) {
                            val onclick = item.attr("onclick")
                            val matcher = regexShooterFileOnclick.matcher(onclick)
                            if (matcher.find()) {
                                val fileUrl = String.format(
                                    "$activeBase/download/%s/-/%s/%s",
                                    matcher.group(1), matcher.group(2), matcher.group(3)
                                )
                                data.add(SubtitleBean().apply {
                                    name = item.selectFirst("#filelist-name")?.text() ?: matcher.group(3)
                                    url = fileUrl
                                    isZip = false
                                })
                            }
                        }
                        post { callback(SubtitleData().apply { isNew = true; isZip = false; subtitleList = data }) }
                    } else {
                        // 部分字幕不是压缩包，直接提供下载
                        val item = doc.selectFirst(".download a#btn_download")
                        val href = item?.attr("href") ?: ""
                        val lower = href.lowercase()
                        if (lower.endsWith("srt") || lower.endsWith("ass") || lower.endsWith("scc") || lower.endsWith("ttml")) {
                            val title = href.substring(href.lastIndexOf("/") + 1)
                            data.add(SubtitleBean().apply {
                                name = runCatching { URLDecoder.decode(title, "UTF-8") }.getOrDefault(title)
                                url = "$activeBase$href"
                                isZip = false
                            })
                            post { callback(SubtitleData().apply { isNew = true; isZip = false; subtitleList = data }) }
                        } else {
                            post { callback(SubtitleData().apply { isNew = true; isZip = false; subtitleList = null }) }
                        }
                    }
                } catch (e: Throwable) {
                    retryWithFallbackUrl(pageUrl, retried) {
                        post { callback(SubtitleData().apply { isNew = true; isZip = false; subtitleList = null }) }
                    }
                }
            }
        })
    }

    /**
     * 详情页/下载页请求失败兜底：URL 主机替换为备用域名后重试一次。
     * [onGiveUp] 在重试后仍失败或无法替换（已在备用域名上失败）时调用。
     */
    private fun retryWithFallbackUrl(url: String, retried: Boolean, onGiveUp: () -> Unit) {
        val fallbackUrl = subtitleUrlWithHost(url, ASSRT_HOST_FALLBACK)
        if (!retried && fallbackUrl != url) {
            switchToFallback()
            requestDetailPage(fallbackUrl, { onGiveUp() }, retried = true)
        } else {
            onGiveUp()
        }
    }

    /** 将 URL 主机替换为目标域名，解析失败或已为目标域名时原样返回 */
    private fun subtitleUrlWithHost(url: String, host: String): String {
        return runCatching {
            val parsed = url.toHttpUrlOrNull() ?: return url
            val rebuilt = parsed.newBuilder().host(host).build().toString()
            if (rebuilt == url) url else rebuilt
        }.getOrDefault(url)
    }

    /** 解析真实字幕下载地址（不跟随重定向，取 location 头），失败时切备用域名重试，回调在主线程执行 */
    fun getSubtitleUrl(subtitle: SubtitleBean, loader: SearchSubtitleDialog.SubtitleLoader) {
        requestDownloadLocation(subtitle.url, subtitle, loader, retried = false)
    }

    /** 请求下载地址 location，失败时替换为备用域名重试一次 */
    private fun requestDownloadLocation(url: String, subtitle: SubtitleBean, loader: SearchSubtitleDialog.SubtitleLoader, retried: Boolean) {
        okHttpHelper.noRedirectClient().newCall(htmlRequest(url)).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                retryDownloadLocation(url, subtitle, loader, retried)
            }

            override fun onResponse(call: Call, response: Response) {
                val location = response.header("location")
                if (location.isNullOrEmpty()) {
                    response.close()
                    retryDownloadLocation(url, subtitle, loader, retried)
                    return
                }
                subtitle.url = location
                post { loader.loadSubtitle(subtitle) }
            }
        })
    }

    /** 下载地址请求失败兜底：主机替换为备用域名重试一次，仍失败则上报地址解析失败 */
    private fun retryDownloadLocation(url: String, subtitle: SubtitleBean, loader: SearchSubtitleDialog.SubtitleLoader, retried: Boolean) {
        val fallbackUrl = subtitleUrlWithHost(url, ASSRT_HOST_FALLBACK)
        if (!retried && fallbackUrl != url) {
            switchToFallback()
            requestDownloadLocation(fallbackUrl, subtitle, loader, retried = true)
        } else {
            post { loader.onLoadError(context.getString(uiR.string.subtitle_dl_addr_failed)) }
        }
    }

    companion object {
        /** 主搜索域名，部分网络无法访问时自动切换到 [ASSRT_HOST_FALLBACK] 重试 */
        private const val ASSRT_HOST = "2.assrt.net"
        private const val ASSRT_HOST_FALLBACK = "www.assrt.net"
    }
}
