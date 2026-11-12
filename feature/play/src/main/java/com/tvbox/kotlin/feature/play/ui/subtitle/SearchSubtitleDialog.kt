package com.tvbox.kotlin.feature.play.ui.subtitle

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.feature.play.R

/**
 * 在线搜索字幕弹窗（assrt.net）。
 *
 * 搜索框支持遥控器焦点导航与回车搜索；结果列表支持分页加载；
 * 压缩包结果进入详情页展开内部字幕文件，返回键回到搜索结果。
 * 字幕加载结果通过 [SubtitleLoader] 回调宿主，宿主调用 [notifyLoadResult] 反馈加载结果。
 */
class SearchSubtitleDialog(context: Context) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    /** 字幕加载回调，由宿主实现 */
    interface SubtitleLoader {
        fun loadSubtitle(subtitle: SubtitleBean)
        fun onLoadSuccess()
        fun onLoadError(error: String)
    }

    private val mContext = context
    private lateinit var mGridView: TvRecyclerView
    private lateinit var searchAdapter: SearchSubtitleAdapter
    private lateinit var subtitleSearchEt: EditText
    private lateinit var loadingContainer: View
    private lateinit var loadingTip: TextView

    private var mSubtitleLoader: SubtitleLoader? = null

    /** 搜索结果 item 聚焦后是否已恢复编辑框可聚焦（每次搜索重置） */
    private var editTextFocusRestored = false

    /** 字幕搜索仓库，由宿主通过 [setSubtitleRepository] 注入 */
    private var subtitleRepository: SubtitleRepository? = null

    private var page = 1
    private val maxPage = 5
    private var searchWord = ""
    private val zipSubtitles = mutableListOf<SubtitleBean>()
    private var isSearchPag = true
    private var loadingMore = false

    init {
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                val imm = mContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                // 输入法会话活跃且编辑框持焦点时，返回键先收起输入法并移出焦点；
                // 再次返回（焦点已不在编辑框）即可正常关闭弹窗
                if (imm?.isActive == true && subtitleSearchEt.hasFocus()) {
                    imm.hideSoftInputFromWindow(subtitleSearchEt.windowToken, 0)
                    subtitleSearchEt.clearFocus()
                    // 焦点明确转移到搜索按钮，避免系统把焦点回退到编辑框
                    // （否则再次返回仍走输入法分支，导致无法退出弹窗）
                    findViewById<View>(com.tvbox.kotlin.feature.play.R.id.inputSubmit)?.requestFocus()
                    return@setOnKeyListener true
                }
                if (!isSearchPag) {
                    // 压缩包详情页按返回键回到搜索结果
                    isSearchPag = true
                    loadingContainer.visibility = View.GONE
                    mGridView.visibility = View.VISIBLE
                    searchAdapter.setNewData(zipSubtitles)
                    searchAdapter.enableLoadMore = page < maxPage
                    return@setOnKeyListener true
                }
                dismiss()
                return@setOnKeyListener true
            }
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_search_subtitle)
        window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        initView()
    }

    private fun initView() {
        loadingContainer = findViewById(R.id.loadingContainer)
        loadingTip = findViewById(R.id.loadingTip)
        mGridView = findViewById(R.id.mGridView)
        subtitleSearchEt = findViewById(R.id.input_sub)
        val searchBtn = findViewById<TextView>(R.id.inputSubmit)

        searchAdapter = SearchSubtitleAdapter(
            onClick = { subtitle, _ ->
                if (mSubtitleLoader != null) {
                    if (subtitle.isZip) {
                        // 压缩包：进入详情页加载内部字幕文件列表
                        isSearchPag = false
                        loadingTip.text = mContext.getString(uiR.string.subtitle_search_loading_list)
                        loadingContainer.visibility = View.VISIBLE
                        mGridView.visibility = View.GONE
                        repository()?.getSearchResultSubtitleUrls(subtitle) { data ->
                            onDetailLoaded(data)
                        }
                    } else {
                        // 文件：直接加载字幕
                        loadingTip.text = mContext.getString(uiR.string.subtitle_search_loading)
                        loadingContainer.visibility = View.VISIBLE
                        mGridView.visibility = View.GONE
                        loadSubtitle(subtitle)
                    }
                }
            },
            onLoadMore = {
                if (!loadingMore && searchAdapter.enableLoadMore && searchAdapter.itemCount > 0) {
                    loadingMore = true
                    repository()?.searchResult(searchWord, page) { data ->
                        loadingMore = false
                        onSearchResult(data)
                    }
                }
            }
        )
        mGridView.setHasFixedSize(true)
        // TvRecyclerView + V7LinearLayoutManager，居中/间距由布局 tv_* 属性提供
        mGridView.layoutManager = V7LinearLayoutManager(mContext, 1, false)
        mGridView.adapter = searchAdapter
        mGridView.isFocusable = true
        mGridView.isFocusableInTouchMode = true
        mGridView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        mGridView.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {}
            override fun onItemSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {
                if (!editTextFocusRestored) {
                    editTextFocusRestored = true
                    restoreEditTextFocusable()
                }
            }
            override fun onItemClick(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {}
        })

        // 软键盘搜索/完成按钮直接触发搜索
        subtitleSearchEt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO
            ) {
                search(subtitleSearchEt.text.toString().trim())
                true
            } else {
                false
            }
        }
        // 编辑框获取焦点时主动弹出软键盘
        subtitleSearchEt.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) showSoftInput(v)
        }
        // 方向键在 ACTION_UP 处理移焦（与系统按键事件流一致），
        // 回车/确认键直接触发搜索
        subtitleSearchEt.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP) {
                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                        search(subtitleSearchEt.text.toString().trim())
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        subtitleSearchEt.focusSearch(View.FOCUS_DOWN)?.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // 光标在末尾或文本为空时移焦右侧搜索按钮（文本中间保留光标右移）
                        val len = subtitleSearchEt.text?.length ?: 0
                        if (len == 0 || subtitleSearchEt.selectionStart == len) {
                            subtitleSearchEt.focusSearch(View.FOCUS_RIGHT)?.requestFocus()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            } else {
                false
            }
        }
        searchBtn.setOnClickListener {
            search(subtitleSearchEt.text.toString().trim())
        }
        searchAdapter.setNewData(emptyList())
        // 应用构造期间缓存的搜索词
        pendingSearchWord?.let { applySearchWord(it) }
    }

    /** 主动显示软键盘 */
    private fun showSoftInput(view: View) {
        view.post {
            val imm = mContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * 编辑框可聚焦。注意：isFocusable=false 会连带清掉 isFocusableInTouchMode，
     * 若只恢复 isFocusable，触摸模式下点击编辑框无法聚焦、软键盘弹不出（遥控按键不受影响），
     * 必须同时恢复 focusableInTouchMode。
     */
    private fun restoreEditTextFocusable() {
        subtitleSearchEt.isFocusable = true
        subtitleSearchEt.isFocusableInTouchMode = true
    }

    /** 待应用的搜索词：弹窗构造时可能尚未初始化视图，先缓存，initView 后生效 */
    private var pendingSearchWord: String? = null

    /** 设置搜索词（移除常见视频扩展名后填入搜索框） */
    fun setSearchWord(wd: String?) {
        var word = wd ?: ""
        word = word.replace(Regex("(?i)\\.(mp4|mkv|avi|mov|wmv|flv|ts)$"), "")
        word = word.trim()
        pendingSearchWord = word
        if (::subtitleSearchEt.isInitialized) {
            applySearchWord(word)
        }
    }

    /** 将搜索词填入输入框并请求焦点 */
    private fun applySearchWord(word: String) {
        subtitleSearchEt.setText(word)
        subtitleSearchEt.setSelection(word.length)
        subtitleSearchEt.requestFocus()
    }

    /** 发起搜索 */
    fun search(wd: String) {
        isSearchPag = true
        if (!TextUtils.isEmpty(wd)) {
            loadingTip.text = mContext.getString(uiR.string.subtitle_search_searching)
            loadingContainer.visibility = View.VISIBLE
            mGridView.visibility = View.GONE
            searchWord = wd
            // 搜索期间禁止编辑框被焦点回退抢占：列表清空/隐藏时系统会把焦点回退到
            // 弹窗首个可聚焦组件（编辑框，聚焦会弹输入法），表现为"搜索未结束时抢焦点"；
            // 结果返回后 onSearchResult 恢复可聚焦并聚焦结果列表
            subtitleSearchEt.isFocusable = false
            subtitleSearchEt.clearFocus()
            editTextFocusRestored = false
            searchAdapter.setNewData(emptyList())
            page = 1
            repository()?.searchResult(wd, page) { data ->
                onSearchResult(data)
            }
        } else {
            Toast.makeText(mContext, mContext.getString(uiR.string.subtitle_search_empty_input), Toast.LENGTH_SHORT).show()
        }
    }

    /** 搜索结果回调 */
    private fun onSearchResult(subtitleData: SubtitleData?) {
        loadingContainer.visibility = View.GONE
        mGridView.visibility = View.VISIBLE
        if (subtitleData == null) {
            restoreEditTextFocusable()
            Toast.makeText(mContext, mContext.getString(uiR.string.subtitle_search_error), Toast.LENGTH_SHORT).show()
            return
        }
        val data = subtitleData.subtitleList
        if (data == null) {
            restoreEditTextFocusable()
            mGridView.post { Toast.makeText(mContext, mContext.getString(uiR.string.subtitle_search_no_result), Toast.LENGTH_SHORT).show() }
            return
        }
        if (data.isNotEmpty()) {
            if (subtitleData.isZip) {
                if (subtitleData.isNew) {
                    searchAdapter.setNewData(data)
                    zipSubtitles.clear()
                    zipSubtitles.addAll(data)
                } else {
                    searchAdapter.addData(data)
                    zipSubtitles.addAll(data)
                }
                page++
                searchAdapter.enableLoadMore = page <= maxPage
            } else {
                searchAdapter.setNewData(data)
                searchAdapter.enableLoadMore = false
            }
            // 数据就绪且布局完成后再聚焦列表首项；编辑框可聚焦推迟到列表聚焦稳定后
            // 再恢复（TvRecyclerView 聚焦 item 为异步过程），避免恢复可聚焦瞬间焦点
            // 被系统回退到编辑框（抢一次焦点再闪回列表）
            mGridView.post {
                mGridView.requestFocus()
                mGridView.postDelayed({
                    restoreEditTextFocusable()
                }, 200)
            }
        } else {
            restoreEditTextFocusable()
            searchAdapter.enableLoadMore = false
        }
    }

    /** 压缩包详情页加载结果回调 */
    private fun onDetailLoaded(subtitleData: SubtitleData?) {
        loadingContainer.visibility = View.GONE
        mGridView.visibility = View.VISIBLE
        if (subtitleData == null || subtitleData.subtitleList == null) {
            // 详情页无可用字幕时回到搜索列表
            isSearchPag = true
            searchAdapter.setNewData(zipSubtitles)
            searchAdapter.enableLoadMore = page < maxPage
            Toast.makeText(mContext, mContext.getString(uiR.string.subtitle_search_no_result), Toast.LENGTH_SHORT).show()
            return
        }
        val data = subtitleData.subtitleList ?: return
        if (data.isEmpty()) {
            isSearchPag = true
            searchAdapter.setNewData(zipSubtitles)
            searchAdapter.enableLoadMore = page < maxPage
            Toast.makeText(mContext, mContext.getString(uiR.string.subtitle_search_no_result), Toast.LENGTH_SHORT).show()
            return
        }
        searchAdapter.setNewData(data)
        searchAdapter.enableLoadMore = false
        mGridView.post { mGridView.requestFocus() }
    }

    private fun loadSubtitle(subtitle: SubtitleBean) {
        repository()?.getSubtitleUrl(subtitle, mSubtitleLoader ?: return)
    }

    fun setSubtitleLoader(loader: SubtitleLoader) {
        mSubtitleLoader = loader
    }

    fun setSubtitleRepository(repository: SubtitleRepository) {
        subtitleRepository = repository
    }

    private fun repository(): SubtitleRepository? {
        return subtitleRepository ?: run {
            Toast.makeText(mContext, mContext.getString(uiR.string.subtitle_search_error), Toast.LENGTH_SHORT).show()
            null
        }
    }

    /** 通知字幕加载结果：成功关闭弹窗，失败恢复列表显示。可能从子线程回调 */
    fun notifyLoadResult(success: Boolean, message: String?) {
        val context = mContext
        val dialog = this
        val runnable = Runnable {
            if (!TextUtils.isEmpty(message)) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            if (success) {
                dialog.dismiss()
            } else {
                loadingContainer.visibility = View.GONE
                mGridView.visibility = View.VISIBLE
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runnable.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(runnable)
        }
    }

    override fun dismiss() {
        // 关闭弹窗时清空上次搜索结果，避免下次打开仍显示旧数据
        searchAdapter.setNewData(emptyList())
        zipSubtitles.clear()
        page = 1
        super.dismiss()
    }
}

/** 搜索结果列表适配器，最后一项可见时触发加载更多 */
class SearchSubtitleAdapter(
    private val onClick: (SubtitleBean, Int) -> Unit,
    private val onLoadMore: () -> Unit
) : RecyclerView.Adapter<SearchSubtitleAdapter.Holder>() {

    private val data = mutableListOf<SubtitleBean>()

    /** 是否启用加载更多（仅压缩包搜索分页时启用） */
    var enableLoadMore = false

    fun setNewData(list: List<SubtitleBean>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    fun addData(list: List<SubtitleBean>) {
        data.addAll(list)
        notifyDataSetChanged()
    }

    fun getData(): List<SubtitleBean> = data

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_subtitle_result, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val bean = data[position]
        holder.tvName.text = bean.name
        holder.tvInfo.text = if (bean.isZip) holder.itemView.context.getString(uiR.string.subtitle_archive) else holder.itemView.context.getString(uiR.string.subtitle_file)
        holder.itemView.setOnClickListener { onClick(bean, position) }
        // 滚动到末尾时触发加载更多
        if (enableLoadMore && position >= itemCount - 1) {
            onLoadMore()
        }
    }

    override fun getItemCount(): Int = data.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.subtitleName)
        val tvInfo: TextView = itemView.findViewById(R.id.subtitleNameInfo)
    }
}
