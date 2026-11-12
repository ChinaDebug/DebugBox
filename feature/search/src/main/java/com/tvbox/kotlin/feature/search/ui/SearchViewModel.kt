package com.tvbox.kotlin.feature.search.ui

import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.bean.Movie
import com.tvbox.kotlin.core.common.bean.SourceBean
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.data.repo.SearchPresenter
import com.tvbox.kotlin.core.network.OkHttpHelper
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.spider.CmsApi
import com.tvbox.kotlin.core.spider.SearchHelper
import android.content.Context
import com.tvbox.kotlin.core.ui.BaseViewModel
import com.tvbox.kotlin.core.ui.R as uiR
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * 搜索状态机：空闲 / 加载中 / 成功 / 空结果 / 错误
 */
sealed class SearchState {
    object Idle : SearchState()
    object Loading : SearchState()
    object Success : SearchState()
    object Empty : SearchState()
    data class Error(val msg: String) : SearchState()
}

/**
 * 搜索进度信息，驱动界面状态栏更新
 *
 * @param sorting 是否正在对最终结果排序（排序中展示独立状态，排序完成后展示最终结果数）
 */
data class SearchProgress(
    val total: Int = 0,
    val finished: Int = 0,
    val resultCount: Int = 0,
    val sorting: Boolean = false
)

/**
 * 聚合搜索左侧源过滤条目
 *
 * @param key       站点 key
 * @param name      站点显示名
 * @param hasResult 是否已有搜索结果，用于追加到过滤列表
 */
data class SourceFilterItem(
    val key: String,
    val name: String,
    val hasResult: Boolean = false
)

