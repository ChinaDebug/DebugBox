package com.tvbox.kotlin.core.spider

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.text.TextUtils
import android.util.Base64
import androidx.core.content.ContextCompat
import com.github.catvod.Init
import com.github.catvod.crawler.JarLoader
import com.github.catvod.crawler.JsLoader
import com.github.catvod.crawler.JsSpiderFactory
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderApi
import com.github.catvod.crawler.SpiderNull
import com.github.catvod.crawler.pyLoader
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.TypedEvent
import com.tvbox.kotlin.core.common.bean.LiveChannelGroup
import com.tvbox.kotlin.engine.quickjs.QuickJsEngine
import com.tvbox.kotlin.core.common.bean.LiveChannelItem
import com.tvbox.kotlin.core.common.bean.ParseBean
import com.tvbox.kotlin.core.common.bean.SourceBean
import com.tvbox.kotlin.core.common.util.ApiLineStore
import com.tvbox.kotlin.core.common.util.DefaultConfig
import com.tvbox.kotlin.core.common.util.FileUtils
import com.tvbox.kotlin.core.common.util.HawkListHelper
import com.tvbox.kotlin.core.common.util.MD5
import com.tvbox.kotlin.core.common.util.M3U8
import com.tvbox.kotlin.core.common.util.StoragePermissionUtils
import com.tvbox.kotlin.core.network.HostDnsMap
import com.tvbox.kotlin.core.network.OkHttpHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * 站点配置与爬虫调度核心。
 * 负责点播接口订阅的加载、解析、多站点管理、默认解析切换、直播/EPG 配置，
 * 并通过 [getCSP] 将站点请求委托给对应 Spider（jar/js/py）。
 * Hilt 全局单例，构造注入 Application 上下文、网络封装与偏好存储。
 */
