package com.tvbox.kotlin.feature.live.ui

import android.annotation.TargetApi
import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.bean.Epginfo
import com.tvbox.kotlin.core.common.bean.LiveChannelItem
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.HawkListHelper
import com.tvbox.kotlin.core.player.MyVideoView
import com.tvbox.kotlin.core.player.PlayerConstants
import com.tvbox.kotlin.core.player.PlayerHelper
import com.tvbox.kotlin.core.ui.BaseBindingActivity
import com.tvbox.kotlin.core.ui.CoilUtils
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.feature.live.R
import com.tvbox.kotlin.feature.live.databinding.ActivityLivePlayBinding
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 直播页：全屏播放、频道组/频道列表、频道号输入、多源切换、EPG 节目单、
 * 直播设置、频道收藏与上次频道记忆。
 * 上下方向键切台，左右方向键左显隐底部EPG/右换源，确认键弹频道列表
 * 数字键输入频道号，2 秒无输入自动跳转
 * 菜单键弹出设置面板
 */
@AndroidEntryPoint
class LivePlayActivity : BaseBindingActivity<ActivityLivePlayBinding>() {

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var playerHelper: PlayerHelper

    private val viewModel: LiveViewModel by viewModels()

    private lateinit var controller: LiveControlView

    private lateinit var groupAdapter: LiveChannelGroupAdapter
    private lateinit var channelAdapter: LiveChannelAdapter
    private lateinit var settingGroupAdapter: LiveSettingGroupAdapter
    private lateinit var settingItemAdapter: LiveSettingItemAdapter
    private lateinit var epgAdapter: LiveEpgAdapter
    private lateinit var epgDateAdapter: LiveEpgDateAdapter

    private val settingGroups = ArrayList<LiveSettingGroup>()

    private val handler = Handler(Looper.getMainLooper())

    /** 频道号输入缓冲 */
    private var selectedChannelNumber = 0

    /** 双击退出计时 */
    private var exitTime = 0L

    /** 超时换源累计次数 */
    private var changeSourceTimes = 0

    /** 当前频道所有线路已试完仍失败：停止自动切源并提示一次，等待用户手动操作 */
    private var allSourcesFailed = false

    /** EPG 日期初始化标记 */
    private var epgDateInited = false

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override val layoutId = com.tvbox.kotlin.feature.live.R.layout.activity_live_play

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun initView(savedInstanceState: Bundle?) {
        hideSystemUI()
        setupBackPress()

        initVideoView()
        initChannelGroupView()
        initChannelView()
        initSettingGroupView()
        initSettingItemView()
        initEpgDateView()
        initEpgListView()
        initSettingGroupList()

        binding.tvLeftChannelListLayout.visibility = View.INVISIBLE
        binding.tvRightSettingLayout.visibility = View.INVISIBLE
        binding.tvBottomLayout.visibility = View.INVISIBLE

        binding.mDivLeft.setOnClickListener { divLoadEpgL() }
        binding.mDivRight.setOnClickListener { divLoadEpgR() }

        // Intent 初始频道
        intent?.extras?.let { b ->
            val g = b.getInt("groupIndex", -1)
            val c = b.getInt("channelIndex", -1)
            if (g >= 0 && c >= 0) viewModel.setInitialChannel(g, c)
        }
    }

