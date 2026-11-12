package com.tvbox.kotlin.feature.home.ui

import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.bean.AbsSortXml
import com.tvbox.kotlin.core.common.bean.Movie
import com.tvbox.kotlin.core.common.bean.MovieSort
import com.tvbox.kotlin.core.common.util.ApiLineStore
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.ui.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * 首页 ViewModel，负责站点配置加载、jar 加载与首页分类数据获取。
 * 通过 [ApiConfig] 回调式接口加载配置，封装为协程挂起调用，避免主线程阻塞。
 * JSON 解析使用 Android 内置 [JSONObject]，避免对 Gson 的传递依赖。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val apiConfig: ApiConfig,
    private val appPreferences: com.tvbox.kotlin.core.common.util.AppPreferences
) : BaseViewModel() {

    /** 分类列表 */
    private val _sortList = MutableStateFlow<List<MovieSort.SortData>>(emptyList())
    val sortList: StateFlow<List<MovieSort.SortData>> = _sortList.asStateFlow()

    /** 首页推荐视频（my0 分类使用） */
    private val _homeVideos = MutableStateFlow<List<Movie.Video>>(emptyList())
    val homeVideos: StateFlow<List<Movie.Video>> = _homeVideos.asStateFlow()

    /** 加载状态 */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 当前首页源名称 */
    private val _sourceName = MutableStateFlow("")
    val sourceName: StateFlow<String> = _sourceName.asStateFlow()

    /**
     * 配置加载失败消息。
     * 由 UI 层弹窗展示并调用 [consumeConfigError] 消费，避免重复弹窗。
     */
    private val _configError = MutableStateFlow<String?>(null)
    val configError: StateFlow<String?> = _configError.asStateFlow()

    /** 订阅地址为多仓（接口合集）时的线路列表，由 UI 弹窗让用户选择 */
    private val _apiLines = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val apiLines: StateFlow<List<Pair<String, String>>> = _apiLines.asStateFlow()

    /** 配置是否就绪 */
    private var configLoaded = false

    /** jar 是否就绪 */
    private var jarLoaded = false

    /** 配置与 jar 均已就绪，可执行首页内容刷新（供首页聚合页真实刷新判断） */
    val isHomeReady: Boolean get() = configLoaded && jarLoaded

    /** 搜索 spider 预热标记，避免重复执行 */
    private var searchSpiderWarmStarted = false

    /**
     * 加载首页完整流程：配置 -> jar -> homeContent。
     * 任一阶段失败会通过 [error] 分发提示，并尽量降级到缓存或空数据。
     */
    fun loadHome(useCache: Boolean) {
        if (_loading.value) return
        launchIO {
            _loading.value = true
            try {
                if (!configLoaded) {
                    loadConfig(useCache)
                }
                val spider = apiConfig.getSpider()
                if (configLoaded && !jarLoaded && spider.isNotEmpty()) {
                    loadJar(useCache, spider)
                } else if (configLoaded && spider.isEmpty()) {
                    jarLoaded = true
                }
                // 主 jar 加载完成后再预热搜索 Spider：
                // 预热线程此前在 loadJar 之前启动，会在主 jar 就绪前 getCSP 拿到
                // SpiderNull，导致冷启动时 jar 源无法加载（预热与加载的竞态）
                if (configLoaded && !searchSpiderWarmStarted && !useCache && jarLoaded) {
                    searchSpiderWarmStarted = true
                    apiConfig.warmSearchSpiders()
                }
                if (configLoaded && jarLoaded) {
                    // py 引擎未就绪时等待初始化（带超时），避免加载态永久卡住
                    waitPyReady()
                    loadHomeContent()
                }
            } catch (t: Throwable) {
                error(t.message ?: "加载失败")
            } finally {
                _loading.value = false
            }
        }
    }

    /** 切换源或配置变更后重置状态并重新加载 */
    fun resetAndReload(useCache: Boolean) {
        configLoaded = false
        jarLoaded = false
        _sortList.value = emptyList()
        _homeVideos.value = emptyList()
        loadHome(useCache)
    }

    /** 选择多仓线路：写入订阅地址，清缓存后重新加载配置（不自动切换，由用户选择） */
    fun chooseApiLine(url: String) {
        if (url.isEmpty()) return
        launchIO {
            appPreferences.put(AppConstants.API_URL, url)
            _apiLines.value = emptyList()
            apiConfig.clearAllCache()
            resetAndReload(false)
        }
    }

    /** 读取已保存的多仓（接口合集）线路列表，未加载过多仓时返回空列表 */
    fun readApiLines(): List<Pair<String, String>> = ApiLineStore.readLines(appPreferences)

    /** 仅重新拉取首页分类内容（配置与 jar 已就绪时使用） */
    fun refreshHomeContent() {
        if (!configLoaded || !jarLoaded) return
        launchIO {
            _loading.value = true
            try {
                waitPyReady()
                loadHomeContent()
            } catch (t: Throwable) {
                error(t.message ?: "刷新失败")
            } finally {
                _loading.value = false
            }
        }
    }

    /** 首页源为 PY 且引擎未就绪时等待初始化（带超时），协程挂起不阻塞主线程 */
    private suspend fun waitPyReady() {
        var waited = 0L
        while (checkPyReady()) {
            if (waited >= PY_READY_WAIT_MS) return
            delay(PY_READY_RETRY_MS)
            waited += PY_READY_RETRY_MS
        }
    }

    companion object {
        /** py 引擎就绪等待上限（毫秒），超时后直接尝试加载 */
        private const val PY_READY_WAIT_MS = 10_000L
        /** py 引擎就绪轮询间隔（毫秒） */
        private const val PY_READY_RETRY_MS = 300L
    }

    /** 首页源为 PY 且引擎未就绪时置位等待，返回 true 表示需等待 */
    private fun checkPyReady(): Boolean {
        val home = apiConfig.homeSourceBean
        return home.api?.contains(".py") == true && !apiConfig.isPyLoaderReady()
    }

    private suspend fun loadConfig(useCache: Boolean) = suspendCancellableCoroutine<Unit> { cont ->
        apiConfig.loadConfig(useCache, object : ApiConfig.LoadConfigCallback {
            override fun success() {
                configLoaded = true
                if (cont.isActive) cont.resume(Unit)
            }

            override fun retry() {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun apiLines(lines: List<Pair<String, String>>) {
                // 多仓配置：交 UI 弹窗让用户选择线路，本次加载流程结束
                _apiLines.value = lines
                if (cont.isActive) cont.resume(Unit)
            }

            override fun error(msg: String) {
                if (msg == "-1") {
                    // 使用缓存配置且无网络时，ApiConfig 返回 -1 表示降级使用本地缓存
                    configLoaded = true
                    jarLoaded = true
                } else {
                    // 配置加载失败用弹窗提示（标题为错误信息，可跳设置），而非 Toast
                    _configError.value = msg
                    configLoaded = true
                    jarLoaded = true
                }
                if (cont.isActive) cont.resume(Unit)
            }
        })
    }

    /** 消费配置错误弹窗消息，防止重复弹出 */
    fun consumeConfigError() {
        _configError.value = null
    }

    private suspend fun loadJar(useCache: Boolean, jar: String) = suspendCancellableCoroutine<Unit> { cont ->
        apiConfig.loadJar(useCache, jar, object : ApiConfig.LoadConfigCallback {
            override fun success() {
                jarLoaded = true
                if (cont.isActive) cont.resume(Unit)
            }

            override fun retry() {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun error(msg: String) {
                jarLoaded = true
                configLoaded = true
                if (msg.isNotEmpty()) this@HomeViewModel.error(msg)
                if (cont.isActive) cont.resume(Unit)
            }
        })
    }

    /** 调用爬虫 homeContent 并解析为分类列表与首页视频 */
    private fun loadHomeContent() {
        val home = apiConfig.homeSourceBean
        _sourceName.value = home.name ?: ""
        val json = apiConfig.homeContent(true)
        val absSortXml = parseHomeContent(json)
        val sorts = absSortXml?.classes?.sortList ?: emptyList()
        // 分类列表按内容去重，避免每次刷新都重建全部 Fragment 导致分类位置丢失
        val newSorts = adjustSorts(sorts)
        if (!sameSortList(_sortList.value, newSorts)) {
            _sortList.value = newSorts
        }
        val videos = absSortXml?.videoList ?: absSortXml?.list?.videoList ?: emptyList()
        // 首页推荐视频来自当前首页源，补充 sourceKey 供详情/历史/更新检测使用
        val homeKey = home.key
        if (videos.isNotEmpty() && homeKey != null) {
            videos.forEach { if (it.sourceKey == null) it.sourceKey = homeKey }
        }
        _homeVideos.value = videos
    }

    /** 比较两个分类列表是否内容一致（id/name/sort 均相同视为一致） */
    private fun sameSortList(
        oldList: List<MovieSort.SortData>,
        newList: List<MovieSort.SortData>
    ): Boolean {
        if (oldList.size != newList.size) return false
        for (i in oldList.indices) {
            if (oldList[i].id != newList[i].id ||
                oldList[i].name != newList[i].name ||
                oldList[i].sort != newList[i].sort
            ) {
                return false
            }
        }
        return true
    }

    /**
     * 使用 [JSONObject] 解析 homeContent 返回的 JSON 字符串。
     * 兼容标准 JSON 格式：{ "class": [...], "list": [...] }。
     * 返回 XML 或解析失败时降级为 null，由调用方处理空数据。
     */
    private fun parseHomeContent(json: String): AbsSortXml? {
        if (json.isBlank()) return null
        return try {
            val obj = JSONObject(json)
            val absSortXml = AbsSortXml()
            // 解析分类列表
            if (obj.has("class")) {
                val classArr = obj.getJSONArray("class")
                val sortList = ArrayList<MovieSort.SortData>()
                for (i in 0 until classArr.length()) {
                    val cls = classArr.getJSONObject(i)
                    val sortData = MovieSort.SortData()
                    sortData.id = cls.optString("type_id").trim()
                    sortData.name = cls.optString("type_name").trim()
                    // 分类 id/名称缺失时跳过，避免分类菜单出现空白项
                    if (sortData.id.isNullOrEmpty() || sortData.name.isNullOrEmpty()) continue
                    if (cls.has("type_flag") && !cls.isNull("type_flag")) {
                        sortData.flag = cls.getString("type_flag")
                    }
                    sortList.add(sortData)
                }
                // 解析分类筛选
                if (obj.has("filters")) {
                    val filtersObj = obj.optJSONObject("filters")
                    if (filtersObj != null) {
                        val filtersMap = LinkedHashMap<String, ArrayList<MovieSort.SortFilter>>()
                        val keys = filtersObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val filterList = ArrayList<MovieSort.SortFilter>()
                            val one = filtersObj.opt(key)
                            if (one is JSONObject) {
                                filterList.add(parseSortFilter(one))
                            } else if (one is org.json.JSONArray) {
                                for (j in 0 until one.length()) {
                                    filterList.add(parseSortFilter(one.getJSONObject(j)))
                                }
                            }
                            filtersMap[key] = filterList
                        }
                        for (sortData in sortList) {
                            filtersMap[sortData.id]?.let { sortData.filters = it }
                        }
                    }
                }
                val movieSort = MovieSort()
                movieSort.sortList = sortList
                absSortXml.classes = movieSort
            }
            // 解析首页推荐视频
            if (obj.has("list")) {
                val listArr = obj.getJSONArray("list")
                val videos = ArrayList<Movie.Video>()
                for (i in 0 until listArr.length()) {
                    videos.add(parseVideo(listArr.getJSONObject(i)))
                }
                absSortXml.videoList = videos
            }
            absSortXml
        } catch (_: Throwable) {
            null
        }
    }

    /** 解析单个筛选对象 */
    private fun parseSortFilter(obj: JSONObject): MovieSort.SortFilter {
        val filter = MovieSort.SortFilter()
        filter.key = obj.optString("key")
        filter.name = obj.optString("name")
        val values = LinkedHashMap<String, String>()
        val valueArr = obj.optJSONArray("value")
        if (valueArr != null) {
            for (i in 0 until valueArr.length()) {
                val item = valueArr.getJSONObject(i)
                val n = if (item.has("n")) item.optString("n") else ""
                val v = if (item.has("v")) item.optString("v") else ""
                values[n] = v
            }
        }
        filter.values = values
        return filter
    }

    /** 解析单个影片 JSON 对象为 [Movie.Video] */
    private fun parseVideo(obj: JSONObject): Movie.Video {
        val video = Movie.Video()
        video.id = obj.optString("vod_id")
        video.name = obj.optString("vod_name")
        video.pic = obj.optString("vod_pic")
        video.lang = obj.optString("vod_lang")
        video.area = obj.optString("vod_area")
        video.year = obj.optInt("vod_year", 0)
        video.note = obj.optString("vod_remarks")
        video.actor = obj.optString("vod_actor")
        video.director = obj.optString("vod_director")
        video.type = obj.optString("type_name")
        video.last = obj.optString("vod_time")
        video.tag = obj.optString("vod_tag")
        // 配置中心类卡片 action：空串归一为 null，避免点击时误判进入 action 分支
        video.action = obj.optString("action").takeIf { it.isNotEmpty() }
        return video
    }

    /**
     * 按站点配置的 categories 顺序调整分类，未配置的分类追加到末尾。
     * 并在最前注入 my0 首页聚合分类。
     */
    private fun adjustSorts(sorts: List<MovieSort.SortData>): List<MovieSort.SortData> {
        val categories = apiConfig.homeSourceBean.categories
        val result = ArrayList<MovieSort.SortData>()
        if (!categories.isNullOrEmpty() && sorts.isNotEmpty()) {
            for (cate in categories) {
                val matched = sorts.firstOrNull { it.name == cate }
                if (matched != null && result.none { it.id == matched.id }) {
                    result.add(matched)
                }
            }
            for (s in sorts) {
                if (result.none { it.id == s.id }) result.add(s)
            }
        } else {
            result.addAll(sorts)
        }
        // 注入首页聚合页 my0，名称由 UI 层按本地化资源填充
        result.add(0, MovieSort.SortData("my0", ""))
        // sort 值越小越靠前
        result.sort()
        return result
    }
}
