package com.tvbox.kotlin.feature.play.ui

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.ui.PlayResolver
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.feature.play.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 在线弹幕搜索对话框：按番剧名查询内置弹幕库，选择剧集后回调已转换的弹幕 XML。
 *
 * 与源 jar 无关，所有源都可使用；搜索/加载在 [PlayViewModel] 内切 IO 执行。
 * 搜索框支持遥控器焦点导航与回车搜索，搜索与选中下载过程显示进度状态。
 */
class DanmuSearchDialog(
    context: Context,
    private val viewModel: PlayViewModel,
    private val initialKeyword: String,
    private val onSelected: (String) -> Unit,
    private val onRestoreSource: (() -> Unit)? = null
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    private val scope = MainScope()
    private val items = mutableListOf<PlayResolver.OnlineDanmuItem>()
    private lateinit var recycler: TvRecyclerView
    private lateinit var emptyView: TextView
    private lateinit var keywordInput: EditText
    private lateinit var searchButton: View
    private lateinit var loadingContainer: View
    private lateinit var loadingTip: TextView
    private var adapter: ResultAdapter? = null
    private var searchJob: Job? = null
    private var loadJob: Job? = null

    init {
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                // 输入法活跃且编辑框持焦点时，返回键先收起输入法并移出焦点；
                // 焦点明确交给搜索按钮，避免系统回退到编辑框导致再次返回仍收起输入法
                if (imm?.isActive == true && keywordInput.hasFocus()) {
                    imm.hideSoftInputFromWindow(keywordInput.windowToken, 0)
                    keywordInput.clearFocus()
                    searchButton.requestFocus()
                    return@setOnKeyListener true
                }
            }
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_danmu_search)
        window?.apply {
            setGravity(Gravity.CENTER)
            // 输入法弹出时不顶起弹窗，避免列表被压缩后焦点错位
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            setLayout(
                context.resources.getDimensionPixelSize(uiR.dimen.vs_960),
                context.resources.getDimensionPixelSize(uiR.dimen.vs_600)
            )
        }
        keywordInput = findViewById(R.id.et_danmu_keyword)
        emptyView = findViewById(R.id.tv_danmu_empty)
        recycler = findViewById(R.id.trv_danmu_results)
        searchButton = findViewById(R.id.btn_danmu_search)
        loadingContainer = findViewById(R.id.danmu_loading)
        loadingTip = findViewById(R.id.danmu_loading_tip)
        recycler.layoutManager = V7LinearLayoutManager(context)
        adapter = ResultAdapter()
        recycler.adapter = adapter

        keywordInput.setText(initialKeyword)
        keywordInput.setSelection(initialKeyword.length)
        keywordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                search()
                true
            } else {
                false
            }
        }
        // 编辑框获得焦点时弹出软键盘
        keywordInput.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) showSoftInput(v)
        }
        keywordInput.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                    search()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    keywordInput.focusSearch(View.FOCUS_DOWN)?.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // 光标在末尾或文本为空时移到搜索按钮，文本中间保留光标右移
                    val length = keywordInput.text?.length ?: 0
                    if (length == 0 || keywordInput.selectionStart == length) {
                        searchButton.requestFocus()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        searchButton.setOnClickListener { search() }
        findViewById<View>(R.id.dialog_close)?.setOnClickListener { dismiss() }
        // 恢复源弹幕：仅播放页提供（App 设置不传回调时隐藏）
        findViewById<View>(R.id.btn_danmu_source)?.apply {
            visibility = if (onRestoreSource == null) View.GONE else View.VISIBLE
            setOnClickListener {
                dismiss()
                onRestoreSource?.invoke()
            }
        }
        if (initialKeyword.isNotBlank()) {
            // 首帧后再发起搜索：onCreate 阶段窗口尚未 attach，聚焦状态切换会打断首个请求
            keywordInput.post { search() }
        } else {
            keywordInput.requestFocus()
        }
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    /** 按输入关键字搜索候选弹幕 */
    private fun search() {
        val keyword = keywordInput.text.toString().trim()
        if (TextUtils.isEmpty(keyword)) {
            Toast.makeText(context, uiR.string.danmu_search_empty_input, Toast.LENGTH_SHORT).show()
            return
        }
        searchJob?.cancel()
        loadJob?.cancel()
        items.clear()
        adapter?.notifyDataSetChanged()
        emptyView.visibility = View.GONE
        recycler.visibility = View.GONE
        showLoading(uiR.string.danmu_search_searching)
        // 搜索期间编辑框不可聚焦：列表隐藏后系统会把焦点回退到首个可聚焦组件（编辑框），
        // 触发输入法弹出并抢走焦点，结果返回后再恢复
        keywordInput.isFocusable = false
        keywordInput.clearFocus()
        searchJob = scope.launch {
            val list = viewModel.searchOnlineDanmuList(keyword)
            items.clear()
            items.addAll(list)
            adapter?.notifyDataSetChanged()
            hideLoading()
            recycler.visibility = View.VISIBLE
            emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            restoreKeywordFocusable()
            if (list.isNotEmpty()) recycler.post { recycler.requestFocus() }
        }
    }

    /** 选择候选：显示下载状态，拉取并转换弹幕后回调宿主加载 */
    private fun choose(item: PlayResolver.OnlineDanmuItem) {
        loadJob?.cancel()
        recycler.visibility = View.GONE
        showLoading(uiR.string.danmu_search_loading)
        loadJob = scope.launch {
            val content = viewModel.loadOnlineDanmuContent(item.url)
            when {
                content == null -> {
                    Toast.makeText(context, uiR.string.danmu_load_failed, Toast.LENGTH_SHORT).show()
                    restoreResultList()
                }
                // 接口返回成功但该剧集没有弹幕：提示换来源，避免与网络失败混淆
                content.isEmpty() -> {
                    Toast.makeText(context, uiR.string.danmu_empty_for_episode, Toast.LENGTH_SHORT).show()
                    restoreResultList()
                }
                else -> {
                    onSelected(content)
                    dismiss()
                }
            }
        }
    }

    private fun showLoading(tipRes: Int) {
        loadingTip.setText(tipRes)
        loadingContainer.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingContainer.visibility = View.GONE
    }

    /** 失败后恢复结果列表并交还列表焦点 */
    private fun restoreResultList() {
        hideLoading()
        recycler.visibility = View.VISIBLE
        recycler.post { recycler.requestFocus() }
    }

    /** 恢复编辑框可聚焦，同时恢复触摸模式，否则点击编辑框无法弹出输入法 */
    private fun restoreKeywordFocusable() {
        keywordInput.isFocusable = true
        keywordInput.isFocusableInTouchMode = true
    }

    private fun showSoftInput(view: View) {
        view.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultAdapter.Holder>() {

        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_danmu_search, parent, false) as TextView
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.text.text = item.name
            holder.text.setOnClickListener { choose(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