    override fun initData() {
        observeFlows()
        viewModel.loadChannels()
        // 按偏好恢复右上角时间/网速显示（init 中 showTime/showNetSpeed），
        // 否则重启进直播后已开启的显示项不出现，或残留占位文本
        showTime()
        showNetSpeed()
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized && binding.mVideoView.isPlaying.not()) {
            binding.mVideoView.resume()
        }
        checkAndUpdateEpgDate()
        handler.post(updateTimeRun)
    }

    override fun onPause() {
        super.onPause()
        binding.mVideoView.pause()
        handler.removeCallbacks(updateTimeRun)
        handler.removeCallbacks(updateNetSpeedRun)
        handler.removeCallbacks(updateBufferNetSpeedRun)
    }

    /** 后台播放类型为 2（画中画）且系统支持时，离开页面进入画中画 */
    @TargetApi(Build.VERSION_CODES.O)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (appPreferences.getInt(AppConstants.BACKGROUND_PLAY_TYPE, 0) != 2) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        binding.mVideoView.release()
    }

    // ------------------------------------------------------------------
    // 系统与返回
    // ------------------------------------------------------------------

    /** 全屏沉浸式：隐藏系统栏并让内容延伸到刘海/挖孔区域。
     * Android 11+ 用 WindowInsetsController + setDecorFitsSystemWindows(false)，
     * Android 9/10 需显式 SHORT_EDGES。 */
    private fun hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = lp
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val controller = window.insetsController
                controller?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                // 必须关闭系统窗口适配，否则全屏内容无法延伸到刘海/挖孔区域
                window.setDecorFitsSystemWindows(false)
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            } else {
                window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            }
        } catch (_: Throwable) {
        }
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.tvLeftChannelListLayout.visibility == View.VISIBLE -> hideChannelList()
                    binding.tvRightSettingLayout.visibility == View.VISIBLE -> hideSettingLayout()
                    binding.tvBottomLayout.visibility == View.VISIBLE -> hideChannelInfo()
                    else -> exitLive()
                }
            }
        })
    }

    private fun exitLive() {
        if (System.currentTimeMillis() - exitTime < 2000) {
            finish()
        } else {
            exitTime = System.currentTimeMillis()
            Toast.makeText(this, getString(uiR.string.hm_exit_live), Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // 播放器
    // ------------------------------------------------------------------

    private fun initVideoView() {
        controller = LiveControlView(this)
        controller.setListener(object : LiveControlView.Listener {
            override fun singleTap(e: MotionEvent): Boolean {
                val fiveScreen = resources.displayMetrics.widthPixels / 5
                when {
                    e.x < fiveScreen * 2 -> showChannelList()
                    e.x < fiveScreen * 3 -> toggleChannelInfo()
                    else -> showSettingGroup()
                }
                return true
            }

            override fun longPress() = showSettingGroup()

            override fun playStateChanged(playState: Int) = handlePlayState(playState)

            override fun changeSource(direction: Int) {
                if (direction > 0) viewModel.playNextSource() else viewModel.playPreSource()
            }

            override fun togglePlay() {
                if (binding.mVideoView.isPlaying) {
                    binding.mVideoView.pause()
                } else {
                    binding.mVideoView.start()
                }
            }
        })
        controller.setDoubleTapTogglePlayEnabled(false)
        binding.mVideoView.setVideoController(controller)
        // 播放状态由宿主转发（MyVideoView 生命周期 → 控制器/超时逻辑）
        binding.mVideoView.onPlayStateChange = { state ->
            controller.onPlayStateChanged(state)
            // 缓冲时右上角显示网速，播放中隐藏
            updateTopRightNetSpeed(state)
        }
        // 显式装配 Exo 播放内核，避免依赖全局 PlayerFactory 隐式状态导致首次进直播崩溃
        updatePlayerCfg()
    }

    /** 装配 Exo 内核与渲染；直播内核恒定 Exo（系统内核已移除，历史档位由 updateCfg 归一兜底） */
    private fun updatePlayerCfg(forcePlayerType: Int = PlayerConstants.PLAYER_TYPE_EXO) {
        playerHelper.updateCfg(binding.mVideoView, null, forcePlayerType)
    }

    private fun handlePlayState(playState: Int) {
        viewModel.updatePlayState(playState)
        handler.removeCallbacks(connectTimeoutChangeSourceRun)
        handler.removeCallbacks(connectTimeoutReplayRun)
        when (playState) {
            PlayerConstants.STATE_PREPARING, PlayerConstants.STATE_BUFFERING -> {
                val timeout = appPreferences.getInt(AppConstants.LIVE_CONNECT_TIMEOUT, 2)
                if (timeout == 0) {
                    handler.postDelayed(connectTimeoutReplayRun, 30_000L)
                } else {
                    handler.postDelayed(connectTimeoutChangeSourceRun, timeout * 5000L)
                }
            }
            PlayerConstants.STATE_PLAYING, PlayerConstants.STATE_BUFFERED, PlayerConstants.STATE_PREPARED -> {
                changeSourceTimes = 0
                allSourcesFailed = false
                updateVideoSize()
            }
            PlayerConstants.STATE_ERROR, PlayerConstants.STATE_PLAYBACK_COMPLETED -> {
                val timeout = appPreferences.getInt(AppConstants.LIVE_CONNECT_TIMEOUT, 2)
                if (timeout == 0) {
                    handler.postDelayed(connectTimeoutReplayRun, 30_000L)
                } else {
                    handler.post(connectTimeoutChangeSourceRun)
                }
            }
        }
    }

    private val connectTimeoutChangeSourceRun = Runnable {
        // 本频道线路已全部失败并提示过：直接结束，避免每个错误事件重复提示
        if (allSourcesFailed) return@Runnable
        val channel = viewModel.currentChannel.value ?: return@Runnable
        changeSourceTimes++
        if (channel.sourceNum > changeSourceTimes) {
            // 仅自动切换到本频道下一路源，不跨频道跳台
            viewModel.playNextSource()
        } else {
            // 本频道所有线路均已失败：停止自动尝试，交由用户手动换台/换源
            changeSourceTimes = 0
            allSourcesFailed = true
            Toast.makeText(
                this,
                getString(uiR.string.toast_live_all_sources_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val connectTimeoutReplayRun = Runnable { replayCurrentChannel() }

    /** 用户手动换台/换源：解除"频道所有线路失败"停止态，允许新一轮自动切源 */
    private fun resetAutoSkip() {
        allSourcesFailed = false
    }

    private fun replayCurrentChannel() {
        val url = viewModel.currentUrl.value
        if (url.isNotEmpty()) {
            binding.mVideoView.release()
            binding.mVideoView.setUrl(url, viewModel.currentHeaders.value)
            binding.mVideoView.start()
        }
    }

    private fun updateVideoSize() {
        val size = binding.mVideoView.videoSize
        if (size != null && size.size >= 2 && size[0] > 0 && size[1] > 0) {
            binding.tvSize.text = "${size[0]} x ${size[1]}"
        }
    }

    // ------------------------------------------------------------------
    // 频道组列表
    // ------------------------------------------------------------------

    private fun initChannelGroupView() {
        binding.mGroupGridView.setHasFixedSize(true)
        binding.mGroupGridView.layoutManager = V7LinearLayoutManager(this, 1, false)
        groupAdapter = LiveChannelGroupAdapter()
        binding.mGroupGridView.adapter = groupAdapter
        // 手机/模拟器触摸点击：直接切换分组
        groupAdapter.setOnItemClickListener { position, _ ->
            selectChannelGroup(position)
        }
        binding.mGroupGridView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, state: Int) {
                resetHideChannelList()
            }
        })
        binding.mGroupGridView.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) = Unit
            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                if (position < 0) return
                // 分组获得焦点即把右侧频道列表切换到对应分组（selectChannelGroup(position, true, -1)），
                // 否则频道列表始终停留在旧分组，无法预览该分组的频道
                selectChannelGroup(position)
                groupAdapter.setFocusedGroupIndex(position)
                resetHideChannelList()
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
                // 触摸点击先把焦点移到该项，保证选中/焦点态可见
                itemView?.requestFocusFromTouch()
                selectChannelGroup(position)
            }
        })
    }

    private fun selectChannelGroup(position: Int) {
        if (position !in viewModel.channelGroups.value.indices) return
        if (viewModel.isNeedPassword(position)) {
            Toast.makeText(this, getString(uiR.string.toast_group_need_password), Toast.LENGTH_SHORT).show()
            return
        }
        // 分组未变化则不重载数据，避免打断频道列表滚动/焦点
        val needLoad = position != groupAdapter.getSelectedGroupIndex()
        viewModel.selectChannelGroup(position)
        groupAdapter.setSelectedGroupIndex(position)
        // 触摸/方向键切换分组时清除频道列表残留的焦点/选中高亮
        channelAdapter.setFocusedChannelIndex(-1)
        binding.mChannelGridView.clearFocus()
        if (needLoad) {
            val channels = viewModel.channelGroups.value.getOrNull(position)?.getLiveChannels()
            channelAdapter.setNewData(channels)
            if (position == viewModel.getPlayingGroupIndex()) {
                // 切回实际播放的分组：滚动并选中播放中的频道（记忆恢复）
                scrollToCurrentChannel()
            } else {
                channelAdapter.setSelectedChannelIndex(-1)
                binding.mChannelGridView.scrollToPosition(0)
            }
        }
        resetHideChannelList()
    }

    // ------------------------------------------------------------------
    // 频道列表
    // ------------------------------------------------------------------

    private fun initChannelView() {
        binding.mChannelGridView.setHasFixedSize(true)
        binding.mChannelGridView.layoutManager = V7LinearLayoutManager(this, 1, false)
        channelAdapter = LiveChannelAdapter()
        binding.mChannelGridView.adapter = channelAdapter
        channelAdapter.setOnItemClickListener { position, _ -> clickLiveChannel(position) }
        binding.mChannelGridView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, state: Int) {
                resetHideChannelList()
            }
        })
        binding.mChannelGridView.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) = Unit
            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                if (position < 0) return
                groupAdapter.setFocusedGroupIndex(-1)
                channelAdapter.setFocusedChannelIndex(position)
                resetHideChannelList()
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
                clickLiveChannel(position)
            }
        })
    }

    private fun clickLiveChannel(position: Int) {
        if (position < 0) return
        channelAdapter.setSelectedChannelIndex(position)
        epgDateAdapter.setSelectedIndex(0)
        hideChannelList()
        viewModel.playChannel(viewModel.currentGroupIndex.value, position, false)
    }

    private fun scrollToCurrentChannel() {
        val groupIndex = viewModel.currentGroupIndex.value
        val channelIndex = viewModel.currentChannel.value?.channelIndex ?: -1
        if (groupIndex >= 0) {
            binding.mGroupGridView.scrollToPosition(groupIndex)
            groupAdapter.setSelectedGroupIndex(groupIndex)
        }
        if (channelIndex >= 0) {
            binding.mChannelGridView.scrollToPosition(channelIndex)
            channelAdapter.setSelectedChannelIndex(channelIndex)
        }
    }

    // ------------------------------------------------------------------
    // 设置面板
    // ------------------------------------------------------------------

    private fun initSettingGroupView() {
        binding.mSettingGroupView.setHasFixedSize(true)
        binding.mSettingGroupView.layoutManager = V7LinearLayoutManager(this, 1, false)
        settingGroupAdapter = LiveSettingGroupAdapter()
        binding.mSettingGroupView.adapter = settingGroupAdapter
        settingGroupAdapter.setOnItemClickListener { position, _ -> selectSettingGroup(position) }
        binding.mSettingGroupView.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) = Unit
            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                settingGroupAdapter.setFocusedGroupIndex(position)
                resetHideSettingLayout()
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
                selectSettingGroup(position)
            }
        })
    }

    private fun initSettingItemView() {
        binding.mSettingItemView.setHasFixedSize(true)
        binding.mSettingItemView.layoutManager = V7LinearLayoutManager(this, 1, false)
        settingItemAdapter = LiveSettingItemAdapter()
        binding.mSettingItemView.adapter = settingItemAdapter
        settingItemAdapter.setOnItemClickListener { position, _ -> clickSettingItem(position) }
        binding.mSettingItemView.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) = Unit
            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                if (position < 0) return
                settingGroupAdapter.setFocusedGroupIndex(-1)
                settingItemAdapter.setFocusedItemIndex(position)
                resetHideSettingLayout()
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
                clickSettingItem(position)
            }
        })
    }

    private fun initSettingGroupList() {
        val groupNames = listOf(getString(uiR.string.live_menu_line), getString(uiR.string.live_menu_ratio), getString(uiR.string.live_menu_timeout), getString(uiR.string.live_menu_pref), getString(uiR.string.live_menu_addr), getString(uiR.string.live_menu_exit))
        val scaleItems = listOf(getString(uiR.string.live_scale_default), "16:9", "4:3", getString(uiR.string.live_scale_fill), getString(uiR.string.live_scale_original), getString(uiR.string.live_scale_crop))
        val timeoutItems = listOf(getString(uiR.string.live_timeout_off), "5s", "10s", "15s", "20s", "25s", "30s")
        val personalItems = listOf(getString(uiR.string.live_pref_show_time), getString(uiR.string.live_pref_show_speed), getString(uiR.string.live_pref_reverse_zap), getString(uiR.string.live_pref_cross_select), getString(uiR.string.live_pref_disable_pwd), getString(uiR.string.live_pref_fav_channel))
        val liveAddrItems = listOf(getString(uiR.string.live_addr_history))
        val allItems = listOf(
            emptyList(),        // 线路选择，运行时动态填充
            scaleItems,
            timeoutItems,
            personalItems,
            liveAddrItems,
            emptyList()         // 退出直播：点击分组即直接退出，无确认项
        )
        settingGroups.clear()
        for (i in groupNames.indices) {
            val group = LiveSettingGroup(i, groupNames[i])
            allItems[i].forEachIndexed { idx, name ->
                group.items.add(LiveSettingItem(idx, name))
            }
            settingGroups.add(group)
        }
        // 恢复偏好选中状态
        val timeoutIdx = appPreferences.getInt(AppConstants.LIVE_CONNECT_TIMEOUT, 2)
        if (timeoutIdx in settingGroups[2].items.indices) settingGroups[2].items[timeoutIdx].selected = true
        settingGroups[3].items[0].selected = appPreferences.getBoolean(AppConstants.LIVE_SHOW_TIME, false)
        settingGroups[3].items[1].selected = appPreferences.getBoolean(AppConstants.LIVE_SHOW_NET_SPEED, false)
        settingGroups[3].items[2].selected = appPreferences.getBoolean(AppConstants.LIVE_CHANNEL_REVERSE, false)
        settingGroups[3].items[3].selected = appPreferences.getBoolean(AppConstants.LIVE_CROSS_GROUP, false)
        settingGroups[3].items[4].selected = appPreferences.getBoolean(AppConstants.LIVE_SKIP_PASSWORD, false)
    }

    private fun selectSettingGroup(position: Int) {
        if (position !in settingGroups.indices) return
        settingGroupAdapter.setSelectedGroupIndex(position)
        when (position) {
            0 -> {
                // 线路选择：用当前频道源名动态填充
                val channel = viewModel.currentChannel.value
                val sourceNames = channel?.channelSourceNames ?: arrayListOf()
                val items = sourceNames.mapIndexed { idx, name -> LiveSettingItem(idx, name) }.toMutableList()
                settingGroups[0].items.clear()
                settingGroups[0].items.addAll(items)
                settingItemAdapter.setNewData(items)
                settingItemAdapter.selectItem(channel?.sourceIndex ?: 0, true)
            }
            1 -> {
                settingItemAdapter.setNewData(settingGroups[1].items)
                settingItemAdapter.selectItem(appPreferences.getInt(AppConstants.PLAY_SCALE, 0), true)
            }
            2 -> {
                settingItemAdapter.setNewData(settingGroups[2].items)
                settingItemAdapter.selectItem(appPreferences.getInt(AppConstants.LIVE_CONNECT_TIMEOUT, 2), true)
            }
            3 -> settingItemAdapter.setNewData(settingGroups[3].items)
            4 -> settingItemAdapter.setNewData(settingGroups[4].items)
            5 -> {
                // 退出直播：点击分组即直接退出，无需再选右侧"确定"项
                finish()
                return
            }
        }
        resetHideSettingLayout()
    }

    private fun clickSettingItem(position: Int) {
        val groupIndex = settingGroupAdapter.getSelectedGroupIndex()
        if (groupIndex !in settingGroups.indices) return
        when (groupIndex) {
            0 -> {
                settingItemAdapter.selectItem(position, true)
                viewModel.setSourceIndex(position)
            }
            1 -> {
                settingItemAdapter.selectItem(position, true)
                binding.mVideoView.setScreenScaleType(position)
                savePref(AppConstants.PLAY_SCALE, position)
            }
            2 -> {
                settingItemAdapter.selectItem(position, true)
                savePref(AppConstants.LIVE_CONNECT_TIMEOUT, position)
            }
            3 -> handlePersonalSetting(position)
            4 -> showLiveHistoryDialog()
            5 -> finish()
        }
        resetHideSettingLayout()
    }

    private fun handlePersonalSetting(position: Int) {
        when (position) {
            0 -> togglePref(AppConstants.LIVE_SHOW_TIME, position) { showTime() }
            1 -> togglePref(AppConstants.LIVE_SHOW_NET_SPEED, position) { showNetSpeed() }
            2 -> togglePref(AppConstants.LIVE_CHANNEL_REVERSE, position) {}
            3 -> togglePref(AppConstants.LIVE_CROSS_GROUP, position) {}
            4 -> togglePref(AppConstants.LIVE_SKIP_PASSWORD, position) {}
            5 -> viewModel.toggleCollectCurrentChannel()
        }
    }

    /** 直播地址：弹出历史地址列表选择切换，当前地址置顶 */
    private fun showLiveHistoryDialog() {
        val history = HawkListHelper.getList(AppConstants.LIVE_HISTORY)
        if (history.isEmpty()) {
            Toast.makeText(this, getString(uiR.string.toast_no_live_addr), Toast.LENGTH_SHORT).show()
            return
        }
        val current = appPreferences.getString(AppConstants.LIVE_URL, "")
        val items = ArrayList<String>()
        if (current.isNotEmpty() && history.contains(current)) {
            items.add(current)
            history.filter { it != current }.forEach { items.add(it) }
        } else {
            items.addAll(history)
        }
        // widthPx 直接按屏幕像素加宽：直播地址文本较长，默认宽度显示不全，
        // 取屏幕宽 65% 保证完整展示（mm2px 在宽屏设备上换算偏小，不使用）
        com.tvbox.kotlin.core.ui.dialog.SelectDialog(
            this,
            getString(uiR.string.dia_history_live),
            items,
            { it },
            widthPx = (resources.displayMetrics.widthPixels * 0.65f).toInt()
        ) { url, _ ->
            viewModel.loadLiveFromUrl(url)
        }.show()
    }

    private fun togglePref(
        key: String,
        position: Int,
        afterToggle: (Boolean) -> Unit
    ) {
        val newVal = !appPreferences.getBoolean(key, false)
        savePref(key, newVal)
        settingItemAdapter.toggleItem(position, true)
        afterToggle(newVal)
    }

    /**
     * 用当前播放器内核重新装配并播放当前频道。
     * 直播内核已恒定 Exo（系统内核移除，playerType 仅兼容归一），方法保留供重播入口复用。
     */
    private fun replayWithNewPlayer(playerType: Int) {
        binding.mVideoView.release()
        updatePlayerCfg(playerType)
        val url = viewModel.currentUrl.value
        if (url.isNotEmpty()) {
            binding.mVideoView.setUrl(url, viewModel.currentHeaders.value)
            binding.mVideoView.start()
        }
    }

    // ------------------------------------------------------------------
    // EPG
    // ------------------------------------------------------------------

    /** 频道列表右侧"节目"分隔条：切换到 EPG 面板模式（divLoadEpgR） */
    private fun divLoadEpgR() {
        binding.mGroupGridView.visibility = View.GONE
        binding.mEpgInfoGridView.visibility = View.VISIBLE
        binding.mGroupEPG.visibility = View.VISIBLE
        binding.mDivLeft.visibility = View.VISIBLE
        binding.mDivRight.visibility = View.GONE
        binding.tvLeftChannelListLayout.visibility = View.INVISIBLE
        // 立即同步一次 EPG 显示：StateFlow 空→空不发射（未命中频道连续切换/初始即未命中），
        // 若不强制同步，txtNoEpg 空态提示会停留在初始 GONE 导致面板空白
        updateEpgDisplay(viewModel.epgList.value)
        // 打开面板时拉取当前频道节目单
        viewModel.currentChannel.value?.let { viewModel.loadEpg(it.channelName) }
        showChannelList()
    }

    /** 频道列表左侧"节目"分隔条：退出 EPG 面板恢复分组列表（divLoadEpgL） */
    private fun divLoadEpgL() {
        binding.mGroupGridView.visibility = View.VISIBLE
        binding.mEpgInfoGridView.visibility = View.GONE
        binding.mGroupEPG.visibility = View.GONE
        binding.mDivLeft.visibility = View.GONE
        binding.mDivRight.visibility = View.VISIBLE
        binding.tvLeftChannelListLayout.visibility = View.INVISIBLE
        showChannelList()
    }

    private fun initEpgDateView() {
        binding.mEpgDateGridView.setHasFixedSize(true)
        binding.mEpgDateGridView.layoutManager = V7LinearLayoutManager(this, 1, false)
        epgDateAdapter = LiveEpgDateAdapter()
        binding.mEpgDateGridView.adapter = epgDateAdapter
        epgDateAdapter.setOnItemClickListener { position, item ->
            epgDateAdapter.setSelectedIndex(position)
            viewModel.loadEpg(viewModel.currentChannel.value?.channelName, item.dateParamVal)
        }
        binding.mEpgDateGridView.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) = Unit
            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                epgDateAdapter.setFocusedIndex(position)
                resetHideChannelList()
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
                epgDateAdapter.setSelectedIndex(position)
                viewModel.loadEpg(viewModel.currentChannel.value?.channelName, epgDateAdapter.getItem(position)?.dateParamVal ?: Date())
            }
        })
        checkAndUpdateEpgDate()
    }

    private fun initEpgListView() {
        binding.mEpgInfoGridView.setHasFixedSize(true)
        binding.mEpgInfoGridView.layoutManager = V7LinearLayoutManager(this, 1, false)
        epgAdapter = LiveEpgAdapter()
        binding.mEpgInfoGridView.adapter = epgAdapter
        binding.mEpgInfoGridView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, state: Int) {
                resetHideChannelList()
            }
        })
        // 手机/模拟器点击：选中节目项并保持频道列表常显，等待自动隐藏
        epgAdapter.setOnItemClickListener { position, _ ->
            if (position !in 0 until epgAdapter.itemCount) return@setOnItemClickListener
            epgAdapter.setSelectedEpgIndex(position)
            binding.mEpgInfoGridView.setSelectedPosition(position)
            resetHideChannelList()
        }
        // 电视遥控器：焦点进入/离开与确认键点击
        binding.mEpgInfoGridView.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                epgAdapter.setFocusedEpgIndex(-1)
            }

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                epgAdapter.setFocusedEpgIndex(position)
                resetHideChannelList()
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {
                if (position !in 0 until epgAdapter.itemCount) return
                epgAdapter.setSelectedEpgIndex(position)
                binding.mEpgInfoGridView.setSelectedPosition(position)
                resetHideChannelList()
            }
        })
    }

    private fun checkAndUpdateEpgDate() {
        val todayStr = timeFormat.format(Date())
        if (epgDateInited && todayStr == timeFormat.format(Date(Calendar.getInstance().timeInMillis))) return
        if (!epgDateInited || epgDateAdapter.itemCount == 0) {
            val dates = ArrayList<LiveEpgDate>()
            val calendar = Calendar.getInstance()
            for (i in 0 until 2) {
                dates.add(LiveEpgDate(i, if (i == 0) getString(uiR.string.live_today) else getString(uiR.string.live_tomorrow), calendar.time))
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
            epgDateAdapter.setNewData(dates)
            epgDateAdapter.setSelectedIndex(0)
            epgDateInited = true
        }
    }

    // ------------------------------------------------------------------
    // 显示/隐藏动画
    // ------------------------------------------------------------------

    private fun showChannelList() {
        when {
            binding.tvBottomLayout.visibility == View.VISIBLE -> hideChannelInfo()
            binding.tvRightSettingLayout.visibility == View.VISIBLE -> hideSettingLayout()
            binding.tvLeftChannelListLayout.visibility == View.INVISIBLE -> {
                val groupIndex = viewModel.currentGroupIndex.value
                if (groupIndex >= 0) {
                    val channels = viewModel.channelGroups.value.getOrNull(groupIndex)?.getLiveChannels()
                    channelAdapter.setNewData(channels)
                }
                scrollToCurrentChannel()
                handler.postDelayed({
                    if (binding.mGroupGridView.isComputingLayout || binding.mChannelGridView.isComputingLayout) {
                        handler.postDelayed({ focusCurrentChannel() }, 100)
                    } else {
                        focusCurrentChannel()
                    }
                }, 200)
            }
            else -> hideChannelList()
        }
    }

    private fun focusCurrentChannel() {
        val groupIndex = viewModel.currentGroupIndex.value
        val channelIndex = viewModel.currentChannel.value?.channelIndex ?: -1
        groupAdapter.setSelectedGroupIndex(groupIndex)
        channelAdapter.setSelectedChannelIndex(channelIndex)
        if (channelIndex >= 0) {
            val holder = binding.mChannelGridView.findViewHolderForAdapterPosition(channelIndex)
            holder?.itemView?.requestFocus()
        }
        binding.tvLeftChannelListLayout.visibility = View.VISIBLE
        binding.tvLeftChannelListLayout.alpha = 0f
        binding.tvLeftChannelListLayout.translationX = -binding.tvLeftChannelListLayout.width / 2f
        binding.tvLeftChannelListLayout.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .setListener(null)
        resetHideChannelList()
    }

    private fun hideChannelList() {
        if (binding.tvLeftChannelListLayout.visibility != View.VISIBLE) return
        binding.tvLeftChannelListLayout.animate()
            .translationX(-binding.tvLeftChannelListLayout.width / 2f)
            .alpha(0f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    binding.tvLeftChannelListLayout.visibility = View.INVISIBLE
                    binding.tvLeftChannelListLayout.clearAnimation()
                }
            })
    }

    private fun showSettingGroup() {
        when {
            binding.tvLeftChannelListLayout.visibility == View.VISIBLE -> hideChannelList()
            binding.tvBottomLayout.visibility == View.VISIBLE -> hideChannelInfo()
            binding.tvRightSettingLayout.visibility == View.INVISIBLE -> {
                loadCurrentSourceList()
                settingGroupAdapter.setNewData(settingGroups)
                selectSettingGroup(0)
                binding.mSettingGroupView.scrollToPosition(0)
                handler.postDelayed({
                    val holder = binding.mSettingGroupView.findViewHolderForAdapterPosition(0)
                    holder?.itemView?.requestFocus()
                    binding.tvRightSettingLayout.visibility = View.VISIBLE
                    binding.tvRightSettingLayout.alpha = 0f
                    binding.tvRightSettingLayout.translationX = binding.tvRightSettingLayout.width / 2f
                    binding.tvRightSettingLayout.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(250)
                        .setInterpolator(DecelerateInterpolator())
                        .setListener(null)
                    resetHideSettingLayout()
                }, 200)
            }
            else -> hideSettingLayout()
        }
    }

    private fun hideSettingLayout() {
        if (binding.tvRightSettingLayout.visibility != View.VISIBLE) return
        binding.tvRightSettingLayout.animate()
            .translationX(binding.tvRightSettingLayout.width / 2f)
            .alpha(0f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    binding.tvRightSettingLayout.visibility = View.INVISIBLE
                    binding.tvRightSettingLayout.clearAnimation()
                    settingGroupAdapter.setSelectedGroupIndex(-1)
                }
            })
    }

    private fun showChannelInfo() {
        if (binding.tvBottomLayout.visibility != View.VISIBLE) {
            binding.tvBottomLayout.visibility = View.VISIBLE
            binding.tvBottomLayout.translationY = binding.tvBottomLayout.height / 2f
            binding.tvBottomLayout.alpha = 0f
            binding.tvBottomLayout.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(DecelerateInterpolator())
                .setListener(null)
        }
        resetHideChannelInfo()
    }

    private fun hideChannelInfo() {
        if (binding.tvBottomLayout.visibility != View.VISIBLE) return
        binding.tvBottomLayout.animate()
            .alpha(0f)
            .translationY(binding.tvBottomLayout.height / 2f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    binding.tvBottomLayout.visibility = View.INVISIBLE
                    binding.tvBottomLayout.clearAnimation()
                }
            })
    }

    private fun toggleChannelInfo() {
        if (binding.tvLeftChannelListLayout.visibility == View.VISIBLE) hideChannelList()
        else if (binding.tvRightSettingLayout.visibility == View.VISIBLE) hideSettingLayout()
        else if (binding.tvBottomLayout.visibility != View.VISIBLE) showChannelInfo()
        else hideChannelInfo()
    }

    private fun resetHideChannelList() {
        handler.removeCallbacks(hideChannelListRun)
        handler.postDelayed(hideChannelListRun, 6000)
    }

    private fun resetHideSettingLayout() {
        handler.removeCallbacks(hideSettingLayoutRun)
        handler.postDelayed(hideSettingLayoutRun, 6000)
    }

    private fun resetHideChannelInfo() {
        handler.removeCallbacks(hideChannelInfoRun)
        handler.postDelayed(hideChannelInfoRun, 6000)
    }

    private val hideChannelListRun = Runnable { hideChannelList() }
    private val hideSettingLayoutRun = Runnable { hideSettingLayout() }
    private val hideChannelInfoRun = Runnable { hideChannelInfo() }

    // ------------------------------------------------------------------
    // 频道号输入
    // ------------------------------------------------------------------

    private fun numericKeyDown(digit: Int) {
        selectedChannelNumber = selectedChannelNumber * 10 + digit
        binding.tvSelectedChannel.text = selectedChannelNumber.toString()
        binding.tvSelectedChannel.visibility = View.VISIBLE
        handler.removeCallbacks(playSelectedChannelRun)
        handler.postDelayed(playSelectedChannelRun, 2000)
    }

    private val playSelectedChannelRun = Runnable {
        binding.tvSelectedChannel.visibility = View.GONE
        binding.tvSelectedChannel.text = ""
        if (selectedChannelNumber > 0) {
            val groups = viewModel.channelGroups.value
            var min = 1
            for (g in groups.indices) {
                val channels = groups[g].getLiveChannels() ?: continue
                val max = min + channels.size - 1
                if (selectedChannelNumber in min..max) {
                    val channelIndex = selectedChannelNumber - min
                    viewModel.selectChannelGroup(g)
                    viewModel.playChannel(g, channelIndex, false)
                    break
                }
                min = max + 1
            }
        }
        selectedChannelNumber = 0
    }

    // ------------------------------------------------------------------
    // 按键分发
    // ------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                showSettingGroup()
                return true
            }
            if (!isListOrSettingVisible()) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        resetAutoSkip()
                        if (appPreferences.getBoolean(AppConstants.LIVE_CHANNEL_REVERSE, false))
                            viewModel.playNext() else viewModel.playPrevious()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        resetAutoSkip()
                        if (appPreferences.getBoolean(AppConstants.LIVE_CHANNEL_REVERSE, false))
                            viewModel.playPrevious() else viewModel.playNext()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        toggleChannelInfo()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        resetAutoSkip()
                        viewModel.playNextSource()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        showChannelList()
                        return true
                    }
                    else -> {
                        val digit = digitFromKeyCode(keyCode)
                        if (digit >= 0) {
                            numericKeyDown(digit)
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isListOrSettingVisible(): Boolean =
        binding.tvLeftChannelListLayout.visibility == View.VISIBLE ||
            binding.tvRightSettingLayout.visibility == View.VISIBLE

    private fun digitFromKeyCode(keyCode: Int): Int = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> -1
    }

    // ------------------------------------------------------------------
    // 触摸手势（滑动换台）
    // ------------------------------------------------------------------

    /** 滑动换台触发阈值（像素，约 140dp） */
    private val slideThreshold by lazy { (140 * resources.displayMetrics.density).toInt() }
    private var touchStartY = 0f
    private var touchTracking = false

    /**
     * 触摸上下滑动换台（与方向键一致，受 LIVE_CHANNEL_REVERSE 偏好影响）：
     * 上滑切上一台、下滑切下一台（reverse 开启时相反）。
     * 滑动超过阈值即消费事件，避免透传给视频视图触发音量/亮度手势。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isListOrSettingVisible()) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartY = ev.rawY
                    touchTracking = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchTracking) {
                        val dy = ev.rawY - touchStartY
                        if (kotlin.math.abs(dy) > slideThreshold) {
                            touchTracking = false
                            val reverse =
                                appPreferences.getBoolean(AppConstants.LIVE_CHANNEL_REVERSE, false)
                            if (dy < 0) {
                                // 上滑：与 DPAD_UP 一致
                                if (reverse) viewModel.playNext() else viewModel.playPrevious()
                            } else {
                                // 下滑：与 DPAD_DOWN 一致
                                if (reverse) viewModel.playPrevious() else viewModel.playNext()
                            }
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchTracking = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------
    // 时间/网速
    // ------------------------------------------------------------------

    private val updateTimeRun = object : Runnable {
        override fun run() {
            val now = Date()
            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val dateFmt = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
            val weekFmt = SimpleDateFormat("EEEE", Locale.CHINA)
            binding.tvSysTime.text = timeFmt.format(now)
            binding.tvWeek.text = weekFmt.format(now)
            binding.tvDate.text = dateFmt.format(now)
            // 右上角独立时间显示也随任务刷新，避免一直显示初始占位文本
            binding.tvTime.text = timeFmt.format(now)
            updateVideoSize()
            handler.postDelayed(this, 1000)
        }
    }

    /** 上次非 0 网速（KB/s）与时间戳：用于平滑下载暂停的短瞬归 0 */
    private var lastNetSpeedKb = 0L
    private var lastNetSpeedTime = 0L

    /** 网速平滑保持时长（毫秒）：下载分片间隙的短瞬保持上次速率，避免常驻网速闪动归 0（保持实时性） */
    private val netSpeedHoldMs = 1000L

    /** 获取平滑后的网速（KB/s，GSY TrafficStats 统计）：短瞬无下载流量时保持上次速率 */
    private fun currentNetSpeedKb(): Long {
        val now = System.currentTimeMillis()
        val speed = binding.mVideoView.tcpSpeed.coerceAtLeast(0)
        if (speed > 0) {
            lastNetSpeedKb = speed
            lastNetSpeedTime = now
            return speed
        }
        return if (now - lastNetSpeedTime < netSpeedHoldMs) lastNetSpeedKb else 0L
    }

    private val updateNetSpeedRun = object : Runnable {
        override fun run() {
            // 右下角常驻网速：始终显示数值（MB/s），GSY 统计单位为 KB/s，除以 1024 换算
            val speed = currentNetSpeedKb()
            binding.tvNetSpeed.text = String.format(Locale.getDefault(), "%.2fMB/s", speed / 1024.0)
            handler.postDelayed(this, 1000)
        }
    }

    /** 右上角缓冲网速刷新任务：仅加载/缓冲期间运行，同步刷新右上角加载圈与速率 */
    private val updateBufferNetSpeedRun = object : Runnable {
        override fun run() {
            val speed = currentNetSpeedKb()
            binding.tvLoadNetSpeedRightTop.text =
                String.format(Locale.getDefault(), "%.2fMB/s", speed / 1024.0)
            binding.llLoadNetSpeedRightTop.visibility = View.VISIBLE
            handler.postDelayed(this, 1000)
        }
    }

    /** 按播放状态更新右上角网速：加载/缓冲时显示并刷新，其余状态立即隐藏 */
    private fun updateTopRightNetSpeed(playState: Int) {
        val buffering = playState == PlayerConstants.STATE_PREPARING ||
            playState == PlayerConstants.STATE_BUFFERING
        if (buffering) {
            handler.post(updateBufferNetSpeedRun)
        } else {
            handler.removeCallbacks(updateBufferNetSpeedRun)
            binding.llLoadNetSpeedRightTop.visibility = View.GONE
        }
    }

    private fun showTime() {
        if (appPreferences.getBoolean(AppConstants.LIVE_SHOW_TIME, false)) {
            handler.post(updateTimeRun)
            binding.tvTime.visibility = View.VISIBLE
        } else {
            handler.removeCallbacks(updateTimeRun)
            binding.tvTime.visibility = View.GONE
        }
    }

    /** 网速开关：偏好开启时右下角常驻显示并刷新，关闭时恒隐藏 */
    private fun showNetSpeed() {
        if (appPreferences.getBoolean(AppConstants.LIVE_SHOW_NET_SPEED, false)) {
            handler.post(updateNetSpeedRun)
            binding.tvNetSpeed.visibility = View.VISIBLE
        } else {
            handler.removeCallbacks(updateNetSpeedRun)
            binding.tvNetSpeed.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------
    // 偏好封装
    // ------------------------------------------------------------------

    private fun savePref(key: String, value: Any) {
        // 同步更新内存缓存并异步落盘：不依赖 Activity 生命周期，
        // 避免点选后立即退出导致协程取消、偏好丢失（如直播解码器选择）
        appPreferences.putSync(key, value)
    }

    // ------------------------------------------------------------------
    // Flow 订阅
    // ------------------------------------------------------------------

    private fun observeFlows() {
        collectStarted(viewModel.channelGroups) { list ->
            groupAdapter.setNewData(list)
            if (list.isNotEmpty()) {
                val groupIndex = viewModel.currentGroupIndex.value
                if (groupIndex >= 0) {
                    groupAdapter.setSelectedGroupIndex(groupIndex)
                    channelAdapter.setNewData(list.getOrNull(groupIndex)?.getLiveChannels())
                }
            }
            // 频道列表加载完成后预加载所有频道 logo
            viewModel.preloadAllChannelLogos()
        }
        collectStarted(viewModel.currentGroupIndex) { index ->
            if (index >= 0) groupAdapter.setSelectedGroupIndex(index)
        }
        collectStarted(viewModel.currentChannel) { channel ->
            if (channel != null) {
                updateChannelInfo(channel)
                // 切台立即同步刷新 logo（基于当前频道计算，不等待异步 loadEpg）
                showChannelLogo()
                // 同步频道列表选中态：当前播放的频道在列表中高亮并滚动到可见位置，
                // 否则跨组切台/换源后列表选中态丢失（当前播放频道无焦点）
                val idx = channel.channelIndex
                if (idx >= 0) {
                    channelAdapter.setSelectedChannelIndex(idx)
                    binding.mChannelGridView.scrollToPosition(idx)
                }
            }
        }
        collectStarted(viewModel.currentUrl) { url ->
            if (url.isNotEmpty()) {
                binding.mVideoView.release()
                binding.mVideoView.setUrl(url, viewModel.currentHeaders.value)
                binding.mVideoView.start()
                showChannelInfo()
            }
        }
        collectStarted(viewModel.epgList) { list ->
            updateEpgDisplay(list)
        }
        collectStarted(viewModel.channelNumberInput) { input ->
            if (input.isEmpty()) {
                binding.tvSelectedChannel.visibility = View.GONE
                binding.tvSelectedChannel.text = ""
            } else {
                binding.tvSelectedChannel.text = input
                binding.tvSelectedChannel.visibility = View.VISIBLE
            }
        }
        collectStarted(viewModel.loadState) { state ->
            if (state == LiveLoadState.EMPTY) {
                Toast.makeText(this, getString(uiR.string.toast_no_live_channel), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        collectStarted(viewModel.toast) { msg ->
            if (msg.isNotEmpty()) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
        collectStarted(viewModel.error) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateChannelInfo(channel: LiveChannelItem) {
        binding.tvChannelName.text = channel.channelName
        binding.tvChannelNumber.text = channel.channelNum.toString()
        val sourceNum = channel.sourceNum
        val sourceIdx = channel.sourceIndex
        binding.tvSource.text = if (sourceNum <= 0) "1/1" else getString(uiR.string.live_line_fmt, sourceIdx + 1, sourceNum)
    }

    /**
     * 展示频道 logo：
     * 内存缓存 → 本地文件缓存（未过期）→ 失败标记 → 网络 primary/fallback → 成功写缓存/失败标记。
     * logo 地址由当前频道同步计算（computeChannelLogo），不依赖异步 StateFlow，切台立即刷新。
     */
    private fun showChannelLogo() {
        val channel = viewModel.currentChannel.value ?: return
        val channelName = channel.channelName ?: return
        val (logoUrl, fallback) = viewModel.computeChannelLogo(channelName)
        val epgid = com.tvbox.kotlin.core.common.util.EpgUtil.getEpgid(channelName)?.ifEmpty { channelName } ?: channelName
        val placeholder = com.tvbox.kotlin.core.ui.R.drawable.img_logo_placeholder
        // 1. 内存缓存命中（未过期）
        viewModel.getCachedLogo(epgid)?.let {
            binding.tvLogo.setImageDrawable(it)
            return
        }
        // 2. 本地文件缓存（未过期）：file:// URI 供 Coil 本地解码，避免被协议校验拦成占位图
        val cacheFile = viewModel.getLogoCacheFile(epgid)
        if (cacheFile.exists() &&
            System.currentTimeMillis() - cacheFile.lastModified() < 24 * 60 * 60 * 1000L
        ) {
            CoilUtils.load(
                this, binding.tvLogo, android.net.Uri.fromFile(cacheFile).toString(),
                placeholder = placeholder, error = placeholder,
                onSuccess = { viewModel.cacheLogo(epgid, it) }
            )
            return
        }
        // 3. 已标记失败：直接占位，不再重复请求
        if (viewModel.isLogoFailed(epgid)) {
            binding.tvLogo.setImageResource(placeholder)
            return
        }
        // 4. 网络加载 primary（tvg-logo 或订阅地址 logo 字段），失败回退 fallback，再失败标记
        CoilUtils.load(
            this, binding.tvLogo, logoUrl,
            placeholder = placeholder, error = placeholder,
            onSuccess = { viewModel.cacheLogo(epgid, it) },
            onFail = {
                // 仅当前频道仍匹配该 epgid 时才继续回退/标记，避免切台后旧请求误操作
                if (isCurrentEpgid(epgid) && fallback != null) {
                    CoilUtils.load(
                        this, binding.tvLogo, fallback,
                        placeholder = placeholder, error = placeholder,
                        onSuccess = { viewModel.cacheLogo(epgid, it) },
                        onFail = { if (isCurrentEpgid(epgid)) viewModel.markLogoFailed(epgid) }
                    )
                } else if (isCurrentEpgid(epgid)) {
                    viewModel.markLogoFailed(epgid)
                }
            }
        )
    }

    /** 指定 epgid 是否仍对应当前播放频道（切台后旧加载请求的结果应被忽略） */
    private fun isCurrentEpgid(epgid: String): Boolean {
        val channel = viewModel.currentChannel.value ?: return false
        val name = channel.channelName ?: return false
        return (com.tvbox.kotlin.core.common.util.EpgUtil.getEpgid(name)?.ifEmpty { name } ?: name) == epgid
    }

    private fun updateEpgDisplay(list: List<Epginfo>) {
        // 当前频道是否支持回看，控制 EPG 列表"回看/预约"标记（CanBack）
        epgAdapter.includeBack = viewModel.currentChannel.value?.include_back ?: false
        // EPG 未命中时列表插入getString(uiR.string.live_no_program)条目(00:00-23:59)，
        // 该条目覆盖全天时间，LiveEpgAdapter 会判定为当前时段显示"直播"图标+波纹图
        if (list.isEmpty()) {
            val placeholder = Epginfo(Date(), getString(uiR.string.live_no_program), Date(), "00:00", "23:59", 0)
            epgAdapter.setNewData(listOf(placeholder))
            epgAdapter.setSelectedEpgIndex(0)
            binding.mEpgInfoGridView.setSelectedPosition(0)
            binding.txtNoEpg.visibility = View.GONE
            binding.tvCurrentProgramTime.text = "00:00 - 23:59"
            binding.tvCurrentProgramName.text = getString(uiR.string.live_no_program)
            binding.tvNextProgramTime.text = "00:00 - 23:59"
            binding.tvNextProgramName.text = getString(uiR.string.live_no_program)
            return
        }
        epgAdapter.setNewData(list)
        // 左侧 EPG 面板：节目单为空时显示空态提示
        binding.txtNoEpg.visibility = View.GONE
        val now = Date()
        var currentIdx = -1
        for (i in list.indices.reversed()) {
            val e = list[i]
            if (e.startdateTime != null && e.enddateTime != null &&
                now.after(e.startdateTime) && now.before(e.enddateTime)
            ) {
                currentIdx = i
                break
            }
        }
        if (currentIdx >= 0) {
            val cur = list[currentIdx]
            binding.tvCurrentProgramTime.text = "${cur.start} - ${cur.end}"
            binding.tvCurrentProgramName.text = cur.title
            if (currentIdx < list.size - 1) {
                val next = list[currentIdx + 1]
                binding.tvNextProgramTime.text = "${next.start} - ${next.end}"
                binding.tvNextProgramName.text = next.title
            } else {
                binding.tvNextProgramTime.text = "00:00 - 23:59"
                binding.tvNextProgramName.text = getString(uiR.string.live_no_program)
            }
            binding.mEpgInfoGridView.setSelectedPosition(currentIdx)
            epgAdapter.setSelectedEpgIndex(currentIdx)
            // 左侧 EPG 面板：滚动到当前播放节目（showEpg）
            binding.mEpgInfoGridView.post {
                binding.mEpgInfoGridView.smoothScrollToPosition(currentIdx)
            }
        } else {
            val first = list[0]
            binding.tvCurrentProgramTime.text = "00:00 - 23:59"
            binding.tvCurrentProgramName.text = getString(uiR.string.live_current_no_program)
            binding.tvNextProgramTime.text = "${first.start} - ${first.end}"
            binding.tvNextProgramName.text = first.title
        }
    }

    private fun loadCurrentSourceList() {
        val channel = viewModel.currentChannel.value ?: return
        val sourceNames = channel.channelSourceNames ?: arrayListOf()
        val items = sourceNames.mapIndexed { idx, name -> LiveSettingItem(idx, name) }.toMutableList()
        settingGroups[0].items.clear()
        settingGroups[0].items.addAll(items)
    }
}
