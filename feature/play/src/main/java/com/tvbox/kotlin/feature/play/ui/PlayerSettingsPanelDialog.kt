package com.tvbox.kotlin.feature.play.ui

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.player.PlayerConstants
import com.tvbox.kotlin.core.player.PlayerHelper
import com.tvbox.kotlin.core.player.effect.anime4k.Anime4kEffects
import com.tvbox.kotlin.core.player.effect.anime4k.Anime4kSettings
import com.tvbox.kotlin.core.player.effect.anime4k.Anime4kStatus
import com.tvbox.kotlin.core.player.effect.anime4k.Anime4kTier
import com.tvbox.kotlin.core.player.effect.anime4k.PicturePreset
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.core.ui.dialog.SelectDialog
import com.tvbox.kotlin.feature.play.R
import java.util.Locale

/** 面板打开期间画质增强状态轮询间隔 */
private const val STATUS_REFRESH_INTERVAL_MS = 500L

/** 软解导致画面调节不可用时的置灰透明度 */
private const val SOFT_DECODE_ALPHA = 0.4f

/**
 * 播放器右侧设置面板：左侧分类、右侧功能项，收纳原底栏的字幕/弹幕/画质/音轨/视轨/屏显/缩放/解码器/片头片尾与投屏入口。
 *
 * 依赖宿主 [PlayHost] 提供播放器与状态，[ControllerListenerDelegate] 复用既有菜单行为；
 * 倍速滑块与画质增强即时生效，打开期间暂停控制栏自动隐藏。
 */
