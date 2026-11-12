package com.tvbox.kotlin.feature.setting.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.event.RefreshEvent
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.HawkListHelper
import com.tvbox.kotlin.core.common.util.HistoryHelper
import com.tvbox.kotlin.core.data.VideoUpdateChecker
import com.tvbox.kotlin.core.data.db.AppDataManager
import com.tvbox.kotlin.core.data.repo.CacheManager
import com.tvbox.kotlin.core.data.repo.RoomDataManger
import com.tvbox.kotlin.core.network.OkHttpHelper
import com.tvbox.kotlin.core.server.ControlManager
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.ui.AppNavigator
import com.tvbox.kotlin.core.ui.BaseBindingActivity
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.feature.setting.R
import com.tvbox.kotlin.feature.setting.databinding.ActivitySettingBinding
import com.tvbox.kotlin.feature.setting.ui.dialog.ApiDialog
import com.tvbox.kotlin.feature.setting.ui.dialog.ApiHistoryDialog
import com.tvbox.kotlin.feature.setting.ui.dialog.AboutDialog
import com.tvbox.kotlin.feature.setting.ui.dialog.BackupDialog
import com.tvbox.kotlin.feature.setting.ui.dialog.CastDialog
import com.tvbox.kotlin.feature.setting.ui.dialog.CleanResetDialog
import com.tvbox.kotlin.feature.setting.ui.dialog.MediaSettingDialog
import com.tvbox.kotlin.core.ui.dialog.SelectDialog
import com.tvbox.kotlin.feature.setting.ui.dialog.WallpaperDialog
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 设置页：
 * 左侧菜单隐藏，右侧 ScrollView 内两栏分组展示全部设置项。
 * 返回时检测订阅设置是否变更，变更则重新进入启动页加载。
 */
@AndroidEntryPoint
class SettingActivity : BaseBindingActivity<ActivitySettingBinding>() {

    private val viewModel: SettingViewModel by viewModels()

    override val layoutId = R.layout.activity_setting

    // ---------- 依赖 ----------
    @Inject lateinit var apiConfig: ApiConfig
    @Inject lateinit var controlManager: ControlManager
    @Inject lateinit var okHttpHelper: OkHttpHelper
    @Inject lateinit var cacheManager: CacheManager
    @Inject lateinit var videoUpdateChecker: VideoUpdateChecker
    @Inject lateinit var roomDataManger: RoomDataManger
    @Inject lateinit var appDataManager: AppDataManager
    @Inject lateinit var navigator: AppNavigator

    private val preferences: AppPreferences by lazy { AppPreferences.getShared(this) }

    // 显示名称表（本地化资源，随语言切换）
    private val scaleNames: Array<String> by lazy {
        arrayOf(
            getString(uiR.string.scale_default), "16:9", "4:3",
            getString(uiR.string.scale_fill), getString(uiR.string.scale_original),
            getString(uiR.string.scale_crop)
        )
    }
    private val bgPlayNames: Array<String> by lazy {
        arrayOf(
            getString(uiR.string.bgplay_off), getString(uiR.string.bgplay_on),
            getString(uiR.string.bgplay_pip)
        )
    }
    private val themeNames: Array<String> by lazy {
        arrayOf(
            getString(uiR.string.theme_0), getString(uiR.string.theme_1),
            getString(uiR.string.theme_2), getString(uiR.string.theme_3),
            getString(uiR.string.theme_4), getString(uiR.string.theme_5),
            getString(uiR.string.theme_6)
        )
    }
    private val updateIntervalNames: Array<String> by lazy {
        arrayOf(
            getString(uiR.string.interval_off), getString(uiR.string.interval_30),
            getString(uiR.string.interval_1h), getString(uiR.string.interval_2h)
        )
    }
    private val updateIntervalValues = intArrayOf(
        VideoUpdateChecker.INTERVAL_OFF,
        VideoUpdateChecker.INTERVAL_30MIN,
        VideoUpdateChecker.INTERVAL_1HOUR,
        VideoUpdateChecker.INTERVAL_2HOURS
    )

    // 返回时变更检测快照
    private var homeSourceKey: String? = null
    private var currentApi: String = ""
    private var currentLive: String = ""
    private var currentEpg: String = ""
    private var dnsOpt: Int = 0
    private var cacheCleared = false

