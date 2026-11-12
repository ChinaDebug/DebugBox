package com.tvbox.kotlin.feature.play.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.bean.ParseBean
import com.tvbox.kotlin.core.common.bean.VodInfo
import com.tvbox.kotlin.core.common.event.RefreshEvent
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.DefaultConfig
import com.tvbox.kotlin.core.common.util.MD5
import com.tvbox.kotlin.core.data.VideoUpdateChecker
import com.tvbox.kotlin.core.data.repo.RoomDataManger
import com.tvbox.kotlin.core.player.MyVideoView
import com.tvbox.kotlin.core.player.PlayerConstants
import com.tvbox.kotlin.core.player.PlayerHelper
import com.tvbox.kotlin.core.server.ControlManager
import com.tvbox.kotlin.core.server.cast.CastManager
import com.tvbox.kotlin.core.server.cast.discovery.DeviceDiscovery
import com.tvbox.kotlin.core.server.cast.model.CastData
import com.tvbox.kotlin.core.server.cast.model.CastDevice
import com.tvbox.kotlin.core.ui.BaseViewModel
import com.tvbox.kotlin.core.ui.PlayResolver
import com.tvbox.kotlin.core.ui.R as uiR
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject

/** 播放状态机 */
enum class PlayState {
    IDLE, PREPARING, PREPARED, PLAYING, PAUSED, BUFFERING, BUFFERED, ERROR, COMPLETED
}

/**
 * 播放页 ViewModel。
 *
 * 职责：
 * - 维护当前播放 URL、进度、状态、剧集、解码器、缩放、倍速等状态；
 * - 通过 [RoomDataManger] 读写观看记录与播放进度；
 * - 通过 [AppPreferences] 持久化播放器配置；
 * - 通过 [PlayerHelper] 获取播放器配置；
 * - 调度上一集/下一集、切换解码器/缩放/倍速。
 *
 * 解析器列表由外部通过 [setParseBeans] 注入（实际站点在 :app 模块通过 ApiConfig 提供），
 * 本模块不直接依赖 core:spider，保持单向依赖。
 */
