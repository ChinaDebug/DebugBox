package com.tvbox.kotlin.feature.detail.ui

import android.annotation.TargetApi
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.util.Rational
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.bean.VodInfo
import com.tvbox.kotlin.core.common.bean.VodSeriesGroup
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.ui.AppNavigator
import com.tvbox.kotlin.core.ui.BaseBindingActivity
import com.tvbox.kotlin.core.ui.CoilUtils
import com.tvbox.kotlin.core.ui.R as CoreUiR
import com.tvbox.kotlin.core.ui.dialog.DescDialog
import com.tvbox.kotlin.feature.detail.R
import com.tvbox.kotlin.feature.detail.databinding.ActivityDetailBinding
import com.tvbox.kotlin.feature.play.ui.PlayFragment
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 影片详情页：展示详情信息、剧集线路与集数列表，支持收藏、历史记录、排序与跳转播放。
 */
@AndroidEntryPoint
class DetailActivity : BaseBindingActivity<ActivityDetailBinding>() {

    @Inject
    lateinit var navigator: AppNavigator

    @Inject
    lateinit var appPreferences: AppPreferences

    private val viewModel: DetailViewModel by viewModels()

    private lateinit var seriesFlagAdapter: SeriesFlagAdapter
    private lateinit var seriesAdapter: SeriesAdapter
    private lateinit var seriesGroupAdapter: SeriesGroupAdapter

    /** 当前剧集分组索引与每组分集数 */
    private var groupIndex = 0
    private var groupCount = 20
    /** 各分组持有的剧集子列表 */
    private var seriesGroups: MutableList<MutableList<VodInfo.VodSeries>>? = null

    /** 快搜对话框 */
    private var quickSearchDialog: QuickSearchDialog? = null

    /** 网格列数：按最长集名动态计算（2~6 列），长集名时列数减少以完整展示 */
    private var gridSpanCount = 6

    /** 主题色，运行时从当前主题解析 */
    private var themeColor: Int = Color.WHITE

    /** 列表/搜索等入口透传的竖版封面，重新加载详情时保持使用 */
    private var initialPic: String? = null

    /** 防抖时间戳 */
    private var lastClickTime = 0L

    // ------------------------------------------------------------------
    // 预览播放器相关
    // ------------------------------------------------------------------

    /** 预览播放器 Fragment */
    private var playFragment: PlayFragment? = null

    /** 是否启用预览播放（设置开关，默认开启） */
    private val showPreview: Boolean by lazy {
        appPreferences.getBoolean(AppConstants.SHOW_PREVIEW, true)
    }

    /** 是否处于全屏预览状态 */
    private var fullWindows = false

    /** 小窗/全屏 LayoutParams 快照 */
    private var windowsPreview: ViewGroup.LayoutParams? = null
    private var windowsFull: ViewGroup.LayoutParams? = null

    /** 播放成功状态（仅播放成功后才允许进入全屏） */
    private var playSuccess = false

    /** 当前预览播放的线路与集数 */
    private var previewPlayingFlag = ""
    private var isPreviewPlaying = false
    private var previewPlayIndex = -1

    /** 预览播放器使用的 VodInfo 深拷贝 */
    private var previewVodInfo: VodInfo? = null

    /** 当前线路（用于选集时判断线路切换） */
    private var preFlag = ""

    override val layoutId: Int = R.layout.activity_detail

    override fun initView(savedInstanceState: Bundle?) {
        resolveThemeColor()
        initPreviewPlayer()
        initRecyclerView()
        initClickListeners()
        initPreviewFocus()
        initBackPress()
    }

