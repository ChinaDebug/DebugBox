package com.tvbox.kotlin.feature.live.ui

import android.content.Context
import com.google.gson.JsonArray
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.bean.Epginfo
import com.tvbox.kotlin.core.common.bean.LiveChannelGroup
import com.tvbox.kotlin.core.common.bean.LiveChannelItem
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.network.OkHttpHelper
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.ui.BaseViewModel
import com.tvbox.kotlin.core.ui.R as uiR
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.inject.Inject

/** 直播加载状态 */
enum class LiveLoadState { IDLE, LOADING, SUCCESS, EMPTY, ERROR }

/**
 * 直播页 ViewModel：管理频道组列表、当前频道、播放地址、EPG 节目单与频道号输入。
 *
 * 数据来源：
 * - 频道列表来自 [ApiConfig.getChannelGroupList]
 * - EPG 通过 [OkHttpHelper] 请求 [AppConstants.EPG_URL]
 * - 上次频道记忆写入 [AppPreferences]
 */
@HiltViewModel
class LiveViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiConfig: ApiConfig,
    private val appPreferences: AppPreferences,
    private val okHttpHelper: OkHttpHelper
) : BaseViewModel() {

    /** 频道分组列表 */
    private val _channelGroups = MutableStateFlow<List<LiveChannelGroup>>(emptyList())
    val channelGroups: StateFlow<List<LiveChannelGroup>> = _channelGroups.asStateFlow()

    /** 当前频道组索引 */
    private val _currentGroupIndex = MutableStateFlow(-1)
    val currentGroupIndex: StateFlow<Int> = _currentGroupIndex.asStateFlow()

    /** 当前频道组 */
    private val _currentGroup = MutableStateFlow<LiveChannelGroup?>(null)
    val currentGroup: StateFlow<LiveChannelGroup?> = _currentGroup.asStateFlow()

    /** 当前频道 */
    private val _currentChannel = MutableStateFlow<LiveChannelItem?>(null)
    val currentChannel: StateFlow<LiveChannelItem?> = _currentChannel.asStateFlow()

    /** 当前播放 URL */
    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    /** 当前播放请求头 */
    private val _currentHeaders = MutableStateFlow<Map<String, String>>(emptyMap())
    val currentHeaders: StateFlow<Map<String, String>> = _currentHeaders.asStateFlow()

    /** EPG 节目单 */
    private val _epgList = MutableStateFlow<List<Epginfo>>(emptyList())
    val epgList: StateFlow<List<Epginfo>> = _epgList.asStateFlow()

    /** 播放状态 */
    private val _playState = MutableStateFlow(0)
    val playState: StateFlow<Int> = _playState.asStateFlow()

    // ------------------------------------------------------------------
    // 频道 logo 缓存与失败标记
    // ------------------------------------------------------------------
    /** 内存 logo 缓存条目 */
    private data class LogoCacheEntry(val drawable: android.graphics.drawable.Drawable, val cachedAt: Long)

    /** 内存 logo 缓存：epgid → 缓存条目（线程安全） */
    private val channelLogoCache = java.util.concurrent.ConcurrentHashMap<String, LogoCacheEntry>()
    /** 加载失败的 epgid 集合（失败后不再重复请求，直接显示占位图，线程安全） */
    private val logoFailedCache = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** logo 文件缓存有效期（24 小时） */
    private val LOGO_CACHE_EXPIRE_MS = 24 * 60 * 60 * 1000L
    /** logo 文件缓存目录 */
    private val LOGO_CACHE_DIR = "live_logo_cache"
    /** 预加载进行中标记（避免重复启动） */
    @Volatile
    private var logoPreloading = false

    /** 频道号输入缓冲 */
    private val _channelNumberInput = MutableStateFlow("")
    val channelNumberInput: StateFlow<String> = _channelNumberInput.asStateFlow()

    /** 加载状态 */
    private val _loadState = MutableStateFlow(LiveLoadState.IDLE)
    val loadState: StateFlow<LiveLoadState> = _loadState.asStateFlow()

    /** 提示事件 */
    private val _toastMsg = MutableStateFlow<String?>(null)
    val toastMsg: StateFlow<String?> = _toastMsg.asStateFlow()

    /** 当前频道索引（组内） */
    private var currentChannelIndex: Int = -1

    /** 实际播放频道的分组索引：切换分组不改变，用于切回原分组时恢复频道列表位置 */
    private var playingGroupIndex = -1

    /** 读取实际播放频道的分组索引 */
    fun getPlayingGroupIndex(): Int = playingGroupIndex

    /** 已确认密码的分组索引 */
    private val passwordConfirmedGroups = mutableSetOf<Int>()

    /** 正在下载的全量 EPG URL 集合：同一 URL 同时仅一个协程下载，其余等待完成后复用缓存（线程安全） */
    private val downloadingXmltvUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 单条 EPG 节目（start/stop/title 原始字符串） */
    private data class XmltvProgramme(val start: String, val stop: String, val title: String)

    /** 全量 xmltv 内存缓存（不可变快照，整体原子替换） */
    private data class XmltvCache(
        val sourceUrl: String,
        val cacheTime: Long,
        val channels: Map<String, String>,
        val programmes: Map<String, List<XmltvProgramme>>
    )

    /** 全量 xmltv 内存缓存快照，null 表示未加载 */
    @Volatile
    private var xmltvCache: XmltvCache? = null
    /** 是否已从本地文件加载过缓存（仅首次加载一次） */
    private var xmltvCacheLoadedFromFile = false

    /** Intent 传入的初始频道索引 */
    private var initialGroupIndex = -1
    private var initialChannelIndex = -1

    /** 设置 Intent 携带的初始频道，加载完成后优先播放 */
    fun setInitialChannel(groupIndex: Int, channelIndex: Int) {
        initialGroupIndex = groupIndex
        initialChannelIndex = channelIndex
    }

    /** 加载频道组列表 */
    fun loadChannels() {
        _loadState.value = LiveLoadState.LOADING
        launchIO {
            // 确保 txt 直播源已解析（FongMi 格式已填充时直接跳过）
            apiConfig.ensureLiveChannelsLoaded()
            val list = apiConfig.getChannelGroupList()
            if (list.isEmpty()) {
                _loadState.value = LiveLoadState.EMPTY
                return@launchIO
            }
            // 拷贝快照，避免持有 ApiConfig 内部共享列表（可能被后台 loadTxtLives 并发 clear）
            _channelGroups.value = ArrayList(list)
            _loadState.value = LiveLoadState.SUCCESS
            restoreLastChannel()
        }
    }

    /** 加载指定 txt 直播源地址，成功后刷新频道列表并恢复上次频道 */
    fun loadLiveFromUrl(url: String) {
        launchIO {
            val ok = apiConfig.loadLiveFromUrl(url)
            if (ok) {
                _channelGroups.value = ArrayList(apiConfig.getChannelGroupList())
                restoreLastChannel()
                toast(context.getString(uiR.string.toast_live_switched))
            } else {
                toast(context.getString(uiR.string.toast_live_load_failed))
            }
        }
    }

    /** 恢复上次播放的频道，优先使用 Intent 传入的索引 */
    private fun restoreLastChannel() {
        val groups = _channelGroups.value
        if (groups.isEmpty()) return
        var groupIndex = -1
        var channelIndex = -1
        if (initialGroupIndex in groups.indices && initialChannelIndex >= 0) {
            groupIndex = initialGroupIndex
            val channels = groups[groupIndex].getLiveChannels()
            if (channels != null && initialChannelIndex < channels.size) {
                channelIndex = initialChannelIndex
            }
        }
        if (groupIndex < 0) {
            val lastName = appPreferences.getString(AppConstants.LIVE_CHANNEL, "")
            val lastGroupName = appPreferences.getString(AppConstants.LIVE_CHANNEL_GROUP, "")
            // 上次播放线路：按频道名匹配后恢复（超出源数量时回退 0）
            val lastSource = appPreferences.getInt(AppConstants.LIVE_CHANNEL_SOURCE, 0)
            if (lastName.isNotEmpty()) {
                outer@ for (g in groups.indices) {
                    val channels = groups[g].getLiveChannels() ?: continue
                    for (c in channels.indices) {
                        val item = channels[c]
                        if (item.channelName == lastName &&
                            (lastGroupName.isEmpty() || groups[g].groupName == lastGroupName)
                        ) {
                            groupIndex = g
                            channelIndex = c
                            if (lastSource > 0 && channels[c].sourceNum > 0) {
                                channels[c].sourceIndex = lastSource.coerceAtMost(channels[c].sourceNum - 1)
                            }
                            break@outer
                        }
                    }
                }
            }
        }
        if (groupIndex < 0) {
            groupIndex = groups.indexOfFirst { it.groupPassword.isNullOrEmpty() }.takeIf { it >= 0 } ?: 0
            channelIndex = 0
        }
        selectChannelGroup(groupIndex)
        if (channelIndex >= 0) {
            playChannel(groupIndex, channelIndex, false)
        }
    }

    /** 选择频道分组，仅更新组索引，不自动播放 */
    fun selectChannelGroup(groupIndex: Int) {
        if (groupIndex !in _channelGroups.value.indices) return
        _currentGroupIndex.value = groupIndex
        _currentGroup.value = _channelGroups.value[groupIndex]
    }

    /**
     * 播放指定频道
     * @param groupIndex 频道组索引
     * @param channelIndex 组内频道索引
     * @param changeSource 是否仅切换源（不更新组与频道索引）
     */
    fun playChannel(groupIndex: Int, channelIndex: Int, changeSource: Boolean) {
        val groups = _channelGroups.value
        if (groupIndex !in groups.indices) return
        if (changeSource) {
            // 换源：沿用当前播放频道对象（playNextSource 已递增其 sourceIndex），
            // 不能重新从列表取对象，否则 sourceIndex 被覆盖回旧值（右键只能切换一次源的根因）
            val playing = _currentChannel.value ?: return
            val channels = groups[groupIndex].getLiveChannels()
            if (channels.isNullOrEmpty() || channelIndex !in channels.indices) return
            // 把已切换的 sourceIndex 同步回列表对象，切台后线路记忆保留
            channels[channelIndex].sourceIndex = playing.sourceIndex
            playChannelInternal(groups[groupIndex], channels[channelIndex])
        } else {
            val channels = groups[groupIndex].getLiveChannels()
            if (channels.isNullOrEmpty() || channelIndex !in channels.indices) return
            _currentGroupIndex.value = groupIndex
            _currentGroup.value = groups[groupIndex]
            currentChannelIndex = channelIndex
            playingGroupIndex = groupIndex
            playChannelInternal(groups[groupIndex], channels[channelIndex])
        }
    }

    /** 实际执行播放：更新频道对象、URL 与 EPG */
    private fun playChannelInternal(group: LiveChannelGroup, channel: LiveChannelItem) {
        // 回看支持标记：源地址含 PLTV/8888（HLS 回看流）视为支持回看
        channel.include_back = channel.getUrl()?.contains("PLTV/8888") == true
        // 浅拷贝强制发射：换源复用同一列表对象时 StateFlow 引用相等不发射，UI（底部路线显示）不刷新
        _currentChannel.value = channel.copy()
        val url = channel.getUrl().orEmpty()
        // 强制发射：换源/换频道后 URL 可能与上次相同（如源循环回首个），StateFlow 去重不发射
        // 导致播放器不重载，先置空再置值确保每次都触发 collect 重载
        _currentUrl.value = ""
        _currentUrl.value = url
        _currentHeaders.value = buildPlayHeaders(url)
        saveLastChannel(group, channel)
        loadEpg(channel.channelName)
        _playState.value = 0
    }

    /** 切换到下一频道 */
    fun playNext() {
        val (g, c) = getNextChannel(1) ?: return
        playChannel(g, c, false)
    }

    /** 切换到上一频道 */
    fun playPrevious() {
        val (g, c) = getNextChannel(-1) ?: return
        playChannel(g, c, false)
    }

    /** 切换到下一个源 */
    fun playNextSource() {
        val channel = _currentChannel.value ?: return
        if (channel.sourceNum <= 1) return
        channel.nextSource()
        playChannel(_currentGroupIndex.value, currentChannelIndex, true)
    }

    /** 切换到上一个源 */
    fun playPreSource() {
        val channel = _currentChannel.value ?: return
        if (channel.sourceNum <= 1) return
        channel.preSource()
        playChannel(_currentGroupIndex.value, currentChannelIndex, true)
    }

    /** 直接设置源索引 */
    fun setSourceIndex(sourceIndex: Int) {
        val channel = _currentChannel.value ?: return
        if (sourceIndex !in 0 until channel.sourceNum) return
        channel.sourceIndex = sourceIndex
        playChannel(_currentGroupIndex.value, currentChannelIndex, true)
    }

    /**
     * 计算上/下一个频道位置
     * @return 组索引与频道索引，无有效频道返回 null
     */
    private fun getNextChannel(direction: Int): Pair<Int, Int>? {
        val groups = _channelGroups.value
        if (groups.isEmpty()) return null
        val reverse = appPreferences.getBoolean(AppConstants.LIVE_CHANNEL_REVERSE, false)
        val crossGroup = appPreferences.getBoolean(AppConstants.LIVE_CROSS_GROUP, false)
        val realDir = if (reverse) -direction else direction
        var groupIndex = _currentGroupIndex.value
        if (groupIndex !in groups.indices) return null
        var channels = getAccessibleChannels(groups[groupIndex])
        if (channels.isNullOrEmpty()) return null
        var channelIndex = currentChannelIndex
        if (realDir > 0) {
            channelIndex++
            if (channelIndex >= channels.size) {
                channelIndex = 0
                if (crossGroup) {
                    groupIndex = findNextNoPasswordGroup(groups, groupIndex, 1)
                    channels = getAccessibleChannels(groups[groupIndex])
                }
            }
        } else {
            channelIndex--
            if (channelIndex < 0) {
                if (crossGroup) {
                    groupIndex = findNextNoPasswordGroup(groups, groupIndex, -1)
                    channels = getAccessibleChannels(groups[groupIndex])
                }
                channelIndex = (channels?.size ?: 1) - 1
            }
        }
        if (channels.isNullOrEmpty() || channelIndex !in channels.indices) return null
        return Pair(groupIndex, channelIndex)
    }

    /** 跨组查找下一个无密码分组 */
    private fun findNextNoPasswordGroup(
        groups: List<LiveChannelGroup>,
        current: Int,
        direction: Int
    ): Int {
        var index = current
        var count = 0
        do {
            index += direction
            if (index >= groups.size) index = 0
            if (index < 0) index = groups.size - 1
            count++
            if (count > groups.size) return current
        } while (!groups[index].groupPassword.isNullOrEmpty() && index != current)
        return index
    }

    /** 获取可访问的频道列表（跳过未确认密码的加密分组） */
    private fun getAccessibleChannels(group: LiveChannelGroup?): List<LiveChannelItem>? {
        if (group == null) return null
        if (!group.groupPassword.isNullOrEmpty() &&
            !appPreferences.getBoolean(AppConstants.LIVE_SKIP_PASSWORD, false) &&
            group.groupIndex !in passwordConfirmedGroups
        ) {
            return null
        }
        return group.getLiveChannels()
    }

    /** 校验分组密码 */
    fun confirmGroupPassword(groupIndex: Int, password: String): Boolean {
        val groups = _channelGroups.value
        if (groupIndex !in groups.indices) return false
        val ok = groups[groupIndex].groupPassword == password
        if (ok) {
            passwordConfirmedGroups.add(groupIndex)
        }
        return ok
    }

    /** 判断分组是否需要密码 */
    fun isNeedPassword(groupIndex: Int): Boolean {
        val groups = _channelGroups.value
        if (groupIndex !in groups.indices) return false
        if (appPreferences.getBoolean(AppConstants.LIVE_SKIP_PASSWORD, false)) return false
        val group = groups[groupIndex]
        return !group.groupPassword.isNullOrEmpty() && group.groupIndex !in passwordConfirmedGroups
    }

    /**
     * 数字键输入频道号
     * @return 当前输入缓冲，2秒无输入由 Activity 触发确认
     */
    fun inputChannelDigit(digit: Int): String {
        val current = _channelNumberInput.value
        val merged = if (current.length >= 5) digit.toString() else current + digit
        _channelNumberInput.value = merged
        return merged
    }

    /** 确认频道号跳转，返回是否成功 */
    fun confirmChannelNumber(): Boolean {
        val numStr = _channelNumberInput.value
        _channelNumberInput.value = ""
        if (numStr.isEmpty()) return false
        val target = numStr.toIntOrNull() ?: return false
        if (target <= 0) return false
        val groups = _channelGroups.value
        var min = 1
        for (g in groups.indices) {
            val channels = getAccessibleChannels(groups[g]) ?: continue
            val max = min + channels.size - 1
            if (target in min..max) {
                val channelIndex = target - min
                selectChannelGroup(g)
                playChannel(g, channelIndex, false)
                return true
            }
            min = max + 1
        }
        toast(context.getString(uiR.string.toast_channel_not_found, target))
        return false
    }

    /** 清空频道号输入 */
    fun clearChannelNumber() {
        _channelNumberInput.value = ""
    }

    // ------------------------------------------------------------------
    // EPG 节目单
    // ------------------------------------------------------------------
    /**
     * 加载 EPG 节目单（全量源 .gz/.xml/.json）：
     * 从本地文件缓存读进内存（XmltvCache），命中且未过期直接从内存构建节目单；
     * 否则下载（gz 保存到本地文件）→ 解析进内存 → 写文件缓存。
     * 全量源各频道共享同一份下载结果，不随切台取消；日期过滤在本地缓存内完成。
     */
    fun loadEpg(channelName: String?, requestDate: Date = Date()) {
        if (channelName.isNullOrEmpty()) {
            _epgList.value = emptyList()
            return
        }
        val epgUrl = appPreferences.getString(AppConstants.EPG_URL, "")
        launchIO {
            val epgid = com.tvbox.kotlin.core.common.util.EpgUtil.getEpgid(channelName)?.ifEmpty { channelName } ?: channelName
            // 快速切台时旧协程可能晚完成，仅当仍是当前频道才更新 EPG（防并发覆盖）
            val isCurrent = _currentChannel.value?.channelName == channelName
            // clan:// 本地/局域网 EPG 统一经内置 HTTP /file/ 读取，转换后作为下载与缓存键（保证缓存键一致）
            val fetchUrl = if (epgUrl.startsWith("clan://")) apiConfig.clanToAddress(epgUrl) else epgUrl
            if (fetchUrl.isEmpty()) {
                if (isCurrent) _epgList.value = emptyList()
                return@launchIO
            }
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(requestDate)
            try {
                // requestEpg 内部：缓存命中直接返回（不清空）；未命中下载前才清空旧节目单
                val list = requestEpg(fetchUrl, epgid, channelName, dateStr, requestDate)
                if (_currentChannel.value?.channelName == channelName) {
                    _epgList.value = list
                }
            } catch (e: Throwable) {
                if (isCurrent) _epgList.value = emptyList()
            }
        }
    }

    /** 同步计算频道 logo 地址（频道自带 tvg-logo 优先，无则用订阅地址 logo 字段模板），返回 (primary, fallback) */
    fun computeChannelLogo(channelName: String?): Pair<String?, String?> {
        if (channelName.isNullOrEmpty()) return null to null
        val epgid = com.tvbox.kotlin.core.common.util.EpgUtil.getEpgid(channelName)?.ifEmpty { channelName } ?: channelName
        // 优先频道自带台标（m3u 源 #EXTINF 的 tvg-logo / JSON 源 logo 字段），无则从订阅地址 logo 字段地址读取
        val tvgLogo = _currentChannel.value?.tvgLogo?.takeIf { it.isNotEmpty() }
        val fallback = buildLogoUrl(channelName, epgid)
        return (tvgLogo ?: fallback) to (if (tvgLogo != null) fallback else null)
    }

    /** 请求并解析 EPG（仅全量源 .gz/.xml/.json），非全量 URL 返回空 */
    private suspend fun requestEpg(
        requestUrl: String,
        epgid: String,
        channelName: String,
        dateStr: String,
        date: Date
    ): List<Epginfo> {
        val lower = requestUrl.lowercase()
        if (!lower.endsWith(".gz") && !lower.endsWith(".xml") && !lower.endsWith(".json")) {
            return emptyList()
        }
        // 首次从本地文件读缓存进内存（仅执行一次）
        if (!xmltvCacheLoadedFromFile) {
            loadXmltvCacheFromFile()
            xmltvCacheLoadedFromFile = true
        }
        // EPG 源变更时清空内存缓存（epgUrl != sourceUrl → 清空重新下载）
        xmltvCache?.takeIf { requestUrl != it.sourceUrl }?.let { xmltvCache = null }
        // 内存缓存命中且有效：直接在内存中读取节目单（秒级返回）
        xmltvCache?.let { cache ->
            if (isXmltvCacheValid(cache)) {
                return getEpgFromXmltvCache(epgid, channelName, dateStr, date)
            }
        }
        // 未命中缓存：先清空旧频道节目单（显示默认空态），再下载
        if (_currentChannel.value?.channelName == channelName) {
            _epgList.value = emptyList()
        }
        // 缓存未命中/失效：下载（gz 保存到本地文件，互斥防并发）→ 解析进内存 → 写文件缓存
        val raw = downloadFullEpg(requestUrl)
        if (raw.isNotEmpty()) {
            parseAndCacheEpgData(raw, requestUrl)
            return getEpgFromXmltvCache(epgid, channelName, dateStr, date)
        }
        return emptyList()
    }

    /**
     * 下载全量 EPG 内容（"下载 gz 到本地"设计）：
     * - .gz：优先读本地 gz 文件（未过期直接解压复用，避免重复下载），否则网络下载并保存 gz 原始文件到本地；
     * - .xml/.json：同上将文本保存到本地。
     * 同一 URL 同时仅一个协程下载，其余等待其完成后复用。
     */
    private suspend fun downloadFullEpg(requestUrl: String): String {
        waitForXmltvDownload(requestUrl)
        if (!tryMarkXmltvDownloading(requestUrl)) return ""
        return try {
            val lower = requestUrl.lowercase()
            if (lower.endsWith(".gz")) {
                val bytes = readOrDownloadBytes(requestUrl, File(context.filesDir, EPG_GZ_FILE))
                GZIPInputStream(ByteArrayInputStream(bytes)).readBytes().toString(Charsets.UTF_8)
            } else {
                readOrDownloadText(requestUrl, File(context.filesDir, EPG_RAW_FILE))
            }
        } catch (_: Throwable) {
            ""
        } finally {
            downloadingXmltvUrls.remove(requestUrl)
        }
    }

    /** gz 全量源：本地文件未过期则读取，否则网络下载字节并写入本地 */
    private fun readOrDownloadBytes(url: String, localFile: File): ByteArray {
        val localValid = localFile.exists() &&
            System.currentTimeMillis() - localFile.lastModified() < XMLTV_CACHE_EXPIRE_MS
        if (localValid) return localFile.readBytes()
        return okHttpHelper.stream(url).use { it.readBytes() }.also { bytes ->
            try {
                localFile.writeBytes(bytes)
            } catch (_: Throwable) {
            }
        }
    }

    /** xml/json 全量源：本地文件未过期则读取，否则网络下载文本并写入本地 */
    private fun readOrDownloadText(url: String, localFile: File): String {
        val localValid = localFile.exists() &&
            System.currentTimeMillis() - localFile.lastModified() < XMLTV_CACHE_EXPIRE_MS
        if (localValid) return localFile.readText(Charsets.UTF_8)
        return okHttpHelper.get(url).also { text ->
            try {
                localFile.writeText(text)
            } catch (_: Throwable) {
            }
        }
    }

    /** 等待同 URL 进行中的全量 EPG 下载完成（最多等待 [EPG_DOWNLOAD_WAIT_MS]） */
    private suspend fun waitForXmltvDownload(url: String) {
        val deadline = System.currentTimeMillis() + EPG_DOWNLOAD_WAIT_MS
        while (System.currentTimeMillis() < deadline && isXmltvDownloading(url)) {
            delay(200)
        }
    }

    /** 指定 URL 是否正在被其他协程下载 */
    private fun isXmltvDownloading(url: String): Boolean = url in downloadingXmltvUrls

    /** 尝试登记为下载者；已被其他协程登记则返回 false（ConcurrentHashMap.add 原子返回是否新增） */
    private fun tryMarkXmltvDownloading(url: String): Boolean = downloadingXmltvUrls.add(url)

    /** 内存缓存是否有效：未超有效期；深夜时段含昨日节目则续期 */
    private fun isXmltvCacheValid(cache: XmltvCache): Boolean {
        if (System.currentTimeMillis() - cache.cacheTime < XMLTV_CACHE_EXPIRE_MS) return true
        if (isLateNight() && hasYesterdayProgramme(cache.programmes)) {
            xmltvCache = cache.copy(cacheTime = System.currentTimeMillis())
            return true
        }
        return false
    }

    /** 是否处于深夜时段（23:00-05:00）：此时间段 EPG 源可能尚未更新，沿用昨日缓存 */
    private fun isLateNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 5
    }

    /** 内存缓存中是否包含昨日节目（start 前 8 位日期为昨天），用于深夜缓存续期判断 */
    private fun hasYesterdayProgramme(programmes: Map<String, List<XmltvProgramme>>): Boolean {
        val yesterday = SimpleDateFormat("yyyyMMdd", Locale.US)
            .format(Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000))
        return programmes.values.any { list ->
            list.any { prog -> prog.start.length >= 8 && prog.start.substring(0, 8) == yesterday }
        }
    }

    /**
     * 解析全量 EPG 内容并写入内存缓存与本地文件缓存：
     * - JSON：节目元素需带 channel/ch/id 字段，按频道建立映射；
     * - XML（xmltv）：<channel> 建立 id→display-name 映射，<programme> 按 channel 属性聚合节目。
     */
    private fun parseAndCacheEpgData(paramString: String, sourceUrl: String) {
        try {
            val trimmedParam = paramString.trim()
            val channelIdMap = HashMap<String, String>()
            val programmesMap = HashMap<String, MutableList<XmltvProgramme>>()
            if (trimmedParam.startsWith("{") || trimmedParam.startsWith("[")) {
                val array: JSONArray? = if (trimmedParam.startsWith("[")) {
                    JSONArray(trimmedParam)
                } else {
                    val jsonObj = JSONObject(trimmedParam)
                    var arr: JSONArray? = null
                    val fields = arrayOf("epg_data", "data", "programs", "list", "items", "epg")
                    for (field in fields) {
                        arr = jsonObj.optJSONArray(field)
                        if (arr != null) break
                    }
                    if (arr == null) {
                        val keys = jsonObj.keys()
                        while (keys.hasNext()) {
                            val v = jsonObj.opt(keys.next())
                            if (v is JSONArray) {
                                arr = v
                                break
                            }
                        }
                    }
                    arr
                }
                if (array != null) {
                    for (b in 0 until array.length()) {
                        val obj = array.optJSONObject(b) ?: continue
                        val title = obj.optString("title", obj.optString("name", ""))
                        val start = obj.optString("start", obj.optString("startTime", ""))
                        val stop = obj.optString("end", obj.optString("stop", obj.optString("endTime", "")))
                        val channel = obj.optString("channel", obj.optString("ch", obj.optString("id", "")))
                        if (channel.isEmpty()) continue
                        if (!channelIdMap.containsKey(channel)) channelIdMap[channel] = channel
                        programmesMap.getOrPut(channel) { ArrayList() }
                            .add(XmltvProgramme(start, stop, title))
                    }
                }
            } else if (trimmedParam.contains("<tv") || trimmedParam.contains("<programme>")) {
                // DOM 解析 xmltv，display-name 取第一个，容错性优于流式解析
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(InputSource(StringReader(paramString)))
                val channels = doc.getElementsByTagName("channel")
                for (j in 0 until channels.length) {
                    val channel = channels.item(j) as Element
                    val id = channel.getAttribute("id")
                    val displayNames = channel.getElementsByTagName("display-name")
                    if (displayNames.length > 0) {
                        channelIdMap[id] = displayNames.item(0).textContent
                    }
                }
                val programmes = doc.getElementsByTagName("programme")
                for (i in 0 until programmes.length) {
                    val programme = programmes.item(i) as Element
                    val channelId = programme.getAttribute("channel")
                    val titles = programme.getElementsByTagName("title")
                    var title = ""
                    if (titles.length > 0) title = titles.item(0).textContent
                    programmesMap.getOrPut(channelId) { ArrayList() }
                        .add(XmltvProgramme(programme.getAttribute("start"), programme.getAttribute("stop"), title))
                }
            }
            if (channelIdMap.isNotEmpty() && programmesMap.isNotEmpty()) {
                // 整体原子替换缓存快照（programmes 协变自 Map<String, MutableList>，无需复制）
                xmltvCache = XmltvCache(
                    sourceUrl = sourceUrl,
                    cacheTime = System.currentTimeMillis(),
                    channels = channelIdMap,
                    programmes = programmesMap
                )
                saveXmltvCacheToFile()
            }
        } catch (_: Throwable) {
            // 解析失败不缓存，下次切台重新下载解析
        }
    }

    /** 将内存缓存写入本地文件（channels + programmes JSON） */
    private fun saveXmltvCacheToFile() {
        val cache = xmltvCache ?: return
        try {
            val cacheObj = JSONObject()
            cacheObj.put("sourceUrl", cache.sourceUrl)
            cacheObj.put("cacheTime", cache.cacheTime)
            val channelsObj = JSONObject()
            cache.channels.forEach { (id, name) -> channelsObj.put(id, name) }
            cacheObj.put("channels", channelsObj)
            val programmesObj = JSONObject()
            cache.programmes.forEach { (channelId, list) ->
                val progArray = JSONArray()
                for (prog in list) {
                    progArray.put(
                        JSONObject().apply {
                            put("start", prog.start)
                            put("stop", prog.stop)
                            put("title", prog.title)
                        }
                    )
                }
                programmesObj.put(channelId, progArray)
            }
            cacheObj.put("programmes", programmesObj)
            File(context.filesDir, XMLTV_CACHE_FILE).writeText(cacheObj.toString())
        } catch (_: Throwable) {
        }
    }

    /** 从本地文件读缓存进内存 */
    private fun loadXmltvCacheFromFile() {
        try {
            val cacheFile = File(context.filesDir, XMLTV_CACHE_FILE)
            if (!cacheFile.exists()) return
            val cacheObj = JSONObject(cacheFile.readText(Charsets.UTF_8))
            // channels/programmes 任一缺失视为缓存文件损坏，不构造缓存（触发重新下载）
            val channelsObj = cacheObj.optJSONObject("channels") ?: return
            val programmesObj = cacheObj.optJSONObject("programmes") ?: return
            val channels = HashMap<String, String>()
            val keys = channelsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                channels[key] = channelsObj.getString(key)
            }
            val programmes = HashMap<String, MutableList<XmltvProgramme>>()
            val progKeys = programmesObj.keys()
            while (progKeys.hasNext()) {
                val channelId = progKeys.next()
                val progArray = programmesObj.getJSONArray(channelId)
                val progList = ArrayList<XmltvProgramme>()
                for (i in 0 until progArray.length()) {
                    val progObj = progArray.getJSONObject(i)
                    progList.add(
                        XmltvProgramme(
                            start = progObj.optString("start", ""),
                            stop = progObj.optString("stop", ""),
                            title = progObj.optString("title", "")
                        )
                    )
                }
                programmes[channelId] = progList
            }
            xmltvCache = XmltvCache(
                sourceUrl = cacheObj.optString("sourceUrl", ""),
                cacheTime = cacheObj.optLong("cacheTime", 0),
                channels = channels,
                programmes = programmes
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * 从内存缓存构建指定频道、指定日期的节目列表：
     * display-name 去掉 [-_\s] 后与频道名或 epgid（epgTagName）匹配；
     * 节目按 start/stop 日期（yyyy-MM-dd）过滤，跨天节目用开始日期。
     */
    private fun getEpgFromXmltvCache(
        epgTagName: String,
        channelName: String,
        requestDateStr: String,
        date: Date
    ): List<Epginfo> {
        val cache = xmltvCache ?: return emptyList()
        val arrayList = ArrayList<Epginfo>()
        var matchedChannelId: String? = null
        for ((id, displayName) in cache.channels) {
            val normalizedDisplayName = displayName.replace("[-_\\s]".toRegex(), "")
            val normalizedChannelName = channelName.replace("[-_\\s]".toRegex(), "")
            val normalizedEpgTagName = epgTagName.replace("[-_\\s]".toRegex(), "")
            if (normalizedDisplayName == normalizedChannelName || normalizedDisplayName == normalizedEpgTagName) {
                matchedChannelId = id
                break
            }
        }
        if (matchedChannelId == null) return arrayList
        val programmes = cache.programmes[matchedChannelId] ?: return arrayList
        var index = 0
        for (progInfo in programmes) {
            val start = progInfo.start
            val stop = progInfo.stop
            val title = progInfo.title
            var programmeStartDateStr = ""
            var programmeEndDateStr = ""
            if (start.length >= 8) {
                programmeStartDateStr = start.substring(0, 4) + "-" + start.substring(4, 6) + "-" + start.substring(6, 8)
            }
            if (stop.length >= 8) {
                programmeEndDateStr = stop.substring(0, 4) + "-" + stop.substring(4, 6) + "-" + stop.substring(6, 8)
            }
            if (programmeStartDateStr != requestDateStr && programmeEndDateStr != requestDateStr) continue
            var startTime = ""
            var endTime = ""
            if (start.length >= 14) {
                startTime = start.substring(8, 10) + ":" + start.substring(10, 12)
            }
            if (stop.length >= 14) {
                endTime = stop.substring(8, 10) + ":" + stop.substring(10, 12)
            }
            var programmeDate = date
            if (programmeStartDateStr != requestDateStr && programmeEndDateStr == requestDateStr) {
                try {
                    programmeDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(programmeStartDateStr)
                } catch (_: Throwable) {
                    programmeDate = date
                }
            }
            arrayList.add(Epginfo(date, title, programmeDate, startTime, endTime, index))
            index++
        }
        return arrayList
    }

    /** 构建 logo 地址 */
    private fun buildLogoUrl(channelName: String, epgid: String): String? {
        val template = appPreferences.getString(AppConstants.LOGO_URL, "")
        if (template.isEmpty()) return null
        return template.replace("{name}", encode(channelName)).replace("{epgid}", encode(epgid))
    }

    /** logo 文件缓存路径（非法字符替换为 _） */
    fun getLogoCacheFile(epgid: String): java.io.File {
        val safeName = epgid.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")
        return java.io.File(java.io.File(context.filesDir, LOGO_CACHE_DIR), "$safeName.png")
    }

    /** 读取未过期的内存 logo 缓存（过期条目顺手移除，避免无界增长） */
    fun getCachedLogo(epgid: String): android.graphics.drawable.Drawable? {
        val entry = channelLogoCache[epgid] ?: return null
        if (System.currentTimeMillis() - entry.cachedAt < LOGO_CACHE_EXPIRE_MS) return entry.drawable
        channelLogoCache.remove(epgid)
        return null
    }

    /** 保存 logo 到内存与本地文件缓存 */
    fun cacheLogo(epgid: String, drawable: android.graphics.drawable.Drawable) {
        channelLogoCache[epgid] = LogoCacheEntry(drawable, System.currentTimeMillis())
        try {
            val bitmap = if (drawable is android.graphics.drawable.BitmapDrawable) {
                drawable.bitmap
            } else {
                val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 100
                val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 100
                val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            }
            val file = getLogoCacheFile(epgid)
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Throwable) {
        }
    }

    /** epgid 是否已标记加载失败 */
    fun isLogoFailed(epgid: String): Boolean = epgid in logoFailedCache

    /** 标记 epgid 加载失败 */
    fun markLogoFailed(epgid: String) {
        logoFailedCache.add(epgid)
    }

    /**
     * 预加载所有频道 logo：
     * 跳过已失败/已有有效文件缓存的频道，下载成功后写入内存+文件缓存，失败标记失败。
     * 有界并发下载，切台时可直接命中缓存秒显。
     */
    fun preloadAllChannelLogos() {
        if (logoPreloading) return
        logoPreloading = true
        launchIO {
            try {
                val logoTemplate = appPreferences.getString(AppConstants.LOGO_URL, "")
                val groups = _channelGroups.value
                // 先收集需要预加载的频道（epgid, primary, fallback），跳过失败标记与有效文件缓存
                val tasks = ArrayList<Triple<String, String, String?>>()
                outer@ for (group in groups) {
                    val channels = group.getLiveChannels() ?: continue
                    for (item in channels) {
                        if (!coroutineContext.isActive) break@outer
                        val channelName = item.channelName ?: continue
                        val epgid = com.tvbox.kotlin.core.common.util.EpgUtil.getEpgid(channelName)?.ifEmpty { channelName } ?: channelName
                        if (isLogoFailed(epgid)) continue
                        val cacheFile = getLogoCacheFile(epgid)
                        if (cacheFile.exists() &&
                            System.currentTimeMillis() - cacheFile.lastModified() < LOGO_CACHE_EXPIRE_MS
                        ) {
                            continue
                        }
                        val fallbackLogo = if (logoTemplate.isNotEmpty()) {
                            logoTemplate.replace("{name}", channelName).replace("{epgid}", epgid)
                        } else null
                        // 优先频道自带台标（m3u 源 #EXTINF 的 tvg-logo / JSON 源 logo 字段），无则用订阅地址 logo 字段模板
                        val tvgLogo = item.tvgLogo?.takeIf { it.isNotEmpty() }
                        val primaryLogo = tvgLogo ?: fallbackLogo
                        if (primaryLogo.isNullOrEmpty()) continue
                        tasks.add(Triple(epgid, primaryLogo, fallbackLogo))
                    }
                }
                // 有界并发下载：完成即写内存+文件缓存，失败标记失败
                val semaphore = Semaphore(8)
                coroutineScope {
                    tasks.forEach { (epgid, primary, fallback) ->
                        launch(Dispatchers.IO) {
                            semaphore.withPermit {
                                if (!coroutineContext.isActive) return@withPermit
                                val drawable = com.tvbox.kotlin.core.ui.CoilUtils.loadDrawable(context, primary)
                                    ?: fallback?.let { com.tvbox.kotlin.core.ui.CoilUtils.loadDrawable(context, it) }
                                if (drawable != null) {
                                    cacheLogo(epgid, drawable)
                                } else {
                                    markLogoFailed(epgid)
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                logoPreloading = false
            }
        }
    }

    /** URL 编码 */
    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /** 保存上次频道记忆（含线路，下次进入按频道名/组名/线路恢复） */
    private fun saveLastChannel(group: LiveChannelGroup, channel: LiveChannelItem) {
        launchIO {
            appPreferences.put(AppConstants.LIVE_CHANNEL, channel.channelName ?: "")
            appPreferences.put(AppConstants.LIVE_CHANNEL_GROUP, group.groupName ?: "")
            appPreferences.put(AppConstants.LIVE_CHANNEL_SOURCE, channel.sourceIndex)
        }
    }

    /**
     * 构造直播播放请求头
     * 解析 [ApiConfig.livePlayHeaders]，匹配 URL 命中 flag 时使用对应 header，否则使用默认 UA。
     */
    fun buildPlayHeaders(url: String): Map<String, String> {
        val headers = HashMap<String, String>()
        val playHeaders: JsonArray? = apiConfig.livePlayHeaders
        try {
            var matched = false
            if (playHeaders != null && playHeaders.size() > 0) {
                for (i in 0 until playHeaders.size()) {
                    val headerObj = playHeaders[i].asJsonObject
                    val flags = headerObj.getAsJsonArray("flag")
                    val headerData = headerObj.getAsJsonObject("header")
                    for (j in 0 until flags.size()) {
                        val flag = flags[j].asString
                        if (url.contains(flag)) {
                            matched = true
                            headerData?.keySet()?.forEach { key ->
                                headerData.get(key)?.let { headers[key] = it.asString }
                            }
                            break
                        }
                    }
                    if (matched) break
                }
            }
            if (!matched) headers["User-Agent"] = "Lavf/59.27.100"
        } catch (_: Throwable) {
            headers["User-Agent"] = "Lavf/59.27.100"
        }
        return headers
    }

    /** 更新播放状态 */
    fun updatePlayState(state: Int) {
        _playState.value = state
    }

    /** 当前频道是否收藏 */
    fun isCurrentChannelCollected(): Boolean {
        val channel = _currentChannel.value ?: return false
        val name = channel.channelName ?: return false
        val favorites = getFavoriteSet()
        return name in favorites
    }

    /** 切换当前频道收藏状态 */
    fun toggleCollectCurrentChannel() {
        val channel = _currentChannel.value ?: return
        val name = channel.channelName ?: return
        launchIO {
            val favorites = getFavoriteSet().toMutableSet()
            if (name in favorites) {
                favorites.remove(name)
                toast(context.getString(uiR.string.det_fav_del))
            } else {
                favorites.add(name)
                toast(context.getString(uiR.string.toast_fav_added))
            }
            saveFavoriteSet(favorites)
        }
    }

    /** 读取收藏频道集合 */
    private fun getFavoriteSet(): Set<String> {
        val json = appPreferences.getString(KEY_LIVE_FAVORITES, "")
        if (json.isEmpty()) return emptySet()
        return try {
            val arr = JSONArray(json)
            val set = LinkedHashSet<String>()
            for (i in 0 until arr.length()) set.add(arr.getString(i))
            set
        } catch (_: Throwable) {
            emptySet()
        }
    }

    /** 持久化收藏频道集合 */
    private suspend fun saveFavoriteSet(set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        appPreferences.put(KEY_LIVE_FAVORITES, arr.toString())
    }

    /** 显示提示并消费 */
    fun consumeToast() {
        _toastMsg.value = null
    }

    companion object {
        private const val KEY_LIVE_FAVORITES = "live_favorites"

        /** 全量 xmltv 缓存有效期（毫秒），1 小时 */
        private const val XMLTV_CACHE_EXPIRE_MS = 60 * 60 * 1000L

        /** 全量 EPG 下载等待超时（毫秒）：其他协程下载中时最多等待其完成，超时后本次放弃 */
        private const val EPG_DOWNLOAD_WAIT_MS = 20_000L

        /** 跨启动全量 xmltv 文件缓存（xmltv_cache.json） */
        private const val XMLTV_CACHE_FILE = "xmltv_cache.json"

        /** 全量 gz 原始文件缓存：下载后保存到本地，加载时优先从本地读 */
        private const val EPG_GZ_FILE = "epg_data.xml.gz"

        /** 全量 xml/json 原始文件缓存 */
        private const val EPG_RAW_FILE = "epg_data.xml"
    }
}
