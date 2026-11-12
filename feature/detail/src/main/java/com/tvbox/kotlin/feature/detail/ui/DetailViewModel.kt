package com.tvbox.kotlin.feature.detail.ui

import androidx.lifecycle.viewModelScope
import com.tvbox.kotlin.core.common.bean.Movie
import com.tvbox.kotlin.core.common.bean.SourceBean
import com.tvbox.kotlin.core.common.bean.VodInfo
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.data.repo.RoomDataManger
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.spider.CmsApi
import com.tvbox.kotlin.core.spider.SearchHelper
import com.tvbox.kotlin.core.ui.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/** 详情页加载状态 */
enum class LoadState { IDLE, LOADING, SUCCESS, EMPTY, ERROR }

/** 跳转播放参数 */
data class PlayParam(
    val url: String,
    val title: String?,
    val sourceKey: String,
    val episodeIndex: Int,
    val episodeName: String?
)

/**
 * 详情页 ViewModel：负责详情加载、线路切换、剧集选择、收藏与历史记录管理。
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    private val apiConfig: ApiConfig,
    private val appPreferences: AppPreferences,
    private val roomDataManger: RoomDataManger,
    private val cmsApi: CmsApi,
    private val searchHelper: SearchHelper
) : BaseViewModel() {

    /** 当前影片详情 */
    private val _vodInfo = MutableStateFlow<VodInfo?>(null)
    val vodInfo: StateFlow<VodInfo?> = _vodInfo.asStateFlow()

    /** 当前选中线路 flag */
    private val _selectedFlag = MutableStateFlow<String?>(null)
    val selectedFlag: StateFlow<String?> = _selectedFlag.asStateFlow()

    /** 当前选中剧集索引（绝对索引） */
    private val _selectedEpisode = MutableStateFlow(0)
    val selectedEpisode: StateFlow<Int> = _selectedEpisode.asStateFlow()

    /** 收藏状态 */
    private val _collected = MutableStateFlow(false)
    val collected: StateFlow<Boolean> = _collected.asStateFlow()

    /** 加载状态 */
    private val _loadState = MutableStateFlow(LoadState.IDLE)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    /** 跳转播放事件 */
    private val _playEvent = MutableSharedFlow<PlayParam>(extraBufferCapacity = 8)
    val playEvent: SharedFlow<PlayParam> = _playEvent.asSharedFlow()

    /** 排序反转状态 */
    private val _sortReversed = MutableStateFlow(false)
    val sortReversed: StateFlow<Boolean> = _sortReversed.asStateFlow()

    /** 快搜结果（当前源快速搜索） */
    private val _quickResults = MutableStateFlow<List<Movie.Video>>(emptyList())
    val quickResults: StateFlow<List<Movie.Video>> = _quickResults.asStateFlow()

    /** 快搜分词词条 */
    private val _quickWords = MutableStateFlow<List<String>>(emptyList())
    val quickWords: StateFlow<List<String>> = _quickWords.asStateFlow()

    private var quickSearchJob: Job? = null

    /** 快搜结果累积锁 */
    private val quickResultLock = Any()

    /** 快搜多源并发上限，与搜索页一致 */
    private val quickSearchSemaphore = Semaphore(QUICK_SEARCH_CONCURRENCY)

    /** 当前站点 key */
    private var sourceKey: String = ""

    /** 当前影片 id */
    private var vodId: String = ""

    /** 最近一次透传的竖版封面，重试/切换源时保持使用 */
    private var lastPic: String? = null

    /** 加载详情数据 */
    fun loadDetail(id: String, key: String, pic: String? = null) {
        if (id.isBlank()) {
            _loadState.value = LoadState.EMPTY
            return
        }
        lastPic = pic
        vodId = id
        sourceKey = key
        _loadState.value = LoadState.LOADING
        launchIO {
            try {
                val sourceBean = apiConfig.getSource(key)
                if (sourceBean == null) {
                    _loadState.value = LoadState.ERROR
                    error("站点不存在")
                    return@launchIO
                }
                // 按站点类型获取详情：type 3 走爬虫，type 0/1/4 直连采集站 HTTP
                val video = when (sourceBean.type) {
                    3 -> {
                        val json = withTimeout(15_000L) {
                            apiConfig.getCSP(sourceBean).detailContent(listOf(id))
                        }
                        if (json.isNullOrBlank()) null else parseFirstVideo(json)
                    }
                    0, 1, 4 -> cmsApi.detail(sourceBean, id)
                    else -> null
                }
                if (video == null) {
                    _loadState.value = LoadState.EMPTY
                    return@launchIO
                }
                video.id = id
                // 列表/搜索等入口透传的竖版封面覆盖详情接口返回的横版封面，
                // 保证详情页展示与保存的历史/收藏封面统一为竖版海报
                if (!pic.isNullOrBlank()) {
                    video.pic = pic
                }
                if (video.name.isNullOrBlank()) video.name = "片名"

                val info = VodInfo().apply {
                    setVideo(video)
                    sourceKey = key
                }
                restoreFromHistory(info)
                // 补齐线路与选中索引有效性，保证消费方读取到完整数据
                val initFlag = info.playFlag?.takeIf { info.seriesMap?.containsKey(it) == true }
                    ?: info.seriesFlags?.firstOrNull()?.name
                if (initFlag != null) {
                    if (info.playFlag != initFlag) {
                        info.playFlag = initFlag
                        info.seriesFlags?.forEach { it.selected = (it.name == initFlag) }
                    }
                    val initList = info.seriesMap?.get(initFlag)
                    if (!initList.isNullOrEmpty() && info.playEpisodeIndex !in initList.indices) {
                        info.playEpisodeIndex = 0
                    }
                }
                _vodInfo.value = info
                _loadState.value = LoadState.SUCCESS

                val flag = info.playFlag ?: info.seriesFlags?.firstOrNull()?.name
                if (flag != null) {
                    selectFlag(flag, triggerPlay = false)
                } else {
                    _selectedFlag.value = null
                }
                _sortReversed.value = info.reverseSort

                _collected.value = roomDataManger.isVodCollect(key, id)
            } catch (e: TimeoutCancellationException) {
                _loadState.value = LoadState.ERROR
                error("加载详情超时")
            } catch (e: Throwable) {
                _loadState.value = LoadState.ERROR
                error(e.message ?: "加载详情失败")
            }
        }
    }

    /** 从历史记录恢复播放位置与排序状态 */
    private suspend fun restoreFromHistory(info: VodInfo) {
        val id = info.id ?: return
        val history = roomDataManger.getVodInfo(sourceKey, id) ?: return
        info.playFlag = history.playFlag
        info.playIndex = history.playIndex
        info.playGroup = history.playGroup
        info.playGroupCount = history.playGroupCount
        info.playEpisodeIndex = history.playEpisodeIndex
        info.playEpisodeName = history.playEpisodeName
        info.playNote = history.playNote
        info.reverseSort = history.reverseSort
        info.playerCfg = history.playerCfg
        if (info.reverseSort) {
            info.reverse()
        }
    }

    /** 切换播放线路，尝试按集名匹配新线路的集数位置 */
    fun selectFlag(flagName: String, triggerPlay: Boolean) {
        val info = _vodInfo.value ?: return
        val map = info.seriesMap ?: return
        if (map.isEmpty()) return
        val oldFlag = info.playFlag
        val oldIndex = info.playEpisodeIndex
        val oldSeriesList = oldFlag?.let { map[it] }
        val preTotalEpisodes = oldSeriesList?.size ?: 0
        val currentName = oldSeriesList?.getOrNull(oldIndex)?.name

        info.playFlag = flagName
        info.seriesFlags?.forEach { flag ->
            flag.selected = (flag.name == flagName)
        }

        val newSeriesList = map[flagName]
        val newTotalEpisodes = newSeriesList?.size ?: 0
        var newIndex: Int
        if (newTotalEpisodes <= 0) {
            newIndex = 0
            info.playEpisodeIndex = 0
            info.playGroup = 0
            info.playIndex = 0
        } else {
            // 视觉位置：正序为当前集序号（1 起），倒序为从尾部数的位置
            val visualPosition = if (info.reverseSort) preTotalEpisodes - oldIndex else oldIndex + 1
            newIndex = currentName?.let { name ->
                newSeriesList?.indexOfFirst { it.name == name }?.takeIf { it >= 0 }
            } ?: when {
                visualPosition > newTotalEpisodes -> if (info.reverseSort) 0 else newTotalEpisodes - 1
                else -> if (info.reverseSort) newTotalEpisodes - visualPosition else visualPosition - 1
            }
            info.playEpisodeIndex = newIndex
            info.updatePlayPositionFromEpisodeIndex()
        }
        // 数据变更全部完成后再发布状态，避免 StateFlow 同步唤醒收集器读到中间态
        _selectedEpisode.value = newIndex
        _selectedFlag.value = flagName

        if (triggerPlay) {
            triggerPlay(info, newIndex)
        }
    }

    /** 选择指定剧集并触发播放 */
    fun selectEpisode(absoluteIndex: Int) {
        val info = _vodInfo.value ?: return
        val flag = info.playFlag ?: return
        val seriesList = info.seriesMap?.get(flag) ?: return
        if (absoluteIndex !in seriesList.indices) return

        info.playEpisodeIndex = absoluteIndex
        info.updatePlayPositionFromEpisodeIndex()
        _selectedEpisode.value = absoluteIndex
        // 换集立即写历史，对齐控制栏切集（进程被杀不丢）
        saveHistory()
        triggerPlay(info, absoluteIndex)
    }

    /** 仅更新选中索引，不触发播放 */
    fun updateSelectedEpisode(absoluteIndex: Int) {
        val info = _vodInfo.value ?: return
        info.playEpisodeIndex = absoluteIndex
        info.updatePlayPositionFromEpisodeIndex()
        _selectedEpisode.value = absoluteIndex
    }

    /**
     * 从播放页返回时同步播放位置：播放页是独立 Activity（info 为序列化副本），
     * 控制栏切集后本实例的 info 未更新，从历史记录恢复最新线路/集索引，
     * 保证集数列表选中状态一致，避免记忆进度按旧集保存异常。
     */
    fun syncEpisodeFromHistory() {
        val info = _vodInfo.value ?: return
        if (sourceKey.isEmpty() || vodId.isEmpty()) return
        launchIO {
            val history = roomDataManger.getVodInfo(sourceKey, vodId) ?: return@launchIO
            val flag = history.playFlag ?: return@launchIO
            val index = history.playEpisodeIndex
            val flagChanged = flag != info.playFlag
            val indexChanged = index != info.playEpisodeIndex
            if (flagChanged || indexChanged) {
                info.playFlag = flag
                info.playEpisodeIndex = index
                info.updatePlayPositionFromEpisodeIndex()
                info.playGroupCount = history.playGroupCount
                info.playEpisodeName = history.playEpisodeName
                // 仅发布选中线路/集索引驱动集数列表刷新；
                // 不重发 vodInfo（深拷贝会触发 updateDetailInfo 重置选中状态）
                if (flagChanged) _selectedFlag.value = flag
                _selectedEpisode.value = index
            }
        }
    }

    /**
     * 预览播放器切集（上一集/下一集/自动连播）后同步详情页选中状态。
     * 预览窗 PlayFragment 持有独立的 VodInfo 深拷贝，其内部切集不会驱动本
     * ViewModel 的 [_selectedEpisode]，且不触发 Activity.onResume/退出全屏；
     * 此处按切集后的线路/集索引直接同步并发布选中状态，驱动集数列表高亮与滚动，
     * 与"从全屏返回时 syncEpisodeFromHistory"等效。
     */
    fun syncPreviewEpisode(flag: String, index: Int) {
        val info = _vodInfo.value ?: return
        if (info.playFlag != flag) {
            info.playFlag = flag
            info.seriesFlags?.forEach { it.selected = (it.name == flag) }
            _selectedFlag.value = flag
        }
        val seriesList = info.seriesMap?.get(flag)
        if (index != info.playEpisodeIndex && index in 0 until (seriesList?.size ?: 0)) {
            info.playEpisodeIndex = index
            info.updatePlayPositionFromEpisodeIndex()
            _selectedEpisode.value = index
        }
    }

    /** 触发播放事件 */
    private fun triggerPlay(info: VodInfo, index: Int) {
        val flag = info.playFlag ?: return
        val seriesList = info.seriesMap?.get(flag) ?: return
        val series = seriesList.getOrNull(index) ?: return
        val url = series.url ?: return
        info.saveCurrentEpisodeInfo()
        launchIO {
            _playEvent.emit(
                PlayParam(
                    url = url,
                    title = info.name,
                    sourceKey = sourceKey,
                    episodeIndex = index,
                    episodeName = series.name
                )
            )
        }
    }

    /** 切换剧集排序（正序/倒序） */
    fun toggleSort() {
        val info = _vodInfo.value ?: return
        val flag = info.playFlag ?: return
        val seriesList = info.seriesMap?.get(flag) ?: return
        if (seriesList.isEmpty()) return

        val currentIndex = info.playEpisodeIndex
        val total = seriesList.size
        info.reverseSort = !info.reverseSort
        info.reverse()

        // 反转后保持当前集数位置：重算集索引、所在分组与组内索引
        val newIndex = total - 1 - currentIndex
        info.playEpisodeIndex = newIndex
        info.playGroup = newIndex / maxOf(info.playGroupCount, 1)
        info.playIndex = newIndex % maxOf(info.playGroupCount, 1)
        info.saveCurrentEpisodeInfo()
        // 数据变更全部完成后再发布状态，避免 StateFlow 同步唤醒收集器读到中间态
        _selectedEpisode.value = newIndex
        _sortReversed.value = info.reverseSort
    }

    /** 切换收藏状态 */
    fun toggleCollect() {
        val info = _vodInfo.value ?: return
        val id = info.id ?: return
        launchIO {
            if (_collected.value) {
                roomDataManger.deleteVodCollect(sourceKey, info)
                _collected.value = false
                toast("已取消收藏")
            } else {
                roomDataManger.insertVodCollect(sourceKey, info)
                _collected.value = true
                toast("已加入收藏")
            }
        }
    }

    /** 保存播放记录到历史记录 */
    fun saveHistory() {
        val info = _vodInfo.value ?: return
        val map = info.seriesMap ?: return
        val flag = info.playFlag ?: return
        launchIO {
            try {
                info.saveCurrentEpisodeInfo()
                val seriesList = map[flag]
                info.playNote = seriesList?.getOrNull(info.playEpisodeIndex)?.name ?: ""
                info.totalEpisodes = seriesList?.size ?: 0
                roomDataManger.insertVodRecord(sourceKey, info)
            } catch (e: Throwable) {
                // 保存历史记录失败不影响主流程
            }
        }
    }

    /**
     * 生成解析器列表 JSON（供预览播放器 setData 注入 parseList）。
     * 预览播放器与播放页保持同一套解析器，缺失会导致 parse=1 时解析失败。
     */
    fun getParseListJson(): String? {
        val list = apiConfig.getParseBeanList()
        if (list.isEmpty()) return null
        val array = JSONArray()
        for (bean in list) {
            val obj = JSONObject()
            obj.put("name", bean.name ?: "")
            obj.put("url", bean.getUrl() ?: "")
            obj.put("ext", bean.ext ?: "")
            obj.put("type", bean.type)
            obj.put("isDefault", bean.isDefault())
            array.put(obj)
        }
        return array.toString()
    }

    /**
     * 详情页快搜：以关键词并发搜索所有可搜索且支持快搜的站点，
     * 结果累积到 [quickResults]（过滤当前源当前影片自身），
     * 分词词条由 [SearchHelper.splitWords] 生成写入 [quickWords]。
     * 再次调用（点击分词换词重搜）会取消上一次任务并重置结果。
     */
    fun quickSearch(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        quickSearchJob?.cancel()

        _quickWords.value = searchHelper.splitWords(trimmed)
        _quickResults.value = emptyList()

        val checkedSources = searchHelper.getSourcesForSearch()
        // 参与搜索的源列表：首页源置顶，过滤不可搜索、未勾选或不支持快搜的源
        val sources = ArrayList(apiConfig.getSourceBeanList())
        val home = apiConfig.homeSourceBean
        sources.remove(home)
        sources.add(0, home)
        val searchSources = sources.filter { bean ->
            bean.isSearchable() && bean.isQuickSearch() &&
                (checkedSources == null || checkedSources.containsKey(bean.key))
        }
        if (searchSources.isEmpty()) {
            toast("没有可搜索的站点")
            return
        }

        quickSearchJob = viewModelScope.launch(handler) {
            supervisorScope {
                searchSources.map { source ->
                    launch(Dispatchers.IO) {
                        quickSearchSemaphore.withPermit {
                            val result = withTimeoutOrNull(QUICK_SEARCH_TIMEOUT_MS) {
                                try {
                                    searchFromSource(source, trimmed)
                                } catch (_: Throwable) {
                                    emptyList()
                                }
                            } ?: emptyList()
                            // 过滤掉当前源当前影片自身，避免重复展示
                            val filtered = result.filter { video ->
                                !(video.sourceKey == sourceKey && video.id == vodId)
                            }
                            if (filtered.isNotEmpty()) {
                                synchronized(quickResultLock) {
                                    val current = ArrayList(_quickResults.value)
                                    current.addAll(filtered)
                                    _quickResults.value = current
                                }
                            }
                        }
                    }
                }.joinAll()
            }
        }
    }

    /** 获取站点显示名（快搜结果列表展示用） */
    fun getSourceName(key: String?): String? = key?.let { apiConfig.getSource(it)?.name }

    /** 按站点类型分派快搜：type 3 爬虫，type 0/1/4 采集站 HTTP 直连 */
    private fun searchFromSource(source: SourceBean, keyword: String): List<Movie.Video> {
        val key = source.key ?: return emptyList()
        return if (source.type == 3) {
            val spider = apiConfig.getCSP(source)
            parseQuickSearchVideos(spider.searchContent(keyword, false), key)
        } else {
            cmsApi.search(source, keyword)
        }
    }

    /** 解析爬虫返回的 JSON 搜索列表（与搜索页同一套字段） */
    private fun parseQuickSearchVideos(json: String, sourceKey: String): List<Movie.Video> {
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
                video.type = v.optString("type_name")
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

    /** 重新加载详情 */
    fun retry() {
        if (vodId.isNotBlank() && sourceKey.isNotBlank()) {
            loadDetail(vodId, sourceKey, lastPic)
        }
    }

    /**
     * 解析详情 JSON 为首个 Video。
     * feature:detail 模块未直接依赖 Gson，使用 org.json.JSONObject 解析。
     */
    private fun parseFirstVideo(json: String): Movie.Video? {
        return try {
            val root = JSONObject(json)
            val list = root.optJSONArray("list") ?: return null
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                return parseVideoObject(item)
            }
            null
        } catch (e: Throwable) {
            null
        }
    }

    /** 从 JSONObject 提取字段构建 Video，并解析播放线路与集数 */
    private fun parseVideoObject(obj: JSONObject): Movie.Video {
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
        // 填充集数信息（beanList），VodInfo.setVideo 依赖该字段构建线路集数
        cmsApi.fillBeanList(video)
        return video
    }

    companion object {
        /** 快搜多源并发上限 */
        private const val QUICK_SEARCH_CONCURRENCY = 5

        /** 快搜单源超时（毫秒） */
        private const val QUICK_SEARCH_TIMEOUT_MS = 12_000L
    }
}
