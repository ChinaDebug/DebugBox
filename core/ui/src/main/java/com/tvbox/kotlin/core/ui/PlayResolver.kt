package com.tvbox.kotlin.core.ui

import com.tvbox.kotlin.core.common.bean.ParseBean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * 播放解析契约接口。
 *
 * feature:play 模块通过此接口调用爬虫引擎获取播放内容并解析视频地址，
 * 避免直接依赖 core:spider。由 :app 模块提供实现并注入。
 */
interface PlayResolver {

    /**
     * 调用爬虫获取播放内容。
     *
     * @param sourceKey 站点源 key
     * @param flag 播放标志（对应 VodInfo.playFlag）
     * @param id 剧集 id（对应 VodSeries.id 或 url）
     * @param vipFlags VIP 标志列表
     * @return 播放内容 JSON 字符串，包含 url/parse/header/danmu 等字段；
     *         失败返回空字符串
     */
    suspend fun loadPlayerContent(
        sourceKey: String,
        flag: String,
        id: String,
        vipFlags: List<String>
    ): String

    /**
     * 使用解析器解析视频地址。
     *
     * - type 0（嗅探）：加载解析页拦截视频请求；
     * - type 1/2（JSON）：请求解析接口，从响应中提取实际播放地址；
     * - type 3（聚合）：同 JSON 处理；
     * - type 4（超级解析）：按 flag 聚合解析站，JSON 并行 + WebView 嗅探竞争。
     *
     * @param parseBean 解析器配置
     * @param url 原始视频 URL
     * @param flag 播放标志（超级解析按 flag 过滤解析站）
     * @return 解析结果，包含实际播放 URL 和请求头
     */
    suspend fun resolveVideo(parseBean: ParseBean, url: String, flag: String = ""): ResolveResult

    /**
     * 解析视频地址并持续收集备用地址。
     *
     * 与 [resolveVideo] 相同：type 4 超级解析下所有解析站并发竞速，首个成功结果立即返回；
     * 同时其余解析站后续竞速返回的成功地址经 [Pair.second] 通道持续上报，
     * 作为播放失败时的备用地址（加载提示不变，全部地址失败才提示失败）。
     *
     * 默认实现：委托 [resolveVideo]，备用通道为空（关闭）。
     */
    suspend fun resolveVideoWithBackups(
        parseBean: ParseBean,
        url: String,
        flag: String = ""
    ): Pair<ResolveResult, ReceiveChannel<ResolveResult>> {
        val empty = Channel<ResolveResult>().apply { close() }
        return resolveVideo(parseBean, url, flag) to empty
    }

    /** 获取默认解析器，null 表示未配置解析器 */
    suspend fun getDefaultParse(): ParseBean?

    /**
     * 归一化播放地址：
     * - proxy:// 本地代理地址（如 B 站类爬虫返回的 proxy://do=xxx）转为本机 http 代理地址，
     *   并附加 siteKey 供代理分发定位处理 jar；
     * - video:// 前缀剥除（需再走解析器）。
     * 其他地址原样返回。
     */
    suspend fun normalizePlayUrl(url: String, sourceKey: String = ""): String = url

    /**
     * 拉取弹幕内容，失败返回 null。
     *
     * 本地代理地址（含 127.0.0.1/localhost）直接调用爬虫 proxyLocal 获取内容，
     * 不依赖端口占位是否与实际监听端口一致（Parser.getXmlContent）；
     * 其余地址按 HTTP 拉取。
     */
    suspend fun fetchDanmu(url: String): String?

    /**
     * 判断播放标志是否命中源的 VIP 解析规则。
     * 未命中时解析流程应直接嗅探播放地址本身，而非套用默认解析器。
     */
    suspend fun containsFlag(flag: String): Boolean

    /**
     * 特殊播放协议处理：
     * tvbox-drive:// 直链 / 荐片 / magnet/thunder 下载类 / 空地址 / type 0-1 非 http 校验。
     * 返回 null 表示地址为普通协议，走常规解析流程。
     */
    suspend fun resolvePlayProtocol(episodeUrl: String, sourceKey: String): ProtocolResult?

    /**
     * 停止下载类协议的后台任务（开源 BT 下载/荐片 P2P），播放器销毁时调用
     *（Thunder.stop() 与 Jianpian.finish）。
     */
    fun stopDownloadProtocols()

    /** 特殊协议处理结果：url 非空即可播放；error 非空表示协议识别后不可播放 */
    data class ProtocolResult(
        val url: String = "",
        val headers: Map<String, String>? = null,
        val error: String? = null
    )

    /** 解析结果 */
    data class ResolveResult(
        val url: String,
        val headers: Map<String, String>? = null,
        /** 解析失败原因（url 为空时有效），用于上层展示具体错误 */
        val error: String? = null,
        /** 解析来源名称（嗅探/解析命中反查），由播放层在真正播放成功后提示 */
        val sourceName: String? = null
    )
}