@HiltViewModel
class PlayViewModel @Inject constructor(
    private val application: Application,
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
    private val roomDataManger: RoomDataManger,
    private val videoUpdateChecker: VideoUpdateChecker,
    private val playerHelper: PlayerHelper,
    private val playResolver: PlayResolver,
    private val controlManager: ControlManager,
    savedStateHandle: SavedStateHandle
) : BaseViewModel() {

    companion object {
        /** 进度缓存键前缀（与 RoomDataManger 删除历史时清理进度共用） */
        private const val PROGRESS_KEY_PREFIX = AppConstants.VOD_PROGRESS_KEY_PREFIX
        /** 默认起始播放位置（毫秒） */
        private const val DEFAULT_START_POSITION = 0L
        /** 倍速档位 */
        // 倍速统一档位（0.5 ~ 3.0），上下键导航与倍速菜单共用
        val SPEED_OPTIONS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

        /** 解析阶段：无 */
        const val RESOLVE_STAGE_NONE = 0
        /** 解析阶段：常规解析中 */
        const val RESOLVE_STAGE_PARSE = 1
        /** 解析阶段：嗅探中 */
        const val RESOLVE_STAGE_SNIFF = 2
        /** 解析阶段：超级解析中 */
        const val RESOLVE_STAGE_SUPER = 3

        /** 主解析失败后等待备用地址的时限（毫秒） */
        private const val BACKUP_FIRST_TIMEOUT_MS = 20_000L

        /** 临近结束不再保存进度的剩余时长阈值（毫秒） */
        private const val PROGRESS_END_MARGIN_MS = 120_000L

        /** 剩余时长按总时长比例判定时的除数（1/20 即 5%） */
        private const val PROGRESS_END_RATIO_DIVISOR = 20L
    }

    /** 当前播放 URL */
    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    /** 爬虫 playerContent 返回的 LRC 歌词内容（源未提供时为空），播放层据此显示滚动歌词 */
    private val _currentLrc = MutableStateFlow("")
    val currentLrc: StateFlow<String> = _currentLrc.asStateFlow()

    /** 当前播放进度（毫秒） */
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    /** 视频总时长（毫秒），由宿主在播放进度回调中更新（saveProgress 依赖其判断是否可保存） */
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    /** 最近一次保存进度的播放地址，供历史记录持久化进度键（删除历史时清理进度） */
    @Volatile
    private var lastPlayUrl: String = ""

    /** 更新视频总时长（毫秒） */
    fun updateDuration(milliseconds: Long) {
        if (milliseconds > 0) _duration.value = milliseconds
    }

    /** 当前播放状态 */
    private val _playState = MutableStateFlow(PlayState.IDLE)
    val playState: StateFlow<PlayState> = _playState.asStateFlow()

    /** 当前影片信息 */
    private val _vodInfo = MutableStateFlow<VodInfo?>(null)
    val vodInfo: StateFlow<VodInfo?> = _vodInfo.asStateFlow()

    /** 当前剧集信息 */
    private val _currentEpisode = MutableStateFlow<VodInfo.VodSeries?>(null)
    val currentEpisode: StateFlow<VodInfo.VodSeries?> = _currentEpisode.asStateFlow()

    /** 当前解码器类型，取值参考 [PlayerConstants.PLAYER_TYPE_*] */
    private val _playerType = MutableStateFlow(PlayerConstants.PLAYER_TYPE_EXO)
    val playerType: StateFlow<Int> = _playerType.asStateFlow()

    /** 当前缩放类型，取值参考 [PlayerConstants.SCREEN_SCALE_*] */
    private val _scaleType = MutableStateFlow(0)
    val scaleType: StateFlow<Int> = _scaleType.asStateFlow()

    /** 当前播放倍速 */
    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** 解析器列表，由外部注入 */
    private val _parseBeans = MutableStateFlow<List<ParseBean>>(emptyList())
    val parseBeans: StateFlow<List<ParseBean>> = _parseBeans.asStateFlow()

    /** 当前选中的解析器 */
    private val _currentParse = MutableStateFlow<ParseBean?>(null)
    val currentParse: StateFlow<ParseBean?> = _currentParse.asStateFlow()

    /** 播放标题 */
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    /** 当前解析阶段，用于加载提示（[RESOLVE_STAGE_*]） */
    private val _resolveStage = MutableStateFlow(RESOLVE_STAGE_NONE)
    val resolveStage: StateFlow<Int> = _resolveStage.asStateFlow()

    /** 投屏设备列表 */
    private val _castDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    val castDevices: StateFlow<List<CastDevice>> = _castDevices.asStateFlow()

    /** 是否正在搜索投屏设备 */
    private val _castScanning = MutableStateFlow(false)
    val castScanning: StateFlow<Boolean> = _castScanning.asStateFlow()

    /** 播放进度记忆 key，由 playerContent 返回，用于进度缓存隔离 */
    private var progressKey: String? = null
    /** 起始播放位置，用于首次播放恢复 */
    private var startPosition: Long = DEFAULT_START_POSITION
    /** 跳过本次播放的进度恢复：手动"下一集"/自动连播时置位，避免源返回固定 progressKey 误恢复旧集进度 */
    private var skipProgressRestore = false
    /** 已实际开始播放的线路（startPlayUrl 装配播放器时标记），保存进度键以此为准 */
    @Volatile
    private var playbackStartedFlag: String? = null
    /** 已实际开始播放的集索引，与 [playbackStartedFlag] 配套 */
    @Volatile
    private var playbackStartedIndex = -1
    /** 来源站点 key */
    private var sourceKey: String? = null
    /** 备用播放地址队列：解析时竞速收集的其他解析站结果，首个播放失败后依次尝试 */
    private val backupPlayUrls = java.util.concurrent.ConcurrentLinkedQueue<com.tvbox.kotlin.core.ui.PlayResolver.ResolveResult>()
    /** 字幕路径 */
    private var playSubtitle: String = ""
    /** 弹幕 URL 或 base64 数据，由解析结果填充 */
    private var danmuSource: String = ""
    /** 已下载的弹幕内容缓存，换源/换集时清空 */
    private var danmuContentCache: String? = null
    /** 播放器配置 JSON，由详情页下发 */
    private var playerCfg: JSONObject? = null
    /** 当前会话内用户手动切换的播放器类型，-1 表示未手动切换 */
    private var userSelectedPlayerType = -1

    init {
        // 从偏好恢复播放器配置（历史 0/1 档位归一为 Exo）
        _playerType.value = PlayerConstants.normalizePlayType(
            appPreferences.get(PlayerConstants.KEY_PLAY_TYPE, PlayerConstants.PLAYER_TYPE_EXO)
        )
        _scaleType.value = appPreferences.get(PlayerConstants.KEY_PLAY_SCALE, 0)
    }

    // ------------------------------------------------------------------
    // 播放信息加载
    // ------------------------------------------------------------------

    /**
     * 从 Intent extras 加载播放信息。
     * @param url 播放地址，可为空（依赖后续解析）
     * @param title 标题
     * @param position 起始位置（毫秒）
     * @param vodId 影片 id
     * @param sourceKey 来源站点 key
     * @param vodInfo 影片信息，包含剧集列表
     * @param playerCfgJson 播放器配置 JSON
     */
    fun loadPlayInfo(
        url: String,
        title: String?,
        position: Long,
        vodId: String?,
        sourceKey: String?,
        vodInfo: VodInfo?,
        playerCfgJson: String?
    ) {
        _title.value = title ?: ""
        startPosition = if (position > 0) position else DEFAULT_START_POSITION
        this.sourceKey = sourceKey
        _vodInfo.value = vodInfo
        if (!playerCfgJson.isNullOrEmpty()) {
            try {
                playerCfg = JSONObject(playerCfgJson)
                val sc = playerCfg!!.optInt("sc", -1)
                if (sc >= 0) _scaleType.value = sc
            } catch (_: Throwable) {
                playerCfg = null
            }
        } else {
            playerCfg = null
        }
        _playerType.value = resolvePlayerType()
        if (url.isNotEmpty()) {
            // 直接给定播放地址：不写 _currentUrl，播放由 UI 层显式调用
            // startPlayUrl 驱动，避免 currentUrl 收集器与显式播放重复
        } else if (vodInfo != null) {
            // 未给定地址时按当前剧集加载
            refreshCurrentEpisode(vodInfo)
        }
    }

    /**
     * 解析默认内核：用户手动指定优先，否则按点播首选内核映射
     * （1/2→Exo、3→MX、4→Reex、5→Kodi，内置内核恒为 Exo，MX/Reex/Kodi 为外置）。
     */
    private fun resolvePlayerType(): Int {
        if (userSelectedPlayerType >= 0) return userSelectedPlayerType
        val preferred = appPreferences.get(AppConstants.VOD_PLAYER_PREFERRED, 0)
        return when (preferred) {
            1, 2 -> PlayerConstants.PLAYER_TYPE_EXO
            3 -> PlayerConstants.PLAYER_TYPE_MX
            4 -> PlayerConstants.PLAYER_TYPE_REEX
            5 -> PlayerConstants.PLAYER_TYPE_KODI
            else -> PlayerConstants.PLAYER_TYPE_EXO
        }
    }

    /** 刷新当前剧集信息，返回剧集 URL */
    private fun refreshCurrentEpisode(vodInfo: VodInfo): String? {
        val flag = vodInfo.playFlag ?: return null
        val seriesList = vodInfo.seriesMap?.get(flag) ?: return null
        val index = vodInfo.playEpisodeIndex
        if (index < 0 || index >= seriesList.size) return null
        val series = seriesList[index]
        _currentEpisode.value = series
        return series.url
    }

    // ------------------------------------------------------------------
    // 解析调度
    // ------------------------------------------------------------------

    /**
     * 加载播放内容并解析。
     *
     * 流程：
     * 1. 调用 [PlayResolver.loadPlayerContent] 获取爬虫返回的播放内容 JSON；
     * 2. 解析 JSON 提取 url / parse / header / danmu；
     * 3. 若 parse=1 且存在解析器，调用 [PlayResolver.resolveVideo] 解析实际地址；
     * 4. 返回最终的播放地址和请求头。
     *
     * @param episodeUrl 剧集 URL 或 ID
     * @return 解析结果，url 为空表示失败
     */
    suspend fun loadAndResolve(episodeUrl: String): ResolvedPlay {
        val key = sourceKey ?: return ResolvedPlay()
        // 新集解析开始：清空上一集的歌词，避免新集无 lrc 时残留旧歌歌词
        _currentLrc.value = ""
        val info = _vodInfo.value
        val flag = info?.playFlag ?: ""
        collectGeneration++
        backupCollectJob?.cancel()
        backupCollectJob = null
        backupPlayUrls.clear()
        backupSeenUrls.clear()
        // 空地址提示"无视频源地址"
        if (episodeUrl.isEmpty()) return ResolvedPlay(error = context.getString(uiR.string.play_err_no_video_addr))

        // ===== 特殊播放协议链（tvbox-drive/荐片/magnet 下载类/空地址/type 校验）=====
        val protocol = runCatchingResume {
            playResolver.resolvePlayProtocol(episodeUrl, key)
        }.getOrNull()
        if (protocol != null) {
            if (protocol.error != null) return ResolvedPlay(error = protocol.error)
            if (protocol.url.isNotEmpty()) {
                // tvbox-drive 直链：进度键用去前缀地址（progressKey 规则）
                setProgressKey(protocol.url)
                return ResolvedPlay(
                    protocol.url,
                    mergeHeaders(protocol.headers, playerCfgHeaders()),
                    needParse = false
                )
            }
        }

        val vipFlags: List<String> = emptyList()

        val contentJson = runCatchingResume {
            playResolver.loadPlayerContent(key, flag, episodeUrl, vipFlags)
        }.getOrDefault("")
        if (contentJson.isEmpty()) {
            // 爬虫/直连均未返回播放内容时，若剧集地址本身为可直接播放的视频格式则免解析直播，
            // 避免"不需要解析的片源"被误报为解析失败；否则提示"获取播放信息错误"
            if (DefaultConfig.isVideoFormat(episodeUrl)) {
                return ResolvedPlay(episodeUrl, null, needParse = false)
            }
            return ResolvedPlay(error = context.getString(uiR.string.play_err_fetch_info))
        }

        val content = try {
            JSONObject(contentJson)
        } catch (_: Throwable) {
            return ResolvedPlay()
        }

        // 爬虫 playerContent 直接返回的 LRC 歌词（OK影视系爬虫 lrc 字段），播放层据此显示滚动歌词
        val lrc = content.optString("lrc").orEmpty()
        if (lrc.isNotEmpty()) setLrc(lrc)

        // 爬虫返回 url 为空时兼容读取 playUrl ，避免把剧集 id 当播放地址
        var playUrl = content.optString("url").ifEmpty { content.optString("playUrl") }.ifEmpty { episodeUrl }
        if (playUrl.startsWith("[")) {
            playUrl = firstUrlByArray(playUrl)
        }
        // 归一化特殊前缀：proxy:// 本地代理转 http 直链；video:// 剥前缀
        val proxySource = playUrl.startsWith("proxy://")
        val videoSource = playUrl.startsWith("video://")
        if (proxySource || videoSource) {
            playUrl = runCatchingResume { playResolver.normalizePlayUrl(playUrl, key) }.getOrDefault(playUrl)
        }
        val headerStr = content.optString("header")
        val headers = parseHeaderStr(headerStr)
        // 爬虫返回的地址若为下载类特殊协议（磁力/迅雷解密链/荐片等），交由协议链处理
        // （此类地址直接进入协议链，不走普通解析器）
        val contentProtocol = runCatchingResume {
            playResolver.resolvePlayProtocol(playUrl, key)
        }.getOrNull()
        if (contentProtocol != null) {
            if (contentProtocol.error != null) return ResolvedPlay(error = contentProtocol.error)
            if (contentProtocol.url.isNotEmpty()) {
                return ResolvedPlay(
                    contentProtocol.url,
                    mergeHeaders(headers, contentProtocol.headers),
                    needParse = false
                )
            }
        }
        // parse 标记：content 显式给出则信任（type 0/1 源按源类型判定写入）；
        // 缺失时按地址是否为可直接播放的视频格式兜底判定，避免"不需要解析的片源"误走解析器而解析失败
        val parse = if (content.has("parse")) {
            content.optInt("parse", 1)
        } else {
            when {
                DefaultConfig.isVideoFormat(playUrl) -> 0
                proxySource -> 0
                videoSource -> 1
                else -> 1
            }
        }
        val jx = content.optInt("jx", 0) == 1
        val danmu = content.optString("danmaku").ifEmpty { content.optString("danmu") }.orEmpty()

        val contentKey = content.optString("key").orEmpty()
        if (contentKey.isNotEmpty()) {
            setProgressKey(contentKey)
        } else {
            // 源未返回进度键时，用影片稳定标识构造（源key+影片id+线路+集id），
            // 不依赖动态播放地址，避免换源/防盗链地址变化导致进度丢失
            setProgressKey(buildStableProgressKey(info, flag))
        }

        // 每次解析都同步弹幕来源供 Activity 加载：源为空同样写入（清空），
        // 避免换集/换源后沿用上一集的弹幕源与缓存内容
        setDanmuSource(danmu)

        if (parse == 1 || jx) {
            // 播放地址本身已是视频格式（m3u8/mp4 等）时直接播放，跳过嗅探与解析站：
            // NBY 等源的 time 授权码窗口极短（秒级），WebView 嗅探初始化与解析站耗时
            // 都会导致授权过期（服务器返回"链接失效"）；直接播放（ExoPlayer 秒级请求）
            // 对 parse=1 的播放地址嗅探命中后立即播放。
            // headers 无 UA 时补移动端 UA：NBY 等源线路 ua 配置为 iPhone Safari，服务器校验移动端 UA。
            // 注意 isVideoFormat 会排除含 "rl=" 的地址（如 getM3u8?url=NBY-xx.m3u8），
            // 故额外识别 query 带 m3u8/mpd 扩展名的 HLS/DASH 地址。
            val lowerPlay = playUrl.lowercase()
            val playableNow = playUrl.startsWith("http") && (
                DefaultConfig.isVideoFormat(playUrl) ||
                    lowerPlay.contains("m3u8") || lowerPlay.contains(".mpd")
                )
            if (playableNow) {
                val playHeaders = if (headers == null || headers.keys.none {
                        it.equals("User-Agent", true) || it.equals("user-agent", true)
                    }) {
                    (headers ?: emptyMap()) + ("User-Agent" to DefaultConfig.MOBILE_UA)
                } else {
                    headers
                }
                return ResolvedPlay(playUrl, playHeaders, needParse = false, lrc = lrc)
            }
            var parseBean = _currentParse.value
            var useDefaultParse = parseBean != null
            if (parseBean == null) {
                val cfgPlayUrl = content.optString("playUrl").orEmpty()
                useDefaultParse = jx ||
                    (cfgPlayUrl.isEmpty() && runCatchingResume { playResolver.containsFlag(flag) }.getOrDefault(false))
                parseBean = if (useDefaultParse) {
                    val list = _parseBeans.value
                    list.firstOrNull { it.isDefault() } ?: list.firstOrNull()
                        ?: runCatchingResume { playResolver.getDefaultParse() }.getOrNull()
                } else {
                    buildParseBean(cfgPlayUrl)
                }
            }
            // 记录解析失败原因，供块外回退分支展示具体错误
            var resolveError: String? = null
            if (parseBean != null) {
                // 按解析器类型更新加载阶段提示（常规/嗅探/超级解析）
                _resolveStage.value = when (parseBean.type) {
                    ParseBean.TYPE_SNIFF -> RESOLVE_STAGE_SNIFF
                    4 -> RESOLVE_STAGE_SUPER
                    else -> RESOLVE_STAGE_PARSE
                }
                val resolvedPair = runCatchingResume {
                    playResolver.resolveVideoWithBackups(parseBean, playUrl, flag)
                }.getOrNull()
                val resolved = resolvedPair?.first
                resolveError = resolved?.error
                _resolveStage.value = RESOLVE_STAGE_NONE
                if (resolved != null && resolved.url.isNotEmpty()) {
                    // 主地址加入去重集合：channel 中与主地址相同的竞速结果不再进备用池
                    // （同一站的首个地址常与主地址相同，播放失败时避免重复切换同地址）
                    backupSeenUrls.add(normalizeVideoUrl(resolved.url))
                    // 后台收集备用地址（其他解析站竞速结果），首个播放失败时依次尝试
                    startCollectBackups(resolvedPair?.second)
                    val mergedHeaders = mergeHeaders(headers, resolved.headers)
                    // 仅超级解析（type 4 聚合竞速）提示具体命中站名：竞速结果已带站名，
                    // 其余解析路径 sourceName 为空不提示（手动选择/普通默认解析器用户已知来源）
                    return ResolvedPlay(
                        resolved.url,
                        mergedHeaders,
                        needParse = useDefaultParse,
                        parseSource = resolved.sourceName,
                        lrc = lrc
                    )
                }
                // 主解析失败：立即尝试备用通道中的地址（其他解析站竞速结果），
                // 等全部地址失败后才提示失败
                val backupChannel = resolvedPair?.second
                if (backupChannel != null) {
                    val backup = withTimeoutOrNull(BACKUP_FIRST_TIMEOUT_MS) {
                        backupChannel.receiveCatching().getOrNull()
                    }
                    if (backup != null && backup.url.isNotEmpty()) {
                        // 当前备用地址同样加入去重集合，避免重复切换
                        backupSeenUrls.add(normalizeVideoUrl(backup.url))
                        // 剩余备用地址继续进池，播放失败时由播放层依次尝试
                        startCollectBackups(backupChannel)
                        return ResolvedPlay(
                            backup.url,
                            backup.headers,
                            needParse = useDefaultParse,
                            parseSource = backup.sourceName,
                            lrc = lrc
                        )
                    }
                }
            }
            if (DefaultConfig.isVideoFormat(playUrl) || playUrl.startsWith("http")) {
                return ResolvedPlay(playUrl, headers, needParse = false, lrc = lrc)
            }
            return ResolvedPlay(error = resolveError ?: context.getString(uiR.string.play_err_parse_failed))
        }

        return ResolvedPlay(playUrl, headers, needParse = false, lrc = lrc)
    }

    /** 取下一个备用播放地址（解析时竞速收集的其他解析站结果），无则返回 null。
     * 由播放层在首个地址播放失败时调用，备用地址全部失败后才提示失败。 */
    fun takeNextBackupUrl(): com.tvbox.kotlin.core.ui.PlayResolver.ResolveResult? =
        backupPlayUrls.poll()

    /** 备用地址池是否还有待尝试的地址（播放超时快速切换备用时判断用） */
    fun hasBackupUrls(): Boolean = !backupPlayUrls.isEmpty()

    /** 清空备用播放地址（解析收集协程退出时兜底调用） */
    fun clearBackupUrls() {
        backupPlayUrls.clear()
    }

    /** 备用解析是否仍在进行（备用通道尚未关闭），播放失败且备用池为空时据此延迟重试 */
    private val backupCollecting = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 备用解析仍在进行（其他解析站尚未全部完成） */
    fun hasPendingBackup(): Boolean = backupCollecting.get()

    /** 当前备用收集协程：新一轮解析开始时取消（防止旧集地址混入新集备用池） */
    private var backupCollectJob: Job? = null

    /** 解析代际计数：新一轮解析开始自增，旧收集协程检测到代际变化立即退出（cancel 是异步的） */
    private var collectGeneration = 0

    /** 已进入备用池的地址（归一化去重）：去掉 query 签名后比较，同一地址不同签名只进池一次 */
    private val backupSeenUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 处理源返回的多清晰度 JSON 数组地址：
     * - 兼容 [label, url] 与 [url, label] 两种顺序；
     * - 兼容 http 与协议相对（//）地址；
     * - 按清晰度标签选最高的地址；标签无法识别时取首个含地址的项。
     */
    private fun firstUrlByArray(url: String): String {
        if (!url.startsWith("[")) return url
        return try {
            val array = org.json.JSONArray(url)
            var bestUrl = ""
            var bestScore = Int.MIN_VALUE
            var firstUrl = ""
            fun record(label: String, itemUrl: String) {
                if (itemUrl.isEmpty()) return
                if (firstUrl.isEmpty()) firstUrl = itemUrl
                var score = qualityScore(label)
                // 标签无清晰度信息时，尝试从地址本身提取（部分源仅在 URL 中带清晰度标识）
                if (score < 0) score = qualityScoreFromUrl(itemUrl)
                if (score > bestScore) {
                    bestScore = score
                    bestUrl = itemUrl
                }
            }
            var i = 0
            while (i < array.length()) {
                val a = array.optString(i).trim()
                val b = if (i + 1 < array.length()) array.optString(i + 1).trim() else ""
                val aIsUrl = isUrlLike(a)
                val bIsUrl = isUrlLike(b)
                when {
                    // [label, url]
                    bIsUrl && !aIsUrl -> record(a, b)
                    // [url, label]
                    aIsUrl && !bIsUrl -> record(b, a)
                    // 连续地址（无标签）：取第一个
                    aIsUrl && bIsUrl -> record("", a)
                }
                i += 2
            }
            bestUrl.ifEmpty { firstUrl }
        } catch (_: Throwable) {
            url
        }
    }

    /** 是否为可播放地址：http(s)、协议相对 //、本地代理 proxy://、需解析的 video://、内嵌数据 data: */
    private fun isUrlLike(text: String): Boolean =
        text.startsWith("http", ignoreCase = true) || text.startsWith("//") ||
            text.startsWith("proxy") || text.startsWith("video") || text.startsWith("data")

    /**
     * 解析清晰度标签为高度分（px），无法识别返回 -1（不参与最高清晰度选择）。
     * 4K/8K/极清 优先于数字提取，避免 "4k1080" 之类混合标签被判成 1080。
     */
    private fun qualityScore(label: String): Int {
        val l = label.lowercase()
        // 4K 级（关键词优先）
        if (l.contains("4k") || l.contains("8k") || l.contains("uhd") || l.contains("极清") ||
            l.contains("2160") || l.contains("3840") || l.contains("4096")
        ) return 2160
        // 2K / 1440p
        if (l.contains("2k") || l.contains("1440") || l.contains("qhd")) return 1440
        if (l.contains("蓝光") || l.contains("超清")) return 1080
        // 数字提取（1080/720/480/360 等）
        Regex("(\\d{3,4})").find(l)?.let { m ->
            val n = m.groupValues[1].toIntOrNull()
            if (n != null) {
                return when {
                    n >= 2160 -> 2160
                    n >= 1440 -> 1440
                    n >= 1080 -> 1080
                    n >= 720 -> 720
                    n >= 480 -> 480
                    else -> 360
                }
            }
        }
        return when {
            l.contains("高清") || l.contains("hd") -> 720
            l.contains("标清") || l.contains("sd") -> 480
            l.contains("流畅") -> 360
            else -> -1
        }
    }

    /**
     * 从地址本身提取清晰度分：仅匹配明确的关键词（4k/2160/1080p/720p 等带单位标识），
     * 不做裸数字提取，避免 URL 中端口号/时间戳等数字干扰判断。
     */
    private fun qualityScoreFromUrl(url: String): Int {
        val l = url.lowercase()
        if (l.contains("4k") || l.contains("8k") || l.contains("uhd") || l.contains("2160") ||
            l.contains("3840") || l.contains("4096") || l.contains("极清")
        ) return 2160
        if (l.contains("1440") || l.contains("2k")) return 1440
        if (l.contains("1080") || l.contains("蓝光") || l.contains("超清")) return 1080
        if (l.contains("720") || l.contains("高清")) return 720
        if (l.contains("480") || l.contains("标清")) return 480
        if (l.contains("360") || l.contains("流畅")) return 360
        return -1
    }

    /**
     * 归一化播放地址用于去重：去掉 ? 及之后的 query 参数。
     * CDN 同一视频每次请求的签名（sign/tok）不同，URL 字符串不同导致去重失效，
     * 归一化后"同一地址"只保留一个备用项。
     */
    private fun normalizeVideoUrl(url: String): String {
        val idx = url.indexOf('?')
        return if (idx > 0) url.substring(0, idx) else url
    }

    /**
     * 是否为 .ts 分片地址（单个分片无法播放，不应进备用池）。
     * 只过滤带明确分片特征的 .ts（/hls/、/seg/、/segment/、数字序号）；
     * 无后缀 m3u8（可能无后缀）保留入池，分片由解析侧"每 CDN 只取第一个命中"收敛。
     */
    private fun isHlsSegment(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".ts") && (lower.contains("/hls/") || lower.contains("/seg/") ||
            lower.contains("/segment/") || Regex("\\d{6,}\\.ts(\\?|$)").containsMatchIn(lower))
    }

    /** 后台消费解析备用通道，把其他解析站竞速结果持续收集进备用池（不阻塞播放协程） */
    private fun startCollectBackups(channel: ReceiveChannel<com.tvbox.kotlin.core.ui.PlayResolver.ResolveResult>?) {
        if (channel == null) return
        val gen = collectGeneration
        backupCollectJob?.cancel()
        backupCollecting.set(true)
        backupCollectJob = viewModelScope.launch {
            try {
                for (r in channel) {
                    // 新一轮解析已开始：旧收集协程立即退出，防止旧集地址混入新集备用池
                    if (gen != collectGeneration) break
                    if (r.url.isNotEmpty() && !isHlsSegment(r.url) &&
                        backupSeenUrls.add(normalizeVideoUrl(r.url))
                    ) {
                        backupPlayUrls.offer(r)
                    }
                }
            } finally {
                backupCollecting.set(false)
            }
        }
    }

    /**
     * 执行可能失败的可挂起调用：协程取消时向上抛出取消异常（不吞掉），
     * 其余异常转为失败，避免被取消的解析协程继续执行播放覆盖新请求。
     */
    private inline fun <T> runCatchingResume(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /** 解析 header JSON 字符串为 Map */
    private fun parseHeaderStr(headerStr: String): Map<String, String>? {
        if (headerStr.isEmpty()) return null
        return try {
            val json = JSONObject(headerStr)
            val map = HashMap<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.optString(k)
            }
            map
        } catch (_: Throwable) {
            null
        }
    }

    /** 从播放器配置 JSON 提取请求头（playerCfg.headers 数组），用于 tvbox-drive 直链的 headers 装配 */
    private fun playerCfgHeaders(): Map<String, String>? {
        val cfg = playerCfg ?: return null
        return try {
            val arr = cfg.optJSONArray("headers") ?: return null
            val map = HashMap<String, String>()
            for (i in 0 until arr.length()) {
                val h = arr.optJSONObject(i) ?: continue
                val name = h.optString("name")
                if (name.isNotEmpty()) map[name] = h.optString("value")
            }
            if (map.isEmpty()) null else map
        } catch (_: Throwable) {
            null
        }
    }

    /** 合并两组请求头，后者优先 */
    private fun mergeHeaders(
        a: Map<String, String>?,
        b: Map<String, String>?
    ): Map<String, String>? {
        if (a.isNullOrEmpty() && b.isNullOrEmpty()) return null
        if (a.isNullOrEmpty()) return b
        if (b.isNullOrEmpty()) return a
        val merged = HashMap(a)
        merged.putAll(b)
        return merged
    }

    /** 按源 playUrl 配置构造解析器（json:/parse:/空嗅探） */
    private fun buildParseBean(cfgPlayUrl: String): ParseBean? {
        if (cfgPlayUrl.startsWith("json:")) {
            return ParseBean().apply {
                type = ParseBean.TYPE_JSON
                setUrl(cfgPlayUrl.substring(5))
            }
        }
        if (cfgPlayUrl.startsWith("parse:")) {
            val name = cfgPlayUrl.substring(6)
            _parseBeans.value.firstOrNull { it.name == name }?.let { return it }
        }
        // 空配置时解析站为空，解析流程直接嗅探播放地址本身
        return ParseBean().apply {
            type = ParseBean.TYPE_SNIFF
            setUrl(cfgPlayUrl)
        }
    }

    /** 解析后的播放信息 */
    data class ResolvedPlay(
        val url: String = "",
        val headers: Map<String, String>? = null,
        /** 当前播放内容是否需要解析器，用于控制解析栏显隐 */
        val needParse: Boolean = false,
        /** 解析失败原因（url 为空时有效），用于展示具体错误 */
        val error: String? = null,
        /** 解析来源名称（超级解析命中的具体站名），由播放层在真正播放成功后提示 */
        val parseSource: String? = null,
        /** 爬虫 playerContent 直接返回的 LRC 歌词内容（OK影视系爬虫 lrc 字段），为空表示源未提供 */
        val lrc: String = ""
    )

    // ------------------------------------------------------------------
    // 播放控制
    // ------------------------------------------------------------------

    /** 设置当前播放 URL，由 Activity 在解析完成后调用 */
    fun setPlayUrl(url: String) {
        _currentUrl.value = url
        _currentPosition.value = 0L
    }

    /** 设置爬虫返回的 LRC 歌词内容（解析播放内容时写入），播放层观察后显示 */
    fun setLrc(lrc: String) {
        if (_currentLrc.value != lrc) _currentLrc.value = lrc
    }

    /** 更新播放状态 */
    fun updatePlayState(state: PlayState) {
        _playState.value = state
    }

    /** 更新播放进度，由 Activity 在播放过程中回调 */
    fun updatePosition(position: Long) {
        _currentPosition.value = position
    }

    /** 设置进度缓存 key */
    fun setProgressKey(key: String?) {
        progressKey = key
    }

    /** 设置字幕路径 */
    fun setSubtitle(path: String) {
        playSubtitle = path
    }

    /** 获取字幕路径 */
    fun getSubtitle(): String = playSubtitle

    /** 获取弹幕来源（URL 或 base64 数据） */
    fun getDanmu(): String = danmuSource

    /**
     * 已下载的弹幕 XML/JSON 内容缓存，开关/设置重载时直接解析缓存，避免重复网络请求。
     */
    fun getCachedDanmuContent(): String? = danmuContentCache

    /** 缓存弹幕内容（播放新剧集/切换弹幕源时由 [setDanmuSource] 清空） */
    fun cacheDanmuContent(content: String) {
        if (content.isNotEmpty()) danmuContentCache = content
    }

    /** 拉取弹幕内容（本地代理地址由实现层直接调用爬虫 proxyLocal），失败返回 null */
    suspend fun fetchDanmu(url: String): String? =
        runCatchingResume { playResolver.fetchDanmu(url) }.getOrNull()

    /** 停止下载类协议后台任务（开源 BT 下载/荐片 P2P），播放器销毁时调用 */
    fun stopProtocolTasks() {
        runCatchingResume { playResolver.stopDownloadProtocols() }
    }

    /** 设置弹幕来源，由 Intent extra 注入时调用；来源变化时清空内容缓存 */
    fun setDanmu(danmu: String) {
        applyDanmuSource(danmu)
    }

    /** 设置弹幕来源（解析流程内部调用）；来源变化时清空内容缓存 */
    fun setDanmuSource(danmu: String) {
        applyDanmuSource(danmu)
    }

    /**
     * 应用弹幕源：仅来源变化时清空已下载内容缓存。
     * 同源重解析（同集换备用地址/重试）保留缓存，避免重复抓取源 jar；
     * 来源为空时同样落库清空，避免换集后沿用上一集的弹幕源与缓存内容（弹幕串集）。
     */
    private fun applyDanmuSource(danmu: String) {
        val resolved = resolveDanmuSource(danmu)
        if (resolved != danmuSource) danmuContentCache = null
        danmuSource = resolved
    }

    /**
     * 替换弹幕 URL 中本地服务的占位端口为实际端口。
     * 解析完成后把 `127.0.0.1:-1` 替换为
     * `127.0.0.1:RemoteServer.serverPort`（静态默认 9978，服务启动后为实际端口，无条件替换）。
     */
    private fun resolveDanmuSource(danmu: String): String {
        if (danmu.isEmpty() ||
            (!danmu.contains("127.0.0.1:-1") && !danmu.contains("localhost:-1"))
        ) return danmu
        // 服务未启动时 currentPort() 返回 -1，此时用默认端口兜底，
        // 保证 -1 占位始终被替换为有效端口（默认 9978）
        val port = controlManager.currentPort().takeIf { it > 0 } ?: AppConstants.LOCAL_SERVER_PORT
        return danmu
            .replace("127.0.0.1:-1", "127.0.0.1:$port")
            .replace("localhost:-1", "localhost:$port")
    }

    /** 获取起始播放位置，读取后清零避免重复 seek */
    fun consumeStartPosition(): Long {
        val pos = startPosition
        startPosition = DEFAULT_START_POSITION
        return pos
    }

    /**
     * 从播放器配置中提取请求头（headers 数组：{"name":..., "value":...}），
     * 供直链播放（如网盘 WebDAV 认证）应用。
     */
    fun getPlayerCfgHeaders(): Map<String, String>? {
        val cfg = playerCfg ?: return null
        return try {
            val arr = cfg.optJSONArray("headers") ?: return null
            val map = HashMap<String, String>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val name = item.optString("name")
                val value = item.optString("value")
                if (name.isNotEmpty()) map[name] = value
            }
            if (map.isEmpty()) null else map
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 应用播放器配置到 [MyVideoView]，委托给 [PlayerHelper.updateCfg]。
     * @param videoView 目标视图
     * @param forcePlayerType 强制播放器类型，>=0 时覆盖配置
     */
    fun applyPlayerConfig(videoView: MyVideoView, forcePlayerType: Int) {
        playerHelper.updateCfg(videoView, playerCfg, forcePlayerType)
    }

    /** 画中画是否开启：后台播放类型为 2（画中画）时启用 */
    fun isPipEnabled(): Boolean =
        appPreferences.get(AppConstants.BACKGROUND_PLAY_TYPE, 0) == 2

    /** 后台播放类型：0=关闭 1=开启 2=画中画 */
    fun backgroundPlayType(): Int =
        appPreferences.get(AppConstants.BACKGROUND_PLAY_TYPE, 0)

    /** 获取进度缓存 key，无则使用 URL 的 MD5 */
    fun getProgressKey(): String {
        val key = progressKey
        if (!key.isNullOrEmpty()) return key
        val url = _currentUrl.value
        return if (url.isNotEmpty()) MD5.string2MD5(url) ?: url else ""
    }

    /**
     * 构造稳定进度键（源key + 影片id + 线路 + 集id + 集索引），与动态播放地址无关，
     * 保证同影片同集在不同播放地址下共享/恢复同一进度。
     * 用绝对索引 playEpisodeIndex 取集：该索引在详情页/播放页/历史记录间稳定一致，
     * 避免 getplayIndex()（依赖 playGroup/playIndex 计算）在不同页面计算不一致导致键漂移，
     * 造成删除历史清理不到进度缓存。
     * 集索引参与键：即使集名/地址相同也保证每集进度独立（集 id 缺失时用集地址兜底）。
     */
    private fun buildStableProgressKey(info: VodInfo?, flag: String): String {
        val sourceKey = sourceKey ?: ""
        val vodId = info?.id ?: ""
        val index = info?.playEpisodeIndex ?: 0
        val series = info?.getVodSeries(flag, index)
        val episodeId = series?.episodeId ?: ""
        val idPart = if (episodeId.isNotEmpty()) "$index|$episodeId" else "$index|${series?.url ?: ""}"
        return "$sourceKey|$vodId|$flag|$idPart"
    }

    /**
     * 片头片尾记忆键：源 key + 影片 id，跨集共享。
     * 供控制视图持久化/恢复跳过时间，退出视频后重新进入仍保留设置。
     */
    fun skipTimeKey(): String {
        val info = _vodInfo.value ?: return ""
        val key = sourceKey ?: return ""
        val vodId = info.id ?: return ""
        return "$key|$vodId"
    }

    /**
     * 切集后立即同步历史记录（集索引/线路），
     * 保证从全屏返回详情页时集数列表选中集与当前播放集一致。
     * 不依赖播放进度（position 可能为 0），切集成功即落库。
     */
    private fun syncHistoryAfterEpisodeSwitch() {
        val info = _vodInfo.value ?: return
        val key = sourceKey ?: return
        val vodId = info.id ?: return
        if (key.isEmpty() || vodId.isEmpty()) return
        val flag = info.playFlag
        // 持久化当前集进度键，删除历史记录时据此清理该集进度缓存
        info.playProgressKey = if (!flag.isNullOrEmpty()) buildStableProgressKey(info, flag) else lastPlayUrl
        launchIO {
            runCatching { roomDataManger.insertVodRecord(key, info) }
        }
    }

    /**
     * 播放下一集。
     * @return 下一集 URL，null 表示无下一集
     */
    fun playNext(): String? {
        val info = _vodInfo.value ?: return null
        val flag = info.playFlag ?: return null
        val seriesList = info.seriesMap?.get(flag) ?: return null
        val oldIndex = info.playEpisodeIndex
        val nextIndex = oldIndex + 1
        if (nextIndex >= seriesList.size) return null
        info.playEpisodeIndex = nextIndex
        // 同步组内索引，保证 getplayIndex()（进度键/解析取集依赖）与当前集一致
        val groupCount = maxOf(info.playGroupCount, 1)
        info.playGroup = nextIndex / groupCount
        info.playIndex = nextIndex % groupCount
        info.saveCurrentEpisodeInfo()
        // 切集成功立即同步历史，返回详情页时集数列表选中集一致
        syncHistoryAfterEpisodeSwitch()
        val series = seriesList[nextIndex]
        _currentEpisode.value = series
        _currentUrl.value = series.url ?: ""
        _currentPosition.value = 0L
        return series.url
    }

    /**
     * 播放上一集。
     * @return 上一集 URL，null 表示无上一集
     */
    fun playPrevious(): String? {
        val info = _vodInfo.value ?: return null
        val flag = info.playFlag ?: return null
        val seriesList = info.seriesMap?.get(flag) ?: return null
        val oldIndex = info.playEpisodeIndex
        val prevIndex = oldIndex - 1
        if (prevIndex < 0) return null
        info.playEpisodeIndex = prevIndex
        // 同步组内索引，保证 getplayIndex() 与当前集一致
        val groupCount = maxOf(info.playGroupCount, 1)
        info.playGroup = prevIndex / groupCount
        info.playIndex = prevIndex % groupCount
        info.saveCurrentEpisodeInfo()
        // 切集成功立即同步历史，返回详情页时集数列表选中集一致
        syncHistoryAfterEpisodeSwitch()
        val series = seriesList[prevIndex]
        _currentEpisode.value = series
        _currentUrl.value = series.url ?: ""
        _currentPosition.value = 0L
        return series.url
    }

    /** 选集播放，返回选中集 URL */
    fun selectEpisode(episodeIndex: Int): String? {
        val info = _vodInfo.value ?: return null
        val flag = info.playFlag ?: return null
        val seriesList = info.seriesMap?.get(flag) ?: return null
        if (episodeIndex < 0 || episodeIndex >= seriesList.size) return null
        info.playEpisodeIndex = episodeIndex
        // 同步组内索引，保证 getplayIndex() 与当前集一致
        val groupCount = maxOf(info.playGroupCount, 1)
        info.playGroup = episodeIndex / groupCount
        info.playIndex = episodeIndex % groupCount
        info.saveCurrentEpisodeInfo()
        // 切集成功立即同步历史，返回详情页时集数列表选中集一致
        syncHistoryAfterEpisodeSwitch()
        val series = seriesList[episodeIndex]
        _currentEpisode.value = series
        _currentUrl.value = series.url ?: ""
        _currentPosition.value = 0L
        return series.url
    }

    /**
     * 切换线路并选中指定集（原子操作），返回目标集 URL。
     * 不经过 switchFlag 的重置索引步骤，避免切集瞬间 currentEpisode 抖动到第一集。
     */
    fun selectEpisodeInFlag(flag: String, episodeIndex: Int): String? {
        val info = _vodInfo.value ?: return null
        if (info.playFlag != flag) {
            info.playFlag = flag
            info.seriesFlags?.forEach { it.selected = (it.name == flag) }
        }
        return selectEpisode(episodeIndex)
    }

    /**
     * 切换播放线路（flag），并重置剧集索引到 0。
     * @return 新线路下第一集 URL，无剧集返回 null
     */
    fun switchFlag(flag: String): String? {
        val info = _vodInfo.value ?: return null
        val seriesList = info.seriesMap?.get(flag) ?: return null
        if (seriesList.isEmpty()) return null
        info.playFlag = flag
        info.playEpisodeIndex = 0
        info.playGroup = 0
        info.playIndex = 0
        info.saveCurrentEpisodeInfo()
        val series = seriesList[0]
        _currentEpisode.value = series
        _currentUrl.value = series.url ?: ""
        _currentPosition.value = 0L
        return series.url
    }

    /** 反转当前线路的剧集顺序（与选集弹窗的倒序按钮同步） */
    fun reverseSeries() {
        val info = _vodInfo.value ?: return
        val flag = info.playFlag ?: return
        val map = info.seriesMap ?: return
        val list = map[flag] ?: return
        val mutable = list.toMutableList()
        java.util.Collections.reverse(mutable)
        map[flag] = mutable
    }

    // ------------------------------------------------------------------
    // 解码器/缩放/倍速切换
    // ------------------------------------------------------------------

    /**
     * 手动切换解码器（播放器选择弹窗）：仅本次播放会话内临时指定。
     * 不写入全局点播首选偏好（KEY_PLAY_TYPE / VOD_PLAYER_PREFERRED），也不污染
     * playerCfg 的 ps 标记，重新进入播放页时仍按设置里的点播首选生效。
     */
    fun switchPlayer(type: Int) {
        userSelectedPlayerType = type
        _playerType.value = type
    }

    /** 切换缩放类型，同步写入偏好 */
    fun switchScale(scale: Int) {
        _scaleType.value = scale
        launchIO {
            appPreferences.put(PlayerConstants.KEY_PLAY_SCALE, scale)
        }
        updatePlayerCfg { put("sc", scale) }
    }

    /** 切换播放倍速 */
    fun switchSpeed(speed: Float) {
        _speed.value = speed
    }

    /** 切换到下一个缩放类型，循环 */
    fun cycleScale(): Int {
        val types = intArrayOf(
            PlayerConstants.SCREEN_SCALE_DEFAULT,
            PlayerConstants.SCREEN_SCALE_16_9,
            PlayerConstants.SCREEN_SCALE_4_3,
            PlayerConstants.SCREEN_SCALE_MATCH_PARENT,
            PlayerConstants.SCREEN_SCALE_ORIGINAL,
            PlayerConstants.SCREEN_SCALE_CENTER_CROP
        )
        val current = _scaleType.value
        val currentIndex = types.indexOfFirst { it == current }
        val nextIndex = (currentIndex + 1) % types.size
        val next = types[nextIndex]
        switchScale(next)
        return next
    }

    /** 更新播放器配置 JSON，同步回写 VodInfo */
    private fun updatePlayerCfg(block: JSONObject.() -> Unit) {
        val cfg = playerCfg ?: JSONObject().also { playerCfg = it }
        try {
            cfg.block()
            _vodInfo.value?.playerCfg = cfg.toString()
        } catch (_: Throwable) {
            // 配置更新失败忽略，避免影响主流程
        }
    }

    // ------------------------------------------------------------------
    // 解析器
    // ------------------------------------------------------------------

    /**
     * 注入解析器列表，由 :app 模块通过 ApiConfig.getParseBeanList 提供后调用。
     * 仅更新列表，不自动回填 [_currentParse]，避免回填触发 currentParse 收集器
     * 重复 loadAndPlay 导致播放器重建；实际解析器选择在 [loadAndResolve] 中惰性完成。
     */
    fun setParseBeans(list: List<ParseBean>) {
        _parseBeans.value = list
    }

    /** 切换当前解析器 */
    fun changeParse(parse: ParseBean) {
        _currentParse.value = parse
    }

    // ------------------------------------------------------------------
    // 进度记忆与历史记录
    // ------------------------------------------------------------------

    /**
     * 保存播放进度到偏好缓存。
     * @param url 播放地址
     * @param position 当前位置（毫秒）
     */
    fun saveProgress(url: String, position: Long) {
        if (url.isEmpty() || position <= 0) return
        lastPlayUrl = url
        // 总时长为 0（未进入播放）时不保存
        if (_duration.value <= 0) return
        val cacheKey = resolveProgressKey(url)
        val duration = _duration.value
        // 剩余时长不足阈值时清除该集进度：避免下次恢复临近结尾立即播完自动切集
        val endMargin = minOf(PROGRESS_END_MARGIN_MS, duration / PROGRESS_END_RATIO_DIVISOR)
        launchIO {
            if (duration - position <= endMargin) {
                appPreferences.remove(cacheKey)
            } else {
                appPreferences.put(cacheKey, position)
            }
        }
    }

    /**
     * 读取播放进度缓存（断点续播）。
     * 返回 max(缓存进度, 跳片头秒数 st*1000)。
     */
    fun getSavedProgress(url: String): Long {
        if (url.isEmpty()) return 0L
        val rec = appPreferences.getLong(resolveProgressKey(url), 0L)
        val skip = playerCfg?.optLong("st", 0)?.times(1000L) ?: 0L
        return maxOf(rec, skip)
    }

    /**
     * 删除指定地址的进度缓存（播放完成时清除当前集进度，避免下次恢复临近结尾）。
     */
    fun clearProgress(url: String) {
        if (url.isEmpty()) return
        val cacheKey = resolveProgressKey(url)
        launchIO {
            appPreferences.remove(cacheKey)
        }
    }

    /** 置位：下一次播放跳过进度恢复（手动"下一集"/自动连播时） */
    fun markSkipProgressRestore() {
        skipProgressRestore = true
    }

    /** 消费跳过进度恢复标记，供播放装配读取 */
    fun consumeSkipProgressRestore(): Boolean {
        val skip = skipProgressRestore
        skipProgressRestore = false
        return skip
    }

    /**
     * 进度键：以已实际开始播放的集（startPlayUrl 装配时标记）为准。
     * 保存/恢复/清除统一用该键：切集解析期间播放器仍是旧集（标记未更新）则落到旧集键，
     * 新集装配后标记更新为新集，键随之切换，与旧版"播放器绑定 progressKey"等价。
     */
    private fun resolveProgressKey(url: String): String {
        val info = _vodInfo.value ?: return PROGRESS_KEY_PREFIX + (MD5.string2MD5(url) ?: url)
        val flag = playbackStartedFlag ?: info.playFlag
        val index = if (playbackStartedFlag != null) playbackStartedIndex else info.playEpisodeIndex
        return if (!flag.isNullOrEmpty() && index >= 0) {
            buildProgressKey(info, flag, index)
        } else {
            PROGRESS_KEY_PREFIX + (MD5.string2MD5(url) ?: url)
        }
    }

    /** 播放器实际装配目标集后标记：后续进度键绑定该集（等价旧版 setProgressKey） */
    fun markPlaybackStarted() {
        val info = _vodInfo.value ?: return
        playbackStartedFlag = info.playFlag
        playbackStartedIndex = info.playEpisodeIndex
    }

    /** 按线路与集索引构建稳定进度键（与 URL 无关，集索引+集id 参与保证每集独立） */
    private fun buildProgressKey(info: VodInfo?, flag: String, index: Int): String {
        val source = sourceKey ?: ""
        val vodId = info?.id ?: ""
        val series = info?.getVodSeries(flag, index)
        val episodeId = series?.episodeId ?: ""
        val idPart = if (episodeId.isNotEmpty()) "$index|$episodeId" else "$index|${series?.url ?: ""}"
        val suffix = MD5.string2MD5("$flag|$idPart") ?: "$flag|$idPart"
        return PROGRESS_KEY_PREFIX + "$source|$vodId|" + suffix
    }

    /**
     * 写入观看记录到 Room。
     * 仅当播放成功且有实际进度（position > 0）时才记录，
     * 避免未播放/解析失败的影片进入历史。
     * @param position 当前播放进度（毫秒），由调用方传入播放器实际位置
     */
    fun saveVodRecord(position: Long = _currentPosition.value) {
        if (position <= 0) return
        val info = _vodInfo.value ?: return
        val key = sourceKey ?: return
        val vodId = info.id ?: return
        info.saveCurrentEpisodeInfo()
        // 记录当前剧集名到 playNote，便于历史展示
        val flag = info.playFlag
        // 持久化进度键（每集稳定键，与进度缓存键一致），删除历史记录时据此清理进度缓存
        info.playProgressKey = if (!flag.isNullOrEmpty()) buildStableProgressKey(info, flag) else lastPlayUrl
        var currentSourceEpisodes = 0
        if (!flag.isNullOrEmpty()) {
            val seriesList = info.seriesMap?.get(flag)
            currentSourceEpisodes = seriesList?.size ?: 0
            val idx = info.playEpisodeIndex
            if (seriesList != null && idx in 0 until seriesList.size) {
                info.playNote = seriesList[idx].name ?: ""
            }
        }
        // 标记是否已看到最新集，供历史页更新徽章展示
        val playIndex = info.playEpisodeIndex
        val isLatestEpisode = if (info.reverseSort) {
            playIndex == 0
        } else {
            currentSourceEpisodes > 0 && playIndex + 1 >= currentSourceEpisodes
        }
        videoUpdateChecker.setVideoUpdate(key, vodId, !isLatestEpisode)
        EventHub.post(RefreshEvent(RefreshEvent.TYPE_HISTORY))
        launchIO {
            try {
                roomDataManger.insertVodRecord(key, info)
            } catch (e: Throwable) {
                error(context.getString(uiR.string.play_err_save_record, e.message ?: ""))
            }
        }
    }

    // ------------------------------------------------------------------
    // 投屏发送
    // ------------------------------------------------------------------

    /** 启动投屏设备发现 */
    fun startCastDiscovery() {
        if (_castScanning.value) return
        _castScanning.value = true
        _castDevices.value = emptyList()
        CastManager.getInstance(application).startDeviceDiscovery(object : DeviceDiscovery.DeviceDiscoveryListener {
            override fun onDeviceFound(device: CastDevice) {
                val current = _castDevices.value.toMutableList()
                if (current.none { it.getDeviceId() == device.getDeviceId() }) {
                    current.add(device)
                    _castDevices.value = current
                }
            }

            override fun onDiscoveryComplete() {
                _castScanning.value = false
            }

            override fun onDiscoveryError(error: String) {
                _castScanning.value = false
                this@PlayViewModel.error(error)
            }
        })
    }

    /** 停止投屏设备发现 */
    fun stopCastDiscovery() {
        CastManager.getInstance(application).stopDeviceDiscovery()
        _castScanning.value = false
    }

    /** 投屏当前视频到目标设备，结果通过 [onResult] 回调（成功后暂停本地播放）。
     *  携带弹幕数据（优先已下载的 XML 内容，否则原始 URL/base64，接收端均可解析） */
    fun castCurrentToDevice(device: CastDevice, onResult: ((Boolean) -> Unit)? = null) {
        val url = _currentUrl.value
        if (url.isEmpty()) {
            error(context.getString(uiR.string.play_err_no_content_cast))
            onResult?.invoke(false)
            return
        }
        val episodeName = _currentEpisode.value?.name ?: ""
        val danmuData = getCachedDanmuContent() ?: getDanmu()
        val data = CastData(
            url = url,
            title = _title.value.ifEmpty { episodeName },
            position = _currentPosition.value,
            duration = _duration.value,
            isPlaying = _playState.value == PlayState.PLAYING,
            episodeName = episodeName,
            playerType = _playerType.value,
            danmakuData = danmuData.takeIf { it.isNotEmpty() },
            hasDanmaku = danmuData.isNotEmpty()
        )
        CastManager.getInstance(application).castToDevice(device, data) { success ->
            if (success) {
                toast(context.getString(uiR.string.cast_success))
            } else {
                error(context.getString(uiR.string.cast_failed))
            }
            onResult?.invoke(success)
        }
    }

    // ------------------------------------------------------------------
    // 资源释放
    // ------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        stopCastDiscovery()
    }
}