@Singleton
class ApiConfig @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpHelper: OkHttpHelper,
    private val appPreferences: com.tvbox.kotlin.core.common.util.AppPreferences,
    private val jsSpiderFactory: JsSpiderFactory,
    private val cmsApi: CmsApi
) {

    private val sourceBeanList = LinkedHashMap<String, SourceBean>()
    private val liveChannelGroupList = ArrayList<LiveChannelGroup>()

    /** txt 直播源拉取互斥锁：防止 parseJson 后台协程与 ensureLiveChannelsLoaded 并发拉取/清空同一列表 */
    private val txtLiveMutex = Mutex()

    private val parseBeanList = ArrayList<ParseBean>()
    private val adHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val warmedSearchSpiderKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** hosts 域名→IP 直连映射（配置 hosts 字段），供网络层绕过 DNS 污染直连 */
    private var myHosts: Map<String, String> = emptyMap()

    @Volatile
    private var homeSource: SourceBean? = null
    @Volatile
    private var defaultParse: ParseBean? = null
    @Volatile
    private var vipParseFlags: List<String> = ArrayList()
    @Volatile
    private var spider: String = ""
    @Volatile
    var wallpaper: String = ""
        private set
    @Volatile
    var livePlayHeaders: JsonArray? = null
        private set
    @Volatile
    private var jarCache: String = "true"

    private val emptyHome = SourceBean()
    private val jarLoader = JarLoader()
    private val jsLoader = JsLoader().apply { jsSpiderFactory = this@ApiConfig.jsSpiderFactory }
    private val pyLoader = pyLoader()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        clearLoader()
        // 桥接源配置广告正则到 M3U8 净化：M3U8.getRegex 依赖 adRegexProvider 注入，
        // 这里从 VideoParseRuler（源配置 rules 段解析写入）懒读取，使 M3U8.purify 广告净化与计数生效。
        // adRegexProvider 每次调用实时查询，clearRule/重新解析后无需重复注入。
        M3U8.adRegexProvider = { tsUrlPre ->
            VideoParseRuler.getHostsRegex()
                .entries
                .firstOrNull { (host, regex) -> tsUrlPre.contains(host) && regex.isNotEmpty() }
                ?.value
        }
    }

    // ------------------------------------------------------------------
    // 配置加载
    // ------------------------------------------------------------------
    /** 加载订阅配置，优先命中本地缓存，失败回退缓存，最终回调结果 */
    fun loadConfig(useCache: Boolean, callback: LoadConfigCallback) {
        scope.launch {
            val apiUrl = normalizeUrl(appPreferences.getString(AppConstants.API_URL, AppConstants.DEFAULT_API_URL))
            if (apiUrl.isEmpty()) {
                // 仅配置了直播地址、未配点播订阅时视为已有配置：以空点播配置成功返回，
                // 首页不再反复弹"订阅地址为空"引导（直播入口可正常使用）
                if (appPreferences.getString(AppConstants.LIVE_URL, "").isEmpty()) {
                    callback.error(context.getString(R.string.config_err_empty_api))
                } else {
                    callback.success()
                }
                return@launch
            }
            // 同步配置目录 URL，供爬虫返回的相对路径图片（./config/xxx.jpg）补全为完整地址
            DefaultConfig.updateConfigBaseUrl(baseUrlOf(apiUrl))
            val cache = File(context.filesDir.absolutePath, MD5.string2MD5(apiUrl) ?: "")
            if (useCache && cache.exists()) {
                try {
                    parseJson(apiUrl, cache)
                    callback.success()
                    return@launch
                } catch (e: Throwable) {
                    LOG.e(e)
                }
            }
            val parsed = resolveConfigUrl(apiUrl)
            val configUrl = parsed.first
            val configKey = parsed.second
            try {
                val request = Request.Builder()
                    .url(configUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", REQUEST_ACCEPT)
                    .build()
                okHttpHelper.client().newCall(request).execute().use { response ->
                    var json = ""
                    val body = response.body
                    if (body != null) {
                        var result = body.string()
                        result = findResult(result, configKey)
                        if (apiUrl.startsWith("clan")) {
                            result = clanContentFix(clanToAddress(apiUrl), result)
                        }
                        result = fixContentPath(apiUrl, result)
                        json = result
                    }
                    // 多仓（接口合集）：不自动切换，交由 UI 弹窗让用户选择线路
                    val apiLines = parseApiCollection(json)
                    if (apiLines.isNotEmpty()) {
                        scope.launch {
                            // 按来源分别保存线路列表，支持多个多仓源共存
                            ApiLineStore.putLines(appPreferences, apiUrl, apiLines)
                        }
                        callback.apiLines(apiLines)
                        return@launch
                    }
                    parseJson(apiUrl, json)
                    writeCache(cache, json)
                    callback.success()
                }
            } catch (e: Throwable) {
                LOG.e(e)
                if (cache.exists()) {
                    try {
                        parseJson(apiUrl, cache)
                        callback.success()
                        return@launch
                    } catch (e2: Throwable) {
                        LOG.e(e2)
                    }
                }
                callback.error(context.getString(R.string.config_err_fetch, e.message ?: ""))
            }
        }
    }

    /** 加载主爬虫 jar，支持 img+ 隐写、md5 校验与缓存复用 */
    fun loadJar(useCache: Boolean, spiderJar: String, callback: LoadConfigCallback) {
        scope.launch {
            val urls = spiderJar.split(";md5;")
            var jarUrl = urls[0]
            val md5 = if (urls.size > 1) urls[1].trim() else ""
            val cache = File(context.filesDir.absolutePath, "csp/${MD5.string2MD5(jarUrl) ?: ""}.jar")
            if (md5.isNotEmpty() || useCache) {
                if (cache.exists() && (useCache || MD5.getFileMd5(cache).equals(md5, ignoreCase = true))) {
                    if (jarLoader.load(cache.absolutePath)) callback.success()
                    else callback.error(context.getString(R.string.config_err_cache_jar))
                    return@launch
                }
            } else {
                if (jarCache.toBoolean() && cache.exists() && !FileUtils.isWeekAgo(cache)) {
                    if (jarLoader.load(cache.absolutePath)) {
                        callback.success()
                        return@launch
                    }
                }
            }
            val isJarInImg = jarUrl.startsWith("img+")
            jarUrl = jarUrl.replace("img+", "")
            try {
                val request = Request.Builder()
                    .url(jarUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", REQUEST_ACCEPT)
                    .build()
                okHttpHelper.client().newCall(request).execute().use { response ->
                    val body = response.body
                    if (body == null) {
                        callback.error(context.getString(R.string.config_err_net_jar_empty))
                        return@launch
                    }
                    cache.parentFile?.mkdirs()
                    if (cache.exists()) cache.delete()
                    FileOutputStream(cache).use { fos ->
                        if (isJarInImg) {
                            val imgJar = getImgJar(body.string())
                            if (imgJar.isEmpty()) {
                                callback.error(context.getString(R.string.config_err_img_jar_empty))
                                return@launch
                            }
                            fos.write(imgJar)
                        } else {
                            body.byteStream().use { input ->
                                val buffer = ByteArray(4096)
                                var n: Int
                                while (input.read(buffer).also { n = it } != -1) {
                                    fos.write(buffer, 0, n)
                                }
                            }
                        }
                        fos.flush()
                    }
                    if (jarLoader.load(cache.absolutePath)) callback.success()
                    else callback.error(context.getString(R.string.config_err_jar_write))
                }
            } catch (e: Throwable) {
                LOG.e(e)
                if (cache.exists() && jarLoader.load(cache.absolutePath)) {
                    callback.success()
                    return@launch
                }
                callback.error(context.getString(R.string.config_err_jar_net, e.message ?: ""))
            }
        }
    }

    /**
     * 规范化地址输入：全角标点转半角，去除零宽字符与首尾空白。
     * 分享文本粘贴的订阅地址常含全角冒号（http：//），OkHttp 会直接抛
     * IllegalArgumentException 导致进程崩溃，故在读取与解析入口统一校正。
     */
    private fun normalizeUrl(url: String): String {
        return url.trim()
            .replace('\uFF1A', ':')
            .replace('\uFF0F', '/')
            .replace('\uFF0E', '.')
            .replace('\uFF1F', '?')
            .replace('\uFF1D', '=')
            .replace('\uFF06', '&')
            .replace("\u200B", "")
            .replace("\uFEFF", "")
    }

    /** 解析配置 URL，分离 ;pk; 密钥并处理 clan/http 前缀 */
    private fun resolveConfigUrl(apiUrl: String): Pair<String, String?> {
        val cleaned = normalizeUrl(apiUrl)
        var tempKey: String? = null
        var configUrl: String
        if (cleaned.contains(PK)) {
            val a = cleaned.split(PK)
            tempKey = a.getOrNull(1)
            configUrl = when {
                cleaned.startsWith("clan") -> clanToAddress(a[0])
                cleaned.startsWith("http") -> a[0]
                else -> "http://" + a[0]
            }
        } else if (cleaned.startsWith("clan")) {
            configUrl = clanToAddress(cleaned)
        } else if (!cleaned.startsWith("http")) {
            // 修正 configUrl 未赋值的缺陷，此处使用 apiUrl 拼接
            configUrl = "http://$cleaned"
        } else {
            configUrl = cleaned
        }
        return Pair(configUrl, tempKey)
    }

    /**
     * 多仓（接口合集）识别：返回内容为 `{"urls":[{name,url},...]}` 且不含 sites 字段时，
     * 解析出线路列表（name to url），由 UI 弹窗让用户选择，不自动切换订阅地址。
     */
    fun parseApiCollection(jsonStr: String): List<Pair<String, String>> {
        return try {
            val trimmed = jsonStr.trim()
            val start = trimmed.indexOf("{")
            val end = trimmed.lastIndexOf("}")
            if (start < 0 || end <= start) return emptyList()
            val info = Gson().fromJson(trimmed.substring(start, end + 1), JsonObject::class.java)
            // 正常点播配置含 sites 字段，不算多仓
            if (info.has("sites") || !info.has("urls") || !info.get("urls").isJsonArray) return emptyList()
            val result = ArrayList<Pair<String, String>>()
            // urls 缺失或非数组时返回空列表，避免 asJsonArray 抛异常
            for (element in DefaultConfig.safeJsonArray(info, "urls") ?: emptyList()) {
                val (name, url) = when {
                    element.isJsonObject -> {
                        val obj = element.asJsonObject
                        val n = DefaultConfig.safeJsonString(obj, "name", "")
                            .ifEmpty { "线路${result.size + 1}" }
                        val u = DefaultConfig.safeJsonString(obj, "url", "")
                            .ifEmpty { DefaultConfig.safeJsonString(obj, "api", "") }
                        n to u
                    }
                    element.isJsonPrimitive -> "线路${result.size + 1}" to element.asString
                    else -> "" to ""
                }
                if (url.isNotEmpty()) result.add(name to url)
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeCache(cache: File, json: String) {
        try {
            cache.parentFile?.mkdirs()
            if (cache.exists()) cache.delete()
            FileOutputStream(cache).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        } catch (e: Throwable) {
            LOG.e(e)
        }
    }

    @Throws(Throwable::class)
    private fun parseJson(apiUrl: String, f: File) {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(FileInputStream(f), "UTF-8")).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
        }
        parseJson(apiUrl, sb.toString())
    }

    /** 解析配置 JSON：spider/wallpaper/sites/parses/lives/rules/flags/ads */
    @Synchronized
    private fun parseJson(apiUrl: String, jsonStr: String) {
        // 每次重新解析配置前（含切换订阅源）清理旧加载器，
        // 否则上一个订阅源的 jar/js/py 加载器与 Spider 实例残留，无法停止卸载
        clearLoader()
        // 非对象内容（如顶层数组）按空配置处理，交由上层提示或回退缓存
        val infoJson: JsonObject = Gson().fromJson(jsonStr, JsonObject::class.java) ?: JsonObject()
        jarCache = DefaultConfig.safeJsonString(infoJson, "jarCache", "true")
        spider = DefaultConfig.safeJsonString(infoJson, "spider", "")
        wallpaper = DefaultConfig.safeJsonString(infoJson, "wallpaper", "")
        livePlayHeaders = DefaultConfig.safeJsonArray(infoJson, "livePlayHeaders")

        // 站点源
        var firstSite: SourceBean? = null
        // sites 字段可能缺失/为 null/值为 null（部分订阅配置未声明 video 节点），判空避免迭代 null 崩溃
        val sites = try {
            if (infoJson.has("video") && !infoJson.get("video").isJsonNull) {
                infoJson.getAsJsonObject("video").getAsJsonArray("sites")
            } else if (infoJson.has("sites") && !infoJson.get("sites").isJsonNull) {
                infoJson.getAsJsonArray("sites")
            } else null
        } catch (_: Throwable) {
            null
        }
        synchronized(sourceBeanList) {
            for (opt in sites ?: emptyList()) {
                // 非对象元素跳过，避免 asJsonObject 抛异常中断整份配置解析
                val obj = opt.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                // key/name/type/api 均为必填字段，用安全读取避免缺字段或值为 null 时 getAsString/getAsInt 抛 NPE
                val siteKey = DefaultConfig.safeJsonString(obj, "key", "").trim()
                if (siteKey.isEmpty()) continue
                val sb = SourceBean()
                sb.key = siteKey
                sb.name = DefaultConfig.safeJsonString(obj, "name", siteKey).trim()
                sb.type = DefaultConfig.safeJsonInt(obj, "type", 0)
                sb.api = DefaultConfig.safeJsonString(obj, "api", "").trim()
                sb.searchable = DefaultConfig.safeJsonInt(obj, "searchable", 1)
                sb.quickSearch = DefaultConfig.safeJsonInt(obj, "quickSearch", 1)
                if (siteKey.startsWith("py_")) {
                    sb.filterable = 1
                } else {
                    sb.filterable = DefaultConfig.safeJsonInt(obj, "filterable", 1)
                }
                sb.hide = DefaultConfig.safeJsonInt(obj, "hide", 0)
                sb.playerUrl = DefaultConfig.safeJsonString(obj, "playUrl", "")
                sb.ext = DefaultConfig.safeJsonString(obj, "ext", "")
                sb.jar = DefaultConfig.safeJsonString(obj, "jar", "")
                sb.playerType = DefaultConfig.safeJsonInt(obj, "playerType", -1)
                sb.categories = DefaultConfig.safeJsonStringList(obj, "categories")
                sb.clickSelector = DefaultConfig.safeJsonString(obj, "click", "")
                sb.style = DefaultConfig.safeJsonString(obj, "style", "")
                if (firstSite == null && sb.hide == 0) firstSite = sb
                sourceBeanList[siteKey] = sb
            }
            if (sourceBeanList.isNotEmpty()) {
                val home = appPreferences.getString(AppConstants.HOME_API, "")
                val sh = getSource(home)
                if (sh == null || sh.hide == 1) setSourceBean(firstSite)
                else setSourceBean(sh)
            }
        }

        // VIP 解析 flag
        vipParseFlags = DefaultConfig.safeJsonStringList(infoJson, "flags")

        // 解析地址
        synchronized(parseBeanList) {
            parseBeanList.clear()
            var hasSuperParseFlag = false
            // parses 字段缺失或非数组时跳过，单个解析器字段异常不影响其他解析器
            val parses = DefaultConfig.safeJsonArray(infoJson, "parses")
            if (parses != null) {
                for (opt in parses) {
                    val obj = opt.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    // url 缺字段/为 null 时跳过该解析器，避免 getAsString 抛 NPE 中断整份配置解析
                    val url = DefaultConfig.safeJsonString(obj, "url", "").trim()
                    if (url.isEmpty()) continue
                    // 超级解析标记：url 为 SuperParse 时不入列表，仅标记
                    if ("SuperParse" == url) {
                        hasSuperParseFlag = true
                        continue
                    }
                    val pb = ParseBean()
                    pb.name = DefaultConfig.safeJsonString(obj, "name", "解析").trim()
                    pb.setUrl(url)
                    pb.ext = DefaultConfig.safeJsonString(obj, "ext", "")
                    pb.type = DefaultConfig.safeJsonInt(obj, "type", 0)
                    parseBeanList.add(pb)
                }
                if (parseBeanList.isNotEmpty() || hasSuperParseFlag) addSuperParse()
            }
            // 默认解析
            if (parseBeanList.isNotEmpty()) {
                val defaultParseName = appPreferences.getString(AppConstants.DEFAULT_PARSE, "")
                if (defaultParseName.isNotEmpty()) {
                    for (pb in parseBeanList) {
                        if (pb.name == defaultParseName) setDefaultParse(pb)
                    }
                }
                if (defaultParse == null) setDefaultParse(parseBeanList[0])
            }
        }

        // 直播与 EPG
        synchronized(liveChannelGroupList) {
            liveChannelGroupList.clear()
            val liveURL = appPreferences.getString(AppConstants.LIVE_URL, "")
            val epgURL = appPreferences.getString(AppConstants.EPG_URL, "")
            var liveURLFinal: String? = null
            try {
                // lives 缺失/非数组/空数组时跳过，避免取首元素或非对象元素抛异常
                val livesArray = DefaultConfig.safeJsonArray(infoJson, "lives")
                val livesObj = livesArray?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                if (livesArray != null && livesObj != null) {
                    val lives = livesObj.toString()
                    val index = lives.indexOf("proxy://")
                    if (index != -1) {
                        val endIndex = lives.lastIndexOf("\"")
                        var url = lives.substring(index, endIndex)
                        url = DefaultConfig.checkReplaceProxy(url)
                        val extUrl = Uri.parse(url).getQueryParameter("ext")
                        if (!extUrl.isNullOrEmpty()) {
                            var extUrlFix: String
                            extUrlFix = if (extUrl.startsWith("http") || extUrl.startsWith("clan://")) {
                                extUrl
                            } else {
                                String(
                                    Base64.decode(extUrl, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP),
                                    Charsets.UTF_8
                                )
                            }
                            if (extUrlFix.startsWith("clan://")) {
                                extUrlFix = clanContentFix(clanToAddress(apiUrl), extUrlFix)
                            }
                            putLiveHistory(extUrlFix)
                            // 当前未配置直播地址时，把配置中的直播地址写回偏好，供设置弹窗展示
                            if (liveURL.isBlank()) {
                                scope.launch { appPreferences.put(AppConstants.LIVE_URL, extUrlFix) }
                                liveURLFinal = extUrlFix
                            } else {
                                liveURLFinal = liveURL
                            }
                        }
                        val epg = DefaultConfig.safeJsonString(livesObj, "epg", "")
                        if (epg.isNotEmpty()) {
                            putEPGHistory(epg)
                            scope.launch {
                                appPreferences.put(AppConstants.EPG_URL, if (epgURL.isBlank()) epg else epgURL)
                            }
                        }
                        val logo = DefaultConfig.safeJsonString(livesObj, "logo", "")
                        if (logo.isNotEmpty()) {
                            scope.launch { appPreferences.put(AppConstants.LOGO_URL, logo) }
                        }
                    } else {
                        // FongMi 直播格式
                        if (!lives.contains("type")) {
                            loadLives(livesArray)
                        } else {
                            val fengMiLives = livesArray[0].asJsonObject
                            // 直播内核恒定 Exo，忽略 FongMi 源自带的 playerType
                            val type = DefaultConfig.safeJsonString(fengMiLives, "type", "")
                            if (type == "0") {
                                val url = DefaultConfig.safeJsonString(fengMiLives, "url", "")
                                val epg = DefaultConfig.safeJsonString(fengMiLives, "epg", "")
                                if (epg.isNotEmpty()) {
                                    putEPGHistory(epg)
                                    scope.launch {
                                        appPreferences.put(AppConstants.EPG_URL, if (epgURL.isBlank()) epg else epgURL)
                                    }
                                }
                                val logo = DefaultConfig.safeJsonString(fengMiLives, "logo", "")
                                if (logo.isNotEmpty()) {
                                    scope.launch { appPreferences.put(AppConstants.LOGO_URL, logo) }
                                }
                                if (url.startsWith("http")) {
                                    putLiveHistory(url)
                                    // 当前未配置直播地址时写回偏好，供设置弹窗展示
                                    if (liveURL.isBlank()) {
                                        scope.launch { appPreferences.put(AppConstants.LIVE_URL, url) }
                                        liveURLFinal = url
                                    } else {
                                        liveURLFinal = liveURL
                                    }
                                }
                            }
                        }
                    }
                    if (liveURLFinal.isNullOrBlank()) liveURLFinal = liveURL
                    if (liveURLFinal.isNotBlank()) {
                        // 分组直接持有真实直播源 URL，异步拉取 txt 填充频道
                        val liveChannelGroup = LiveChannelGroup()
                        liveChannelGroup.groupName = liveURLFinal
                        liveChannelGroupList.add(liveChannelGroup)
                        scope.launch { loadTxtLives() }
                    }
                }
            } catch (e: Throwable) {
                LOG.e(e)
            }
        }

        // 嗅探规则：rules 缺失/非数组或单条规则字段异常时跳过，不中断其余配置项
        if (infoJson.has("rules")) {
            VideoParseRuler.clearRule()
            for (oneHostRule in DefaultConfig.safeJsonArray(infoJson, "rules") ?: emptyList()) {
                val obj = oneHostRule.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val host = DefaultConfig.safeJsonString(obj, "host", "")
                if (host.isNotEmpty()) {
                    val rule = DefaultConfig.safeJsonStringList(obj, "rule")
                    if (rule.isNotEmpty()) VideoParseRuler.addHostRule(host, rule)
                    val filter = DefaultConfig.safeJsonStringList(obj, "filter")
                    if (filter.isNotEmpty()) VideoParseRuler.addHostFilter(host, filter)
                }
                // 广告过滤规则（hosts + regex）
                if (obj.has("hosts") && obj.has("regex")) {
                    val rule = ArrayList<String>()
                    val adRules = ArrayList<String>()
                    for (regex in DefaultConfig.safeJsonStringList(obj, "regex")) {
                        // 简化 M3U8.isAd 判断：含 "ad" 视为广告规则
                        if (regex.lowercase().contains("ad")) adRules.add(regex) else rule.add(regex)
                    }
                    for (h in DefaultConfig.safeJsonStringList(obj, "hosts")) {
                        VideoParseRuler.addHostRule(h, rule)
                        VideoParseRuler.addHostRegex(h, adRules)
                    }
                }
                // 嗅探脚本规则
                if (obj.has("hosts") && obj.has("script")) {
                    val scripts = DefaultConfig.safeJsonStringList(obj, "script")
                    for (h in DefaultConfig.safeJsonStringList(obj, "hosts")) {
                        VideoParseRuler.addHostScript(h, scripts)
                    }
                }
            }
        }

        // 广告主机
        if (adHosts.isEmpty()) {
            collectDefaultAds()
            // ads 缺失或非数组时跳过，单项非字符串不参与匹配
            for (host in DefaultConfig.safeJsonArray(infoJson, "ads") ?: emptyList()) {
                val h = host.takeIf { it.isJsonPrimitive }?.asString ?: continue
                if (!adHosts.contains(h)) adHosts.add(h)
            }
        }

        // hosts 域名→IP 直连映射（格式 "域名=IP"），绕过 DNS 污染
        if (infoJson.has("hosts")) {
            val hosts = HashMap<String, String>()
            for (one in DefaultConfig.safeJsonArray(infoJson, "hosts") ?: emptyList()) {
                val entry = one.takeIf { it.isJsonPrimitive }?.asString ?: continue
                val idx = entry.indexOf("=")
                if (idx > 0) hosts[entry.substring(0, idx).trim()] = entry.substring(idx + 1).trim()
            }
            myHosts = hosts
            HostDnsMap.update(hosts)
        } else {
            myHosts = emptyMap()
            HostDnsMap.update(emptyMap())
        }

    }

    /** 加载 FongMi 格式直播频道列表 */
    fun loadLives(livesArray: JsonArray) {
        synchronized(liveChannelGroupList) {
            liveChannelGroupList.clear()
            var groupIndex = 0
            var channelNum = 0
            for (groupElement in livesArray) {
                val groupObj = groupElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val liveChannelGroup = LiveChannelGroup()
                liveChannelGroup.setLiveChannels(ArrayList())
                liveChannelGroup.groupIndex = groupIndex++
                // group 字段可能缺失或为 null，缺省按序号命名，避免 asString 抛 NPE 中断配置解析
                val groupName = groupObj.get("group")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                    .orEmpty().ifEmpty { "线路${groupIndex}" }
                val splitGroupName = groupName.split("_", limit = 2)
                liveChannelGroup.groupName = splitGroupName[0]
                liveChannelGroup.groupPassword = if (splitGroupName.size > 1) splitGroupName[1] else ""
                var channelIndex = 0
                // channels 缺失或非数组时跳过该分组，避免对 null 迭代
                val channels = groupObj.get("channels")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                for (channelElement in channels) {
                    val obj = channelElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    // name 为频道标识，缺失或为空则无法展示，直接跳过
                    val channelName = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                    if (channelName.isEmpty()) continue
                    val liveChannelItem = LiveChannelItem()
                    liveChannelItem.channelName = channelName
                    liveChannelItem.channelIndex = channelIndex++
                    liveChannelItem.channelNum = ++channelNum
                    liveChannelItem.tvgLogo = obj.get("logo")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.isNotEmpty() }
                    val urls = DefaultConfig.safeJsonStringList(obj, "urls")
                    val sourceNames = ArrayList<String>()
                    val sourceUrls = ArrayList<String>()
                    var sourceIndex = 1
                    for (url in urls) {
                        val splitText = url.split("\\$".toRegex(), limit = 2)
                        sourceUrls.add(splitText[0])
                        if (splitText.size > 1) sourceNames.add(splitText[1])
                        else sourceNames.add("源$sourceIndex")
                        sourceIndex++
                    }
                    liveChannelItem.channelSourceNames = sourceNames
                    liveChannelItem.channelUrls = sourceUrls
                    liveChannelGroup.getLiveChannels()?.add(liveChannelItem)
                }
                liveChannelGroupList.add(liveChannelGroup)
            }
        }
    }

    private fun putLiveHistory(url: String) {
        if (url.isNotEmpty()) {
            HawkListHelper.addToList(AppConstants.LIVE_HISTORY, url, 20)
        }
    }

    // ------------------------------------------------------------------
    // 直播源解析（txt / #genre# 格式）
    // ------------------------------------------------------------------

    /**
     * 拉取直播源文本：按字节流 BOM/编码探测解码（兼容 GBK/GB18030）；
     * clan:// 本地/局域网直播源先转为 http 再拉取。
     */
    private fun fetchLiveText(url: String): String {
        val finalUrl = toHttpUrl(url)
        if (finalUrl.isEmpty()) return ""
        val bytes = try {
            okHttpHelper.getBytes(finalUrl, mapOf("User-Agent" to USER_AGENT))
        } catch (e: Throwable) {
            LOG.e(e)
            return ""
        }
        return decodeLiveText(bytes)
    }

    /** clan:// 协议地址统一经 clanToAddress 转为可访问 http 地址，其余原样返回 */
    private fun toHttpUrl(url: String): String = if (url.startsWith("clan://")) clanToAddress(url) else url

    /** 按 BOM/编码探测解码直播源字节：UTF-8 严格合法则用 UTF-8，否则回退 GB18030（GBK 超集） */
    private fun decodeLiveText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // UTF-16 BOM
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // 严格 UTF-8 解码尝试：合法说明是 UTF-8，出现非法序列则回退 GB18030
        return try {
            Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            try {
                String(bytes, Charset.forName("GB18030"))
            } catch (e2: Throwable) {
                String(bytes, Charsets.UTF_8)
            }
        }
    }

    /**
     * 确保直播频道已解析。
     * FongMi 格式在 parseJson 时已填充频道则直接跳过；
     * txt 格式分组初始 channels 为 null，需要异步拉取解析。
     */
    suspend fun ensureLiveChannelsLoaded() {
        val first = liveChannelGroupList.firstOrNull()
        if (first?.getLiveChannels() != null) return
        // 动态直播源：配置未内置静态直播源时，尝试当前源 spider 的 liveContent 接口
        if (liveChannelGroupList.isEmpty()) {
            val liveJson = try {
                homeSpider().liveContent("")
            } catch (e: Throwable) {
                LOG.e(e)
                ""
            }
            val groups = if (liveJson.isNotEmpty()) parseLiveJsonGroups(liveJson) else null
            if (groups != null && groups.isNotEmpty()) {
                synchronized(liveChannelGroupList) {
                    liveChannelGroupList.clear()
                    liveChannelGroupList.addAll(groups)
                }
                return
            }
            // 用户独立配置的直播地址兜底：未配置点播接口（或点播配置不含 lives）时，
            // 只要设置了 LIVE_URL 就直接拉取解析，保证"只添加直播源"也能正常进入直播
            val liveUrl = appPreferences.getString(AppConstants.LIVE_URL, "").trim()
            if (liveUrl.isNotEmpty()) {
                try {
                    if (loadLiveFromUrl(liveUrl)) return
                } catch (e: Throwable) {
                    LOG.e(e)
                }
            }
            return
        }
        val url = first?.groupName ?: return
        if (!url.startsWith("http") && !url.startsWith("clan://")) return
        loadTxtLives()
    }

    /** 拉取并解析 txt 直播源，按 #genre# 分组填充 liveChannelGroupList */
    private suspend fun loadTxtLives() {
        val url = liveChannelGroupList.firstOrNull()?.groupName ?: return
        if (!url.startsWith("http") && !url.startsWith("clan://")) return
        // 互斥执行：并发调用会等待前一次完成，完成后发现频道已填充则直接返回，
        // 避免并发 clear 同一共享列表导致主线程遍历越界崩溃
        txtLiveMutex.withLock {
            val first = liveChannelGroupList.firstOrNull() ?: return@withLock
            if (first.getLiveChannels() != null) return@withLock
            val body = fetchLiveText(url)
            if (body.isEmpty()) return@withLock
            // JSON 直播源（FongMi 格式）优先，否则按 txt/m3u 文本解析
            val groups = parseLiveJsonGroups(body) ?: parseTxtLiveGroups(body)
            if (groups.isEmpty()) return@withLock
            synchronized(liveChannelGroupList) {
                liveChannelGroupList.clear()
                liveChannelGroupList.addAll(groups)
            }
        }
    }

    /**
     * 拉取指定 txt 直播源并替换当前频道列表，同步保存直播地址与历史。
     * @return 是否解析出频道分组
     */
    suspend fun loadLiveFromUrl(url: String): Boolean {
        if (!url.startsWith("http") && !url.startsWith("clan://")) return false
        val body = fetchLiveText(url)
        if (body.isEmpty()) return false
        val groups = parseLiveJsonGroups(body) ?: parseTxtLiveGroups(body)
        if (groups.isEmpty()) return false
        synchronized(liveChannelGroupList) {
            liveChannelGroupList.clear()
            liveChannelGroupList.addAll(groups)
        }
        appPreferences.put(AppConstants.LIVE_URL, url)
        putLiveHistory(url)
        return true
    }

    /** 拉取指定 txt 直播源并转为分组 JSON（供本地代理 do=live 返回） */
    suspend fun fetchLiveTxtJson(liveUrl: String): String? {
        if (!liveUrl.startsWith("http") && !liveUrl.startsWith("clan://")) return null
        val body = fetchLiveText(liveUrl)
        if (body.isEmpty()) return null
        val groups = parseLiveJsonGroups(body) ?: parseTxtLiveGroups(body)
        if (groups.isEmpty()) return null
        val root = JsonArray()
        for (g in groups) {
            val gObj = JsonObject()
            gObj.addProperty("group", g.groupName)
            gObj.addProperty("password", g.groupPassword ?: "")
            val chArr = JsonArray()
            g.getLiveChannels()?.forEach { c ->
                val cObj = JsonObject()
                cObj.addProperty("name", c.channelName)
                if (!c.tvgLogo.isNullOrEmpty()) cObj.addProperty("logo", c.tvgLogo)
                val uArr = JsonArray()
                c.channelUrls?.forEach { uArr.add(it) }
                cObj.add("urls", uArr)
                chArr.add(cObj)
            }
            gObj.add("channels", chArr)
            root.add(gObj)
        }
        return root.toString()
    }

    /**
     * 解析 txt 直播源文本为频道分组列表。
     * 支持标准 TVBox 格式：`分组名,#genre#` 分组标记、`频道名,url1#url2` 频道行，
     * url 可携带 `$源名`，`#` 开头行视为注释忽略。
     * 含 `#EXTINF` 的 m3u/m3u8 格式自动走 [parseM3uLiveGroups] 解析。
     */
    fun parseTxtLiveGroups(content: String): List<LiveChannelGroup> {
        // m3u/m3u8 直播源（#EXTM3U/#EXTINF 格式）：含 #EXTINF 标记行按 m3u 解析
        if (content.contains("#EXTINF")) {
            return parseM3uLiveGroups(content)
        }
        val groups = ArrayList<LiveChannelGroup>()
        var currentGroup: LiveChannelGroup? = null
        var channelNum = 0
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.endsWith("#genre#")) {
                // 去掉 "#genre#" 后缀后还需去掉末尾的分隔逗号：
                // "分组名,#genre#" 去掉后缀会残留尾部逗号导致分组名多一个逗号，
                // TxtSubscribe 用 split(",", 2) 取第一部分的效果
                val rawName = line.substring(0, line.length - "#genre#".length).trim().trimEnd(',')
                val group = LiveChannelGroup()
                group.groupIndex = groups.size
                group.groupName = rawName.ifEmpty { "直播" }
                val pwSplit = group.groupName!!.split("_", limit = 2)
                group.groupName = pwSplit[0].trim()
                group.groupPassword = pwSplit.getOrNull(1)
                group.setLiveChannels(ArrayList())
                groups.add(group)
                currentGroup = group
                continue
            }
            val idx = line.indexOf(",")
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim()
            val urlPart = line.substring(idx + 1).trim()
            if (urlPart.isEmpty()) continue
            // 仅保留 Exo 可播的 http(s) 流地址；rtmp/rtsp 已随内核移除不支持，直接过滤
            val urls = urlPart.split("#")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.startsWith("http") }
            if (urls.isEmpty()) continue
            val group = currentGroup ?: groups.firstOrNull() ?: LiveChannelGroup().also {
                it.groupName = "直播"
                it.setLiveChannels(ArrayList())
                groups.add(it)
            }
            val channels = group.getLiveChannels() ?: ArrayList<LiveChannelItem>().also {
                group.setLiveChannels(it)
            }
            // 同组内同名频道合并为一个条目，URL 追加为多线路源：
            // 避免同一频道名的不同源在列表中重复显示（TxtSubscribe 按频道名聚合）
            val existing = channels.firstOrNull { it.channelName == name }
            if (existing != null) {
                appendChannelSources(existing, urls)
                continue
            }
            val item = LiveChannelItem()
            item.channelName = name
            item.channelIndex = channels.size
            item.channelNum = ++channelNum
            appendChannelSources(item, urls)
            channels.add(item)
        }
        return groups
    }

    /**
     * 解析 m3u/m3u8 直播源文本（#EXTM3U/#EXTINF 格式）为频道分组列表。
     * - `#EXTINF` 行的 `group-title` 属性作为分组名（兼容 `分组,#genre#` 转 txt 风格后缀），无分组归入"直播"；
     * - 逗号后为频道名，其后连续的 URL 行作为该频道多线路源；
     * - URL 可携带 `$源名`，其他 `#` 开头行（#EXTM3U/#EXTVLCOPT 等）视为注释忽略。
     */
    private fun parseM3uLiveGroups(content: String): List<LiveChannelGroup> {
        val groups = ArrayList<LiveChannelGroup>()
        val groupMap = HashMap<String, LiveChannelGroup>()
        var currentGroup: LiveChannelGroup? = null
        var pendingName: String? = null
        var pendingTvgLogo: String? = null
        val pendingUrls = ArrayList<String>()
        var channelNum = 0

        fun groupOf(name: String): LiveChannelGroup {
            val cleaned = cleanGroupTitle(name)
            return groupMap.getOrPut(cleaned) {
                LiveChannelGroup().apply {
                    groupIndex = groups.size
                    groupName = cleaned
                    setLiveChannels(ArrayList())
                    groups.add(this)
                }
            }
        }

        fun flushChannel() {
            val name = pendingName ?: return
            pendingName = null
            val tvgLogo = pendingTvgLogo
            pendingTvgLogo = null
            val urls = pendingUrls.toList()
            pendingUrls.clear()
            val validUrls = urls
                .flatMap { it.split("#") }
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.startsWith("http") }
            if (validUrls.isEmpty()) return
            val group = currentGroup ?: groupOf("直播")
            val channels = group.getLiveChannels() ?: ArrayList<LiveChannelItem>().also {
                group.setLiveChannels(it)
            }
            // 同组内同名频道合并为一个条目，URL 追加为多线路源
            val existing = channels.firstOrNull { it.channelName == name }
            if (existing != null) {
                appendChannelSources(existing, validUrls)
                if (existing.tvgLogo.isNullOrEmpty()) existing.tvgLogo = tvgLogo
                return
            }
            val item = LiveChannelItem()
            item.channelName = name
            item.channelIndex = channels.size
            item.channelNum = ++channelNum
            item.tvgLogo = tvgLogo
            appendChannelSources(item, validUrls)
            channels.add(item)
        }

        for (raw in content.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // 老式 m3u 分组行：#EXTGRP:央视（无 group-title 属性的旧 IPTV 源）
            if (line.startsWith("#EXTGRP:")) {
                val groupName = line.removePrefix("#EXTGRP:").trim()
                if (groupName.isNotEmpty()) currentGroup = groupOf(groupName)
                continue
            }
            if (line.startsWith("#EXTINF")) {
                flushChannel()
                val groupTitle = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                currentGroup = groupTitle?.takeIf { it.isNotBlank() }?.let { groupOf(it) }
                pendingName = line.substringAfterLast(",", "").trim().ifEmpty { null }
                pendingTvgLogo = Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                    ?.takeIf { it.isNotBlank() }
                continue
            }
            if (line.startsWith("#")) continue
            if (pendingName != null) pendingUrls.add(line)
        }
        flushChannel()
        return groups
    }

    /**
     * 清理 m3u 分组名：兼容 `分组,#genre#` 转 txt 风格后缀，
     * 与 [parseTxtLiveGroups] 的 `#genre#` 分组名清理规则保持一致（去后缀 + 去尾部逗号）。
     */
    private fun cleanGroupTitle(name: String): String {
        var cleaned = name.trim()
        if (cleaned.endsWith("#genre#")) {
            cleaned = cleaned.substring(0, cleaned.length - "#genre#".length).trim().trimEnd(',')
        }
        return cleaned.ifEmpty { "直播" }
    }

    /**
     * 解析独立 JSON 直播源（FongMi 格式）为频道分组列表。
     * 支持直接数组 `[{"group":..,"channels":[...]}]` 或含 `data`/`lives` 数组的对象
     * （接口返回 `{"code":0,"data":[...]}`）。
     * 内容不是合法 JSON 直播源时返回 null，调用方回退文本解析。
     */
    private fun parseLiveJsonGroups(content: String): List<LiveChannelGroup>? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        return try {
            val root = Gson().fromJson(trimmed, JsonElement::class.java) ?: return null
            val arr = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> {
                    val obj = root.asJsonObject
                    obj.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
                        ?: obj.get("lives")?.takeIf { it.isJsonArray }?.asJsonArray
                }
                else -> null
            } ?: return null
            val groups = ArrayList<LiveChannelGroup>()
            var channelNum = 0
            for (i in 0 until arr.size()) {
                val groupObj = arr[i].takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val rawGroup = groupObj.get("group")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                val channelsArr = groupObj.get("channels")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                if (rawGroup.isEmpty()) continue
                val group = LiveChannelGroup()
                group.groupIndex = groups.size
                var groupName = rawGroup
                // 兼容 "分组,#genre#" 转 txt 风格后缀，与 txt/m3u 清理规则一致
                if (groupName.endsWith("#genre#")) {
                    groupName = groupName.substring(0, groupName.length - "#genre#".length).trim().trimEnd(',')
                }
                // 兼容 "分组_密码" 分组密码语法（与 loadLives 一致）
                val pwSplit = groupName.split("_", limit = 2)
                group.groupName = pwSplit[0].trim().ifEmpty { "直播" }
                group.groupPassword = pwSplit.getOrNull(1)
                group.setLiveChannels(ArrayList())
                var channelIndex = 0
                for (j in 0 until channelsArr.size()) {
                    val chObj = channelsArr[j].takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val name = chObj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    val urls = DefaultConfig.safeJsonStringList(chObj, "urls")
                    if (urls.isEmpty()) continue
                    val item = LiveChannelItem()
                    item.channelName = name
                    item.channelIndex = channelIndex++
                    item.channelNum = ++channelNum
                    item.tvgLogo = chObj.get("logo")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.isNotEmpty() }
                    val sourceNames = ArrayList<String>()
                    val sourceUrls = ArrayList<String>()
                    var sourceIndex = 1
                    for (url in urls) {
                        val split = url.split("\\$".toRegex(), limit = 2)
                        sourceUrls.add(split[0])
                        sourceNames.add(if (split.size > 1) split[1] else "源$sourceIndex")
                        sourceIndex++
                    }
                    item.channelSourceNames = sourceNames
                    item.channelUrls = sourceUrls
                    group.getLiveChannels()?.add(item)
                }
                groups.add(group)
            }
            groups.ifEmpty { null }
        } catch (e: Throwable) {
            LOG.e(e)
            null
        }
    }

    /** 将 URL 列表按 `$源名` 拆分为源地址与源名，追加到频道的线路列表 */
    private fun appendChannelSources(item: LiveChannelItem, urls: List<String>) {
        val sourceUrls = item.channelUrls ?: ArrayList<String>().also { item.channelUrls = it }
        val sourceNames = item.channelSourceNames ?: ArrayList<String>().also { item.channelSourceNames = it }
        urls.forEach { u ->
            val split = u.split("\\$".toRegex(), limit = 2)
            val url = split[0]
            val srcName = if (split.size > 1) split[1] else "源${sourceUrls.size + 1}"
            sourceUrls.add(url)
            sourceNames.add(srcName)
        }
        item.sourceNum = sourceUrls.size
    }

    fun putEPGHistory(url: String) {
        if (url.isNotEmpty()) {
            HawkListHelper.addToList(AppConstants.EPG_HISTORY, url, 20)
        }
    }

    // ------------------------------------------------------------------
    // Spider 调度
    // ------------------------------------------------------------------
    /** 按 api 后缀选择 jar/js/py 加载器获取 Spider */
    fun getCSP(sourceBean: SourceBean): Spider {
        val api = sourceBean.api ?: ""
        return when {
            api.endsWith(".js") || api.contains(".js?") ->
                jsLoader.getSpider(sourceBean.key ?: "", api, sourceBean.ext ?: "", sourceBean.jar ?: "")
            api.contains(".py") ->
                pyLoader.getSpider(sourceBean.key ?: "", api, sourceBean.ext ?: "")
            else ->
                jarLoader.getSpider(sourceBean.key ?: "", api, sourceBean.ext ?: "", sourceBean.jar ?: "")
        }
    }

    /** 获取首页站点的 Spider 便捷封装 */
    private fun homeSpider(): Spider = getCSP(homeSourceBean)

    fun homeContent(filter: Boolean): String = try {
        val home = homeSourceBean
        // type 0/1/4 为采集站，配置未带 jar 时走爬虫只会退化 SpiderNull 返回空串，
        // 首页分类改由 CMS 直连获取，与 categoryContent 的处理保持一致
        if (home.type == 0 || home.type == 1 || home.type == 4) {
            cmsApi.homeContentJson(home)
        } else {
            homeSpider().homeContent(filter)
        }
    } catch (e: Throwable) {
        LOG.e(e); ""
    }

    fun homeVideoContent(): String = try {
        homeSpider().homeVideoContent()
    } catch (e: Throwable) {
        LOG.e(e); ""
    }

    fun categoryContent(tid: String, pg: String, filter: Boolean, extend: HashMap<String, String>): String {
        val source = homeSourceBean
        // type 0/1/4 为 CMS 采集站，直连 API 并行请求，无需串行锁；
        // 其余 type 3 走 jar/js/py 爬虫，由调用方保证串行执行。
        if (source.type == 0 || source.type == 1 || source.type == 4) {
            return cmsApi.categoryJson(source, tid, pg, extend)
        }
        return try {
            homeSpider().categoryContent(tid, pg, filter, extend)
        } catch (e: Throwable) {
            LOG.e(e); ""
        }
    }

    fun detailContent(ids: List<String>): String = try {
        homeSpider().detailContent(ids)
    } catch (e: Throwable) {
        LOG.e(e); ""
    }

    fun searchContent(key: String, quick: Boolean): String = try {
        homeSpider().searchContent(key, quick)
    } catch (e: Throwable) {
        LOG.e(e); ""
    }

    fun playerContent(sourceKey: String, flag: String, id: String, vipFlags: List<String>): String = try {
        // 按 sourceKey 取源，避免非首页源
        // （搜索/历史/收藏进入的详情）误用首页源解析导致立即"解析失败"
        val source = getSource(sourceKey) ?: homeSourceBean
        // type 0/1/4 为 CMS 采集站，走直连播放接口，
        // 避免走 jarLoader 时退化 SpiderNull 导致空内容而误报"解析失败"。
        if (source.type == 0 || source.type == 1 || source.type == 4) {
            cmsApi.playInfo(source, flag, id)
        } else {
            getCSP(source).playerContent(flag, id, vipFlags)
        }
    } catch (e: Throwable) {
        LOG.e(e); ""
    }

    /** 调用指定源的 action（配置中心类卡片回调），返回 jar 内 JSON 字符串。
     *  仅 type==3 的 jar 爬虫支持 action 回调 */
    fun action(sourceKey: String, action: String): String = try {
        val source = getSource(sourceKey)
        if (source != null && source.type == 3) getCSP(source).action(action) ?: "" else ""
    } catch (e: Throwable) {
        LOG.e(e); ""
    }

    /** 后台预热搜索型 Spider（type==3），避免首次搜索时集中加载 jar 造成卡顿，最多预热 10 个 */
    fun warmSearchSpiders() {
        val sources = ArrayList(sourceBeanList.values)
        val home = homeSourceBean
        val sharedSpiderApis = HashSet<String>()
        val spiderApis = HashSet<String>()
        for (source in sources) {
            if (source.type != 3) continue
            val spiderApiKey = (source.jar ?: "") + "|" + (source.api ?: "")
            if (!spiderApis.add(spiderApiKey)) sharedSpiderApis.add(spiderApiKey)
        }
        thread(name = "warm-search-spider") {
            var eligible = 0
            for (source in sources) {
                if (source.type != 3 || !source.isSearchable()) continue
                if (home.key != null && TextUtils.equals(home.key, source.key)) continue
                // 同类 Spider 可能通过静态状态保存 ext，不能在后台交替初始化
                if (sharedSpiderApis.contains((source.jar ?: "") + "|" + (source.api ?: ""))) continue
                if (eligible >= 10) break
                eligible++
                val warmKey = (source.key ?: "") + "|" + (source.api ?: "") + "|" +
                    (source.jar ?: "") + "|" + (source.ext ?: "")
                synchronized(warmedSearchSpiderKeys) {
                    if (warmedSearchSpiderKeys.contains(warmKey)) continue
                    warmedSearchSpiderKeys.add(warmKey)
                }
                try {
                    getCSP(source)
                } catch (e: Throwable) {
                    LOG.e("echo-warm-search-spider-error ${source.key}:${e.message}")
                }
            }
        }
    }

    fun getPyCSP(url: String): Spider =
        pyLoader.getSpider(MD5.string2MD5(url) ?: "", url, "")

    fun isPyLoaderReady(): Boolean = pyLoader.isInitialized()

    /** 代理请求分发：js 走 jsLoader，py 走 pyLoader，其余走 jarLoader */
    fun proxyLocal(param: Map<String, String>): Array<Any?>? {
        if ("js" == param["do"]) return jsLoader.proxyInvoke(param)
        val apiString = homeSourceBean.api ?: ""
        return if (apiString.contains(".py")) pyLoader.proxyInvoke(param) else jarLoader.proxyInvoke(param)
    }

    fun jsonExt(key: String, jxs: LinkedHashMap<String, String>, url: String): JSONObject =
        jarLoader.jsonExt(key, jxs, url)

    fun jsonExtMix(
        flag: String,
        key: String,
        name: String,
        jxs: LinkedHashMap<String, HashMap<String, String>>,
        url: String
    ): JSONObject = jarLoader.jsonExtMix(flag, key, name, jxs, url)

    // ------------------------------------------------------------------
    // 站点/解析/直播管理
    // ------------------------------------------------------------------
    fun getSpider(): String = spider

    /** hosts 域名→IP 直连映射，供网络层查询 */
    fun getMyHost(): Map<String, String> = myHosts

    fun getSource(key: String): SourceBean? = synchronized(sourceBeanList) { sourceBeanList[key] }

    fun setSourceBean(sourceBean: SourceBean?) {
        if (sourceBean == null) return
        this.homeSource = sourceBean
        scope.launch { appPreferences.put(AppConstants.HOME_API, sourceBean.key ?: "") }
    }

    fun setDefaultParse(parseBean: ParseBean) {
        defaultParse?.setDefault(false)
        defaultParse = parseBean
        scope.launch { appPreferences.put(AppConstants.DEFAULT_PARSE, parseBean.name ?: "") }
        parseBean.setDefault(true)
    }

    fun getDefaultParse(): ParseBean? = defaultParse

    fun getSourceBeanList(): List<SourceBean> = synchronized(sourceBeanList) { ArrayList(sourceBeanList.values) }

    fun getParseBeanList(): List<ParseBean> = synchronized(parseBeanList) { ArrayList(parseBeanList) }

    fun getVipParseFlags(): List<String> = vipParseFlags

    /** 判断 flag 是否命中 VIP 解析规则（忽略大小写包含匹配） */
    fun containsFlag(flag: String): Boolean {
        if (vipParseFlags.isEmpty()) return false
        val normalized = flag.trim().lowercase()
        for (vipFlag in vipParseFlags) {
            if (normalized.contains(vipFlag.trim().lowercase())) return true
        }
        return false
    }

    val homeSourceBean: SourceBean get() = homeSource ?: emptyHome

    /** 返回直播频道分组快照，避免外部持有共享引用被并发 clear */
    fun getChannelGroupList(): List<LiveChannelGroup> =
        synchronized(liveChannelGroupList) { ArrayList(liveChannelGroupList) }

    fun hasAdHost(host: String): Boolean = adHosts.contains(host)

    // ------------------------------------------------------------------
    // clan 协议与路径修正
    // ------------------------------------------------------------------
    /** 将 clan:// 协议地址转换为可访问的 http 地址，localhost 形式需存储权限 */
    fun clanToAddress(lanLink: String): String {
        if (lanLink.startsWith("clan://localhost/")) {
            if (!hasStoragePermission()) {
                EventHub.post(TypedEvent(STORAGE_PERMISSION_DENIED))
                return ""
            }
            return lanLink.replace("clan://localhost/", SpiderApi().getAddress(true) + "file/")
        }
        val link = lanLink.substring(7)
        val end = link.indexOf('/')
        return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1)
    }

    private fun hasStoragePermission(): Boolean =
        StoragePermissionUtils.hasManageExternalStorage(context)

    /** 将内容中的 clan:// 替换为 clan 地址前缀 */
    fun clanContentFix(lanLink: String, content: String): String {
        val fix = lanLink.substring(0, lanLink.indexOf("/file/") + 6)
        return content.replace("clan://", fix)
    }

    /** 修正配置中 "./" 相对路径为绝对路径 */
    fun fixContentPath(url: String, content: String): String {
        if (!content.contains("\"./")) return content
        var fixedUrl = url.replace("file://", "clan://localhost/")
        if (!fixedUrl.startsWith("http") && !fixedUrl.startsWith("clan://")) {
            fixedUrl = "http://$fixedUrl"
        }
        if (fixedUrl.startsWith("clan://")) fixedUrl = clanToAddress(fixedUrl)
        return content.replace("./", fixedUrl.substring(0, fixedUrl.lastIndexOf("/") + 1))
    }

    /** 计算配置目录 URL（http://host/path/），供相对路径图片补全（与 fixContentPath 同一基准） */
    private fun baseUrlOf(url: String): String {
        var fixedUrl = url.replace("file://", "clan://localhost/")
        if (!fixedUrl.startsWith("http") && !fixedUrl.startsWith("clan://")) {
            fixedUrl = "http://$fixedUrl"
        }
        if (fixedUrl.startsWith("clan://")) fixedUrl = clanToAddress(fixedUrl)
        val idx = fixedUrl.lastIndexOf("/")
        // idx <= 7 说明只有协议（http:// 或 https://），无路径，直接补根斜杠
        return if (idx > 7) fixedUrl.substring(0, idx + 1) else fixedUrl.trimEnd('/') + "/"
    }

    // ------------------------------------------------------------------
    // 缓存与加载器清理
    // ------------------------------------------------------------------
    fun clearJarLoader() {
        jarLoader.clear()
    }

    fun clearLoader() {
        jarLoader.clear()
        pyLoader.clear()
        jsLoader.clear()
        // 清理后台预热去重集合，避免切源后新源的 Spider 因旧 warmKey 残留而不被预热
        synchronized(warmedSearchSpiderKeys) {
            warmedSearchSpiderKeys.clear()
        }
    }

    /** 清理全部缓存：爬虫加载器、jar 缓存目录、配置缓存，并重置首页源 */
    fun clearAllCache() {
        try {
            clearLoader()
            File(context.filesDir, "csp").takeIf { it.exists() }?.deleteRecursively()
            File(context.cacheDir, "catvod_csp").takeIf { it.exists() }?.deleteRecursively()
            File(context.cacheDir, "catvod_jsapi").takeIf { it.exists() }?.deleteRecursively()
            File(context.cacheDir, "okhttp").takeIf { it.exists() }?.deleteRecursively()
            File(context.cacheDir, "http_cache").takeIf { it.exists() }?.deleteRecursively()
            File(context.filesDir, "py_plugins").takeIf { it.exists() }?.deleteRecursively()
            QuickJsEngine.clearGlobalCache()
            val apiUrl = appPreferences.getString(AppConstants.API_URL, "")
            if (apiUrl.isNotEmpty()) {
                File(context.filesDir, MD5.string2MD5(apiUrl) ?: "").takeIf { it.exists() }?.delete()
            }
            scope.launch { appPreferences.put(AppConstants.HOME_API, "") }
            synchronized(sourceBeanList) { sourceBeanList.clear() }
            synchronized(liveChannelGroupList) { liveChannelGroupList.clear() }
            synchronized(parseBeanList) { parseBeanList.clear() }
            homeSource = null
            defaultParse = null
            spider = ""
            wallpaper = ""
            livePlayHeaders = null
            adHosts.clear()
            myHosts = emptyMap()
            HostDnsMap.update(emptyMap())
        } catch (e: Exception) {
            LOG.e("清理缓存失败: ${e.message}")
        }
    }

    private fun addSuperParse() {
        val superPb = ParseBean()
        superPb.name = "超级解析"
        superPb.setUrl("SuperParse")
        superPb.ext = ""
        superPb.type = 4
        parseBeanList.add(0, superPb)
    }

    private fun collectDefaultAds() {
        val defaultJson = Gson().fromJson(DEFAULT_ADS, JsonObject::class.java)
        for (host in DefaultConfig.safeJsonArray(defaultJson, "ads") ?: emptyList()) {
            host.takeIf { it.isJsonPrimitive }?.asString?.let { adHosts.add(it) }
        }
    }

    // ------------------------------------------------------------------
    // 配置解密（兼容加密订阅）
    // ------------------------------------------------------------------
    /**
     * 还原加密配置：支持 Base64 头部剥离、2423/2324 双层 AES-CBC、;pk; 密钥 ECB。
     * 对应 FindResult。
     */
    fun findResult(json: String, configKey: String?): String {
        var content = json
        try {
            if (ConfigCipher.isJson(content)) return content
            val pattern = Pattern.compile("[A-Za-z0-9]{8}\\*\\*")
            val matcher = pattern.matcher(content)
            if (matcher.find()) {
                content = content.substring(content.indexOf(matcher.group()) + 10)
                content = String(Base64.decode(content, Base64.DEFAULT))
            }
            if (content.startsWith("2423")) {
                val data = content.substring(content.indexOf("2324") + 4, content.length - 26)
                content = String(ConfigCipher.toBytes(content)).lowercase()
                val key = ConfigCipher.rightPadding(
                    content.substring(content.indexOf("\$#") + 2, content.indexOf("#\$")), "0", 16
                )
                val iv = ConfigCipher.rightPadding(content.substring(content.length - 13), "0", 16)
                return ConfigCipher.cbc(data, key, iv) ?: json
            } else if (configKey != null && !ConfigCipher.isJson(content)) {
                return ConfigCipher.ecb(content, configKey) ?: json
            } else {
                return content
            }
        } catch (e: Throwable) {
            LOG.e(e)
        }
        return json
    }

    /** 从图片隐写配置中提取 jar 字节，匹配 8 位字符+** 头部后取 Base64 体 */
    private fun getImgJar(body: String): ByteArray {
        val pattern = Pattern.compile("[A-Za-z0-9]{8}\\*\\*")
        val matcher = pattern.matcher(body)
        if (matcher.find()) {
            val data = body.substring(body.indexOf(matcher.group()) + 10)
            return Base64.decode(data, Base64.DEFAULT)
        }
        return ByteArray(0)
    }

    // ------------------------------------------------------------------
    // 回调契约
    // ------------------------------------------------------------------

    interface LoadConfigCallback {
        fun success()
        fun retry()
        fun error(msg: String)
        /** 订阅地址为多仓（接口合集）时的线路列表，由 UI 弹窗让用户选择，默认不处理 */
        fun apiLines(lines: List<Pair<String, String>>) {}
    }

    interface FastParseCallback {
        fun success(parse: Boolean, url: String, header: Map<String, String>)
        fun fail(code: Int, msg: String)
    }

    /**
     * AES 配置解密工具，支持 rightPadding/toBytes/ECB/CBC/isJson 加密订阅还原。
     * 仅用于加密订阅配置还原，非通用加解密。
     */
    private object ConfigCipher {
        fun rightPadding(key: String, replace: String, length: Int): String {
            val trimmed = key.trim()
            val curLength = trimmed.length
            return when {
                curLength > length -> trimmed.substring(0, length)
                curLength == length -> trimmed
                else -> {
                    val sb = StringBuilder(trimmed)
                    repeat(length - curLength) { sb.append(replace) }
                    sb.toString()
                }
            }
        }

        fun ecb(data: String, key: String): String? = try {
            val paddedKey = rightPadding(key, "0", 16)
            val keySpec = SecretKeySpec(paddedKey.toByteArray(), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            String(cipher.doFinal(toBytes(data)))
        } catch (e: Throwable) {
            LOG.e(e); null
        }

        fun cbc(data: String, key: String, iv: String): String? = try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            val keySpec = SecretKeySpec(key.toByteArray(), "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv.toByteArray()))
            String(cipher.doFinal(toBytes(data)))
        } catch (e: Throwable) {
            LOG.e(e); null
        }

        fun isJson(content: String): Boolean = try {
            JSONObject(content); true
        } catch (_: Throwable) {
            false
        }

        fun toBytes(src: String): ByteArray {
            val l = src.length / 2
            val ret = ByteArray(l)
            for (i in 0 until l) {
                ret[i] = Integer.valueOf(src.substring(i * 2, i * 2 + 2), 16).toByte()
            }
            return ret
        }
    }

    companion object {
        private const val TAG = "ApiConfig"
        // okhttp 类 UA 是源站识别"TVBox 客户端"的特征：服务器对 okhttp UA 返回隐写配置图（findResult 提取 [A-Za-z0-9]{8}\*\* 后的 Base64 得到完整配置）
        private const val USER_AGENT = "okhttp/4.12.0"
        private const val REQUEST_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9"
        private const val PK = ";pk;"
        private const val STORAGE_PERMISSION_DENIED = "storage_permission_denied"
        private const val DEFAULT_ADS =
            "{\"ads\":[\"mimg.0c1q0l.cn\",\"www.googletagmanager.com\",\"www.google-analytics.com\",\"mc.usihnbcq.cn\",\"mg.g1mm3d.cn\",\"mscs.svaeuzh.cn\",\"cnzz.hhttm.top\",\"tp.vinuxhome.com\",\"cnzz.mmstat.com\",\"www.baihuillq.com\",\"s23.cnzz.com\",\"z3.cnzz.com\",\"c.cnzz.com\",\"stj.v1vo.top\",\"z12.cnzz.com\",\"img.mosflower.cn\",\"tips.gamevvip.com\",\"ehwe.yhdtns.com\",\"xdn.cqqc3.com\",\"www.jixunkyy.cn\",\"sp.chemacid.cn\",\"hm.baidu.com\",\"s9.cnzz.com\",\"z6.cnzz.com\",\"um.cavuc.com\",\"mav.mavuz.com\",\"wofwk.aoidf3.com\",\"z5.cnzz.com\",\"xc.hubeijieshikj.cn\",\"tj.tianwenhu.com\",\"xg.gars57.cn\",\"k.jinxiuzhilv.com\",\"cdn.bootcss.com\",\"ppl.xunzhuo123.com\",\"xomk.jiangjunmh.top\",\"img.xunzhuo123.com\",\"z1.cnzz.com\",\"s13.cnzz.com\",\"xg.huataisangao.cn\",\"z7.cnzz.com\",\"xg.huataisangao.cn\",\"z2.cnzz.com\",\"s96.cnzz.com\",\"q11.cnzz.com\",\"thy.dacedsfa.cn\",\"xg.whsbpw.cn\",\"s19.cnzz.com\",\"z8.cnzz.com\",\"s4.cnzz.com\",\"f5w.as12df.top\",\"ae01.alicdn.com\",\"www.92424.cn\",\"k.wudejia.com\",\"vivovip.mmszxc.top\",\"qiu.xixiqiu.com\",\"cdnjs.hnfenxun.com\",\"cms.qdwght.com\"]}"
    }
}
