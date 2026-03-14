package com.github.tvbox.osc.ui.activity

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.*
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.TextUtils
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import com.blankj.utilcode.util.ToastUtils
import com.chad.library.adapter.base.BaseQuickAdapter
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.bean.*
import com.github.tvbox.osc.cache.RoomDataManger
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.server.PlayService
import com.github.tvbox.osc.ui.adapter.SeriesAdapter
import com.github.tvbox.osc.ui.adapter.SeriesFlagAdapter
import com.github.tvbox.osc.ui.adapter.SeriesGroupAdapter
import com.github.tvbox.osc.ui.dialog.DescDialog
import com.github.tvbox.osc.ui.dialog.PushDialog
import com.github.tvbox.osc.ui.dialog.QuickSearchDialog
import com.github.tvbox.osc.ui.fragment.PlayFragment
import com.github.tvbox.osc.util.*
import com.github.tvbox.osc.util.SubtitleHelper
import com.github.tvbox.osc.util.thunder.Thunder
import com.github.tvbox.osc.viewmodel.SourceViewModel
import com.google.gson.JsonParser
import com.lzy.okgo.OkGo
import com.lzy.okgo.callback.AbsCallback
import com.lzy.okgo.model.Response as OkGoResponse
import com.orhanobut.hawk.Hawk
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.json.JSONObject
import java.io.*
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.regex.Pattern

class DetailActivity : BaseActivity() {

    companion object {
        const val BROADCAST_ACTION = "VOD_CONTROL"
        const val BROADCAST_ACTION_PREV = 0
        const val BROADCAST_ACTION_PLAYPAUSE = 1
        const val BROADCAST_ACTION_NEXT = 2

        private val NUM_PATTERN = Pattern.compile("\\d+")

        fun getNum(str: String?): Int {
            if (str == null) return 0
            val matcher = NUM_PATTERN.matcher(str)
            if (!matcher.find()) return 0
            val group = matcher.group(0)
            return if (TextUtils.isEmpty(group)) 0 else group.toInt()
        }
    }

    // 播放成功状态，用于控制是否允许进入全屏
    private var playSuccess = false

    /**
     * 设置播放成功状态
     */
    fun setPlaySuccess(success: Boolean) {
        playSuccess = success
    }

    private lateinit var llLayout: LinearLayout
    private lateinit var llPlayerFragmentContainer: FragmentContainerView
    private lateinit var llPlayerFragmentContainerBlock: View
    private lateinit var llPlayerPlace: View
    private var playFragment: PlayFragment? = null
    private lateinit var ivThumb: ImageView
    private lateinit var tvName: TextView
    private lateinit var tvYear: TextView
    private lateinit var tvSite: TextView
    private lateinit var tvArea: TextView
    private lateinit var tvLang: TextView
    private lateinit var tvType: TextView
    private lateinit var tvActor: TextView
    private lateinit var tvDirector: TextView
    private lateinit var tvDes: TextView
    private lateinit var tvDesc: TextView
    private lateinit var tvPlay: TextView
    private lateinit var tvSort: TextView
    private lateinit var tvPush: TextView
    private lateinit var tvQuickSearch: TextView
    private lateinit var tvCollect: TextView
    private lateinit var mGridViewFlag: TvRecyclerView
    private lateinit var mGridView: TvRecyclerView
    private lateinit var mSeriesGroupView: TvRecyclerView
    private lateinit var mEmptyPlayList: LinearLayout
    private lateinit var tvPlayUrl: ImageView

    private lateinit var sourceViewModel: SourceViewModel
    private var mVideo: Movie.Video? = null
    private var vodInfo: VodInfo? = null
    private lateinit var seriesFlagAdapter: SeriesFlagAdapter
    private lateinit var seriesAdapter: SeriesAdapter
    private var seriesSelect = false
    private var seriesFlagFocus: View? = null
    private var mCheckSources: HashMap<String, String>? = null
    private lateinit var mGridViewLayoutMgr: V7GridLayoutManager
    private var preFlag = ""
    private var pauseRunnable: MutableList<Runnable>? = null
    private var searchTitle = ""
    private var hadQuickStart = false
    private val quickSearchWord = mutableListOf<String>()
    private var searchExecutorService: ExecutorService? = null
    private val searchExecutorLock = Object()
    private lateinit var seriesGroupAdapter: SeriesGroupAdapter
    private var seriesGroups: MutableList<List<VodInfo.VodSeries>>? = null
    private var groupCount = 0
    private var groupIndex = 0
    private var previewPlayingFlag = ""
    private var isPreviewPlaying = false
    private var previewPlayIndex = -1  // 记录当前播放的绝对索引

    private var previewVodInfo: VodInfo? = null
    private val showPreview: Boolean by lazy { Hawk.get(HawkConfig.SHOW_PREVIEW, true) }
    @JvmField var fullWindows = false
    private var windowsPreview: ViewGroup.LayoutParams? = null
    private var windowsFull: ViewGroup.LayoutParams? = null

    private val quickSearchData = mutableListOf<Movie.Video>()
    private var pipActionReceiver: BroadcastReceiver? = null
    private var quickSearchDialog: QuickSearchDialog? = null
    
    /**
     * Home键广播,用于触发后台服务
     */
    private var mHomeKeyReceiver: BroadcastReceiver? = null
    
    /**
     * 是否开启后台播放标记,不在广播开启,onPause根据标记开启
     */
    var openBackgroundPlay = false

    var vodId: String? = null
    var sourceKey: String? = null
    var firstSourceKey: String? = null

    override fun getLayoutResID(): Int = R.layout.activity_detail

    override fun init() {
        EventBus.getDefault().register(this)
        initReceiver()
        initView()
        initViewModel()
        initData()
    }
    