    override fun initView(savedInstanceState: Bundle?) {
        bindClickListeners()
        binding.llHomeApi.requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFinishing) return
                if (cacheCleared) {
                    navigator.goSplash(this@SettingActivity, false)
                    finish()
                    return
                }
                val homeApiChanged = homeSourceKey != preferences.getString(AppConstants.HOME_API, "")
                val apiChanged = currentApi != preferences.getString(AppConstants.API_URL, "")
                val liveChanged = currentLive != preferences.getString(AppConstants.LIVE_URL, "")
                val epgChanged = currentEpg != preferences.getString(AppConstants.EPG_URL, "")
                val dnsChanged = dnsOpt != preferences.getInt(AppConstants.DOH_URL, 0)
                if (homeApiChanged || apiChanged || liveChanged || epgChanged || dnsChanged) {
                    // 仅接口地址全未变化时使用缓存，否则清理缓存后重新加载
                    val useCache = !apiChanged && !liveChanged && !epgChanged
                    if (!useCache) {
                        lifecycleScope.launch {
                            apiConfig.clearAllCache()
                            videoUpdateChecker.clearCache()
                            videoUpdateChecker.resetCheckState()
                        }
                    }
                    if (apiChanged || liveChanged || epgChanged) {
                        // 接口/直播/EPG 地址变更：清缓存后杀进程重启。
                        // Android 无法卸载已加载的 jar 类，仅跳转启动页（不杀进程）时
                        // 旧 jar 的静态状态与全局功能（如弹幕搜索等）仍会残留，
                        // 只有重启进程才能彻底卸载旧 jar。
                        navigator.goSplash(this@SettingActivity, useCache)
                        finish()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } else {
                        navigator.goSplash(this@SettingActivity, useCache)
                        finish()
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun initData() {
        viewModel.loadConfig()
        homeSourceKey = apiConfig.homeSourceBean.key
        currentApi = preferences.getString(AppConstants.API_URL, "")
        currentLive = preferences.getString(AppConstants.LIVE_URL, "")
        currentEpg = preferences.getString(AppConstants.EPG_URL, "")
        dnsOpt = preferences.getInt(AppConstants.DOH_URL, 0)

        binding.tvApi.text = viewModel.apiUrl.value
        binding.tvHomeApi.text = apiConfig.homeSourceBean.name
        binding.tvCastState.text = if (preferences.getBoolean(AppConstants.CAST_ENABLE, true)) getString(uiR.string.state_on) else getString(uiR.string.state_off)

        collectStarted(viewModel.apiUrl) { binding.tvApi.text = it }
        collectStarted(viewModel.homeShowSource) {
            binding.tvHomeShow.text = if (it) getString(uiR.string.state_on) else getString(uiR.string.state_off)
        }
        collectStarted(viewModel.homeRec) { binding.tvHomeRec.text = getHomeRecTypeName(it) }
        collectStarted(viewModel.historyNum) { binding.tvHomeNum.text = HistoryHelper.getHomeRecName(this, it) }
        collectStarted(viewModel.showPreview) {
            binding.showPreviewText.text = getDetailPageModeName(it)
        }
        collectStarted(viewModel.playScale) {
            binding.tvScaleType.text = scaleNames[it.coerceIn(0, scaleNames.lastIndex)]
        }
        collectStarted(viewModel.backgroundPlayType) {
            binding.tvBackgroundPlayType.text = bgPlayNames[it.coerceIn(0, bgPlayNames.lastIndex)]
        }
        collectStarted(viewModel.videoPurify) {
            binding.tvVideoPurifyText.text = if (it) getString(uiR.string.state_on) else getString(uiR.string.state_off)
        }
        collectStarted(viewModel.homeDefaultShow) {
            binding.tvHomeDefaultShow.text = if (it) getString(uiR.string.state_on) else getString(uiR.string.state_off)
        }
        collectStarted(viewModel.updateCheckEnable) {
            binding.tvUpdateCheckEnable.text = if (it) getString(uiR.string.state_on) else getString(uiR.string.state_off)
            updateEnabledState()
        }
        collectStarted(viewModel.updateCheckStartup) {
            binding.tvUpdateCheckStartup.text = if (it) getString(uiR.string.state_on) else getString(uiR.string.state_off)
        }
        collectStarted(viewModel.updateCheckInterval) {
            binding.tvUpdateCheckInterval.text = getIntervalDisplay(it)
        }
        collectStarted(viewModel.updateCheckWifiOnly) {
            binding.tvUpdateCheckWifiOnly.text = if (it) getString(uiR.string.state_on) else getString(uiR.string.state_off)
        }
        collectStarted(viewModel.locale) {
            binding.tvLocale.text = if (it == 0) getString(uiR.string.lang_zh) else getString(uiR.string.lang_en)
        }
        collectStarted(viewModel.themeIndex) {
            binding.tvTheme.text = themeNames[it.coerceIn(0, themeNames.lastIndex)]
        }
        collectStarted(viewModel.searchView) {
            binding.tvSearchView.text = if (it == 0) getString(uiR.string.search_view_text) else getString(uiR.string.search_view_thumb)
        }
        collectStarted(viewModel.fastSearch) {
            binding.showFastSearchText.text = if (it) getString(uiR.string.fast_search_on) else getString(uiR.string.fast_search_off)
        }
        collectStarted(viewModel.dohIndex) {
            binding.tvDns.text = getDohName(it)
        }
        collectStarted(EventHub.events) { event ->
            if (event is RefreshEvent && event.type == RefreshEvent.TYPE_APP_REFRESH) {
                cacheCleared = true
            }
        }
    }

    // ==================== 点击事件绑定 ====================

    private fun bindClickListeners() {
        // 订阅地址
        binding.llApi.setOnClickListener {
            controlManager.startServer()
            // 配置重载后偏好中的直播/EPG 地址可能已更新，先刷新内存值再展示
            viewModel.refreshUrlFlows()
            ApiDialog(this, viewModel, controlManager).show()
        }
        // 订阅历史
        binding.llApiHistory.setOnClickListener {
            val history = HawkListHelper.getList(AppConstants.API_HISTORY)
            if (history.isEmpty()) {
                Toast.makeText(this, getString(uiR.string.toast_no_api_history), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val current = preferences.getString(AppConstants.API_URL, "")
            if (history.contains(current)) {
                history.remove(current)
                history.add(0, current)
            }
            ApiHistoryDialog(this, AppConstants.API_HISTORY, getString(uiR.string.dia_history_list)) { api ->
                if (api != preferences.getString(AppConstants.API_URL, "")) {
                    lifecycleScope.launch {
                        viewModel.saveApiUrl(api)
                        binding.tvApi.text = api
                        Toast.makeText(this@SettingActivity, getString(uiR.string.toast_cache_cleared), Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
        }
        // 多仓线路选择：读取已保存线路列表弹窗切换点播接口（复用首页线路弹窗样式）
        binding.llApiLine.setOnClickListener { showApiLineDialog() }
        // 首页站点：复用首页 MENU 键的源切换弹窗（多列网格 + 动态宽度），
        // 选中后仅更新显示，返回设置页时统一检测变更并重载
        binding.llHomeApi.setOnClickListener {
            val sites = apiConfig.getSourceBeanList().filter { it.hide == 0 }
            if (sites.isEmpty()) {
                Toast.makeText(this, getString(uiR.string.toast_no_available_source), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val current = apiConfig.homeSourceBean
            // 源数/20 决定额外列数与弹窗宽度（spanCount 0~2，列数 1~3，宽度 380+200*spanCount mm）
            val extraSpan = minOf(2, (sites.size / 20.0).toInt())
            SelectDialog(
                this,
                getString(uiR.string.dia_source),
                sites,
                { it.name ?: it.key ?: "" },
                extraSpan + 1,
                sites.indexOfFirst { it.key == current.key }.coerceAtLeast(0),
                380 + 200 * extraSpan
            ) { bean, _ ->
                apiConfig.setSourceBean(bean)
                binding.tvHomeApi.text = apiConfig.homeSourceBean.name
            }.show()
        }
        // 站点显示
        binding.llHomeShow.setOnClickListener {
            lifecycleScope.launch { viewModel.saveHomeShowSource(!viewModel.homeShowSource.value) }
        }
        // 首页多行样式开关（是/否，true=多行 false=单行）
        binding.tvHomeRecStyle.text =
            if (preferences.getBoolean(AppConstants.HOME_REC_STYLE, false)) getString(uiR.string.hm_yes)
            else getString(uiR.string.hm_no)
        binding.llHomeRecStyle.setOnClickListener {
            val multiLine = !preferences.getBoolean(AppConstants.HOME_REC_STYLE, false)
            lifecycleScope.launch { preferences.put(AppConstants.HOME_REC_STYLE, multiLine) }
            binding.tvHomeRecStyle.text = if (multiLine) getString(uiR.string.hm_yes) else getString(uiR.string.hm_no)
        }
        // 首页推荐类型
        binding.llHomeRec.setOnClickListener {
            val old = viewModel.homeRec.value
            val dialog = SelectDialog(
                this,
                getString(uiR.string.dia_hm_type),
                listOf(
                    getString(uiR.string.rec_douban),
                    getString(uiR.string.rec_site),
                    getString(uiR.string.rec_history)
                ),
                { it },
                1,
                old.coerceIn(0, 2)
            ) { _, pos ->
                lifecycleScope.launch { viewModel.saveHomeRec(pos) }
            }
            dialog.setOnDismissListener {
                if (old != viewModel.homeRec.value) {
                    EventHub.post(RefreshEvent(RefreshEvent.TYPE_HOME_REC_CHANGE))
                }
            }
            dialog.show()
        }
        // 历史保留条数
        binding.llHomeNum.setOnClickListener {
            val names = (0..4).map { HistoryHelper.getHomeRecName(this, it) }.toTypedArray()
            SelectDialog(this, getString(uiR.string.dia_history), names.toList(), { it }, 1, viewModel.historyNum.value.coerceIn(0, 4)) { _, pos ->
                lifecycleScope.launch { viewModel.saveHistoryNum(pos) }
            }.show()
        }
        // 详情页模式
        binding.showPreview.setOnClickListener {
            val current = viewModel.showPreview.value
            SelectDialog(
                this,
                getString(uiR.string.dia_detail_page),
                listOf(getString(uiR.string.detail_page_preview), getString(uiR.string.detail_page_poster)),
                { it },
                1,
                if (current) 0 else 1
            ) { _, pos ->
                lifecycleScope.launch { viewModel.saveShowPreview(pos == 0) }
            }.show()
        }
        // 画面缩放
        binding.llScale.setOnClickListener {
            SelectDialog(this, getString(uiR.string.dia_ratio), scaleNames.toList(), { it }, 1, viewModel.playScale.value.coerceIn(0, 5)) { _, pos ->
                lifecycleScope.launch { viewModel.savePlayScale(pos) }
            }.show()
        }
        // 后台播放
        binding.llBackgroundPlay.setOnClickListener {
            SelectDialog(this, getString(uiR.string.dia_select), bgPlayNames.toList(), { it }, 1, viewModel.backgroundPlayType.value.coerceIn(0, 2)) { _, pos ->
                lifecycleScope.launch { viewModel.saveBackgroundPlayType(pos) }
            }.show()
        }
        // 播放器设置
        binding.llMediaSetting.setOnClickListener {
            MediaSettingDialog(this, apiConfig).show()
        }
        // 广告过滤
        binding.llVideoPurify.setOnClickListener {
            lifecycleScope.launch { viewModel.saveVideoPurify(!viewModel.videoPurify.value) }
        }
        // 安全 DNS
        binding.llDns.setOnClickListener {
            val names = listOf(
                getString(uiR.string.doh_system), getString(uiR.string.doh_tencent),
                getString(uiR.string.doh_ali), getString(uiR.string.doh_360),
                getString(uiR.string.doh_google), getString(uiR.string.doh_adguard),
                getString(uiR.string.doh_quad9)
            )
            SelectDialog(this, getString(uiR.string.dia_dns), names, { it }, 1, viewModel.dohIndex.value.coerceIn(0, names.lastIndex)) { _, pos ->
                lifecycleScope.launch { viewModel.saveDohIndex(pos) }
            }.show()
        }
        // 数据备份还原
        binding.llBackup.setOnClickListener {
            BackupDialog(this, appDataManager, preferences).show()
        }
        // 清理-重置
        binding.llReset.setOnClickListener {
            CleanResetDialog(this, apiConfig, cacheManager, videoUpdateChecker, roomDataManger).show()
        }
        // 壁纸设置（内含：换张壁纸 / 本地文件 / 壁纸虚化 / 重置壁纸）
        binding.llWp.setOnClickListener {
            WallpaperDialog(this, this, apiConfig, okHttpHelper).show()
        }
        // 投屏设置（允许接收投屏开关 / 投屏设备名），开关变化实时同步显示状态
        binding.llCast.setOnClickListener {
            CastDialog(this) {
                binding.tvCastState.text =
                    if (preferences.getBoolean(AppConstants.CAST_ENABLE, true)) getString(uiR.string.state_on)
                    else getString(uiR.string.state_off)
            }.show()
        }
        // 搜索展示
        binding.llSearchView.setOnClickListener {
            SelectDialog(this, getString(uiR.string.dia_search), listOf(
                getString(uiR.string.search_view_text),
                getString(uiR.string.search_view_thumb)
            ), { it }, 1, viewModel.searchView.value.coerceIn(0, 1)) { _, pos ->
                lifecycleScope.launch { viewModel.saveSearchView(pos) }
            }.show()
        }
        // 聚搜
        binding.showFastSearch.setOnClickListener {
            lifecycleScope.launch { viewModel.saveFastSearch(!viewModel.fastSearch.value) }
        }
        // 语言
        binding.llLocale.setOnClickListener {
            val chkLang = viewModel.locale.value
            val dialog = SelectDialog(
                this,
                getString(uiR.string.dia_locale),
                listOf(getString(uiR.string.lang_zh), getString(uiR.string.lang_en)),
                { it },
                1,
                viewModel.locale.value.coerceIn(0, 1)
            ) { _, pos ->
                lifecycleScope.launch { viewModel.saveLocale(pos) }
            }
            dialog.setOnDismissListener {
                if (chkLang != viewModel.locale.value) reloadActivity()
            }
            dialog.show()
        }
        // 主题
        binding.llTheme.setOnClickListener {
            val chkTheme = viewModel.themeIndex.value
            val dialog = SelectDialog(
                this,
                getString(uiR.string.dia_theme),
                themeNames.toList(),
                { it },
                1,
                viewModel.themeIndex.value.coerceIn(0, themeNames.lastIndex)
            ) { _, pos ->
                lifecycleScope.launch { viewModel.saveTheme(pos) }
            }
            dialog.setOnDismissListener {
                if (chkTheme != viewModel.themeIndex.value) reloadActivity()
            }
            dialog.show()
        }
        // 关于
        binding.llAbout.setOnClickListener { showAboutDialog() }
        // 启动时进直播
        binding.llHomeLive.setOnClickListener {
            lifecycleScope.launch { viewModel.saveHomeDefaultShow(!viewModel.homeDefaultShow.value) }
        }
        // 更新检测总开关
        binding.llUpdateCheckEnable.setOnClickListener {
            val dialog = SelectDialog(
                this,
                getString(uiR.string.update_check_enable),
                listOf(getString(uiR.string.state_on), getString(uiR.string.state_off)),
                { it },
                1,
                if (viewModel.updateCheckEnable.value) 0 else 1
            ) { _, pos ->
                lifecycleScope.launch {
                    viewModel.saveUpdateCheckEnable(pos == 0)
                    updateEnabledState()
                }
            }
            dialog.show()
        }
        // 启动时检测
        binding.llUpdateCheckStartup.setOnClickListener {
            val dialog = SelectDialog(
                this,
                getString(uiR.string.update_check_startup),
                listOf(getString(uiR.string.state_on), getString(uiR.string.state_off)),
                { it },
                1,
                if (viewModel.updateCheckStartup.value) 0 else 1
            ) { _, pos ->
                lifecycleScope.launch {
                    viewModel.saveUpdateCheckStartup(pos == 0)
                    checkAndDisableFeature()
                }
            }
            dialog.show()
        }
        // 检测间隔
        binding.llUpdateCheckInterval.setOnClickListener {
            val currentIdx = updateIntervalValues
                .indexOfFirst { it == viewModel.updateCheckInterval.value }
                .coerceAtLeast(0)
            SelectDialog(this, getString(uiR.string.update_check_interval), updateIntervalNames.toList(), { it }, 1, currentIdx) { _, pos ->
                lifecycleScope.launch {
                    viewModel.saveUpdateCheckInterval(updateIntervalValues[pos])
                    checkAndDisableFeature()
                }
            }.show()
        }
        // 仅 WiFi 检测
        binding.llUpdateCheckWifiOnly.setOnClickListener {
            val dialog = SelectDialog(
                this,
                getString(uiR.string.update_check_wifi_only),
                listOf(getString(uiR.string.state_on), getString(uiR.string.state_off)),
                { it },
                1,
                if (viewModel.updateCheckWifiOnly.value) 0 else 1
            ) { _, pos ->
                lifecycleScope.launch { viewModel.saveUpdateCheckWifiOnly(pos == 0) }
            }
            dialog.show()
        }
    }

    // ==================== 多仓线路选择 ====================

    /** 多仓（接口合集）线路选择：读取已保存线路列表弹窗切换点播接口（复用首页线路弹窗样式） */
    private fun showApiLineDialog() {
        val lines = viewModel.readApiLines()
        if (lines.isEmpty()) {
            Toast.makeText(this, getString(uiR.string.toast_no_api_line), Toast.LENGTH_SHORT).show()
            return
        }
        val current = preferences.getString(AppConstants.API_URL, "")
        // 网格排列：线路少时 2 列，多于 14 条时 3 列，避免单竖排
        val spanCount = minOf(3, (lines.size / 5.0).toInt().coerceAtLeast(2))
        SelectDialog(
            this,
            getString(uiR.string.dia_api_line),
            lines,
            { it.first },
            select = lines.indexOfFirst { it.second == current }.coerceAtLeast(0),
            spanCount = spanCount,
            widthMm = 380 + 150 * (spanCount - 1)
        ) { line, _ ->
            if (line.second != current) {
                lifecycleScope.launch {
                    viewModel.saveApiUrl(line.second)
                    binding.tvApi.text = line.second
                    Toast.makeText(this@SettingActivity, getString(uiR.string.toast_cache_cleared), Toast.LENGTH_SHORT).show()
                }
            }
        }.show()
    }

    // ==================== 更新检测联动 ====================

    private fun updateEnabledState() {
        val enabled = viewModel.updateCheckEnable.value
        val alpha = if (enabled) 1.0f else 0.5f
        binding.llUpdateCheckWifiOnly.alpha = alpha
        binding.llUpdateCheckStartup.alpha = alpha
        binding.llUpdateCheckInterval.alpha = alpha
        binding.llUpdateCheckWifiOnly.isEnabled = enabled
        binding.llUpdateCheckStartup.isEnabled = enabled
        binding.llUpdateCheckInterval.isEnabled = enabled
    }

    /** 启动检测关闭且检测间隔关闭时，自动关闭总开关 */
    private fun checkAndDisableFeature() {
        if (!viewModel.updateCheckStartup.value &&
            viewModel.updateCheckInterval.value == VideoUpdateChecker.INTERVAL_OFF
        ) {
            lifecycleScope.launch { viewModel.saveUpdateCheckEnable(false) }
            updateEnabledState()
        }
    }

    // ==================== 关于对话框 ====================

    private fun showAboutDialog() {
        AboutDialog(this).show()
    }

    // ==================== 语言/主题变更后重建 ====================

    private fun reloadActivity() {
        if (isFinishing || isDestroyed) return
        navigator.goSplash(this, true)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    // ==================== 显示名称 ====================

    private fun getHomeRecTypeName(type: Int): String = when (type) {
        1 -> getString(uiR.string.rec_site)
        2 -> getString(uiR.string.rec_history)
        else -> getString(uiR.string.rec_douban)
    }

    private fun getDetailPageModeName(isPreview: Boolean): String =
        if (isPreview) getString(uiR.string.detail_page_preview) else getString(uiR.string.detail_page_poster)

    private fun getIntervalDisplay(interval: Int): String = when (interval) {
        VideoUpdateChecker.INTERVAL_30MIN -> getString(uiR.string.interval_30)
        VideoUpdateChecker.INTERVAL_1HOUR -> getString(uiR.string.interval_1h)
        VideoUpdateChecker.INTERVAL_2HOURS -> getString(uiR.string.interval_2h)
        else -> getString(uiR.string.interval_off)
    }

    /** 安全 DNS 名称本地化（索引与 DohUtil 枚举顺序一致） */
    private fun getDohName(index: Int): String {
        val names = listOf(
            uiR.string.doh_system, uiR.string.doh_tencent, uiR.string.doh_ali,
            uiR.string.doh_360, uiR.string.doh_google, uiR.string.doh_adguard,
            uiR.string.doh_quad9
        )
        return getString(names.getOrElse(index.coerceIn(0, names.lastIndex)) { uiR.string.doh_system })
    }
}
