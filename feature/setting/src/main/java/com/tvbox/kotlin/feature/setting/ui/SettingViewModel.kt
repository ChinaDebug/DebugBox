package com.tvbox.kotlin.feature.setting.ui

import androidx.lifecycle.ViewModel
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.TypedEvent
import com.tvbox.kotlin.core.common.util.ApiLineStore
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.data.VideoUpdateChecker
import com.tvbox.kotlin.core.network.OkHttpHelper
import com.tvbox.kotlin.core.spider.ApiConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 设置页 ViewModel，统一管理所有设置项状态。
 * 通过 [AppPreferences] 读写配置，通过 [ApiConfig] 触发站点重载，
 * 通过 [OkHttpHelper] 间接感知 DoH 变更，修改完成后经 [EventHub] 通知其他模块刷新。
 */
@HiltViewModel
class SettingViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val apiConfig: ApiConfig,
    private val okHttpHelper: OkHttpHelper,
    private val videoUpdateChecker: VideoUpdateChecker
) : ViewModel() {

    /** 事件类型：订阅设置变更，需重启加载 */
    companion object {
        const val EVENT_API_CHANGED = "setting_api_changed"
        const val EVENT_LIVE_CHANGED = "setting_live_changed"
        const val EVENT_EPG_CHANGED = "setting_epg_changed"
        const val EVENT_DOH_CHANGED = "setting_doh_changed"
        const val EVENT_PLAYER_CHANGED = "setting_player_changed"
        const val EVENT_THEME_CHANGED = "setting_theme_changed"
        const val EVENT_WALLPAPER_CHANGED = "setting_wallpaper_changed"
        const val EVENT_CONFIG_REFRESH = "setting_config_refresh"
    }

    // ---------------- 点播接口 ----------------
    private val _apiUrl = MutableStateFlow("")
    val apiUrl: StateFlow<String> = _apiUrl.asStateFlow()

    // ---------------- 直播接口 ----------------
    private val _liveUrl = MutableStateFlow("")
    val liveUrl: StateFlow<String> = _liveUrl.asStateFlow()

    // ---------------- EPG 接口 ----------------
    private val _epgUrl = MutableStateFlow("")
    val epgUrl: StateFlow<String> = _epgUrl.asStateFlow()

    // ---------------- DoH ----------------
    private val _dohIndex = MutableStateFlow(0)
    val dohIndex: StateFlow<Int> = _dohIndex.asStateFlow()

    // ---------------- 播放器 ----------------
    private val _playerType = MutableStateFlow(1)
    val playerType: StateFlow<Int> = _playerType.asStateFlow()

    private val _playScale = MutableStateFlow(0)
    val playScale: StateFlow<Int> = _playScale.asStateFlow()

    private val _playRender = MutableStateFlow(0)
    val playRender: StateFlow<Int> = _playRender.asStateFlow()

    private val _playSpeed = MutableStateFlow(1.0f)
    val playSpeed: StateFlow<Float> = _playSpeed.asStateFlow()

    // ---------------- 主题与壁纸 ----------------
    private val _themeIndex = MutableStateFlow(0)
    val themeIndex: StateFlow<Int> = _themeIndex.asStateFlow()

    private val _wallpaperBlur = MutableStateFlow(true)
    val wallpaperBlur: StateFlow<Boolean> = _wallpaperBlur.asStateFlow()

    private val _wallpaperUrl = MutableStateFlow("")
    val wallpaperUrl: StateFlow<String> = _wallpaperUrl.asStateFlow()

    // ---------------- 字幕 ----------------
    private val _subtitleSize = MutableStateFlow(16)
    val subtitleSize: StateFlow<Int> = _subtitleSize.asStateFlow()

    // ---------------- 首页相关 ----------------
    private val _showPreview = MutableStateFlow(true)
    val showPreview: StateFlow<Boolean> = _showPreview.asStateFlow()

    private val _homeShowSource = MutableStateFlow(false)
    val homeShowSource: StateFlow<Boolean> = _homeShowSource.asStateFlow()

    private val _homeRec = MutableStateFlow(0)
    val homeRec: StateFlow<Int> = _homeRec.asStateFlow()

    private val _backgroundPlayType = MutableStateFlow(0)
    val backgroundPlayType: StateFlow<Int> = _backgroundPlayType.asStateFlow()

    private val _homeDefaultShow = MutableStateFlow(false)
    val homeDefaultShow: StateFlow<Boolean> = _homeDefaultShow.asStateFlow()

    private val _historyNum = MutableStateFlow(0)
    val historyNum: StateFlow<Int> = _historyNum.asStateFlow()

    private val _locale = MutableStateFlow(0)
    val locale: StateFlow<Int> = _locale.asStateFlow()

    private val _searchView = MutableStateFlow(0)
    val searchView: StateFlow<Int> = _searchView.asStateFlow()

    private val _fastSearch = MutableStateFlow(false)
    val fastSearch: StateFlow<Boolean> = _fastSearch.asStateFlow()

    private val _videoPurify = MutableStateFlow(true)
    val videoPurify: StateFlow<Boolean> = _videoPurify.asStateFlow()

    // ---------------- 更新检测 ----------------
    private val _updateCheckEnable = MutableStateFlow(true)
    val updateCheckEnable: StateFlow<Boolean> = _updateCheckEnable.asStateFlow()

    private val _updateCheckStartup = MutableStateFlow(true)
    val updateCheckStartup: StateFlow<Boolean> = _updateCheckStartup.asStateFlow()

    private val _updateCheckInterval = MutableStateFlow(VideoUpdateChecker.INTERVAL_OFF)
    val updateCheckInterval: StateFlow<Int> = _updateCheckInterval.asStateFlow()

    private val _updateCheckWifiOnly = MutableStateFlow(false)
    val updateCheckWifiOnly: StateFlow<Boolean> = _updateCheckWifiOnly.asStateFlow()

    // ---------------- 变更追踪 ----------------
    /** 记录进入设置页时的配置快照，返回时用于检测是否需要重启 */
    private var snapshot: ConfigSnapshot = ConfigSnapshot()

    /** 从偏好加载当前配置到 StateFlow */
    fun loadConfig() {
        _apiUrl.value = appPreferences.getString(AppConstants.API_URL, AppConstants.DEFAULT_API_URL)
        _liveUrl.value = appPreferences.getString(AppConstants.LIVE_URL, "")
        _epgUrl.value = appPreferences.getString(AppConstants.EPG_URL, "")
        _dohIndex.value = appPreferences.getInt(AppConstants.DOH_URL, 0)
        _playerType.value = appPreferences.getInt(AppConstants.PLAY_TYPE, 1)
        _playScale.value = appPreferences.getInt(AppConstants.PLAY_SCALE, 0)
        _playRender.value = appPreferences.getInt(AppConstants.PLAY_RENDER, 1)
        _playSpeed.value = appPreferences.getFloat(AppConstants.PLAY_TIME_STEP, 1.0f)
        _themeIndex.value = appPreferences.getInt(AppConstants.THEME_SELECT, 0)
        _wallpaperBlur.value = appPreferences.getBoolean(AppConstants.WALLPAPER_BLUR, true)
        _wallpaperUrl.value = apiConfig.wallpaper
        _subtitleSize.value = appPreferences.getInt(AppConstants.SUBTITLE_TEXT_SIZE, 16)
        _showPreview.value = appPreferences.getBoolean(AppConstants.SHOW_PREVIEW, true)
        _homeShowSource.value = appPreferences.getBoolean(AppConstants.HOME_SHOW_SOURCE, true)
        _homeRec.value = appPreferences.getInt(AppConstants.HOME_REC, 1)
        _backgroundPlayType.value = appPreferences.getInt(AppConstants.BACKGROUND_PLAY_TYPE, 0)
        _homeDefaultShow.value = appPreferences.getBoolean(AppConstants.HOME_DEFAULT_SHOW, false)
        _historyNum.value = appPreferences.getInt(AppConstants.HOME_NUM, 0)
        _locale.value = appPreferences.getInt(AppConstants.HOME_LOCALE, 0)
        _searchView.value = appPreferences.getInt(AppConstants.SEARCH_VIEW, 1)
        _fastSearch.value = appPreferences.getBoolean(AppConstants.FAST_SEARCH_MODE, false)
        _videoPurify.value = appPreferences.getBoolean(AppConstants.VIDEO_PURIFY, true)
        _updateCheckEnable.value = videoUpdateChecker.isEnable()
        _updateCheckStartup.value = videoUpdateChecker.isStartupCheck()
        _updateCheckInterval.value = videoUpdateChecker.getCheckInterval()
        _updateCheckWifiOnly.value = videoUpdateChecker.isWifiOnly()
        snapshot = captureSnapshot()
    }

    private fun captureSnapshot() = ConfigSnapshot(
        apiUrl = _apiUrl.value,
        liveUrl = _liveUrl.value,
        epgUrl = _epgUrl.value,
        dohIndex = _dohIndex.value,
        playerType = _playerType.value,
        playScale = _playScale.value,
        playRender = _playRender.value,
        playSpeed = _playSpeed.value,
        themeIndex = _themeIndex.value,
        wallpaperBlur = _wallpaperBlur.value,
        showPreview = _showPreview.value,
        homeShowSource = _homeShowSource.value,
        homeRec = _homeRec.value,
        backgroundPlayType = _backgroundPlayType.value,
        homeDefaultShow = _homeDefaultShow.value,
        historyNum = _historyNum.value,
        locale = _locale.value,
        searchView = _searchView.value,
        fastSearch = _fastSearch.value,
        videoPurify = _videoPurify.value
    )

    /** 判断配置是否发生变更，决定返回时是否需重启 */
    fun hasConfigChanged(): Boolean {
        val current = captureSnapshot()
        return current != snapshot
    }

    /** 判断接口类配置是否变更（需清理缓存重启） */
    fun hasSourceChanged(): Boolean {
        return snapshot.apiUrl != _apiUrl.value ||
            snapshot.liveUrl != _liveUrl.value ||
            snapshot.epgUrl != _epgUrl.value ||
            snapshot.dohIndex != _dohIndex.value
    }

    // ---------------- 写入配置 ----------------

    /** 读取已保存的多仓（接口合集）线路列表，未加载过多仓时返回空列表 */
    fun readApiLines(): List<Pair<String, String>> = ApiLineStore.readLines(appPreferences)

    /** 保存点播订阅地址并通知刷新 */
    suspend fun saveApiUrl(url: String) {
        if (url.isBlank()) return
        val changed = _apiUrl.value != url
        _apiUrl.value = url
        appPreferences.put(AppConstants.API_URL, url)
        if (changed) {
            apiConfig.clearAllCache()
            videoUpdateChecker.clearCache()
            videoUpdateChecker.resetCheckState()
            EventHub.post(TypedEvent(EVENT_API_CHANGED))
        }
    }

    /** 保存直播接口地址 */
    suspend fun saveLiveUrl(url: String) {
        val changed = _liveUrl.value != url
        _liveUrl.value = url
        appPreferences.put(AppConstants.LIVE_URL, url)
        if (changed) EventHub.post(TypedEvent(EVENT_LIVE_CHANGED))
    }

    /** 保存 EPG 接口地址 */
    suspend fun saveEpgUrl(url: String) {
        val changed = _epgUrl.value != url
        _epgUrl.value = url
        appPreferences.put(AppConstants.EPG_URL, url)
        if (changed) EventHub.post(TypedEvent(EVENT_EPG_CHANGED))
    }

    /** 重新从偏好读取直播/EPG 地址：配置重载后偏好可能被更新（配置内含 live/epg），打开配置弹窗前调用以显示最新值 */
    fun refreshUrlFlows() {
        _liveUrl.value = appPreferences.getString(AppConstants.LIVE_URL, "")
        _epgUrl.value = appPreferences.getString(AppConstants.EPG_URL, "")
    }

    /** 保存 DoH 选择，重建 OkHttp 客户端使新 DNS 立即生效 */
    suspend fun saveDohIndex(index: Int) {
        val changed = _dohIndex.value != index
        _dohIndex.value = index
        appPreferences.put(AppConstants.DOH_URL, index)
        if (changed) {
            okHttpHelper.rebuildClients()
            EventHub.post(TypedEvent(EVENT_DOH_CHANGED))
        }
    }

    /** 保存缩放模式 */
    suspend fun savePlayScale(scale: Int) {
        _playScale.value = scale
        appPreferences.put(AppConstants.PLAY_SCALE, scale)
        EventHub.post(TypedEvent(EVENT_PLAYER_CHANGED))
    }

    /** 保存渲染模式 */
    suspend fun savePlayRender(render: Int) {
        _playRender.value = render
        appPreferences.put(AppConstants.PLAY_RENDER, render)
        EventHub.post(TypedEvent(EVENT_PLAYER_CHANGED))
    }

    /** 保存倍速 */
    suspend fun savePlaySpeed(speed: Float) {
        _playSpeed.value = speed
        appPreferences.put(AppConstants.PLAY_TIME_STEP, speed)
        EventHub.post(TypedEvent(EVENT_PLAYER_CHANGED))
    }

    /** 保存主题选择 */
    suspend fun saveTheme(index: Int) {
        val changed = _themeIndex.value != index
        _themeIndex.value = index
        appPreferences.put(AppConstants.THEME_SELECT, index)
        if (changed) EventHub.post(TypedEvent(EVENT_THEME_CHANGED))
    }

    /** 保存壁纸虚化开关 */
    suspend fun saveWallpaperBlur(blur: Boolean) {
        _wallpaperBlur.value = blur
        appPreferences.put(AppConstants.WALLPAPER_BLUR, blur)
        EventHub.post(TypedEvent(EVENT_WALLPAPER_CHANGED))
    }

    /** 保存字幕大小 */
    suspend fun saveSubtitleSize(size: Int) {
        _subtitleSize.value = size
        appPreferences.put(AppConstants.SUBTITLE_TEXT_SIZE, size)
    }

    // ---------------- 首页相关设置 ----------------

    /** 保存详情页模式：true=预览模式 false=海报模式 */
    suspend fun saveShowPreview(preview: Boolean) {
        _showPreview.value = preview
        appPreferences.put(AppConstants.SHOW_PREVIEW, preview)
    }

    /** 保存首页是否显示站点名称 */
    suspend fun saveHomeShowSource(show: Boolean) {
        _homeShowSource.value = show
        appPreferences.put(AppConstants.HOME_SHOW_SOURCE, show)
        EventHub.post(TypedEvent(EVENT_CONFIG_REFRESH))
    }

    /** 保存首页推荐站点类型：0=豆瓣热播 1=站点推荐 2=观看历史 */
    suspend fun saveHomeRec(rec: Int) {
        _homeRec.value = rec
        appPreferences.put(AppConstants.HOME_REC, rec)
        EventHub.post(TypedEvent(EVENT_CONFIG_REFRESH))
    }

    /** 保存后台播放类型：0=关闭 1=开启 2=画中画 */
    suspend fun saveBackgroundPlayType(type: Int) {
        _backgroundPlayType.value = type
        appPreferences.put(AppConstants.BACKGROUND_PLAY_TYPE, type)
        EventHub.post(TypedEvent(EVENT_PLAYER_CHANGED))
    }

    /** 保存启动时进直播开关 */
    suspend fun saveHomeDefaultShow(show: Boolean) {
        _homeDefaultShow.value = show
        appPreferences.put(AppConstants.HOME_DEFAULT_SHOW, show)
    }

    /** 保存历史保留条数档位（对应 20/40/60/80/100 条） */
    suspend fun saveHistoryNum(num: Int) {
        _historyNum.value = num
        appPreferences.put(AppConstants.HOME_NUM, num)
    }

    /** 保存语言：0=中文 1=英文 */
    suspend fun saveLocale(locale: Int) {
        _locale.value = locale
        appPreferences.put(AppConstants.HOME_LOCALE, locale)
    }

    /** 保存搜索展示：0=文字列表 1=缩略图 */
    suspend fun saveSearchView(view: Int) {
        _searchView.value = view
        appPreferences.put(AppConstants.SEARCH_VIEW, view)
    }

    /** 保存聚搜开关 */
    suspend fun saveFastSearch(enable: Boolean) {
        _fastSearch.value = enable
        appPreferences.put(AppConstants.FAST_SEARCH_MODE, enable)
    }

    /** 保存广告过滤开关 */
    suspend fun saveVideoPurify(purify: Boolean) {
        _videoPurify.value = purify
        appPreferences.put(AppConstants.VIDEO_PURIFY, purify)
    }

    // ---------------- 更新检测配置 ----------------

    /** 保存更新检测总开关，关闭时同步停止调度并清空结果缓存 */
    suspend fun saveUpdateCheckEnable(enable: Boolean) {
        videoUpdateChecker.setEnable(enable)
        _updateCheckEnable.value = enable
    }

    /** 保存启动检测开关，按联动规则：关闭启动检测且间隔关闭时自动关闭总开关 */
    suspend fun saveUpdateCheckStartup(startup: Boolean) {
        videoUpdateChecker.setStartupCheck(startup)
        _updateCheckStartup.value = startup
        if (!startup && _updateCheckInterval.value == VideoUpdateChecker.INTERVAL_OFF) {
            videoUpdateChecker.setEnable(false)
            _updateCheckEnable.value = false
        }
    }

    /** 保存检测间隔，关闭间隔时按联动规则处理总开关 */
    suspend fun saveUpdateCheckInterval(interval: Int) {
        videoUpdateChecker.setCheckInterval(interval)
        _updateCheckInterval.value = interval
        if (!_updateCheckStartup.value && interval == VideoUpdateChecker.INTERVAL_OFF) {
            videoUpdateChecker.setEnable(false)
            _updateCheckEnable.value = false
        }
    }

    /** 保存仅 WiFi 检测开关 */
    suspend fun saveUpdateCheckWifiOnly(wifiOnly: Boolean) {
        videoUpdateChecker.setWifiOnly(wifiOnly)
        _updateCheckWifiOnly.value = wifiOnly
    }

    /** 发送全局刷新事件，通知首页等模块重载配置 */
    fun notifyConfigRefresh() {
        EventHub.post(TypedEvent(EVENT_CONFIG_REFRESH))
    }

    /** 配置快照，用于变更检测 */
    private data class ConfigSnapshot(
        val apiUrl: String = "",
        val liveUrl: String = "",
        val epgUrl: String = "",
        val dohIndex: Int = 0,
        val playerType: Int = 0,
        val playScale: Int = 0,
        val playRender: Int = 0,
        val playSpeed: Float = 0f,
        val themeIndex: Int = 0,
        val wallpaperBlur: Boolean = true,
        val showPreview: Boolean = true,
        val homeShowSource: Boolean = false,
        val homeRec: Int = 0,
        val backgroundPlayType: Int = 0,
        val homeDefaultShow: Boolean = false,
        val historyNum: Int = 0,
        val locale: Int = 0,
        val searchView: Int = 0,
        val fastSearch: Boolean = false,
        val videoPurify: Boolean = true
    )
}