    /**
     * 预览窗焦点链：动态配置 nextFocus，确保遥控器方向键移出预览窗后能再聚焦回来。
     * 布局静态 nextFocus 在 DataBinding 下偶有失效。
     * 上键导航保持逐级上移（集数→分组→路线→预览窗），仅最顶层路线列表
     * 在顶部按上键时聚焦预览窗；分组/集数列表交回默认导航上移，避免焦点被直接抢到预览窗。
     */
    private fun initPreviewFocus() {
        val block = binding.previewPlayerBlock
        block.nextFocusDownId = R.id.mGridViewFlag
        // 预览窗右键优先到全屏按钮（tvPlay），保证全屏按钮始终可聚焦
        block.nextFocusRightId = R.id.tvPlay
        // 信息区按钮向左逐级回退（焦点链）：收集→简介→推送→快搜→全屏→预览窗，
        // 避免任意按钮按左键直接跳到预览窗
        binding.tvPlay.nextFocusLeftId = R.id.previewPlayerBlock
        binding.tvQuickSearch.nextFocusLeftId = R.id.tvPlay
        binding.tvPush.nextFocusLeftId = R.id.tvQuickSearch
        binding.tvDesc.nextFocusLeftId = R.id.tvPush
        binding.tvCollect.nextFocusLeftId = R.id.tvDesc
        // 仅路线列表（最顶层列表）顶部按上键时聚焦预览窗：
        // 预览窗可见时直接聚焦并消费，避免列表边界焦点搜索失败导致整个界面焦点消失；
        // 预览窗隐藏（海报模式）时交回默认行为
        binding.mGridViewFlag.setOnInBorderKeyEventListener { direction, _ ->
            if (direction == View.FOCUS_UP && block.visibility == View.VISIBLE) {
                block.requestFocus()
                true
            } else {
                false
            }
        }
        // 分组列表顶部按上键 → 聚焦路线列表（列表边界默认焦点搜索找不到上层列表项）
        binding.mSeriesGroupView.setOnInBorderKeyEventListener { direction, _ ->
            if (direction == View.FOCUS_UP) {
                binding.mGridViewFlag.requestFocus()
                true
            } else {
                false
            }
        }
        // 排序按钮（普通 TextView）按上键 → 聚焦路线列表
        binding.tvSort.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                binding.mGridViewFlag.requestFocus()
                true
            } else {
                false
            }
        }
    }

    override fun initData() {
        val vodId = intent.getStringExtra(EXTRA_VOD_ID) ?: intent.getStringExtra(EXTRA_LEGACY_ID)
        val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY)
        initialPic = intent.getStringExtra(EXTRA_PIC)
        if (vodId.isNullOrBlank() || sourceKey.isNullOrBlank()) {
            showState(LoadState.EMPTY)
            return
        }
        observeViewModel()
        viewModel.loadDetail(vodId, sourceKey, initialPic)
    }

    /** 从当前主题解析 color_theme 属性，用于剧集选中高亮 */
    private fun resolveThemeColor() {
        val typedValue = TypedValue()
        theme.resolveAttribute(CoreUiR.attr.color_theme, typedValue, true)
        if (typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            themeColor = typedValue.data
        }
    }

    /** 初始化预览播放器：开启时在海报位置内嵌播放器，关闭时显示海报与"开始播放"按钮 */
    private fun initPreviewPlayer() {
        binding.previewPlayerPlace.visibility = if (showPreview) View.VISIBLE else View.GONE
        binding.ivThumb.visibility = if (showPreview) View.GONE else View.VISIBLE
        if (showPreview) {
            playFragment = PlayFragment().apply {
                playSuccessListener = { success -> playSuccess = success }
                // 预览窗切集（自动连播/上一集/下一集）后同步集数选中状态
                episodeChangedListener = { flag, index -> onPreviewEpisodeChanged(flag, index) }
            }
            supportFragmentManager.beginTransaction()
                .add(R.id.previewPlayer, playFragment!!)
                .commit()
            supportFragmentManager.beginTransaction()
                .show(playFragment!!)
                .commitAllowingStateLoss()
            // 预览模式下 tvPlay 显示为"全屏"按钮（det_expand），点击进入全屏播放；
            // 数据未就绪时不可聚焦，避免系统默认焦点先落在此导致焦点框闪现
            binding.tvPlay.visibility = View.VISIBLE
            binding.tvPlay.text = getString(CoreUiR.string.det_expand)
            binding.tvPlay.isFocusable = false
        } else {
            binding.tvPlay.visibility = View.VISIBLE
            binding.tvPlay.text = getString(CoreUiR.string.det_play)
            binding.tvPlay.requestFocus()
        }
    }

    private fun initRecyclerView() {
        // 线路列表：横向，焦点移动/点击即切换线路并刷新选中下划线
        seriesFlagAdapter = SeriesFlagAdapter()
        binding.mGridViewFlag.apply {
            layoutManager = V7LinearLayoutManager(this@DetailActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = seriesFlagAdapter
            setHasFixedSize(true)
            setOnItemListener(object : TvRecyclerView.OnItemListener {
                /**
                 * 切换线路并刷新选中下划线；线路切换仅更新展示（不中断当前播放），
                 * 播放由集数选择或全屏按钮触发。
                 */
                private fun refresh(itemView: View?, position: Int) {
                    if (position == -1) return
                    val newFlag = seriesFlagAdapter.getItem(position)?.name ?: return
                    val info = viewModel.vodInfo.value ?: return
                    info.seriesFlags?.forEachIndexed { index, flag ->
                        val selected = (index == position)
                        if (flag.selected != selected) {
                            flag.selected = selected
                            seriesFlagAdapter.notifyItemChanged(index)
                        }
                    }
                    if (info.playFlag != newFlag) {
                        viewModel.selectFlag(newFlag, triggerPlay = false)
                    }
                }

                override fun onItemPreSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {}
                override fun onItemSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) =
                    refresh(view, position)
                override fun onItemClick(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {
                    refresh(view, position)
                }
            })
        }

        // 剧集分组：横向列表，切换分组时刷新剧集网格
        seriesGroupAdapter = SeriesGroupAdapter()
        binding.mSeriesGroupView.apply {
            layoutManager = V7LinearLayoutManager(this@DetailActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = seriesGroupAdapter
            setHasFixedSize(true)
            setOnItemListener(object : TvRecyclerView.OnItemListener {
                override fun onItemPreSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {}
                override fun onItemSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) = switchSeriesGroup(position)
                override fun onItemClick(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {
                    switchSeriesGroup(position)
                }
            })
        }

        // 剧集列表：网格（TvRecyclerView + V7GridLayoutManager，居中/间距由布局 tv_* 属性提供）
        seriesAdapter = SeriesAdapter().apply { setThemeColor(themeColor) }
        binding.mGridView.apply {
            layoutManager = V7GridLayoutManager(this@DetailActivity, gridSpanCount)
            adapter = seriesAdapter
            setHasFixedSize(false)
            // 上键处理：焦点跳到记忆集（中间段）时，上方的集数因滚动定位未布局，
            // 焦点 item 的 focusSearch(UP) 找不到上方集数，事件冒泡到此。
            // 此时向上滚动一行显现上方集数（消费按键），滚动到最顶部后交回默认（焦点移出上层）。
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN) {
                    val lm = binding.mGridView.layoutManager as? V7GridLayoutManager
                        ?: return@setOnKeyListener false
                    val first = lm.findFirstVisibleItemPosition()
                    if (first > 0) {
                        binding.mGridView.smoothScrollToPosition(maxOf(0, first - lm.spanCount))
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
        }
        seriesAdapter.setOnItemClickListener { position, _ ->
            if (isFastClick()) return@setOnItemClickListener
            val info = viewModel.vodInfo.value ?: return@setOnItemClickListener
            val flag = info.playFlag ?: return@setOnItemClickListener
            val seriesList = info.seriesMap?.get(flag) ?: return@setOnItemClickListener
            if (seriesList.isEmpty()) return@setOnItemClickListener

            val newAbsoluteIndex = groupIndex * groupCount + position
            var reload = false

            if (info.playEpisodeIndex != newAbsoluteIndex) {
                seriesList.forEachIndexed { index, series ->
                    if (series.selected) {
                        series.selected = false
                        val local = index - groupIndex * groupCount
                        if (local in 0 until seriesAdapter.itemCount) seriesAdapter.notifyItemChanged(local)
                    }
                }
                seriesList[newAbsoluteIndex].selected = true
                seriesAdapter.notifyItemChanged(position)
                info.playIndex = position
                info.playGroup = groupIndex
                info.playEpisodeIndex = newAbsoluteIndex
                reload = true
                playSuccess = false
            }
            if (info.playFlag != preFlag) {
                reload = true
                playSuccess = false
            }

            if (showPreview && !fullWindows && isPreviewPlaying &&
                info.playFlag == previewPlayingFlag &&
                info.getplayIndex() == previewPlayIndex
            ) {
                // 当前集已播放成功时再次点击进入全屏
                if (playSuccess) {
                    toggleFullPreview()
                }
            }
            if (reload || !showPreview) {
                viewModel.selectEpisode(newAbsoluteIndex)
            }
        }
        seriesAdapter.setOnItemLongClickListener { position, _ ->
            // 长按同样触发播放，便于批量选集后快速播放
            viewModel.selectEpisode(groupIndex * groupCount + position)
        }
    }

    /** 计算每组分集数*/
    private fun calculateGroupCount(size: Int): Int = when {
        size > 2500 -> 300
        size > 1500 -> 200
        size > 1000 -> 150
        size > 500 -> 100
        size > 300 -> 50
        size > 100 -> 30
        else -> 20
    }

    /**
     * 动态计算剧集网格列数（2~6 列）：测量最长集名宽度并加缓冲，
     * 用剧集区宽度（屏幕宽 1/3）除以单列宽度得到可容纳列数。
     * 使用默认 Paint（不设 textSize），与分列后 item 的实际排版基准一致。
     */
    private fun updateGridSpanCount(seriesList: List<VodInfo.VodSeries>) {
        val paint = android.graphics.Paint()
        var maxWidth = 1
        seriesList.forEach { series ->
            val width = paint.measureText(series.name ?: "").toInt()
            if (maxWidth < width) maxWidth = width
        }
        maxWidth += 32
        val screenWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.width
        } / 3
        gridSpanCount = (screenWidth / maxWidth).coerceIn(2, 6)
        (binding.mGridView.layoutManager as? V7GridLayoutManager)?.spanCount = gridSpanCount
    }

    /**
     * 按当前线路剧集生成分组数据并刷新网格。
     * 仅一屏内可容纳全部剧集时不显示分组栏（组数 > 1 时展示）。
     */
    private fun updateSeriesGroups(flag: String) {
        val info = viewModel.vodInfo.value ?: return
        val seriesList = info.getFlagSeries(flag)
        if (seriesList.isEmpty()) {
            binding.mSeriesGroupView.visibility = View.GONE
            seriesGroups = null
            return
        }
        // 动态列数：按最长集名宽度计算可容纳列数（2~6 列），
        // 保证非标长集名在单行内完整显示，不被省略或跑马灯截断
        updateGridSpanCount(seriesList)
        groupCount = calculateGroupCount(seriesList.size)
        info.playGroupCount = groupCount
        val absoluteIndex = info.getplayIndex()
        groupIndex = (absoluteIndex / groupCount).coerceIn(0, (seriesList.size - 1) / groupCount)

        val groupsCount = (seriesList.size + groupCount - 1) / groupCount
        val groupList = ArrayList<VodSeriesGroup>(groupsCount)
        val groupsData = ArrayList<MutableList<VodInfo.VodSeries>>(groupsCount)
        for (i in 0 until groupsCount) {
            val start = i * groupCount
            val end = minOf(start + groupCount, seriesList.size)
            groupsData.add(ArrayList(seriesList.subList(start, end)))
            val name = if (info.reverseSort) {
                "${seriesList.size - start}-${seriesList.size - end + 1}"
            } else {
                "${start + 1}-$end"
            }
            groupList.add(VodSeriesGroup(name))
        }
        seriesGroups = groupsData
        groupList[groupIndex].selected = true
        // 全量刷新会重建焦点项，焦点在分集组时刷新后交还，避免焦点闪到按钮
        val hadGroupFocus = binding.mSeriesGroupView.hasFocus()
        seriesGroupAdapter.setData(groupList)
        if (hadGroupFocus) {
            binding.mSeriesGroupView.post {
                if (isFinishing || isDestroyed) return@post
                binding.mSeriesGroupView.requestFocus()
            }
        }
        binding.mSeriesGroupView.visibility = if (groupsCount > 1) View.VISIBLE else View.GONE
        // 全量刷新会重建焦点项，焦点在集数列表时刷新后交还，避免焦点闪到按钮
        val hadSeriesFocus = binding.mGridView.hasFocus()
        seriesAdapter.setData(groupsData[groupIndex])
        if (hadSeriesFocus) {
            binding.mGridView.post {
                if (isFinishing || isDestroyed) return@post
                binding.mGridView.requestFocus()
            }
        }
    }

    /** 切换到指定剧集分组，刷新分组选中态与剧集网格 */
    private fun switchSeriesGroup(position: Int) {
        if (position == groupIndex) return
        val groups = seriesGroups ?: return
        seriesGroupAdapter.getItem(groupIndex)?.selected = false
        seriesGroupAdapter.notifyItemChanged(groupIndex)
        seriesGroupAdapter.getItem(position)?.selected = true
        seriesGroupAdapter.notifyItemChanged(position)
        seriesAdapter.getData().forEachIndexed { index, series ->
            if (series.selected) {
                series.selected = false
                seriesAdapter.notifyItemChanged(index)
            }
        }
        groupIndex = position
        // 全量刷新会重建焦点项，焦点在集数列表时刷新后交还，避免焦点闪到按钮
        val hadSeriesFocus = binding.mGridView.hasFocus()
        groups.getOrNull(position)?.let { seriesAdapter.setData(it) }
        if (hadSeriesFocus) {
            binding.mGridView.post {
                if (isFinishing || isDestroyed) return@post
                binding.mGridView.requestFocus()
            }
        }
        val info = viewModel.vodInfo.value
        val currentPlayIndex = info?.getplayIndex() ?: 0
        val groupStartIndex = position * groupCount
        val groupEndIndex = minOf(
            (position + 1) * groupCount,
            info?.getFlagSeries(info.playFlag ?: "")?.size ?: 0
        )
        if (currentPlayIndex in groupStartIndex until groupEndIndex) {
            val localIndex = currentPlayIndex - groupStartIndex
            seriesAdapter.getItem(localIndex)?.selected = true
            seriesAdapter.notifyItemChanged(localIndex)
        }
        // 布局完成后回顶，避免未布局时滚动产生半个 item 的偏移
        binding.mGridView.post { binding.mGridView.scrollToPosition(0) }
    }

    private fun initClickListeners() {
        // 播放按钮：预览模式切换全屏，海报模式跳转播放页
        binding.tvPlay.setOnClickListener {
            if (isFastClick()) return@setOnClickListener
            if (showPreview) toggleFullPreview() else jumpToPlay()
        }

        // 海报点击播放（非预览模式）
        binding.ivThumb.setOnClickListener {
            if (isFastClick()) return@setOnClickListener
            jumpToPlay()
        }

        // 预览播放器拦截层：非全屏时点击进入全屏
        binding.previewPlayerBlock.setOnClickListener {
            if (isFastClick()) return@setOnClickListener
            if (!fullWindows) {
                toggleFullPreview()
            }
        }

        // 跳转快速搜索：弹出快搜对话框，以当前影片名搜索其他源的相关资源
        binding.tvQuickSearch.setOnClickListener {
            if (isFastClick()) return@setOnClickListener
            val name = viewModel.vodInfo.value?.name ?: return@setOnClickListener
            showQuickSearchDialog(name)
        }

        // 推送（镜像推送，搜索局域网 TVBox 设备后把当前影片镜像推送到接收端播放）
        binding.tvPush.setOnClickListener {
            val info = viewModel.vodInfo.value ?: return@setOnClickListener
            val id = info.id ?: return@setOnClickListener
            val key = info.sourceKey ?: return@setOnClickListener
            val dialog = PushDialog(this)
            dialog.setPushData(id, key)
            dialog.show()
        }

        // 点开简介弹窗
        binding.tvDesc.setOnClickListener {
            showDescDialog()
        }

        // 长按简介复制内容
        binding.tvDesc.setOnLongClickListener {
            val content = removeHtmlTag(viewModel.vodInfo.value?.des)
            copyToClipboard(content)
            showToast("已复制简介")
            true
        }

        // 收藏/取消收藏
        binding.tvCollect.setOnClickListener {
            viewModel.toggleCollect()
        }

        // 排序切换
        binding.tvSort.setOnClickListener {
            viewModel.toggleSort()
        }

        // 复制当前集播放地址（优先预览播放器解析后的真实地址）
        binding.tvPlayUrl.setOnClickListener {
            val realUrl = playFragment?.getPlayUrl()
            if (!realUrl.isNullOrBlank()) {
                copyToClipboard(realUrl)
                showToast("播放地址已复制")
                return@setOnClickListener
            }
            val info = viewModel.vodInfo.value ?: return@setOnClickListener
            val flag = info.playFlag ?: return@setOnClickListener
            val seriesList = info.seriesMap?.get(flag) ?: return@setOnClickListener
            val index = viewModel.selectedEpisode.value
            val url = seriesList.getOrNull(index)?.url
            if (!url.isNullOrBlank()) {
                copyToClipboard(url)
                showToast("播放地址已复制")
            }
        }

        // 重试
        binding.tvRetry.setOnClickListener {
            viewModel.retry()
        }
    }

    /**
     * 弹出快搜对话框并触发搜索。
     * 点击分词换词重搜；点击结果重置预览播放状态后切换到对应源详情。
     */
    private fun showQuickSearchDialog(keyword: String) {
        quickSearchDialog?.dismiss()
        quickSearchDialog = QuickSearchDialog(this).apply {
            sourceNameResolver = { key -> viewModel.getSourceName(key) }
            onWordClick = { word -> viewModel.quickSearch(word) }
            onResultClick = { video ->
                val id = video.id
                val key = video.sourceKey
                if (id != null && key != null) {
                    // 重置预览播放与列表状态，切换到该源详情
                    previewVodInfo = null
                    previewPlayIndex = -1
                    isPreviewPlaying = false
                    playSuccess = false
                    groupIndex = 0
                    seriesGroups = null
                    // 快搜换源：不透传 initialPic，使用新源自己的封面，避免同名异片时封面张冠李戴
                    viewModel.loadDetail(id, key)
                }
            }
            setOnDismissListener { quickSearchDialog = null }
            show()
        }
        viewModel.quickSearch(keyword)
    }

    private fun initBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullWindows) {
                    if (playFragment?.onBackPressed() == true) {
                        return
                    }
                    toggleFullPreview()
                } else {
                    // 仅播放成功且有进度才记录历史
                    if (playSuccess) {
                        saveVod()
                    }
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /** 收集 ViewModel 状态流，驱动 UI 更新 */
    private fun observeViewModel() {
        collectStarted(viewModel.loadState) { state ->
            showState(state)
        }

        collectStarted(viewModel.vodInfo) { info ->
            if (info != null) updateDetailInfo(info)
        }

        collectStarted(viewModel.selectedFlag) { flag ->
            if (flag != null) updateSeriesList(flag)
        }

        collectStarted(viewModel.selectedEpisode) { index ->
            updateEpisodeSelection(index)
        }

        collectStarted(viewModel.collected) { collected ->
            binding.tvCollect.setText(if (collected) CoreUiR.string.det_fav_star else CoreUiR.string.det_fav_unstar)
        }

        collectStarted(viewModel.sortReversed) { reversed ->
            binding.tvSort.setText(if (reversed) CoreUiR.string.det_sort_reverse else CoreUiR.string.det_sort_normal)
            // 排序反转后刷新分组与剧集列表，并同步预览播放器
            val info = viewModel.vodInfo.value ?: return@collectStarted
            val flag = info.playFlag ?: return@collectStarted
            updateSeriesList(flag)
            previewPlayIndex = info.getplayIndex()
            playFragment?.updateVodInfo(info)
        }

        collectStarted(viewModel.quickResults) { results ->
            quickSearchDialog?.setResults(results)
        }

        collectStarted(viewModel.quickWords) { words ->
            quickSearchDialog?.setWords(words)
        }

        collectStarted(viewModel.playEvent) {
            // 海报/预览模式统一走 jumpToPlay：海报模式携带完整 VodInfo 跳转播放页，
            // 仅传原始集地址会绕过播放页解析管线，非直链源直接播放失败
            jumpToPlay()
        }

        collectStarted(viewModel.toast) { msg ->
            showToast(msg)
        }

        collectStarted(viewModel.error) { msg ->
            showToast(msg)
        }
    }

    /** 更新详情区域信息展示 */
    private fun updateDetailInfo(info: VodInfo) {
        binding.tvName.text = info.name

        CoilUtils.load(
            context = this,
            view = binding.ivThumb,
            url = info.pic,
            error = com.tvbox.kotlin.core.ui.R.drawable.icon_empty,
            radius = 14
        )

        setTextShow(binding.tvYear, getString(CoreUiR.string.det_year), if (info.year == 0) "" else info.year.toString())
        setTextShow(binding.tvArea, getString(CoreUiR.string.det_area), info.area)
        setTextShow(binding.tvLang, getString(CoreUiR.string.det_lang), info.lang)
        setTextShow(binding.tvType, getString(CoreUiR.string.det_type), info.type)
        setTextShow(binding.tvActor, getString(CoreUiR.string.det_actor), info.actor)
        setTextShow(binding.tvDirector, getString(CoreUiR.string.det_dir), info.director)
        setTextShow(binding.tvDes, "", removeHtmlTag(info.des))

        setTextShow(binding.tvSite, "", info.sourceKey)

        val flags = info.seriesFlags
        if (!flags.isNullOrEmpty()) {
            seriesFlagAdapter.setData(flags)
            binding.mGridViewFlag.visibility = View.VISIBLE
        } else {
            binding.mGridViewFlag.visibility = View.GONE
        }

        if (info.isSeriesEmpty()) {
            binding.mEmptyPlaylist.visibility = View.VISIBLE
            binding.mGridView.visibility = View.GONE
            binding.tvSort.visibility = View.GONE
            binding.previewPlayer.visibility = View.GONE
            binding.previewPlayerBlock.visibility = View.GONE
        } else {
            binding.mEmptyPlaylist.visibility = View.GONE
            binding.mGridView.visibility = View.VISIBLE
            binding.tvSort.visibility = View.VISIBLE
            if (showPreview) {
                // 预览模式：详情加载完成后立即开始预览播放（仅首次自动启动。
                // collectStarted 基于 repeatOnLifecycle(STARTED)，切后台回前台重新订阅
                // vodInfo 会立即发射当前值：重复 jumpToPlay 会从头重播，且重复把
                // previewPlayerBlock 置 VISIBLE 会在全屏模式下重新拦截触摸
                // （全屏时该拦截层应为 GONE）；换线路/换集由 playEvent(SharedFlow) 驱动。
                // 仅在首次一并处理可见性，之后保持进入页面时的状态）
                if (previewVodInfo == null) {
                    jumpToPlay()
                    binding.previewPlayer.visibility = View.VISIBLE
                    binding.previewPlayerBlock.visibility = View.VISIBLE
                    // 数据就绪后恢复全屏按钮可聚焦，并把焦点直接交给预览窗
                    binding.tvPlay.isFocusable = true
                    binding.previewPlayerBlock.requestFocus()
                }
            } else {
                binding.previewPlayer.visibility = View.GONE
                binding.previewPlayerBlock.visibility = View.GONE
            }
        }
    }

    /** 更新当前线路的剧集列表（含分组） */
    private fun updateSeriesList(flag: String) {
        val info = viewModel.vodInfo.value ?: return
        val seriesList = info.getFlagSeries(flag)
        if (seriesList.isEmpty()) {
            binding.mEmptyPlaylist.visibility = View.VISIBLE
            binding.mGridView.visibility = View.GONE
            binding.mSeriesGroupView.visibility = View.GONE
            return
        }
        binding.mEmptyPlaylist.visibility = View.GONE
        binding.mGridView.visibility = View.VISIBLE

        // 填充缺失的集名
        seriesList.forEachIndexed { index, series ->
            if (series.name.isNullOrBlank()) {
                series.name = if (seriesList.size == 1) info.name else index.toString()
            }
        }
        updateSeriesGroups(flag)
        updateEpisodeSelection(viewModel.selectedEpisode.value)
    }

    /** 更新剧集选中状态并滚动到目标位置 */
    private fun updateEpisodeSelection(index: Int) {
        val info = viewModel.vodInfo.value ?: return
        val flag = info.playFlag ?: viewModel.selectedFlag.value ?: return
        val seriesList = info.getFlagSeries(flag)
        if (seriesList.isEmpty()) return
        // 选中集所在分组与当前分组不一致时先切换分组
        val targetGroup = if (groupCount > 0) index / groupCount else 0
        if (seriesGroups != null && targetGroup != groupIndex && targetGroup in 0 until (seriesGroups?.size ?: 0)) {
            switchSeriesGroup(targetGroup)
        }
        val oldIndex = seriesList.indexOfFirst { it.selected }
        seriesList.forEachIndexed { i, series -> series.selected = (i == index) }
        val localIndex = index - groupIndex * groupCount
        // 仅刷新选中态变化的项，避免全量重建 detach 焦点项导致焦点框闪到按钮
        if (oldIndex >= 0 && oldIndex != index) {
            val oldLocal = oldIndex - groupIndex * groupCount
            if (oldLocal in 0 until seriesAdapter.itemCount) {
                seriesAdapter.notifyItemChanged(oldLocal)
            }
        }
        if (localIndex in 0 until seriesAdapter.itemCount) {
            seriesAdapter.notifyItemChanged(localIndex)
        }
        if (localIndex in 0 until seriesAdapter.itemCount) {
            // 布局完成后滚动：notifyDataSetChanged 后立即 scrollToPosition 时 RecyclerView
            // 尚未测量 item 尺寸，TvRecyclerView 的居中滚动会按错误偏移滚动，导致首行只显示一半
            binding.mGridView.post {
                if (localIndex !in 0 until seriesAdapter.itemCount) return@post
                // 焦点停留在集数列表时（如自动连播切集），让焦点框跟随选中集移动
                if (binding.mGridView.hasFocus()) {
                    (binding.mGridView.layoutManager as? V7GridLayoutManager)
                        ?.scrollToPosition(localIndex)
                    binding.mGridView.post {
                        if (isFinishing || isDestroyed) return@post
                        binding.mGridView.findViewHolderForAdapterPosition(localIndex)
                            ?.itemView?.requestFocus()
                    }
                } else {
                    binding.mGridView.scrollToPosition(localIndex)
                }
            }
        }
    }

    /** 切换加载状态视图 */
    private fun showState(state: LoadState) {
        // 全屏模式下详情页内容区恒隐藏（窗口仅剩视频画面，供画中画渲染）：
        // 加载/成功等状态切换不得恢复内容区，避免 PiP/重载时"整个详情页"闪现
        val contentVisible = !fullWindows
        when (state) {
            LoadState.LOADING -> {
                binding.loadingView.visibility = View.VISIBLE
                binding.errorView.visibility = View.GONE
                binding.llLayout.visibility = View.GONE
            }
            LoadState.SUCCESS -> {
                binding.loadingView.visibility = View.GONE
                binding.errorView.visibility = View.GONE
                binding.llLayout.visibility = if (contentVisible) View.VISIBLE else View.GONE
            }
            LoadState.EMPTY -> {
                binding.loadingView.visibility = View.GONE
                binding.errorView.visibility = View.GONE
                binding.llLayout.visibility = if (contentVisible) View.VISIBLE else View.GONE
                binding.mEmptyPlaylist.visibility = View.VISIBLE
                binding.previewPlayer.visibility = View.GONE
                binding.previewPlayerBlock.visibility = View.GONE
            }
            LoadState.ERROR -> {
                binding.loadingView.visibility = View.GONE
                binding.errorView.visibility = View.VISIBLE
                binding.llLayout.visibility = View.GONE
                binding.previewPlayer.visibility = View.GONE
                binding.previewPlayerBlock.visibility = View.GONE
            }
            LoadState.IDLE -> {
                // 初始状态不处理
            }
        }
    }

    /** 带标签的文本展示，空内容隐藏视图 */
    private fun setTextShow(view: android.widget.TextView, label: String, content: String?) {
        // 清理 TVBox 人物链接标记（[a=...]name[/a] → name），避免原始标记上屏
        val safe = content?.replace(Regex("\\[a=[^\\]]*\\]|\\[/a\\]"), "")?.trim() ?: ""
        if (safe.isEmpty()) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        val labelText = if (label.isNotEmpty()) "$label: " else ""
        val html = "$labelText<font color=\"#FFFFFF\">$safe</font>"
        view.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html)
        }
    }

    /** 移除 HTML 标签与空白字符 */
    private fun removeHtmlTag(info: String?): String =
        info?.replace(Regex("<.*?>"), "")?.replace(Regex("\\s"), "") ?: ""

    /** 复制文本到系统剪贴板 */
    private fun copyToClipboard(content: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, content))
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 简介弹窗：点击简介弹出完整内容对话框，内容过长时可滚动 */
    private fun showDescDialog() {
        val info = viewModel.vodInfo.value ?: return
        val content = removeHtmlTag(info.des)
        if (content.isEmpty()) return
        DescDialog(this)
            .setDescribe(content)
            .show()
    }

    /** 简单防抖，避免快速连续点击触发多次播放 */
    private fun isFastClick(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < 500) return true
        lastClickTime = now
        return false
    }

    // ------------------------------------------------------------------
    // 预览播放器：全屏切换 / 跳转播放
    // ------------------------------------------------------------------

    /**
     * 全屏预览切后台进入画中画：详情页全屏（预览窗放大）时切后台，
     * 若开启画中画（后台播放类型=2）且播放中，进入 PiP 小窗继续显示画面。
     */
    @TargetApi(Build.VERSION_CODES.O)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (fullWindows && playFragment?.isPlayingNow() == true && playFragment?.backgroundPlayType() == 2) {
            enterPreviewPip()
        }
    }

    /** 进入画中画（全屏预览）：播放/暂停按钮复用 PlayFragment 注册的通知控制广播 */
    @TargetApi(Build.VERSION_CODES.O)
    private fun enterPreviewPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val params = PictureInPictureParams.Builder().apply {
                setAspectRatio(Rational(16, 9))
                setActions(
                    listOf(
                        RemoteAction(
                            Icon.createWithResource(this@DetailActivity, android.R.drawable.ic_media_play),
                            "播放/暂停",
                            "播放/暂停",
                            buildPipPendingIntent()
                        )
                    )
                )
            }.build()
            enterPictureInPictureMode(params)
        } catch (_: Throwable) {
            // 进入画中画失败忽略
        }
    }

    /** 画中画播放/暂停按钮 PendingIntent：广播经 PlayFragment 通知控制接收处理 */
    private fun buildPipPendingIntent(): PendingIntent {
        val intent = Intent(com.tvbox.kotlin.core.server.PlayService.ACTION_PLAY_CONTROL).apply {
            setPackage(packageName)
            putExtra(
                com.tvbox.kotlin.core.server.PlayService.EXTRA_CONTROL_ACTION,
                com.tvbox.kotlin.core.server.PlayService.ACTION_PLAY_PAUSE
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(this, PIP_CONTROL_REQUEST, intent, flags)
    }

    /** 切换全屏/小窗预览播放器 */
    private fun toggleFullPreview() {
        if (windowsPreview == null) {
            windowsPreview = binding.previewPlayer.layoutParams
            windowsFull = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = 0
                leftMargin = 0
            }
        }

        if (!fullWindows) {
            binding.llLayout.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            binding.previewPlayer.layoutParams = windowsFull
            binding.previewPlayerBlock.layoutParams = windowsFull
            fullWindows = true
            // 全屏：真正隐藏详情页内容区（信息/列表），窗口仅剩视频画面——
            // 既保证视觉纯净，也使画中画（实时渲染整个窗口）只显示视频
            binding.llLayout.visibility = View.GONE
            // 全屏：音频面板显示标题+大波形
            playFragment?.setFullscreenMode(true)
            binding.previewPlayerPlace.visibility = View.GONE
            hideSystemUI(false)
            // 全屏模式下隐藏拦截视图，让事件完全传递给播放器控制器
            binding.previewPlayerBlock.visibility = View.GONE
            binding.previewPlayerBlock.post {
                window.currentFocus?.clearFocus()
            }
        } else {
            // 先隐藏播放器控制栏，避免视觉闪烁（hideBottom 不会触发布局重建后的重新显示）
            playFragment?.vodController?.hideBottom()
            // 立即改变布局
            binding.previewPlayer.layoutParams = windowsPreview
            binding.previewPlayerBlock.layoutParams = windowsPreview
            fullWindows = false
            // 恢复详情页内容区
            binding.llLayout.visibility = View.VISIBLE
            // 退回预览小窗：音频面板隐藏标题、缩小波形
            playFragment?.setFullscreenMode(false)
            // 延迟显示 UI 元素，让控制器先隐藏
            binding.previewPlayerPlace.visibility = View.INVISIBLE
            binding.previewPlayerBlock.visibility = View.INVISIBLE
            hideSystemUI(true)
            // 延迟显示预览模式 UI，并将焦点交还预览窗，便于遥控器继续操作
            binding.previewPlayer.postDelayed({
                binding.previewPlayerPlace.visibility = View.VISIBLE
                binding.previewPlayerBlock.visibility = View.VISIBLE
                binding.previewPlayerBlock.requestFocus()
            }, 150)
            binding.llLayout.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            // 退出全屏后同步集数选中状态：
            // - 预览窗切集过（previewPlayingFlag 非空）：以预览窗切集结果为准。
            //   预览窗在嗅探/加载中时 Room 历史未更新，直接 syncEpisodeFromHistory 会用旧集覆盖（换集后立即全屏、返回时焦点回退旧集的边界 bug）；
            // - 预览窗未切集（全屏页切集/正常播放）：从播放历史恢复（全屏页把切集进度写入 Room）。
            if (previewPlayingFlag.isNotEmpty()) {
                // previewPlayIndex 为组内索引（getplayIndex），而同步接口接收绝对索引；
                // 直接传入会在多分组时把第 N 组集数错选为第 1 组同位置集
                val info = viewModel.vodInfo.value
                val absoluteIndex = info?.playEpisodeIndex ?: previewPlayIndex
                viewModel.syncPreviewEpisode(previewPlayingFlag, absoluteIndex)
            } else {
                viewModel.syncEpisodeFromHistory()
            }
        }
    }

    /** 跳转播放：预览模式在详情页内播放，海报模式跳转播放页 */
    private fun jumpToPlay() {
        val info = viewModel.vodInfo.value ?: return
        // 线路无效（历史线路失效等）时回退首个可用线路，避免预览播放器不初始化
        val flag = info.playFlag?.takeIf { info.seriesMap?.containsKey(it) == true }
            ?: info.seriesFlags?.firstOrNull()?.name
            ?: return
        val seriesList = info.seriesMap?.get(flag) ?: return
        if (seriesList.isEmpty()) return

        preFlag = flag
        val parseListJson = viewModel.getParseListJson()
        val bundle = Bundle().apply {
            putString("sourceKey", info.sourceKey)
            putSerializable("VodInfo", info)
            // 注入解析器列表，保证 parse=1 时预览播放器可正常解析
            parseListJson?.let { putString("parseList", it) }
        }

        if (showPreview) {
            if (previewVodInfo == null) {
                previewVodInfo = runCatching { info.clone() as VodInfo }.getOrNull()
            }
            previewVodInfo?.let { preview ->
                preview.playerCfg = info.playerCfg
                preview.playFlag = info.playFlag
                preview.playIndex = info.playIndex
                preview.playGroup = info.playGroup
                preview.reverseSort = info.reverseSort
                preview.playGroupCount = info.playGroupCount
                preview.playEpisodeIndex = info.playEpisodeIndex
                preview.playEpisodeName = info.playEpisodeName
                // 同步最新线路数据（切换线路/排序后 info 的列表内容已变化，
                // preview 需重建 seriesMap 才能取到正确的集数与选中状态）
                preview.seriesMap = LinkedHashMap<String, List<VodInfo.VodSeries>>().apply {
                    info.seriesMap?.forEach { (key, value) ->
                        put(
                            key,
                            value.map { series ->
                                VodInfo.VodSeries().apply {
                                    name = series.name
                                    url = series.url
                                    episodeId = series.episodeId
                                    selected = series.selected
                                }
                            }
                        )
                    }
                }
                bundle.putSerializable("VodInfo", preview)
            }
            playFragment?.setData(bundle)
            previewPlayingFlag = info.playFlag ?: ""
            previewPlayIndex = info.getplayIndex()
            isPreviewPlaying = true
        } else {
            navigator.goPlay(
                this@DetailActivity,
                "",
                info.name,
                0L,
                Intent().apply {
                    putExtra(EXTRA_SOURCE_KEY, info.sourceKey)
                    putExtra(EXTRA_VOD_INFO, info)
                    // 注入解析器列表，保证 parse=1 时播放页可正常解析与展示解析栏
                    parseListJson?.let { putExtra("parseList", it) }
                }
            )
        }
    }

    /** 保存观看记录：仅播放成功后保存，优先使用预览播放器的最新数据 */
    private fun saveVod() {
        if (!playSuccess) return
        val fragment = playFragment
        if (fragment != null && fragment.isAdded) {
            fragment.saveVodRecord()
        } else {
            viewModel.saveHistory()
        }
    }

    /**
     * 预览窗切集（自动连播/上一集/下一集）后同步详情页：
     * 预览播放器内部持有独立 VodInfo，切集不经过本页 ViewModel，
     * 通过 [DetailViewModel.syncPreviewEpisode] 同步集数列表选中状态；
     * 同时更新预览播放位置记录，保证"再次点击已播放集进入全屏"判断与当前播放集一致。
     */
    private fun onPreviewEpisodeChanged(flag: String, index: Int) {
        viewModel.syncPreviewEpisode(flag, index)
        previewPlayingFlag = flag
        val info = viewModel.vodInfo.value
        previewPlayIndex = info?.getplayIndex() ?: -1
    }

    // ------------------------------------------------------------------
    // 系统 UI
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun hideSystemUI(shownavbar: Boolean) {
        // 允许内容延伸到刘海/挖孔区域
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (controller != null) {
                var types = WindowInsets.Type.statusBars()
                if (!shownavbar) {
                    types = types or WindowInsets.Type.navigationBars()
                }
                controller.hide(types)
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
            }
            // 必须关闭系统窗口适配，否则内容无法延伸到刘海/挖孔区域
            window.setDecorFitsSystemWindows(false)
        } else {
            var uiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
            uiVisibility = uiVisibility or View.SYSTEM_UI_FLAG_IMMERSIVE
            uiVisibility = uiVisibility or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            if (!shownavbar) {
                uiVisibility = uiVisibility or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                uiVisibility = uiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
            window.decorView.systemUiVisibility = uiVisibility
        }
    }

    // ------------------------------------------------------------------
    // 按键处理
    // ------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && fullWindows) {
            // 全屏模式下先检查控制栏是否可见，若可见则先隐藏控制栏
            if (playFragment?.onBackPressed() == true) {
                return true
            }
            // 控制栏不可见时退出全屏，回到详情页
            toggleFullPreview()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (fullWindows && playFragment?.dispatchKeyEvent(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        // 从播放页返回时同步集数选中状态：播放页是独立 Activity（info 为序列化副本），
        // 控制栏切集后本页选中状态未更新，从历史记录恢复最新线路/集索引
        viewModel.syncEpisodeFromHistory()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 窗口获焦时若焦点未落在任一列表或预览窗，则交还预览窗，
        // 避免系统默认焦点短暂停在按钮上造成焦点框闪现
        if (!hasFocus || !showPreview) return
        if (binding.previewPlayerBlock.visibility != View.VISIBLE) return
        val focus = window.currentFocus ?: return
        val inList = focus is TvRecyclerView || focus.parent is TvRecyclerView
        if (!inList && focus !== binding.previewPlayerBlock) {
            binding.previewPlayerBlock.requestFocus()
        }
    }

    override fun onStop() {
        super.onStop()
        // 页面不可见时保存播放进度到历史记录（仅播放成功后）
        saveVod()
    }

    override fun onDestroy() {
        playFragment?.let { fragment ->
            fragment.clearHandlerMessages()
            if (fragment.isAdded) {
                supportFragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitAllowingStateLoss()
            }
        }
        playFragment = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_VOD_ID = "vodId"
        private const val EXTRA_LEGACY_ID = "id"
        private const val EXTRA_SOURCE_KEY = "sourceKey"
        private const val EXTRA_VOD_INFO = "vodInfo"
        private const val EXTRA_PIC = "pic"

        /** 画中画按钮 PendingIntent 请求码 */
        private const val PIP_CONTROL_REQUEST = 2001
    }
}
