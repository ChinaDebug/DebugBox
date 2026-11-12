package com.github.catvod.crawler

import android.content.Context
import okhttp3.Dns
import java.util.HashMap

/**
 * 爬虫契约基类，jar 内爬虫继承此类实现站点采集逻辑。
 * 方法签名与 com.github.catvod.crawler.Spider 完全一致，保证旧 jar 爬虫兼容。
 * 默认返回空实现，子类按需重写。
 */
open class Spider {

    /** 站点 key，由 JarLoader 在创建 Spider 实例时回填，便于 jar 内爬虫识别自身站点 */
    @JvmField
    var siteKey: String? = null

    @Throws(Exception::class)
    open fun init(context: Context) {
    }

    @Throws(Exception::class)
    open fun init(context: Context, extend: String) {
        init(context)
    }

    /** 由 JarLoader 注入 SpiderApi，jar 内爬虫可重写以使用主进程提供的服务地址、并发请求等能力 */
    open fun initApi(api: SpiderApi) {
    }

    /** 首页数据内容，filter 是否开启筛选 */
    @Throws(Exception::class)
    open fun homeContent(filter: Boolean): String {
        return ""
    }

    /** 首页最近更新数据，homeContent 不含最近更新时可由此接口返回 */
    @Throws(Exception::class)
    open fun homeVideoContent(): String {
        return ""
    }

    /** 分类数据 */
    @Throws(Exception::class)
    open fun categoryContent(tid: String, pg: String, filter: Boolean, extend: HashMap<String, String>): String {
        return ""
    }

    /** 详情数据 */
    @Throws(Exception::class)
    open fun detailContent(ids: List<String>): String {
        return ""
    }

    /** 搜索数据内容 */
    @Throws(Exception::class)
    open fun searchContent(key: String, quick: Boolean): String {
        return ""
    }

    /** 搜索数据内容（分页） */
    @Throws(Exception::class)
    open fun searchContent(key: String, quick: Boolean, pg: String): String {
        return ""
    }

    /** 播放信息 */
    @Throws(Exception::class)
    open fun playerContent(flag: String, id: String, vipFlags: List<String>): String {
        return ""
    }

    /** webview 解析时使用，自定义判断当前加载的 url 是否是视频 */
    @Throws(Exception::class)
    open fun manualVideoCheck(): Boolean {
        return false
    }

    /** 是否手动检测 webview 中加载的 url */
    @Throws(Exception::class)
    open fun isVideoFormat(url: String): Boolean {
        return false
    }

    @Throws(Exception::class)
    open fun proxyLocal(params: Map<String, String>): Array<Any?>? {
        return null
    }

    /** 卡片 action 回调，jar 内爬虫可重写以实现配置中心等带分类菜单的站点 */
    @Throws(Exception::class)
    open fun action(action: String): String? {
        return null
    }

    open fun cancelByTag() {
    }

    open fun destroy() {
    }

    /** 直播列表内容 */
    open fun liveContent(url: String): String {
        return ""
    }

    companion object {
        /** 安全 DNS，jar 内爬虫可通过此获取 Dns 实现 */
        @JvmStatic
        fun safeDns(): Dns = Dns.SYSTEM
    }
}
