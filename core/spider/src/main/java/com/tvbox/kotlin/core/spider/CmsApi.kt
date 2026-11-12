package com.tvbox.kotlin.core.spider

import android.util.Base64
import com.thoughtworks.xstream.XStream
import com.thoughtworks.xstream.io.xml.DomDriver
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.bean.Movie
import com.tvbox.kotlin.core.common.bean.SourceBean
import com.tvbox.kotlin.core.common.util.DefaultConfig
import com.tvbox.kotlin.core.network.OkHttpHelper
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CMS 采集站 HTTP 直连客户端。
 *
 * 对非 Spider 站点（type 0 XML / type 1 JSON / type 4 JSON）不走爬虫，
 * 而是直接 GET 采集站 api 并解析返回内容
 */
@Singleton
class CmsApi @Inject constructor(
    private val okHttpHelper: OkHttpHelper
) {

    /**
     * 直连 GET 采集站接口，失败返回 null。
     *
     * 先以浏览器 UA 请求：部分站点（如樱花）对 okhttp UA 直接返回 403；
     * 仅在被 UA 拦截（401/403/406）时改用 okhttp UA 重试，超时/断网等失败不重试，
     * 避免单次请求翻倍等待。
     */
    fun get(url: String, params: Map<String, String>): String? {
        if (url.isBlank()) return null
        val first = executeGet(url, params, USER_AGENT)
        if (first.second != null) return first.second
        return if (first.first in UA_BLOCK_CODES) executeGet(url, params, LEGACY_USER_AGENT).second else null
    }

    /** 以指定 UA 执行一次 GET，返回 (HTTP 状态码, 响应体)，异常时状态码为 0 */
    private fun executeGet(url: String, params: Map<String, String>, ua: String): Pair<Int, String?> {
        return try {
            val builder = url.toHttpUrlOrNull()?.newBuilder() ?: return 0 to null
            params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            val request = Request.Builder()
                .url(builder.build())
                .header("User-Agent", ua)
                .header("Accept", REQUEST_ACCEPT)
                .build()
            val call = okHttpHelper.client().newCall(request)
            // 单请求绝对超时：全局 OkHttp 的 readTimeout 默认 30s，且阻塞的 execute()
            // 不会被外部协程的 withTimeoutOrNull 中断会卡满 30s；
            // 这里用 Call.timeout 限定整个请求（连接+读+写）时长，超时即抛异常
            call.timeout().timeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            call.execute().use { resp ->
                if (!resp.isSuccessful) return resp.code to null
                val body = resp.body
                resp.code to decodeBody(body?.bytes(), body?.contentType()?.charset())
            }
        } catch (_: Throwable) {
            0 to null
        }
    }

    /** 按响应声明的字符集解码；未声明或解码出现替换符时按 GBK 兜底，兼容国内常见 GBK 采集站 */
    private fun decodeBody(bytes: ByteArray?, charset: java.nio.charset.Charset?): String? {
        if (bytes == null) return null
        val utf8 = String(bytes, charset ?: Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return try {
            val gbk = String(bytes, java.nio.charset.Charset.forName("GBK"))
            if (gbk.contains('\uFFFD')) utf8 else gbk
        } catch (_: Throwable) {
            utf8
        }
    }

    /** 采集站搜索，按参数分派并解析（type 0 优先 XML，失败回退 JSON） */
    fun search(source: SourceBean, wd: String): List<Movie.Video> {
        val api = source.api ?: return emptyList()
        val sourceKey = source.key ?: ""
        return when (source.type) {
            0 -> {
                val text = get(api, mapOf("wd" to wd)) ?: return emptyList()
                parseXmlList(text, sourceKey).ifEmpty { parseJsonList(text, sourceKey) }
            }
            1 -> {
                val text = get(api, mapOf("wd" to wd, "ac" to "detail")) ?: return emptyList()
                parseJsonList(text, sourceKey)
            }
            4 -> {
                val text = get(api, mapOf("wd" to wd, "ac" to "detail", "quick" to "false")) ?: return emptyList()
                parseJsonList(text, sourceKey)
            }
            else -> emptyList()
        }
    }

    /** 采集站详情，按参数分派并解析，返回首个影片 */
    fun detail(source: SourceBean, id: String): Movie.Video? {
        val api = source.api ?: return null
        val extend = source.ext ?: ""
        val sourceKey = source.key ?: ""
        return when (source.type) {
            0 -> {
                val text = get(api, mapOf("ac" to "videolist", "ids" to id, "extend" to extend)) ?: return null
                val list = parseXmlList(text, sourceKey).ifEmpty { parseJsonList(text, sourceKey) }
                list.firstOrNull()
            }
            1, 4 -> {
                val text = get(api, mapOf("ac" to "detail", "ids" to id, "extend" to extend)) ?: return null
                parseJsonList(text, sourceKey).firstOrNull()
            }
            else -> null
        }
    }

    /**
     * 采集站分类内容直连（type 0 XML / type 1 JSON / type 4 JSON），返回标准 JSON 字符串，
     * 与 ApiConfig.categoryContent 的返回契约一致（{"list":[...],"pagecount":N}）。
     * 直连请求可并行执行。
     */
    fun categoryJson(source: SourceBean, tid: String, pg: String, extend: Map<String, String>): String {
        val api = source.api ?: return ""
        val sourceKey = source.key ?: ""
        val params = LinkedHashMap<String, String>()
        params["t"] = tid
        params["pg"] = pg
        // 筛选项既作为独立参数也编码进 f/ext
        extend.forEach { (k, v) -> if (k.isNotBlank() && v.isNotBlank()) params[k] = v }
        return when (source.type) {
            0 -> {
                params["ac"] = "videolist"
                params["f"] = if (extend.isEmpty()) "" else JSONObject(extend).toString()
                val text = get(api, params) ?: return ""
                val videos = parseXmlList(text, sourceKey)
                if (videos.isEmpty()) return text // 解析失败时透传原响应，交由上层兜底
                buildCategoryJson(videos, extractXmlPageCount(text))
            }
            1 -> {
                params["ac"] = "detail"
                params["f"] = if (extend.isEmpty()) "" else JSONObject(extend).toString()
                get(api, params) ?: ""
            }
            4 -> {
                params["ac"] = "detail"
                params["filter"] = "true"
                if (extend.isNotEmpty()) {
                    try {
                        params["ext"] = Base64.encodeToString(
                            JSONObject(extend).toString().toByteArray(Charsets.UTF_8),
                            Base64.DEFAULT or Base64.NO_WRAP
                        )
                    } catch (_: Throwable) {
                    }
                }
                get(api, params) ?: ""
            }
            else -> ""
        }
    }

    /**
     * 采集站首页分类直连（type 0/1/4），返回标准 `{"class":[{"type_id","type_name"}]}` JSON。
     *
     * 采集站配置没有 jar 时 homeContent 会退化成 SpiderNull 返回空串，首页便无分类；
     * CMS 的 JSON 列表接口不返回分类，只有 XML 列表接口带 `<class>` 节点，
     * 故优先请求 XML 路由，该路由不可用时回退为 JSON 列表的 type 分类。
     */
    fun homeContentJson(source: SourceBean): String {
        val api = source.api ?: return ""
        if (api.isBlank()) return ""
        val result = fetchXmlClasses(api).ifEmpty { fetchJsonClasses(api) }
        if (result.isEmpty()) LOG.w("采集站首页分类为空: ${source.name} $api")
        return result
    }

    /** 请求 CMS XML 列表接口，解析 `<class><ty id="x">名称</ty></class>` 分类 */
    private fun fetchXmlClasses(api: String): String {
        val text = get(buildXmlListUrl(api), emptyMap()) ?: return ""
        if (text.isBlank()) return ""
        val classes = ArrayList<Pair<String, String>>()
        CLASS_TY_REGEX.findAll(text).forEach { m ->
            val id = m.groupValues[1].trim()
            val name = cleanXmlText(m.groupValues[2])
            if (id.isNotEmpty() && name.isNotEmpty()) classes.add(id to name)
        }
        return buildClassJson(classes)
    }

    /**
     * 构造 XML 分类接口地址：去掉 query/hash 后在 api 末尾追加 at/xml；
     * 配置已写死 at/json 路由时替换为 at/xml（苹果CMS 两种路由前缀相同）。
     */
    private fun buildXmlListUrl(api: String): String {
        val clean = api.substringBefore('#').substringBefore('?').trimEnd('/')
        val xmlBase = when {
            clean.contains("/at/json") -> clean.replace("/at/json", "/at/xml")
            clean.contains("/at/") -> clean
            else -> "$clean/at/xml"
        }
        return "$xmlBase/?ac=list"
    }

    /** 去掉分类名称里的 CDATA 包裹、内嵌标签与 XML 实体 */
    private fun cleanXmlText(text: String): String {
        var s = text.replace("<![CDATA[", "").replace("]]>", "")
        if (s.contains('<')) s = s.replace(Regex("<[^>]*>"), "")
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
            .replace("&apos;", "'")
            .trim()
    }

    /** 回退方案：优先取 JSON 的 class 数组，缺失时用列表首屏 type_id/type_name 去重 */
    private fun fetchJsonClasses(api: String): String {
        val text = get(api, mapOf("ac" to "list")) ?: return ""
        try {
            val root = JSONObject(text)
            val classArr = root.optJSONArray("class")
            if (classArr != null && classArr.length() > 0) {
                val classes = ArrayList<Pair<String, String>>()
                for (i in 0 until classArr.length()) {
                    val obj = classArr.optJSONObject(i) ?: continue
                    val id = obj.optString("type_id").trim()
                    val name = obj.optString("type_name").trim()
                    if (id.isNotEmpty() && name.isNotEmpty()) classes.add(id to name)
                }
                return buildClassJson(classes)
            }
            val arr = root.optJSONArray("list") ?: return ""
            val classes = LinkedHashMap<String, String>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("type_id").trim()
                val name = obj.optString("type_name").trim()
                if (id.isNotEmpty() && name.isNotEmpty()) classes.putIfAbsent(id, name)
            }
            return buildClassJson(classes.map { it.key to it.value })
        } catch (_: Throwable) {
            return ""
        }
    }

    /** 序列化分类列表为 homeContent 契约 JSON，按 type_id 去重，空列表返回空串交由上层兜底 */
    private fun buildClassJson(classes: List<Pair<String, String>>): String {
        val arr = JSONArray()
        val seen = HashSet<String>()
        for ((id, name) in classes) {
            if (!seen.add(id)) continue
            arr.put(JSONObject().put("type_id", id).put("type_name", name))
        }
        if (arr.length() == 0) return ""
        return JSONObject().put("class", arr).toString()
    }

    /**
     * 采集站播放信息直连（type 0/1/4），返回标准播放 JSON 字符串，失败返回空串。
     *
     * - type 0/1：片源详情已携带真实播放地址，直接构造播放 JSON。若地址为视频格式
     *   且未配置 playUrl 解析器，则 parse=0 直接播放，否则 parse=1 走解析流程；
     * - type 4：GET api?play=url&flag=flag&extend=extend 透传采集站播放接口返回内容。
     *
     * @param flag 播放线路标识
     * @param id 剧集地址或 ID
     */
    fun playInfo(source: SourceBean, flag: String, id: String): String {
        val api = source.api ?: return ""
        return when (source.type) {
            0, 1 -> {
                val playerUrl = (source.playerUrl ?: "").trim()
                val directPlay = DefaultConfig.isVideoFormat(id) && playerUrl.isEmpty()
                try {
                    val result = JSONObject()
                    result.put("key", id)
                    result.put("flag", flag)
                    result.put("parse", if (directPlay) 0 else 1)
                    result.put("url", id)
                    result.put("playUrl", playerUrl)
                    result.toString()
                } catch (_: Throwable) {
                    ""
                }
            }
            4 -> {
                val text = get(
                    api,
                    mapOf("play" to id, "flag" to flag, "extend" to (source.ext ?: ""))
                ) ?: return ""
                try {
                    // 透传采集站播放接口返回，补充 key/flag 字段
                    JSONObject(text).put("key", id).put("flag", flag).toString()
                } catch (_: Throwable) {
                    text
                }
            }
            else -> ""
        }
    }

    /** 从 CMS XML 中提取 pagecount 属性（<list page="1" pagecount="N" total="...">） */
    private fun extractXmlPageCount(text: String): Int {
        val attr = Regex("pagecount\\s*=\\s*\"(\\d+)\"").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (attr != null) return attr
        return Regex("<pagecount>(\\d+)</pagecount>").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    /** 将解析出的影片列表重新序列化为标准分类 JSON 字符串 */
    private fun buildCategoryJson(videos: List<Movie.Video>, pageCount: Int): String {
        val arr = JSONArray()
        for (v in videos) {
            val obj = JSONObject()
            obj.put("vod_id", v.id ?: "")
            obj.put("vod_name", v.name ?: "")
            obj.put("vod_pic", v.pic ?: "")
            obj.put("vod_lang", v.lang ?: "")
            obj.put("vod_area", v.area ?: "")
            obj.put("vod_year", v.year ?: 0)
            obj.put("vod_remarks", v.note ?: "")
            obj.put("vod_actor", v.actor ?: "")
            obj.put("vod_director", v.director ?: "")
            obj.put("type_name", v.type ?: "")
            obj.put("vod_time", v.last ?: "")
            obj.put("vod_tag", v.tag ?: "")
            arr.put(obj)
        }
        val result = JSONObject()
        result.put("list", arr)
        result.put("pagecount", pageCount)
        return result.toString()
    }

    /** 解析采集站 JSON 返回内容（{list:[...]} 结构） */
    fun parseJsonList(text: String, sourceKey: String): List<Movie.Video> {
        if (text.isBlank()) return emptyList()
        return try {
            val obj = JSONObject(text)
            if (!obj.has("list")) return emptyList()
            val arr = obj.getJSONArray("list")
            val videos = ArrayList<Movie.Video>()
            for (i in 0 until arr.length()) {
                val v = parseVideo(arr.getJSONObject(i), sourceKey)
                if (v.id.isNullOrBlank() && v.name.isNullOrBlank()) continue
                videos.add(v)
            }
            videos
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 解析采集站 XML 返回内容（<list><video>...</video></list> 结构），填充 beanList */
    fun parseXmlList(text: String, sourceKey: String): List<Movie.Video> {
        if (text.isBlank()) return emptyList()
        return try {
            val xstream = XStream(DomDriver())
            xstream.autodetectAnnotations(true)
            xstream.processAnnotations(Movie::class.java)
            xstream.ignoreUnknownElements()
            xstream.allowTypes(
                arrayOf(
                    Movie::class.java,
                    Movie.Video::class.java,
                    Movie.Video.UrlBean::class.java,
                    Movie.Video.UrlBean.UrlInfo::class.java,
                    Movie.Video.UrlBean.UrlInfo.InfoBean::class.java
                )
            )
            val movie = xstream.fromXML(text) as? Movie ?: return emptyList()
            movie.videoList?.onEach { video ->
                video.sourceKey = sourceKey
                fillBeanList(video)
            } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 解析单个影片 JSON 对象 */
    private fun parseVideo(obj: JSONObject, sourceKey: String): Movie.Video {
        val video = Movie.Video()
        video.tag = obj.optString("vod_tag")
        video.last = obj.optString("vod_time")
        video.id = obj.optString("vod_id")
        video.tid = obj.optInt("type_id")
        video.name = obj.optString("vod_name")
        video.type = obj.optString("type_name")
        video.pic = obj.optString("vod_pic")
        video.lang = obj.optString("vod_lang")
        video.area = obj.optString("vod_area")
        video.year = obj.optString("vod_year").toIntOrNull() ?: 0
        video.state = obj.optString("vod_state")
        video.note = obj.optString("vod_remarks")
        video.actor = obj.optString("vod_actor")
        video.director = obj.optString("vod_director")
        video.des = obj.optString("vod_content")
        video.action = obj.optString("action")
        video.sourceKey = sourceKey

        val playFrom = obj.optString("vod_play_from")
        val playUrl = obj.optString("vod_play_url")
        if (playFrom.isNotEmpty() && playUrl.isNotEmpty()) {
            val playFlags = playFrom.split("$$$")
            val playUrls = playUrl.split("$$$")
            val infoList = ArrayList<Movie.Video.UrlBean.UrlInfo>()
            for (i in playUrls.indices) {
                if (playUrls[i].isEmpty()) continue
                val urlInfo = Movie.Video.UrlBean.UrlInfo()
                urlInfo.flag = if (i >= playFlags.size || playFlags[i].isEmpty()) "线路$i" else playFlags[i]
                urlInfo.urls = playUrls[i]
                infoList.add(urlInfo)
            }
            val urlBean = Movie.Video.UrlBean()
            urlBean.infoList = infoList
            video.urlBean = urlBean
        }
        fillBeanList(video)
        return video
    }

    /**
     * 将 urls 原始串按 # 拆集、$ 拆「集名|地址」填充 beanList，
     * VodInfo.setVideo 依赖该字段构建线路集数。
     */
    fun fillBeanList(video: Movie.Video) {
        video.urlBean?.infoList?.forEach { urlInfo ->
            val urls = urlInfo.urls.orEmpty()
            val parts = if (urls.contains("#")) urls.split("#") else listOf(urls)
            val beans = ArrayList<Movie.Video.UrlBean.UrlInfo.InfoBean>()
            for (s in parts) {
                val ss = s.split("\\$".toRegex())
                if (ss.isEmpty()) continue
                if (ss.size >= 2) {
                    beans.add(Movie.Video.UrlBean.UrlInfo.InfoBean(ss[0], ss[1]))
                } else {
                    beans.add(Movie.Video.UrlBean.UrlInfo.InfoBean((beans.size + 1).toString(), ss[0]))
                }
            }
            urlInfo.beanList = beans
        }
    }

    private companion object {
        /** 采集站普遍屏蔽 okhttp 等爬虫 UA（部分站点直接 403），统一使用浏览器 UA */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val REQUEST_ACCEPT = "application/json, text/plain, */*"

        /** 历史 UA，作为被 UA 拦截时的重试兜底 */
        const val LEGACY_USER_AGENT = "okhttp/4.12.0"

        /** 判定为 UA 拦截的状态码，仅这些才值得换 UA 重试 */
        val UA_BLOCK_CODES = setOf(401, 403, 406)

        /** CMS XML 列表接口的分类节点，兼容属性顺序、单双引号与 CDATA 名称 */
        val CLASS_TY_REGEX = Regex(
            "<ty\\b[^>]*?(?<![\\w-])id\\s*=\\s*[\"']?([^\"'\\s>]+)[\"']?[^>]*>(.*?)</ty>",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        /** 单次采集站请求的绝对超时（毫秒），与搜索单源超时一致，避免慢源拖住整体搜索 */
        const val REQUEST_TIMEOUT_MS = 10_000L
    }
}