class PlayerSettingsPanelDialog(
    context: Context,
    private val host: PlayHost,
    private val delegate: ControllerListenerDelegate,
    /** 打开时默认选中的分类下标：0 播放 / 1 画面 / 2 音视轨 / 3 弹幕字幕 / 4 屏显 / 5 投屏 */
    private val initialPage: Int = 0
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    private lateinit var categories: List<View>
    private lateinit var pages: List<View>
    /** 档位按钮与档位对应关系，用于高亮当前档位 */
    private lateinit var tierViews: List<Pair<View, Anime4kTier>>

    /** 预设按钮与预设枚举的对应关系（完整对齐 AVBox 预设 + 自定义） */
    private val presetViews by lazy {
        listOf(
            R.id.ps_preset_original to PicturePreset.Original,
            R.id.ps_preset_natural to PicturePreset.Natural,
            R.id.ps_preset_vivid to PicturePreset.Vivid,
            R.id.ps_preset_clear to PicturePreset.Clear,
            R.id.ps_preset_bright to PicturePreset.Bright,
            R.id.ps_preset_cinema to PicturePreset.Cinema,
            R.id.ps_preset_soft to PicturePreset.Soft,
            R.id.ps_preset_warm to PicturePreset.Warm,
            R.id.ps_preset_cool to PicturePreset.Cool,
            R.id.ps_preset_comfort to PicturePreset.Comfort,
            R.id.ps_preset_anime to PicturePreset.Anime,
            R.id.ps_preset_sport to PicturePreset.Sport,
            R.id.ps_preset_game to PicturePreset.Game,
            R.id.ps_preset_custom to PicturePreset.Custom
        )
    }

    /** 缩放按钮与缩放类型的对应关系，用于高亮当前缩放 */
    private val scaleViews = listOf(
        R.id.ps_scale_default to PlayerConstants.SCREEN_SCALE_DEFAULT,
        R.id.ps_scale_16_9 to PlayerConstants.SCREEN_SCALE_16_9,
        R.id.ps_scale_4_3 to PlayerConstants.SCREEN_SCALE_4_3,
        R.id.ps_scale_fill to PlayerConstants.SCREEN_SCALE_MATCH_PARENT,
        R.id.ps_scale_original to PlayerConstants.SCREEN_SCALE_ORIGINAL,
        R.id.ps_scale_crop to PlayerConstants.SCREEN_SCALE_CENTER_CROP
    )

    /** 当前选中的预设；手动改档位/锐化/去模糊后回到自定义 */
    private var currentPreset: PicturePreset? = null

    /** 软解直写 Surface 时视频效果链不会被执行，画面调节与增强统一禁用 */
    private var softDecode = false

    /** 上次展示的实际解码方式（true=软解），避免轮询重复 setText */
    private var decodeCurrentSoft: Boolean? = null

    private val prefs: AppPreferences by lazy { AppPreferences.getShared(context) }

    /** 屏显开关行：行 id / 值 id / 偏好键 */
    private val screenDisplayRows = listOf(
        Triple(R.id.ps_row_net_speed, R.id.ps_net_speed_value, AppConstants.SCREEN_DISPLAY_NET_SPEED),
        Triple(R.id.ps_row_seek_time, R.id.ps_seek_time_value, AppConstants.SCREEN_DISPLAY_SEEK_TIME),
        Triple(R.id.ps_row_sys_time, R.id.ps_sys_time_value, AppConstants.SCREEN_DISPLAY_SYS_TIME),
        Triple(R.id.ps_row_battery, R.id.ps_battery_value, AppConstants.SCREEN_DISPLAY_BATTERY)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_player_settings)
        window?.apply {
            setGravity(Gravity.END)
            setLayout(
                context.resources.getDimensionPixelSize(uiR.dimen.vs_640),
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setDimAmount(0f)
        }
        initCategories()
        initPlayPage()
        initScaleButtons()
        initPicturePage()
        initTrackPage()
        initScreenPage()
        initCastPage()
        findViewById<View>(R.id.settings_scrim)?.setOnClickListener { dismiss() }
        findViewById<View>(R.id.settings_close)?.setOnClickListener { dismiss() }
        selectCategory(initialPage.coerceIn(0, pages.lastIndex))
        refreshPlayValues()
        refreshPicture()
    }

    override fun show() {
        super.show()
        val cat = categories.getOrNull(initialPage.coerceIn(0, categories.lastIndex))
        cat?.post { cat.requestFocus() }
    }

    /** 分类栏与内容页一一对应 */
    private fun initCategories() {
        categories = listOf(
            R.id.cat_play, R.id.cat_picture, R.id.cat_audio,
            R.id.cat_danmu, R.id.cat_screen, R.id.cat_cast
        ).map { findViewById<View>(it) }
        pages = listOf(
            R.id.page_play, R.id.page_picture, R.id.page_audio,
            R.id.page_danmu, R.id.page_screen, R.id.page_cast
        ).map { findViewById<View>(it) }
        categories.forEachIndexed { index, view ->
            view.setOnClickListener { selectCategory(index) }
            view.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) selectCategory(index) }
        }
    }

    /** 切换分类：选中项高亮（透明度），内容页只显示当前页 */
    private fun selectCategory(index: Int) {
        categories.forEachIndexed { i, view ->
            view.isActivated = i == index
            view.alpha = if (i == index) 1f else 0.6f
        }
        pages.forEachIndexed { i, view ->
            view.visibility = if (i == index) View.VISIBLE else View.GONE
        }
    }

    // ==================== 播放 ====================

    private fun initPlayPage() {
        val options = PlayViewModel.SPEED_OPTIONS
        val seek = findViewById<SeekBar>(R.id.ps_speed)
        val valueText = findViewById<TextView>(R.id.ps_speed_value)
        val initial = options.indexOfFirst { it >= host.viewModel.speed.value }.takeIf { it >= 0 } ?: 0
        seek.max = options.size - 1
        seek.progress = initial
        valueText.text = speedLabel(options[initial])
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val speed = options[progress.coerceIn(0, options.lastIndex)]
                valueText.text = speedLabel(speed)
                host.viewModel.switchSpeed(speed)
                host.controller.setSpeedText(speed)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                host.controller.pauseAutoHide()
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                host.controller.resumeAutoHide()
            }
        })

        // 解码器切换会打开内核选择弹窗，先关闭面板避免弹窗叠层
        findViewById<View>(R.id.ps_row_player)?.setOnClickListener {
            dismiss()
            delegate.onPlayerClick()
        }

        // 解码模式：新档位在重新播放时由内核渲染器工厂读取，保存后提示用户
        findViewById<View>(R.id.ps_row_decode)?.setOnClickListener {
            val names = decodeModeNames()
            val current = prefs.getInt(AppConstants.MEDIA3_RENDERER_MODE, 0).coerceIn(0, names.lastIndex)
            SelectDialog(
                context,
                context.getString(uiR.string.media_decode_mode),
                names,
                { it },
                1,
                current
            ) { _, pos ->
                prefs.putSync(AppConstants.MEDIA3_RENDERER_MODE, pos)
                refreshDecodeMode()
                Toast.makeText(context, uiR.string.player_decode_mode_restart_tip, Toast.LENGTH_SHORT).show()
            }.show()
        }
        refreshDecodeMode()
        refreshDecodeCurrent(Anime4kStatus.isSoftVideoRenderer(host.videoView.exoPlayer))
    }

    /** 解码模式名称：0 硬解优先、1 软解优先、2 仅硬解、3 仅软解 */
    private fun decodeModeNames(): List<String> = listOf(
        uiR.string.media_decode_hard,
        uiR.string.media_decode_soft,
        uiR.string.media_decode_hard_only,
        uiR.string.media_decode_soft_only
    ).map { context.getString(it) }

    private fun refreshDecodeMode() {
        val names = decodeModeNames()
        val current = prefs.getInt(AppConstants.MEDIA3_RENDERER_MODE, 0).coerceIn(0, names.lastIndex)
        findViewById<TextView>(R.id.ps_decode_value)?.text = names[current]
    }

    /**
     * 刷新“当前解码方式”提示：配置的解码模式是意愿档位，实际生效的渲染器可能因
     * 硬解不支持回落到 FFmpeg 软解，这里按当前活动视频渲染器展示真实状态。
     */
    private fun refreshDecodeCurrent(soft: Boolean) {
        if (soft == decodeCurrentSoft) return
        decodeCurrentSoft = soft
        findViewById<TextView>(R.id.ps_decode_current)?.text = context.getString(
            if (soft) uiR.string.media_decode_current_soft else uiR.string.media_decode_current_hard
        )
    }

    private fun refreshPlayValues() {
        findViewById<TextView>(R.id.ps_player_value)?.text =
            PlayerHelper.getPlayerName(host.viewModel.playerType.value)
        refreshScale()
        refreshScreenDisplay()
    }

    private fun refreshScale() {
        val current = host.viewModel.scaleType.value
        scaleViews.forEach { (viewId, type) ->
            val view = findViewById<View>(viewId) ?: return@forEach
            val selected = type == current
            view.isActivated = selected
            view.alpha = if (selected) 1f else 0.6f
        }
    }

    /** 画面缩放按钮：与画面预设一致，点击即生效并刷新选中态 */
    private fun initScaleButtons() {
        scaleViews.forEach { (viewId, type) ->
            val view = findViewById<TextView>(viewId) ?: return@forEach
            view.text = PlayerHelper.getScaleName(type)
            view.setOnClickListener {
                host.viewModel.switchScale(type)
                host.controller.setScaleText(PlayerHelper.getScaleName(type))
                refreshScale()
            }
        }
    }

    private fun refreshScreenDisplay() {
        screenDisplayRows.forEach { (_, valueId, key) ->
            findViewById<TextView>(valueId)?.text = context.getString(
                if (prefs.getBoolean(key, true)) uiR.string.state_on else uiR.string.state_off
            )
        }
    }

    // ==================== 画面 ====================

    private fun initPicturePage() {
        initPresetPage()
        findViewById<View>(R.id.ps_row_anime4k)?.setOnClickListener {
            if (blockedBySoftDecode()) return@setOnClickListener
            val enabling = !Anime4kSettings.isEnabledPref(context)
            Anime4kSettings.setEnabled(context, enabling)
            // 开关改变 pass 结构，必须重建效果链
            Anime4kEffects.applyTo(host.videoView)
            refreshPicture()
        }
        // 档位改为横向选项直接选择，避免逐档点击循环
        tierViews = listOf(
            R.id.ps_tier_light to Anime4kTier.Light,
            R.id.ps_tier_standard to Anime4kTier.Standard,
            R.id.ps_tier_high to Anime4kTier.High,
            R.id.ps_tier_ultra to Anime4kTier.Ultra,
            R.id.ps_tier_upscale to Anime4kTier.UpscaleOnly
        ).map { (id, tier) ->
            val view = findViewById<View>(id)
            (view as TextView).text = tier.label
            view.setOnClickListener {
                if (blockedBySoftDecode()) return@setOnClickListener
                Anime4kTier.set(context, tier)
                Anime4kEffects.applyTo(host.videoView)
                refreshPicture()
            }
            view to tier
        }
        findViewById<View>(R.id.ps_row_deblur)?.setOnClickListener {
            if (blockedBySoftDecode()) return@setOnClickListener
            Anime4kSettings.setDeblur(context, !Anime4kSettings.isDeblurPref(context))
            Anime4kEffects.applyTo(host.videoView)
            refreshPicture()
        }
    }

    /** 画面预设：普通预设一键应用；自定义打开 AVBox 风格的自定义调节对话框 */
    private fun initPresetPage() {
        currentPreset = Anime4kSettings.currentPreset(context)
        presetViews.forEach { (viewId, preset) ->
            findViewById<View>(viewId)?.setOnClickListener {
                if (blockedBySoftDecode()) return@setOnClickListener
                if (preset == PicturePreset.Custom) {
                    PictureCustomDialog(host) {
                        currentPreset = PicturePreset.Custom
                        applyPictureParams()
                        refreshPicture()
                    }.show()
                    return@setOnClickListener
                }
                Anime4kSettings.applyPreset(context, preset)
                currentPreset = preset
                applyPictureParams()
                refreshPicture()
            }
        }
    }

    /** 刷新预设按钮选中态 */
    private fun refreshPreset() {
        val dim = if (softDecode) SOFT_DECODE_ALPHA else 1f
        presetViews.forEach { (viewId, preset) ->
            val view = findViewById<View>(viewId) ?: return@forEach
            val selected = preset == currentPreset
            view.isActivated = selected
            view.alpha = (if (selected) 1f else 0.6f) * dim
        }
    }

    /** 刷新增强实际生效状态：构建在 GL 线程异步完成，面板打开期间由轮询持续更新 */
    private fun refreshEffectStatus() {
        val status = findViewById<TextView>(R.id.ps_anime4k_status) ?: return
        // 软解状态会随起播/切轨变化，发现不一致时只重绘置灰态，不重排轮询
        val soft = Anime4kStatus.isSoftVideoRenderer(host.videoView.exoPlayer)
        if (soft != softDecode) {
            softDecode = soft
            applyPictureState()
        }
        refreshDecodeCurrent(soft)
        status.text = when {
            !Anime4kSettings.isEnabledPref(context) ->
                context.getString(uiR.string.quality_status_off)
            // FFmpeg 软解直接写 Surface，不经过视频效果链，增强不可能生效
            softDecode ->
                context.getString(uiR.string.quality_status_soft_decode)
            Anime4kStatus.unavailable() ->
                context.getString(uiR.string.quality_status_failed)
            Anime4kStatus.isEffective() ->
                context.getString(uiR.string.quality_status_effective, Anime4kStatus.lastPassCount)
            // 暂停时不会产生新帧，构建/绘制都停在原地，直接提示恢复播放后生效
            !host.videoView.isPlaying ->
                context.getString(uiR.string.quality_status_pending)
            Anime4kStatus.lastPassCount > 0 ->
                context.getString(uiR.string.quality_status_waiting)
            else ->
                context.getString(uiR.string.quality_status_building)
        }
    }

    /**
     * 持续刷新状态：着色器编译与首帧绘制在 GL 线程异步完成，耗时不定（实测 2 秒以上），
     * 单次延时读取会一直停在“编译中/等待画面刷新”，改为面板打开期间轮询直到状态自动翻转。
     */
    private fun scheduleEffectStatusRefresh() {
        val status = findViewById<TextView>(R.id.ps_anime4k_status) ?: return
        status.removeCallbacks(refreshStatusRunnable)
        status.postDelayed(refreshStatusRunnable, STATUS_REFRESH_INTERVAL_MS)
    }

    private val refreshStatusRunnable = object : Runnable {
        override fun run() {
            if (!isShowing) return
            refreshEffectStatus()
            findViewById<TextView>(R.id.ps_anime4k_status)
                ?.postDelayed(this, STATUS_REFRESH_INTERVAL_MS)
        }
    }

    private fun refreshPicture() {
        softDecode = Anime4kStatus.isSoftVideoRenderer(host.videoView.exoPlayer)
        applyPictureState()
        refreshEffectStatus()
        scheduleEffectStatusRefresh()
    }

    /** 按当前设置与软解状态重绘画面页：开关文本、档位选中态、置灰透明度 */
    private fun applyPictureState() {
        val dim = if (softDecode) SOFT_DECODE_ALPHA else 1f
        findViewById<TextView>(R.id.ps_anime4k_value)?.text = context.getString(
            if (Anime4kSettings.isEnabledPref(context)) uiR.string.state_on else uiR.string.state_off
        )
        findViewById<View>(R.id.ps_row_anime4k)?.alpha = dim
        val current = Anime4kTier.current(context)
        tierViews.forEach { (view, tier) ->
            view.isActivated = tier == current
            view.alpha = (if (tier == current) 1f else 0.5f) * dim
        }
        findViewById<TextView>(R.id.ps_deblur_value)?.text = context.getString(
            if (Anime4kSettings.isDeblurPref(context)) uiR.string.state_on else uiR.string.state_off
        )
        findViewById<View>(R.id.ps_row_deblur)?.alpha = dim
        refreshPreset()
    }

    /** 软解下视频效果链不会被执行：拦截画面调节与增强的修改，只提示原因 */
    private fun blockedBySoftDecode(): Boolean {
        if (!softDecode) return false
        Toast.makeText(context, uiR.string.quality_soft_decode_tip, Toast.LENGTH_SHORT).show()
        return true
    }

    /**
     * 画面预设/色彩/锐度变化：这些参数由 PictureColorEffect 逐帧读取，
     * 效果链结构不变，无需重建，只需让暂停中的画面重绘一帧。
     */
    private fun applyPictureParams() {
        host.videoView.exoPlayer?.let(Anime4kEffects::redrawPausedFrame)
    }

    override fun onStop() {
        findViewById<TextView>(R.id.ps_anime4k_status)?.removeCallbacks(refreshStatusRunnable)
        super.onStop()
    }

    // ==================== 音视频 / 弹幕字幕 ====================

    private fun initTrackPage() {
        // 音轨/视轨入口常显，单轨时也可进入查看当前轨道，不再按轨道数量隐藏
        findViewById<View>(R.id.ps_row_audio)?.setOnClickListener {
            dismiss()
            delegate.onAudioClick()
        }
        findViewById<View>(R.id.ps_row_video)?.setOnClickListener {
            dismiss()
            delegate.onVideoClick()
        }
        findViewById<View>(R.id.ps_row_subtitle)?.setOnClickListener {
            dismiss()
            delegate.onSubtitleClick()
        }
        // 弹幕入口常显：源未提供弹幕字段时，仍可进入设置弹窗使用在线弹幕搜索
        findViewById<View>(R.id.ps_row_danmu)?.setOnClickListener {
            dismiss()
            delegate.onDanmuClick()
        }
    }

    // ==================== 屏显 ====================

    private fun initScreenPage() {
        screenDisplayRows.forEach { (rowId, _, key) ->
            findViewById<View>(rowId)?.setOnClickListener {
                prefs.putSync(key, !prefs.getBoolean(key, true))
                refreshScreenDisplay()
                PlayerMenuHelper.applyScreenDisplay(context, host.controller)
            }
        }
    }

    // ==================== 投屏 ====================

    private fun initCastPage() {
        findViewById<View>(R.id.ps_row_cast)?.setOnClickListener {
            dismiss()
            delegate.onCastClick()
        }
    }

    private fun speedLabel(speed: Float): String =
        String.format(Locale.getDefault(), "x%.2f", speed)
}