    private fun initReceiver() {
        // 注册广播接收器
        if (mHomeKeyReceiver == null) {
            mHomeKeyReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    if (action != null && action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                        openBackgroundPlay = Hawk.get(HawkConfig.BACKGROUND_PLAY_TYPE, 0) == 1 && 
                                playFragment?.player != null && playFragment?.player?.isPlaying == true
                    }
                }
            }
            registerReceiver(mHomeKeyReceiver, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        }
    }

    private fun shutdownSearchExecutor() {
        synchronized(searchExecutorLock) {
            searchExecutorService?.shutdownNow()
            searchExecutorService = null
        }
    }

    private fun initSearchExecutor() {
        synchronized(searchExecutorLock) {
            searchExecutorService?.shutdownNow()
            searchExecutorService = Executors.newFixedThreadPool(5)
        }
    }

    override fun onResume() {
        super.onResume()
        openBackgroundPlay = false
        synchronized(searchExecutorLock) {
            pauseRunnable?.takeIf { it.isNotEmpty() }?.let { runnables ->
                initSearchExecutor()
                runnables.forEach { searchExecutorService?.execute(it) }
                pauseRunnable?.clear()
                pauseRunnable = null
            }
        }
    }

    override fun onPause() {
        super.onPause()
        synchronized(searchExecutorLock) {
            searchExecutorService?.let {
                pauseRunnable = it.shutdownNow().toMutableList()
                searchExecutorService = null
            }
        }
        playFragment?.takeIf { it.isAdded }?.mVideoView?.pause()
    }

    override fun onStop() {
        super.onStop()
        // 页面不可见时保存播放记录（只有播放成功才保存）
        insertVod(firstSourceKey, vodInfo)
    }

    private fun initView() {
        llLayout = findViewById(R.id.llLayout)
        llPlayerPlace = findViewById(R.id.previewPlayerPlace)
        llPlayerFragmentContainer = findViewById(R.id.previewPlayer)
        llPlayerFragmentContainerBlock = findViewById(R.id.previewPlayerBlock)
        ivThumb = findViewById(R.id.ivThumb)

        llPlayerPlace.visibility = if (showPreview) View.VISIBLE else View.GONE
        ivThumb.visibility = if (!showPreview) View.VISIBLE else View.GONE

        tvName = findViewById(R.id.tvName)
        tvYear = findViewById(R.id.tvYear)
        tvSite = findViewById(R.id.tvSite)
        tvArea = findViewById(R.id.tvArea)
        tvLang = findViewById(R.id.tvLang)
        tvType = findViewById(R.id.tvType)
        tvActor = findViewById(R.id.tvActor)
        tvDirector = findViewById(R.id.tvDirector)
        tvDes = findViewById(R.id.tvDes)
        tvDesc = findViewById(R.id.tvDesc)
        tvPlay = findViewById(R.id.tvPlay)
        tvSort = findViewById(R.id.tvSort)
        tvPush = findViewById(R.id.tvPush)
        tvCollect = findViewById(R.id.tvCollect)
        tvQuickSearch = findViewById(R.id.tvQuickSearch)
        tvPlayUrl = findViewById(R.id.tvPlayUrl)
        mEmptyPlayList = findViewById(R.id.mEmptyPlaylist)

        mGridView = findViewById(R.id.mGridView)
        mGridView.setHasFixedSize(false)
        mGridViewLayoutMgr = V7GridLayoutManager(mContext, 6)
        mGridView.layoutManager = mGridViewLayoutMgr
        seriesAdapter = SeriesAdapter()
        mGridView.adapter = seriesAdapter

        mGridViewFlag = findViewById(R.id.mGridViewFlag)
        mGridViewFlag.setHasFixedSize(true)
        mGridViewFlag.layoutManager = V7LinearLayoutManager(mContext, 0, false)
        seriesFlagAdapter = SeriesFlagAdapter()
        mGridViewFlag.adapter = seriesFlagAdapter

        if (showPreview) {
            playFragment = PlayFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.previewPlayer, playFragment!!)
                .commit()
            supportFragmentManager.beginTransaction()
                .show(playFragment!!)
                .commitAllowingStateLoss()
            tvPlay.visibility = View.GONE
        } else {
            tvPlay.visibility = View.VISIBLE
            tvPlay.text = getString(R.string.det_play)
            tvPlay.requestFocus()
        }

        mSeriesGroupView = findViewById(R.id.mSeriesGroupView)
        mSeriesGroupView.setHasFixedSize(true)
        mSeriesGroupView.layoutManager = V7LinearLayoutManager(mContext, 0, false)
        seriesGroupAdapter = SeriesGroupAdapter()
        mSeriesGroupView.adapter = seriesGroupAdapter

        initClickListeners()
        initRecyclerViewListeners()
        setLoadSir(llLayout)
    }

    private fun initClickListeners() {
        tvSort.setOnClickListener {
            vodInfo?.takeIf { it.seriesMap != null && it.playFlag != null && it.seriesMap[it.playFlag]?.isNotEmpty() == true }?.let { info ->
                val currentAbsoluteIndex = info.getplayIndex()
                val totalEpisodes = info.seriesMap[info.playFlag]?.size ?: 0
                info.reverseSort = !info.reverseSort
                updateSortButtonText()

                info.seriesMap[info.playFlag]?.forEach { it.selected = false }
                info.reverse()

                val newAbsoluteIndex = totalEpisodes - 1 - currentAbsoluteIndex
                info.playEpisodeIndex = newAbsoluteIndex
                info.playGroup = newAbsoluteIndex / maxOf(info.playGroupCount, 1)
                info.playIndex = newAbsoluteIndex % maxOf(info.playGroupCount, 1)
                groupIndex = info.playGroup
                groupCount = info.playGroupCount

                info.seriesMap[info.playFlag]?.getOrNull(newAbsoluteIndex)?.selected = true
                info.saveCurrentEpisodeInfo()
                refreshList()
                insertVod(firstSourceKey, info)
                previewPlayIndex = info.getplayIndex()
                playFragment?.updateVodInfo(info)
            }
        }

        tvPush.setOnClickListener {
            val dialog = PushDialog(mContext)
            dialog.setPushData(vodId, sourceKey)
            dialog.show()
        }

        tvPlay.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            if (showPreview) toggleFullPreview() else jumpToPlay()
        }

        ivThumb.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            jumpToPlay()
        }

        llPlayerFragmentContainerBlock.setOnClickListener { v ->
            FastClickCheckUtil.check(v)
            // 只有在非全屏模式下才切换全屏，避免与播放器控制器冲突
            if (!fullWindows) {
                toggleFullPreview()
            }
        }

        tvQuickSearch.setOnClickListener {
            startQuickSearch()
            quickSearchDialog = QuickSearchDialog(this).apply {
                if (!isFinishing && !isDestroyed) {
                    EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, quickSearchData))
                    EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, quickSearchWord))
                }
                show()
                synchronized(searchExecutorLock) {
                    pauseRunnable?.takeIf { it.isNotEmpty() }?.let { runnables ->
                        initSearchExecutor()
                        runnables.forEach { searchExecutorService?.execute(it) }
                        pauseRunnable?.clear()
                        pauseRunnable = null
                    }
                }
                setOnDismissListener {
                    synchronized(searchExecutorLock) {
                        searchExecutorService?.let {
                            pauseRunnable = it.shutdownNow().toMutableList()
                            searchExecutorService = null
                        }
                    }
                    quickSearchDialog = null
                }
            }
        }

        tvCollect.setOnClickListener {
            val text = tvCollect.text.toString()
            val currentSourceKey = sourceKey ?: return@setOnClickListener
            val currentVodInfo = vodInfo ?: return@setOnClickListener

            if (getString(R.string.det_fav_unstar) == text) {
                RoomDataManger.insertVodCollect(currentSourceKey, currentVodInfo)
                ToastHelper.showToast(getString(R.string.det_fav_add))
                tvCollect.setText(R.string.det_fav_star)
            } else {
                RoomDataManger.deleteVodCollect(currentSourceKey, currentVodInfo)
                ToastHelper.showToast(getString(R.string.det_fav_del))
                tvCollect.setText(R.string.det_fav_unstar)
            }
        }

        tvDesc.setOnClickListener { v ->
            runOnUiThread {
                FastClickCheckUtil.check(v)
                DescDialog(mContext).apply {
                    setDescribe(removeHtmlTag(mVideo?.des))
                    show()
                }
            }
        }

        tvDesc.setOnLongClickListener { v ->
            runOnUiThread {
                FastClickCheckUtil.check(v)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val content = removeHtmlTag(mVideo?.des)
                clipboard.setPrimaryClip(ClipData.newPlainText(null, content))
                ToastHelper.showToast("已复制：$content")
            }
            true
        }

        tvPlayUrl.setOnClickListener {
            val info = vodInfo ?: return@setOnClickListener
            val flag = info.playFlag ?: return@setOnClickListener
            val seriesList = info.seriesMap[flag] ?: return@setOnClickListener
            val playIndex = info.getplayIndex()
            if (playIndex < 0 || playIndex >= seriesList.size) return@setOnClickListener

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(null, seriesList[playIndex].url))
            ToastHelper.showToast(getString(R.string.det_url))
        }

        tvName.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val content = "视频ID：${vodId}，图片地址：${mVideo?.pic ?: ""}"
            clipboard.setPrimaryClip(ClipData.newPlainText(null, content))
            ToastHelper.showToast("已复制$content")
        }
    }

    private fun initRecyclerViewListeners() {
        mGridView.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                seriesSelect = false
            }

            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {
                seriesSelect = true
            }

            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {}
        })

        mGridViewFlag.setOnItemListener(object : TvRecyclerView.OnItemListener {
            private fun refresh(itemView: View?, position: Int) {
                if (position == -1) return
                val newFlag = seriesFlagAdapter.getItem(position)?.name ?: return
                val info = vodInfo ?: return

                if (info.playFlag != newFlag) {
                    info.seriesFlags.forEachIndexed { index, flag ->
                        if (flag.name == info.playFlag) {
                            flag.selected = false
                            seriesFlagAdapter.notifyItemChanged(index)
                        }
                    }
                    info.seriesFlags.getOrNull(position)?.selected = true

                    val preFlag = info.playFlag
                    val preTotalEpisodes = info.seriesMap[preFlag]?.size ?: 0
                    info.seriesMap[preFlag]?.forEach { it.selected = false }

                    val currentIndex = info.getplayIndex()
                    val currentEpisodeName = if (preTotalEpisodes > 0 && currentIndex in 0 until preTotalEpisodes) {
                        info.seriesMap[preFlag]?.get(currentIndex)?.name
                    } else null

                    val visualPosition = if (info.reverseSort) preTotalEpisodes - currentIndex else currentIndex + 1

                    info.playFlag = newFlag
                    seriesFlagAdapter.notifyItemChanged(position)

                    val newTotalEpisodes = info.seriesMap[newFlag]?.size ?: 0
                    if (newTotalEpisodes <= 0) {
                        info.playEpisodeIndex = 0
                        info.playGroup = 0
                        info.playIndex = 0
                        refreshList()
                        jumpToPlay()
                        return
                    }

                    val newEpisodeIndex = currentEpisodeName?.let { name ->
                        info.seriesMap[newFlag]?.indexOfFirst { it.name == name }?.takeIf { it >= 0 }
                    } ?: when {
                        visualPosition > newTotalEpisodes -> if (info.reverseSort) 0 else newTotalEpisodes - 1
                        else -> if (info.reverseSort) newTotalEpisodes - visualPosition else visualPosition - 1
                    }

                    val newGroupCount = calculateGroupCount(newTotalEpisodes)
                    info.playGroupCount = newGroupCount
                    info.playEpisodeIndex = newEpisodeIndex
                    info.updatePlayPositionFromEpisodeIndex()

                    refreshList()
                    jumpToPlay()
                }
                seriesFlagFocus = itemView
            }

            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {}
            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) = refresh(itemView, position)
            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) = refresh(itemView, position)
        })

        seriesAdapter.setOnItemClickListener { _, view, position ->
            FastClickCheckUtil.check(view)
            val info = vodInfo ?: return@setOnItemClickListener
            val flag = info.playFlag ?: return@setOnItemClickListener
            val seriesList = info.seriesMap[flag] ?: return@setOnItemClickListener
            if (seriesList.isEmpty()) return@setOnItemClickListener

            val newAbsoluteIndex = groupIndex * groupCount + position
            var reload = false

            if (info.getplayIndex() != newAbsoluteIndex) {
                // 清除所有选中状态并刷新UI
                seriesAdapter.data.forEachIndexed { index, series ->
                    if (series.selected) {
                        series.selected = false
                        seriesAdapter.notifyItemChanged(index)
                    }
                }
                seriesAdapter.getItem(position)?.selected = true
                seriesAdapter.notifyItemChanged(position)
                info.playIndex = position
                info.playGroup = groupIndex
                info.playEpisodeIndex = newAbsoluteIndex
                reload = true
                // 切换选集时，重置播放成功状态
                playSuccess = false
            }
            if (info.playFlag != preFlag) {
                reload = true
                // 切换路线时，重置播放成功状态
                playSuccess = false
            }

            if (showPreview && !fullWindows && isPreviewPlaying &&
                info.playFlag == previewPlayingFlag &&
                info.getplayIndex() == previewPlayIndex) {
                // 只有当前集播放成功后才允许点击进入全屏
                if (playSuccess) {
                    toggleFullPreview()
                }
            }
            if (reload || !showPreview) jumpToPlay()
        }

        mSeriesGroupView.setOnItemListener(object : TvRecyclerView.OnItemListener {
            private fun refresh(itemView: View?, position: Int) {
                if (groupIndex != position) {
                    seriesGroupAdapter.getItem(groupIndex)?.selected = false
                    seriesGroupAdapter.notifyItemChanged(groupIndex)
                    seriesGroupAdapter.getItem(position)?.selected = true
                    seriesGroupAdapter.notifyItemChanged(position)
                    // 清除所有选中状态并刷新UI
                    seriesAdapter.data.forEachIndexed { index, series ->
                        if (series.selected) {
                            series.selected = false
                            seriesAdapter.notifyItemChanged(index)
                        }
                    }

                    groupIndex = position
                    seriesGroups?.getOrNull(position)?.let { seriesAdapter.setNewData(it) }

                    val currentPlayIndex = vodInfo?.getplayIndex() ?: 0
                    val groupStartIndex = position * groupCount
                    val groupEndIndex = minOf((position + 1) * groupCount, vodInfo?.seriesMap?.get(vodInfo?.playFlag)?.size ?: 0)

                    if (currentPlayIndex in groupStartIndex until groupEndIndex) {
                        val localIndex = currentPlayIndex - groupStartIndex
                        seriesAdapter.getItem(localIndex)?.selected = true
                        seriesAdapter.notifyItemChanged(localIndex)
                    }
                }
            }

            override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {}
            override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) = refresh(itemView, position)
            override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) = refresh(itemView, position)
        })
    }

    private fun calculateGroupCount(size: Int): Int = when {
        size > 2500 -> 300
        size > 1500 -> 200
        size > 1000 -> 150
        size > 500 -> 100
        size > 300 -> 50
        size > 100 -> 30
        else -> 20
    }

    fun toggleFullPreview() {
        if (windowsPreview == null) {
            windowsPreview = llPlayerFragmentContainer.layoutParams
            windowsFull = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                topMargin = 0
                leftMargin = 0
            }
        }

        if (!fullWindows) {
            llLayout.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            llPlayerFragmentContainer.layoutParams = windowsFull
            llPlayerFragmentContainerBlock.layoutParams = windowsFull
            fullWindows = true
            llPlayerPlace.visibility = View.GONE
            hideSystemUI(false)
            // 全屏模式下隐藏拦截视图，让事件完全传递给播放器控制器
            llPlayerFragmentContainerBlock.visibility = View.GONE
            llPlayerFragmentContainerBlock.post {
                window.currentFocus?.clearFocus()
            }
        } else {
            // 先隐藏播放器控制器，避免视觉闪烁
            playFragment?.getVodController()?.hideBottom()
            // 立即改变布局
            llPlayerFragmentContainer.layoutParams = windowsPreview
            llPlayerFragmentContainerBlock.layoutParams = windowsPreview
            fullWindows = false
            // 延迟显示UI元素，让控制器先隐藏
            llPlayerPlace.visibility = View.INVISIBLE
            llPlayerFragmentContainerBlock.visibility = View.INVISIBLE
            hideSystemUI(true)
            // 延迟显示预览模式UI
            llPlayerFragmentContainer.postDelayed({
                llPlayerPlace.visibility = View.VISIBLE
                llPlayerFragmentContainerBlock.visibility = View.VISIBLE
            }, 150)
            llLayout.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
    }

    private fun jumpToPlay() {
        val info = vodInfo ?: return
        val flag = info.playFlag ?: return
        val seriesList = info.seriesMap[flag] ?: return
        if (seriesList.isEmpty()) return

        preFlag = flag
        val bundle = Bundle().apply {
            putString("sourceKey", firstSourceKey)
            putSerializable("VodInfo", info)
        }

        if (showPreview) {
            if (previewVodInfo == null) {
                previewVodInfo = try {
                    ByteArrayOutputStream().use { bos ->
                        ObjectOutputStream(bos).use { oos ->
                            oos.writeObject(info)
                            oos.flush()
                        }
                        ByteArrayInputStream(bos.toByteArray()).use { bis ->
                            ObjectInputStream(bis).readObject() as VodInfo
                        }
                    }
                } catch (e: Exception) {
                    null
                }
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
                preview.seriesMap = LinkedHashMap<String, List<VodInfo.VodSeries>>().apply {
                    info.seriesMap.forEach { (key, value) ->
                        val newList = value.map { series ->
                            VodInfo.VodSeries().apply {
                                name = series.name
                                url = series.url
                                episodeId = series.episodeId
                                selected = series.selected
                            }
                        }
                        put(key, newList)
                    }
                }
                bundle.putSerializable("VodInfo", preview)
            }

            playFragment?.setData(bundle)
            previewPlayingFlag = info.playFlag
            previewPlayIndex = info.getplayIndex()
            isPreviewPlaying = true
        } else {
            jumpActivity(PlayActivity::class.java, bundle)
        }
    }

    private fun refreshList() {
        val info = vodInfo ?: return
        val flag = info.playFlag ?: return
        val seriesList = info.seriesMap[flag] ?: return

        var absoluteIndex = info.getplayIndex()
        if (seriesList.size <= absoluteIndex) {
            absoluteIndex = 0
            info.playIndex = 0
            info.playGroup = 0
            info.playEpisodeIndex = 0  // 同步更新 playEpisodeIndex
        }

        seriesList.forEach { it.selected = false }
        if (seriesList.size > absoluteIndex) {
            seriesList[absoluteIndex].selected = true
        } else {
            info.playGroup = 0
            info.playIndex = 0
            info.playEpisodeIndex = 0  // 同步更新 playEpisodeIndex
        }

        seriesList.forEachIndexed { index, series ->
            if (series.name.isNullOrEmpty()) {
                series.name = if (seriesList.size == 1) info.name else "$index"
            }
        }

        val paint = Paint()
        var maxWidth = 1
        seriesList.forEach { series ->
            val width = paint.measureText(series.name).toInt()
            if (maxWidth < width) maxWidth = width
        }
        maxWidth += 32

        val screenWidth = windowManager.defaultDisplay.width / 3
        val offset = (screenWidth / maxWidth).coerceIn(2, 6)
        mGridViewLayoutMgr.spanCount = offset

        val groupList = getSeriesGroupList()
        if (info.playGroup < groupList.size) {
            groupList[info.playGroup].selected = true
        }
        seriesGroupAdapter.setNewData(groupList)

        if (info.playGroup < (seriesGroups?.size ?: 0)) {
            seriesGroups?.get(info.playGroup)?.let { seriesAdapter.setNewData(it) }
        }

        mGridView.postDelayed({
            mGridView.scrollToPosition(info.playIndex)
            mSeriesGroupView.scrollToPosition(info.playGroup)
        }, 100)
    }

    private fun getSeriesGroupList(): List<VodSeriesGroup> {
        val arrayList = mutableListOf<VodSeriesGroup>()
        seriesGroups?.clear() ?: run { seriesGroups = mutableListOf() }

        val info = vodInfo ?: return arrayList
        val flag = info.playFlag ?: return arrayList
        val vodSeries = info.seriesMap[flag] ?: return arrayList
        val size = vodSeries.size

        groupCount = calculateGroupCount(size)
        info.playGroupCount = groupCount

        val absoluteIndex = info.getplayIndex()
        groupIndex = absoluteIndex / groupCount
        if (groupIndex < 0) groupIndex = 0

        val groups = kotlin.math.ceil(size / (groupCount + 0.0f)).toInt()
        for (i in 0 until groups) {
            mSeriesGroupView.visibility = View.VISIBLE
            val start = i * groupCount + 1
            val end = (i + 1) * groupCount
            val infoList = mutableListOf<VodInfo.VodSeries>()

            if (end < size) {
                for (j in start - 1 until end) {
                    infoList.add(vodSeries[j])
                }
                val name = if (info.reverseSort) "${size - start + 1}-${size - end + 1}" else "$start-$end"
                arrayList.add(VodSeriesGroup(name))
            } else {
                for (j in start - 1 until size) {
                    infoList.add(vodSeries[j])
                }
                val name = if (info.reverseSort) "${size - start + 1}-1" else "$start-$size"
                arrayList.add(VodSeriesGroup(name))
            }
            seriesGroups?.add(infoList)
        }
        return arrayList
    }

    private fun initViewModel() {
        sourceViewModel = ViewModelProvider(this).get(SourceViewModel::class.java)
        sourceViewModel.detailResult.observe(this) { absXml ->
            if (isFinishing || isDestroyed) return@observe

            if (absXml?.movie?.videoList?.isNotEmpty() == true) {
                showSuccess()
                if (!absXml.msg.isNullOrEmpty() && absXml.msg != "数据列表") {
                    ToastHelper.showToast(absXml.msg)
                    showEmpty()
                    return@observe
                }

                mVideo = absXml.movie.videoList[0].apply { id = vodId }
                if (mVideo?.name.isNullOrEmpty()) mVideo?.name = "片名"

                vodInfo = VodInfo().apply {
                    setVideo(mVideo)
                    sourceKey = firstSourceKey
                }
                sourceKey = mVideo?.sourceKey

                // 从历史记录中恢复播放信息
                restoreVodInfoFromHistory()

                updateUI()
            } else {
                showEmpty()
                llPlayerFragmentContainer.visibility = View.GONE
                llPlayerFragmentContainerBlock.visibility = View.GONE
            }
        }
    }

    private fun updateUI() {
        val video = mVideo ?: return
        val info = vodInfo ?: return

        tvName.text = video.name
        val firstSource = ApiConfig.get().getSource(firstSourceKey)
        setTextShow(tvSite, getString(R.string.det_source), firstSource?.name ?: "")
        setTextShow(tvYear, getString(R.string.det_year), if (video.year == 0) "" else video.year.toString())
        setTextShow(tvArea, getString(R.string.det_area), video.area)
        setTextShow(tvLang, getString(R.string.det_lang), video.lang)

        // 加载海报图片
        if (!video.pic.isNullOrEmpty()) {
            ImgUtil.load(video.pic, ivThumb, 14)
        } else {
            ivThumb.setImageResource(R.drawable.img_loading_placeholder)
        }

        if (firstSourceKey != sourceKey) {
            val currentSource = ApiConfig.get().getSource(sourceKey)
            setTextShow(tvType, getString(R.string.det_type), "[${currentSource?.name ?: ""}] 解析")
        } else {
            setTextShow(tvType, getString(R.string.det_type), video.type)
        }

        setTextShow(tvActor, getString(R.string.det_actor), video.actor)
        setTextShow(tvDirector, getString(R.string.det_dir), video.director)
        setTextShow(tvDes, getString(R.string.det_des), removeHtmlTag(video.des))

        if (!info.seriesMap.isNullOrEmpty()) {
            mGridViewFlag.visibility = View.VISIBLE
            mGridView.visibility = View.VISIBLE
            tvPlay.visibility = View.VISIBLE
            tvSort.visibility = View.VISIBLE
            mEmptyPlayList.visibility = View.GONE

            seriesFlagAdapter.setNewData(info.seriesFlags)

            if (info.playFlag.isNullOrEmpty() || !info.seriesMap.containsKey(info.playFlag)) {
                info.playFlag = info.seriesMap.keys.firstOrNull()
            }

            info.seriesFlags.forEachIndexed { index, flag ->
                if (flag.name == info.playFlag) {
                    flag.selected = true
                    mGridViewFlag.scrollToPosition(index)
                } else {
                    flag.selected = false
                }
            }
            seriesFlagAdapter.notifyDataSetChanged()

            refreshList()
            updateSortButtonText() // 初始化排序按钮文字

            if (showPreview) {
                jumpToPlay()
                llPlayerFragmentContainer.visibility = View.VISIBLE
                llPlayerFragmentContainerBlock.visibility = View.VISIBLE
                llPlayerFragmentContainerBlock.requestFocus()
            } else {
                // 海报模式：隐藏预览播放器，但保留选集列表显示
                llPlayerFragmentContainer.visibility = View.GONE
                llPlayerFragmentContainerBlock.visibility = View.GONE
            }
        } else {
            showEmpty()
            llPlayerFragmentContainer.visibility = View.GONE
            llPlayerFragmentContainerBlock.visibility = View.GONE
        }

        val isCollect = RoomDataManger.isVodCollect(firstSourceKey, info.id)
        tvCollect.setText(if (isCollect) R.string.det_fav_star else R.string.det_fav_unstar)
    }

    private fun setTextShow(view: TextView, tag: String, info: String?) {
        val content = info ?: ""
        if (content.trim().isEmpty()) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.text = Html.fromHtml(getHtml(tag, content))
    }

    private fun getHtml(label: String, content: String?): String {
        val safeContent = content ?: ""
        val labelText = if (label.isNotEmpty()) "$label: " else ""
        return "${labelText}<font color=\"#FFFFFF\">$safeContent</font>"
    }

    private fun removeHtmlTag(info: String?): String = info?.replace(Regex("\\<.*?\\>"), "")?.replace(Regex("\\s"), "") ?: ""

    private fun updateSortButtonText() {
        vodInfo?.let { info ->
            tvSort.text = getString(if (info.reverseSort) R.string.det_sort_reverse else R.string.det_sort_normal)
        }
    }

    private fun initData() {
        intent?.extras?.let { bundle ->
            val id = bundle.getString("id")
            val key = bundle.getString("sourceKey", "")
            if (id.isNullOrEmpty()) {
                showEmpty()
                return
            }
            loadDetail(id, key)
        } ?: showEmpty()
    }

    private fun loadDetail(vid: String?, key: String) {
        if (vid == null) return
        vodId = vid
        sourceKey = key
        firstSourceKey = key
        showLoading()
        sourceViewModel.getDetail(sourceKey, vodId)

        val isCollect = RoomDataManger.isVodCollect(sourceKey, vodId)
        tvCollect.setText(if (isCollect) R.string.det_fav_star else R.string.det_fav_unstar)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refresh(event: RefreshEvent) {
        if (isFinishing || isDestroyed) return

        when (event.type) {
            RefreshEvent.TYPE_REFRESH -> handleRefreshEvent(event.obj)
            RefreshEvent.TYPE_QUICK_SEARCH_SELECT -> {
                (event.obj as? Movie.Video)?.let { video ->
                    loadDetail(video.id, video.sourceKey)
                }
            }
            RefreshEvent.TYPE_QUICK_SEARCH_WORD_CHANGE -> {
                (event.obj as? String)?.let { switchSearchWord(it) }
            }
            RefreshEvent.TYPE_QUICK_SEARCH_RESULT -> {
                val absXmlObj = event.obj
                if (absXmlObj is AbsXml) searchData(absXmlObj) else searchData(null)
            }
        }
    }

    private fun handleRefreshEvent(obj: Any?) {
        val info = vodInfo ?: return
        when (obj) {
            is Int -> {
                val index = obj
                val mGroupIndex = kotlin.math.floor(index / (groupCount + 0.0f)).toInt()
                var changeGroup = false

                if (mGroupIndex != groupIndex) {
                    changeGroup = true
                    if (info.playIndex in 0 until seriesAdapter.data.size) {
                        seriesAdapter.data[info.playIndex].selected = false
                        seriesAdapter.notifyItemChanged(info.playIndex)
                    }
                    if (groupIndex in 0 until seriesGroupAdapter.data.size) {
                        seriesGroupAdapter.data[groupIndex].selected = false
                        seriesGroupAdapter.notifyItemChanged(groupIndex)
                    }
                    if (mGroupIndex in 0 until seriesGroupAdapter.data.size) {
                        seriesGroupAdapter.data[mGroupIndex].selected = true
                        seriesGroupAdapter.notifyItemChanged(mGroupIndex)
                    }
                    seriesGroups?.getOrNull(mGroupIndex)?.let { seriesAdapter.setNewData(it) }
                    groupIndex = mGroupIndex
                    mSeriesGroupView.scrollToPosition(mGroupIndex)
                }

                if (index != info.playEpisodeIndex) {
                    if (!changeGroup && info.playIndex in 0 until seriesAdapter.data.size) {
                        seriesAdapter.data[info.playIndex].selected = false
                        seriesAdapter.notifyItemChanged(info.playIndex)
                    }
                    info.playEpisodeIndex = index
                    info.playGroup = index / groupCount
                    info.playIndex = index % groupCount
                    if (info.playIndex in 0 until seriesAdapter.data.size) {
                        seriesAdapter.data[info.playIndex].selected = true
                        seriesAdapter.notifyItemChanged(info.playIndex)
                    }
                    mGridView.scrollToPosition(info.playIndex)
                }
            }
            is JSONObject -> {
                info.playerCfg = obj.toString()
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun pushVod(event: RefreshEvent) {
        if (event.type != RefreshEvent.TYPE_PUSH_VOD) return
        (event.obj as? List<String>)?.let { data ->
            OkGo.getInstance().cancelTag("pushVod")
            OkGo.post<String>("http://${data[0]}:${data[1]}/action")
                .tag("pushVod")
                .params("id", vodId)
                .params("sourceKey", sourceKey)
                .params("do", "mirror")
                .execute(object : AbsCallback<String>() {
                    private var responseBody: String? = null

                    @Throws(Throwable::class)
                    override fun convertResponse(response: okhttp3.Response): String {
                        // 使用Java反射访问body，避免Kotlin的访问控制问题
                        val bodyField = okhttp3.Response::class.java.getDeclaredField("body")
                        bodyField.isAccessible = true
                        val body = bodyField.get(response) as? okhttp3.ResponseBody
                        val bodyString = body?.string() ?: ""
                        responseBody = bodyString
                        return bodyString
                    }

                    override fun onSuccess(response: OkGoResponse<String>) {
                        val result = when (responseBody) {
                            "mirrored" -> "推送成功"
                            "source_not_found" -> "推送失败：接收端无此数据源"
                            "missing_params" -> "推送失败：参数缺失"
                            else -> "推送失败：远端版本不支持"
                        }
                        ToastHelper.showLong(result)
                    }

                    override fun onError(response: OkGoResponse<String>) {
                        super.onError(response)
                        ToastHelper.showLong("推送失败：请检查设备是否在线")
                    }
                })
        }
    }

    private fun switchSearchWord(word: String) {
        OkGo.getInstance().cancelTag("quick_search")
        quickSearchData.clear()
        searchTitle = word
        searchResult()
    }

    private fun initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch()
    }

    private fun startQuickSearch() {
        initCheckedSourcesForSearch()
        if (hadQuickStart) return
        hadQuickStart = true

        OkGo.getInstance().cancelTag("quick_search")
        quickSearchWord.clear()
        searchTitle = mVideo?.name ?: ""
        quickSearchData.clear()
        quickSearchWord.add(searchTitle)

        OkGo.get<String>("https://api.yesapi.cn/?service=App.Scws.GetWords&text=$searchTitle&app_key=CEE4B8A091578B252AC4C92FB4E893C3&sign=CB7602F3AC922808AF5D475D8DA33302")
            .tag("fenci")
            .execute(object : AbsCallback<String>() {
                private var responseBody: String? = null

                @Throws(Throwable::class)
                override fun convertResponse(response: okhttp3.Response): String {
                    // 使用Java反射访问body，避免Kotlin的访问控制问题
                    val bodyField = okhttp3.Response::class.java.getDeclaredField("body")
                    bodyField.isAccessible = true
                    val body = bodyField.get(response) as? okhttp3.ResponseBody
                    val bodyString = body?.string() ?: ""
                    responseBody = bodyString
                    return bodyString
                }

                override fun onSuccess(response: OkGoResponse<String>) {
                    quickSearchWord.clear()
                    responseBody?.let { body ->
                        runCatching {
                            val resJson = JsonParser.parseString(body).asJsonObject
                            val wordsJson = resJson.get("data")?.asJsonObject?.get("words")?.asJsonArray
                            wordsJson?.forEach { je ->
                                quickSearchWord.add(je.asJsonObject.get("word").asString)
                            }
                        }
                    }
                    quickSearchWord.add(searchTitle)
                    if (!isFinishing && !isDestroyed) {
                        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, quickSearchWord))
                    }
                }

                override fun onError(response: OkGoResponse<String>) {
                    super.onError(response)
                }
            })

        searchResult()
    }

    private fun searchResult() {
        shutdownSearchExecutor()
        synchronized(searchExecutorLock) {
            searchExecutorService = Executors.newFixedThreadPool(5)
            val searchRequestList = ApiConfig.get().sourceBeanList.toMutableList()
            val home = ApiConfig.get().homeSourceBean
            searchRequestList.remove(home)
            searchRequestList.add(0, home)

            val siteKey = searchRequestList
                .filter { it.isSearchable && it.isQuickSearch }
                .filter { mCheckSources == null || mCheckSources!!.containsKey(it.key) }
                .map { it.key }

            siteKey.forEach { key ->
                searchExecutorService?.execute(QuickSearchRunnable(sourceViewModel, key, searchTitle))
            }
        }
    }

    private class QuickSearchRunnable(
        vm: SourceViewModel,
        private val sourceKey: String,
        private val title: String
    ) : Runnable {
        private val vmRef = WeakReference(vm)

        override fun run() {
            if (Thread.currentThread().isInterrupted) return
            vmRef.get()?.getQuickSearch(sourceKey, title)
        }
    }

    private fun searchData(absXml: AbsXml?) {
        val videoList = absXml?.movie?.videoList
        if (videoList.isNullOrEmpty()) return

        val data = videoList.filter { !(it.sourceKey == sourceKey && it.id == vodId) }
        quickSearchData.addAll(data)
        if (!isFinishing && !isDestroyed) {
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH, data))
        }
    }

    private fun restoreVodInfoFromHistory() {
        val info = vodInfo ?: return
        // 从历史记录中恢复播放信息
        val historyVodInfo = RoomDataManger.getVodInfo(firstSourceKey, info.id)
        if (historyVodInfo != null) {
            info.playFlag = historyVodInfo.playFlag
            info.playIndex = historyVodInfo.playIndex
            info.playGroup = historyVodInfo.playGroup
            info.playGroupCount = historyVodInfo.playGroupCount
            info.playEpisodeIndex = historyVodInfo.playEpisodeIndex
            info.playEpisodeName = historyVodInfo.playEpisodeName
            info.playNote = historyVodInfo.playNote
            info.reverseSort = historyVodInfo.reverseSort
            info.playerCfg = historyVodInfo.playerCfg
            // 如果历史记录是倒序，需要反转列表
            if (info.reverseSort) {
                info.reverse()
            }
        }
    }

    private fun insertVod(sourceKey: String?, vodInfo: VodInfo?) {
        if (vodInfo?.seriesMap == null || vodInfo.playFlag == null) return
        // 只有播放成功才保存
        if (!playSuccess) return

        // 从PlayFragment获取最新的VodInfo，避免使用DetailActivity的旧数据
        val vodInfoToSave = playFragment?.getVodInfo() ?: vodInfo

        vodInfoToSave.saveCurrentEpisodeInfo()
        val seriesList = vodInfoToSave.seriesMap[vodInfoToSave.playFlag]
        vodInfoToSave.playNote = seriesList?.getOrNull(vodInfoToSave.playEpisodeIndex)?.name ?: ""

        val currentSourceEpisodes = seriesList?.size ?: 0
        val playIndex = vodInfoToSave.playEpisodeIndex
        val isLatestEpisode = if (vodInfoToSave.reverseSort) playIndex == 0 else currentSourceEpisodes > 0 && playIndex + 1 >= currentSourceEpisodes

        vodInfoToSave.totalEpisodes = currentSourceEpisodes
        RoomDataManger.insertVodRecord(sourceKey, vodInfoToSave)
        UpdateCheckManager.get().setVideoUpdate(sourceKey, vodInfoToSave.id, !isLatestEpisode)

        // 发送事件刷新历史记录界面（只要不是正在销毁就可以发送）
        if (!isDestroyed) {
            EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
        }
    }

    override fun onDestroy() {
        EventBus.getDefault().unregister(this)
        shutdownSearchExecutor()
        pauseRunnable?.clear()
        pauseRunnable = null

        playFragment?.let { fragment ->
            fragment.clearHandlerMessages()
            if (fragment.isAdded) {
                supportFragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
            }
        }
        playFragment = null

        quickSearchDialog?.takeIf { it.isShowing }?.dismiss()
        quickSearchDialog = null

        pipActionReceiver?.let {
            unregisterReceiver(it)
            pipActionReceiver = null
        }
        
        // 注销广播接收器
        mHomeKeyReceiver?.let {
            unregisterReceiver(it)
            mHomeKeyReceiver = null
        }

        OkGo.getInstance().cancelTag("fenci")
        OkGo.getInstance().cancelTag("detail")
        OkGo.getInstance().cancelTag("quick_search")
        OkGo.getInstance().cancelTag("pushVod")

        if (!showPreview) Thunder.stop(true)
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        if (!supportsPiPMode() || !showPreview || playFragment?.extPlay == true ||
            Hawk.get(HawkConfig.BACKGROUND_PLAY_TYPE, 0) != 2) return

        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        } catch (e: SecurityException) {
            ToastUtils.showShort("画中画 开启失败!")
            return
        }

        val videoView = playFragment?.mVideoView ?: return
        val vWidth = videoView.videoSize[0]
        val vHeight = videoView.videoSize[1]
        val ratio = if (vWidth != 0) {
            val height = if (vWidth.toDouble() / vHeight > 2.39) (vWidth.toDouble() / 2.35).toInt() else vHeight
            Rational(vWidth, height)
        } else {
            Rational(16, 9)
        }

        val actions = listOf(
            generateRemoteAction(android.R.drawable.ic_media_previous, BROADCAST_ACTION_PREV, "Prev", "Play Previous"),
            generateRemoteAction(android.R.drawable.ic_media_play, BROADCAST_ACTION_PLAYPAUSE, "Play", "Play/Pause"),
            generateRemoteAction(android.R.drawable.ic_media_next, BROADCAST_ACTION_NEXT, "Next", "Play Next")
        )

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(actions)
            .build()

        // 进入画中画前隐藏弹幕
        playFragment?.danmuView?.let {
            it.hide()
            it.visibility = View.GONE
        }

        if (!fullWindows) toggleFullPreview()
        enterPictureInPictureMode(params)
        playFragment?.vodController?.hideBottom()
        playFragment?.player?.postDelayed({
            if (playFragment?.player?.isPlaying == false) {
                playFragment?.vodController?.togglePlay()
            }
        }, 400)
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private fun generateRemoteAction(iconResId: Int, actionCode: Int, title: String, desc: String): RemoteAction {
        val intent = PendingIntent.getBroadcast(
            this,
            actionCode,
            Intent(BROADCAST_ACTION).putExtra("action", actionCode),
            PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(Icon.createWithResource(this, iconResId), title, desc, intent)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) {
            // 退出画中画模式，恢复弹幕显示（如果弹幕开启且有数据）
            playFragment?.danmuView?.let { danmuView ->
                if (HawkUtils.getDanmuOpen() && playFragment?.getDanmuText()?.isNotEmpty() == true) {
                    danmuView.visibility = View.VISIBLE
                    danmuView.show()
                    // 同步弹幕进度
                    playFragment?.player?.let { player ->
                        danmuView.seekTo(player.currentPosition)
                    }
                }
            }
            // 如果当前是全屏状态，退出到详情页模式
            if (fullWindows) {
                toggleFullPreview()
            }
            // 确保 Activity 回到前台
            val intent = Intent(this, DetailActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            startActivity(intent)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (fullWindows) {
                // 全屏模式下先检查控制栏是否可见，如果可见则先隐藏控制栏
                if (playFragment?.onBackPressed() == true) {
                    return true
                }
                // 控制栏不可见时退出全屏，回到详情页
                toggleFullPreview()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (fullWindows && playFragment?.dispatchKeyEvent(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullWindows) {
            // 全屏模式下先检查控制栏是否可见，如果可见则先隐藏控制栏
            if (playFragment?.onBackPressed() == true) {
                return
            }
            // 控制栏不可见时退出全屏，回到详情页
            toggleFullPreview()
        } else {
            // 退出前保存播放记录（只有播放成功才保存）
            insertVod(firstSourceKey, vodInfo)
            super.onBackPressed()
        }
    }

    fun toggleSubtitleTextSize() {
        var subtitleTextSize = SubtitleHelper.getTextSize(this)
        if (!fullWindows) {
            subtitleTextSize = (subtitleTextSize * 0.5).toInt()
        }
        EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE, subtitleTextSize))
    }
}