/**
 * 搜索页 ViewModel，负责多源并发搜索、搜索历史、聚合结果与分词管理。
 *
 * 设计要点：
 * - 通过 [ApiConfig.getCSP] 获取各站点 Spider 并调用 searchContent 完成搜索；
 * - 多源并发由 [Semaphore] 限制为 5，避免 Spider 内部状态竞争；
 * - 单源超时 12 秒，超时或异常自动降级为空结果，不影响其他源；
 * - 结果累积线程安全，通过 StateFlow 推送到界面；
 * - 搜索任务绑定 [viewModelScope]，界面销毁自动取消。
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiConfig: ApiConfig,
    private val appPreferences: AppPreferences,
    private val searchPresenter: SearchPresenter,
    private val searchHelper: SearchHelper,
    private val cmsApi: CmsApi,
    private val okHttpHelper: OkHttpHelper
) : BaseViewModel() {

    /** 当前搜索关键字 */
    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    /** 搜索状态 */
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    /** 普通搜索结果（按源扁平累积） */
    private val _searchResults = MutableStateFlow<List<Movie.Video>>(emptyList())
    val searchResults: StateFlow<List<Movie.Video>> = _searchResults.asStateFlow()

    /** 聚合搜索结果（按影片名聚合后展示） */
    private val _fastResults = MutableStateFlow<List<Movie.Video>>(emptyList())
    val fastResults: StateFlow<List<Movie.Video>> = _fastResults.asStateFlow()

    /** 搜索历史 */
    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    /** 热门词列表 */
    private val _hotWords = MutableStateFlow<List<String>>(emptyList())
    val hotWords: StateFlow<List<String>> = _hotWords.asStateFlow()

    /** 是否联想模式：true 展示"猜你想搜"，false 展示"热门搜索" */
    private val _isRecMode = MutableStateFlow(false)
    val isRecMode: StateFlow<Boolean> = _isRecMode.asStateFlow()

    /** 分词列表（聚合搜索使用） */
    private val _splitWords = MutableStateFlow<List<String>>(emptyList())
    val splitWords: StateFlow<List<String>> = _splitWords.asStateFlow()

    /** 源过滤列表（聚合搜索左侧栏） */
    private val _sourceFilter = MutableStateFlow<List<SourceFilterItem>>(emptyList())
    val sourceFilter: StateFlow<List<SourceFilterItem>> = _sourceFilter.asStateFlow()

    /** 搜索进度 */
    private val _progress = MutableStateFlow(SearchProgress())
    val progress: StateFlow<SearchProgress> = _progress.asStateFlow()

    /**
     * 结果版本号：排序完成时递增，UI 依据它强制全量刷新网格呈现排序结果。
     * 不能用 progress.sorting 的跳变（true -> false）作为刷新信号：该中间态可能被
     * StateFlow 合并跳过（排序很快/主线程繁忙时），且排序结果顺序未变时结果列表
     * StateFlow 因内容相等不重发，网格会停留在排序前的顺序。
     */
    private val _resultVersion = MutableStateFlow(0L)
    val resultVersion: StateFlow<Long> = _resultVersion.asStateFlow()

    /** 当前搜索任务，便于取消 */
    private var searchJob: Job? = null

    /**
     * Spider（JS/Python/Jar）执行线程池。
     * 爬虫执行可能是阻塞且不可中断的（QuickJS/Python 死循环、慢脚本），
     * 放独立线程 + future.get(超时) 兜底，超时即放弃等待，防止单个爬虫拖住整体搜索。
     */
    private val spiderExecutor = Executors.newCachedThreadPool()

    /** 联想缓存关键字，相同关键字不重复请求 */
    private var lastRecKey: String = ""

    /** 多源并发上限，与线程池大小一致 */
    private val searchSemaphore = Semaphore(SEARCH_CONCURRENCY)

    /** 结果累积锁 */
    private val resultLock = Any()

    /** 更新当前关键字 */
    fun setKeyword(text: String) {
        _keyword.value = text
    }

    /** 加载搜索历史，按最近优先排序 */
    fun loadHistory() {
        launchIO {
            val history = searchPresenter.getSearchHistory()
                .mapNotNull { it.searchKeyWords }
                .asReversed()
            _searchHistory.value = history
        }
    }

    /** 清空搜索历史 */
    fun clearHistory() {
        launchIO {
            searchPresenter.clearSearchHistory()
            _searchHistory.value = emptyList()
        }
    }

    /**
     * 执行搜索。
     *
     * @param keyword 搜索关键字
     * @param fast    true 走聚合搜索（按源过滤），false 走普通搜索（扁平展示）
     */
    fun search(keyword: String, fast: Boolean) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) {
            toast(context.getString(uiR.string.toast_input_keyword))
            return
        }
        val checkedSources = searchHelper.getSourcesForSearch()
        if (checkedSources.isNullOrEmpty()) {
            toast(context.getString(uiR.string.toast_no_searchable_site))
            return
        }

        // 取消上一次搜索任务，避免结果错乱
        searchJob?.cancel()

        _keyword.value = trimmed
        _searchState.value = SearchState.Loading
        _searchResults.value = emptyList()
        _fastResults.value = emptyList()
        _sourceFilter.value = emptyList()
        _progress.value = SearchProgress()

        // 异步保存搜索历史
        launchIO {
            if (!searchPresenter.keywordsExist(trimmed)) {
                searchPresenter.addKeyWordsTodb(trimmed)
                loadHistory()
            }
        }

        // 聚合搜索时生成本地分词，供界面快速二次搜索
        if (fast) {
            _splitWords.value = searchHelper.splitWords(trimmed)
        }

        // 准备参与搜索的源列表：首页源置顶，过滤不可搜索与未勾选源
        val sources = ArrayList(apiConfig.getSourceBeanList())
        val home = apiConfig.homeSourceBean
        sources.remove(home)
        sources.add(0, home)
        val searchSources = sources.filter { bean ->
            bean.isSearchable() && checkedSources.containsKey(bean.key)
        }

        if (searchSources.isEmpty()) {
            _searchState.value = SearchState.Empty
            return
        }

        _progress.value = SearchProgress(total = searchSources.size)

        // 聚合搜索初始化源过滤列表（全部标记无结果，有结果时动态追加）
        if (fast) {
            _sourceFilter.value = searchSources.map { bean ->
                SourceFilterItem(
                    key = bean.key ?: "",
                    name = bean.name ?: bean.key ?: "",
                    hasResult = false
                )
            }
        }

        searchJob = viewModelScope.launch(handler) {
            val cancelled = supervisorScope {
                val jobs = searchSources.map { source ->
                    launch(Dispatchers.IO) {
                        searchSemaphore.withPermit {
                            searchOneSource(source, trimmed, fast)
                        }
                        _progress.update { it.copy(finished = it.finished + 1) }
                    }
                }
                waitForSearchFinish(jobs)
            }
            // 补足进度并同时标记"正在排序中"：若分开更新（先补足 finished 再置 sorting），
            // UI 会先看到"finished=total 且 sorting=false"而错误显示一次"搜索完成"
            _progress.update {
                it.copy(
                    finished = minOf(it.finished + cancelled, it.total),
                    sorting = true
                )
            }
            // 短暂让出主线程：排序通常很快，不延时的话状态栏"正在排序中"会在同一帧
            // 被"搜索完成"覆盖而不可见；延时保证用户能看到排序状态，
            // 排序完成、网格刷新后再切换为"搜索完成"
            kotlinx.coroutines.delay(SORTING_TIP_MIN_VISIBLE_MS)
            // 按与关键词的相关度排序：完全匹配 > 前缀匹配 > 包含匹配 > 模糊匹配。
            // 排序移入后台线程执行：排序在后台期间主线程空闲，状态栏能及时刷新出
            // "正在排序中"，且结果量大时不会卡住界面
            val sorted = withContext(Dispatchers.Default) {
                if (fast) {
                    sortByRelevance(trimmed, _fastResults.value)
                } else {
                    sortByRelevance(trimmed, _searchResults.value)
                }
            }
            if (fast) {
                _fastResults.value = sorted
            } else {
                _searchResults.value = sorted
            }
            // 让出主线程：确保结果列表的 collect 先刷新网格，再切换为"搜索完成"，
            // 避免"搜索完成"标签先于排序结果出现
            kotlinx.coroutines.delay(SORTING_DONE_REFRESH_LEAD_MS)
            // 排序完成信号：结果顺序未变/内容相等时结果 StateFlow 可能不重发，
            // UI 无法感知排序完成；版本号递增后 UI 强制用最新结果全量刷新网格
            _resultVersion.update { it + 1 }
            // 刷新最终状态：排序完成，展示最终结果数
            val results = if (fast) _fastResults.value else _searchResults.value
            _searchState.value = if (results.isEmpty()) SearchState.Empty else SearchState.Success
            _progress.update { it.copy(resultCount = results.size, sorting = false) }
        }
    }

    /**
     * 等待全部源搜索任务完成后再统一排序。
     * 每个源内部已有可靠超时兜底（单源搜索 [SEARCH_TIMEOUT_MS] 超时 + CmsApi 请求
     * 10s 绝对超时），正常情况最慢源 ≤10s 即返回，不会无限卡住；
     * 这里仅保留一个 [WAIT_ALL_MAX_MS] 极端兜底，防止个别爬虫执行不可中断时永久阻塞。
     *
     * @return 兜底取消的任务数；全部正常完成时为 0。
     *         取消任务的进度由调用方在标记排序时一并补足，避免中间态被 UI 误读为"搜索完成"。
     */
    private suspend fun waitForSearchFinish(jobs: List<Job>): Int {
        val start = System.currentTimeMillis()
        while (jobs.any { !it.isCompleted }) {
            if (System.currentTimeMillis() - start > WAIT_ALL_MAX_MS) {
                break
            }
            kotlinx.coroutines.delay(100)
        }
        // 兜底取消仍未完成的任务，防止其迟到结果在排序后再次追加打乱顺序
        val cancelled = jobs.count { !it.isCompleted }
        jobs.forEach { it.cancel() }
        return cancelled
    }

    /** 取消当前搜索任务 */
    fun cancelSearch() {
        searchJob?.cancel()
        searchJob = null
        _searchState.value = SearchState.Idle
        // 重置进度：避免从聚搜返回/页面重启动态时 collect 重放残留的完成态，
        // 导致普通搜索顶部状态标签被错误唤起
        _progress.value = SearchProgress()
    }

    /**
     * 单源搜索：type 3 走 Spider，type 0/1/4 直连采集站 HTTP。
     * 超时或异常返回空列表，不影响其他源。
     */
    private suspend fun searchOneSource(source: SourceBean, keyword: String, fast: Boolean) {
        val sourceKey = source.key ?: return
        val result = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            try {
                searchFromSource(source, keyword)
            } catch (_: Throwable) {
                emptyList()
            }
        } ?: emptyList()

        // 过滤与关键词无关的结果，避免源返回全量/推荐内容混入搜索结果
        val relevant = filterRelevant(keyword, result)
        if (relevant.isEmpty()) return

        synchronized(resultLock) {
            if (fast) {
                val current = ArrayList(_fastResults.value)
                current.addAll(relevant)
                _fastResults.value = current
                // 标记该源已有结果
                _sourceFilter.value = _sourceFilter.value.map { item ->
                    if (item.key == sourceKey) item.copy(hasResult = true) else item
                }
            } else {
                val current = ArrayList(_searchResults.value)
                current.addAll(relevant)
                _searchResults.value = current
            }
            // 实时同步已累计结果数：避免状态栏 resultCount 长时间为 0，
            _progress.update { it.copy(resultCount = if (fast) _fastResults.value.size else _searchResults.value.size) }
            // 任一源有结果即立即切换到成功态，界面边搜边显示，避免全部源完成前一直等待
            if (_searchState.value !is SearchState.Success) {
                _searchState.value = SearchState.Success
            }
        }
    }

    /** 过滤与关键词无关的结果，提升搜索准确性 */
    private fun filterRelevant(keyword: String, videos: List<Movie.Video>): List<Movie.Video> {
        if (videos.isEmpty()) return videos
        val key = normalizeSearchText(keyword)
        // 空关键词或单字符关键词不做过滤，避免误杀
        if (key.isEmpty() || key.length <= 1) return videos
        val keyChars = key.toSet()
        val hasCjk = keyChars.any { it in CJK_EXT_A || it in CJK_UNIFIED }
        return videos.filter { video ->
            val name = normalizeSearchText(video.name.orEmpty())
            if (name.isEmpty()) return@filter false
            // 强匹配：名称包含完整关键词（含去空格/标点后的变体）
            if (name.contains(key)) return@filter true
            if (hasCjk) {
                // 中文关键词：名称与关键词的字符重叠率过半即视为相关
                val overlap = name.toSet().intersect(keyChars).size
                return@filter overlap >= (keyChars.size + 1) / 2
            }
            // 纯字母/数字关键词：按单词匹配，任一关键词单词命中即保留
            val keyWords = key.split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }
            if (keyWords.isNotEmpty()) {
                return@filter keyWords.any { name.contains(it) }
            }
            false
        }
    }

    /** 搜索文本归一化：转小写并去除空白与常见标点，便于匹配（正则预编译，避免每次匹配重复编译） */
    private fun normalizeSearchText(text: String): String =
        text.lowercase(Locale.ROOT).replace(NORMALIZE_REGEX, "")

    /**
     * 按与关键词的相关度排序：完全匹配 > 前缀匹配 > 包含匹配 > 模糊匹配。
     * 稳定排序（同级保持源返回顺序），普通搜索与聚合搜索完成后统一调用。
     * 先预计算每个结果的 rank（只归一化一次），再比较整数，避免比较回调中
     * 反复做正则归一化导致结果多时排序卡顿。
     */
    private fun sortByRelevance(keyword: String, videos: List<Movie.Video>): List<Movie.Video> {
        if (videos.size <= 1) return videos
        val key = normalizeSearchText(keyword)
        if (key.isEmpty()) return videos
        val ranked = videos.map { video -> video to relevanceRank(key, video.name.orEmpty()) }
        return ranked.sortedWith { a, b -> a.second.compareTo(b.second) }.map { it.first }
    }

    /** 名称与关键词的相关度分级：0 完全匹配 / 1 前缀匹配 / 2 包含匹配 / 3 模糊匹配 */
    private fun relevanceRank(key: String, name: String): Int {
        val n = normalizeSearchText(name)
        return when {
            n == key -> 0
            n.startsWith(key) -> 1
            n.contains(key) -> 2
            else -> 3
        }
    }

    /** 按站点类型分派搜索：type 3 爬虫，type 0/1/4 采集站 HTTP 直连 */
    private fun searchFromSource(source: SourceBean, keyword: String): List<Movie.Video> {
        val sourceKey = source.key ?: return emptyList()
        if (source.type == 3) {
            // Spider（JS/Python/Jar）执行可能是阻塞且不可中断的（QuickJS/Python 死循环、
            // 慢脚本），withTimeoutOrNull 无法中断阻塞调用；这里放独立线程池执行，
            // 超时即放弃等待（卡死线程残留运行但不阻塞整体搜索），保证单源耗时 ≤ SEARCH_TIMEOUT_MS
            return try {
                spiderExecutor.submit<List<Movie.Video>> {
                    val spider = apiConfig.getCSP(source)
                    val json = spider.searchContent(keyword, false)
                    parseVideos(json, sourceKey)
                }.get(SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
                emptyList()
            }
        }
        return cmsApi.search(source, keyword)
    }

    /** 加载热门搜索词（腾讯热搜接口，进程内静态缓存） */
    fun loadHotWords() {
        if (staticHotWords.isNotEmpty()) {
            _hotWords.value = ArrayList(staticHotWords)
            return
        }
        launchIO {
            try {
                val builder = HOT_WORDS_URL.toHttpUrlOrNull()?.newBuilder() ?: return@launchIO
                builder.addQueryParameter("appID", "3172")
                    .addQueryParameter("appKey", "lGhFIPeD3HsO9xEp")
                    .addQueryParameter("platform", "2")
                    .addQueryParameter("channelID", "0")
                    .addQueryParameter("v", "2972330")
                val request = Request.Builder().url(builder.build()).build()
                val result = okHttpHelper.client().newCall(request).execute().use { it.body?.string() ?: "" }
                if (result.isBlank()) return@launchIO
                val obj = JSONObject(result)
                val data = obj.optJSONObject("data") ?: return@launchIO
                val arr = data.optJSONArray("hotWordList") ?: return@launchIO
                val hotList = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val word = arr.getJSONObject(i).optString("searchWord").trim()
                    if (word.isNotEmpty() && !hotList.contains(word)) hotList.add(word)
                }
                staticHotWords.clear()
                for (i in 0 until minOf(10, hotList.size)) {
                    val hotKey = hotList[i].replace(Regex("<|>|《|》|-"), "").split(" ")[0]
                    if (hotKey.isNotEmpty()) staticHotWords.add(HOT_EMOJI[i] + "\uFEFF" + hotKey)
                }
                if (staticHotWords.isNotEmpty()) {
                    _hotWords.value = ArrayList(staticHotWords)
                }
            } catch (_: Throwable) {
            }
        }
    }

    /** 热门词展示（清空输入框或取消联想时调用） */
    fun restoreHotWords() {
        _isRecMode.value = false
        lastRecKey = ""
        if (staticHotWords.isNotEmpty()) {
            _hotWords.value = ArrayList(staticHotWords)
        }
    }

    /** 输入关键字联想（猜你想搜），成功后切换到联想模式；接口失败/无结果时用本地热门词与历史兜底 */
    fun loadRec(keyword: String) {
        val key = keyword.trim()
        if (key.isEmpty()) {
            restoreHotWords()
            return
        }
        if (key == lastRecKey) return
        lastRecKey = key
        launchIO {
            val recList = try {
                val builder = REC_URL.toHttpUrlOrNull()?.newBuilder() ?: return@launchIO
                builder.addQueryParameter("format", "json")
                    .addQueryParameter("page_num", "0")
                    .addQueryParameter("page_size", "50")
                    .addQueryParameter("key", key)
                val request = Request.Builder().url(builder.build()).build()
                val result = okHttpHelper.client().newCall(request).execute().use { it.body?.string() ?: "" }
                if (result.isBlank()) emptyList() else parseRecWords(result)
            } catch (_: Throwable) {
                emptyList()
            }
            val words = if (recList.isNotEmpty()) recList else buildLocalFallback(key)
            if (words.isNotEmpty()) {
                _isRecMode.value = true
                _hotWords.value = words
            }
        }
    }

    /** 解析联想接口返回的关键词列表：优先 keyword_txt，缺失时回退 cell_info.title */
    private fun parseRecWords(result: String): List<String> {
        return try {
            val obj = JSONObject(result)
            val searchData = obj.optJSONObject("data")?.optJSONObject("search_data") ?: return emptyList()
            val vecGroup = searchData.optJSONArray("vecGroupData") ?: return emptyList()
            if (vecGroup.length() == 0) return emptyList()
            val groupData = vecGroup.getJSONObject(0).optJSONArray("group_data") ?: return emptyList()
            val recList = ArrayList<String>()
            for (i in 0 until groupData.length()) {
                val item = groupData.getJSONObject(i)
                val word = item.optJSONObject("dtReportInfo")
                    ?.optJSONObject("reportData")
                    ?.optString("keyword_txt")
                    ?.takeIf { it.isNotBlank() }
                    ?: item.optJSONObject("cell_info")?.optString("title").orEmpty()
                // 去除接口返回中的高亮标签
                val cleaned = word.replace(Regex("<[^>]*>"), "").trim()
                if (cleaned.isNotEmpty() && !recList.contains(cleaned)) recList.add(cleaned)
            }
            recList
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 本地兜底联想：按关键字前缀过滤热门词与搜索历史，保证首拼输入也能出现"猜你想搜" */
    private fun buildLocalFallback(key: String): List<String> {
        val local = LinkedHashSet<String>()
        staticHotWords.forEach { hot ->
            val real = hot.split("\uFEFF").lastOrNull()?.trim().orEmpty()
            if (real.isNotEmpty() && real.startsWith(key)) local.add(real)
        }
        _searchHistory.value.forEach { h ->
            if (h.startsWith(key)) local.add(h)
        }
        return local.take(10).toList()
    }

    /** 解析 Spider.searchContent 返回的 JSON，提取影片列表 */
    private fun parseVideos(json: String, sourceKey: String): List<Movie.Video> {
        if (json.isBlank()) return emptyList()
        return try {
            val obj = JSONObject(json)
            if (!obj.has("list")) return emptyList()
            val listArr = obj.getJSONArray("list")
            val videos = ArrayList<Movie.Video>()
            for (i in 0 until listArr.length()) {
                val v = listArr.getJSONObject(i)
                val video = Movie.Video()
                video.id = v.optString("vod_id")
                video.name = v.optString("vod_name")
                video.pic = v.optString("vod_pic")
                video.note = v.optString("vod_remarks")
                video.lang = v.optString("vod_lang")
                video.area = v.optString("vod_area")
                video.year = v.optInt("vod_year", 0)
                video.sourceKey = sourceKey
                videos.add(video)
            }
            videos
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 读取搜索视图模式：0 文字列表，1 缩略图 */
    fun getSearchViewType(): Int =
        appPreferences.getInt(AppConstants.SEARCH_VIEW, 0)

    /** 是否启用聚合搜索模式 */
    fun isFastSearchMode(): Boolean =
        appPreferences.getBoolean(AppConstants.FAST_SEARCH_MODE, false)

    companion object {
        /**
         * 多源并发上限。
         * type 3 的 jar/js/py 爬虫需受限并发（避免 Spider 状态竞争），
         * type 0/1/4 采集站为无状态 HTTP 直连可充分并行；10 为两者折中，
         * 源较多时（20+ 源）相比原 5 可将批次减半、整体等待明显缩短。
         */
        private const val SEARCH_CONCURRENCY = 10

        /** 单源搜索超时（毫秒）：只砍真正无响应的源，正常慢源（3~5s）应保留结果 */
        private const val SEARCH_TIMEOUT_MS = 10_000L

        /** 等待全部源完成的最大时长（毫秒）：每源内部已有超时兜底，此值仅防爬虫执行不可中断时永久阻塞 */
        private const val WAIT_ALL_MAX_MS = 20_000L

        /** "正在排序中"状态的最短可见时长（毫秒）：排序过快时保证该状态在状态栏/标签可见 */
        private const val SORTING_TIP_MIN_VISIBLE_MS = 200L

        /** 排序完成后、切回"搜索完成"状态前的让出时长（毫秒）：确保结果列表 collect 先刷新网格 */
        private const val SORTING_DONE_REFRESH_LEAD_MS = 60L

        /** 中文扩展 A 区（\u3400-\u4DBF） */
        private val CJK_EXT_A = '\u3400'..'\u4DBF'

        /** 中文基本区（\u4E00-\u9FFF） */
        private val CJK_UNIFIED = '\u4E00'..'\u9FFF'

        /** 搜索文本归一化用正则（预编译，避免高频调用反复编译） */
        private val NORMALIZE_REGEX = Regex("[\\s《》\"'·.。，,、!！?？:：;；~～\\-—_/|()（）\\[\\]【】]")

        /** 腾讯热搜接口地址 */
        private const val HOT_WORDS_URL =
            "https://pbaccess.video.qq.com/trpc.universal_backend_service.hot_word_info.HttpHotWordRecall/GetHotWords"

        /** 输入联想接口地址（猜你想搜） */
        private const val REC_URL =
            "https://tv.aiseet.atianqi.com/i-tvbin/qtv_video/search/get_search_smart_box"

        /** 热门词序号 emoji */
        private val HOT_EMOJI = listOf(
            "🥇", "🥈", "🥉", "❹", "❺", "❻", "❼", "❽", "❾", "❿",
            "⑪", "⑫", "⑬", "⑭", "⑮", "⑯", "⑰", "⑱", "⑲", "⑳"
        )

        /** 进程内热门词缓存 */
        private val staticHotWords = ArrayList<String>()
    }
}
