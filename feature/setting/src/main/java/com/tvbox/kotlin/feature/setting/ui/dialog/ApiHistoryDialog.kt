package com.tvbox.kotlin.feature.setting.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.util.ApiLineStore
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.HawkListHelper
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.core.ui.dialog.TipDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 接口地址历史对话框。
 * 展示 [key] 下保存的历史地址，点击选中、垃圾桶图标删除。
 * - 配置源历史（API_HISTORY）：当前使用的源地址置顶并高亮选中，且禁止删除；
 * - 删除多仓来源订阅地址时二次确认，确认后同步清除其线路列表（API_LINE_MAP 对应项）。
 */
class ApiHistoryDialog(
    context: Context,
    private val key: String,
    private val title: String,
    private val onSelect: (String) -> Unit
) : BaseDialog(context, uiR.style.CustomDialogStyleDim) {

    private val listData = HawkListHelper.getList(key)
    private val appPreferences = AppPreferences.getShared(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 当前使用的实际地址（点播/直播/EPG 各自取当前值，用于删除保护与绑定联动判断） */
    private val actualUrl: String? = when (key) {
        AppConstants.API_HISTORY -> appPreferences.getString(AppConstants.API_URL)
        AppConstants.LIVE_HISTORY -> appPreferences.getString(AppConstants.LIVE_URL)
        AppConstants.EPG_HISTORY -> appPreferences.getString(AppConstants.EPG_URL)
        else -> null
    }

    /**
     * 历史列表高亮项：当前地址为多仓线路之一时，高亮并置顶其绑定来源（多仓源地址）；
     * 否则高亮当前使用的地址本身。
     */
    private val highlightUrl: String? =
        if (key == AppConstants.API_HISTORY) {
            ApiLineStore.findSourceOfLine(appPreferences, actualUrl ?: "") ?: actualUrl
        } else {
            actualUrl
        }

    private lateinit var adapter: HistoryAdapter

    init {
        // 将高亮项（当前使用的地址或其绑定多仓来源）置顶
        if (highlightUrl != null) {
            val idx = listData.indexOf(highlightUrl)
            if (idx > 0) {
                listData.removeAt(idx)
                listData.add(0, highlightUrl)
            }
        }
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(uiR.layout.dialog_api_history)
        setCanceledOnTouchOutside(true)
        findViewById<TextView>(uiR.id.title)?.text = title
        val list = findViewById<TvRecyclerView>(uiR.id.list)
        list.layoutManager = V7LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        adapter = HistoryAdapter(
            data = listData,
            current = highlightUrl,
            onSelect = { value ->
                onSelect(value)
                dismiss()
            },
            onDelete = { value -> handleDelete(value) }
        )
        list.adapter = adapter
        // 高亮项（当前使用的地址或其绑定多仓来源）默认选中
        if (highlightUrl != null) {
            val pos = listData.indexOf(highlightUrl).coerceAtLeast(0)
            list.setSelectedPosition(pos)
            list.setSelection(pos)
        }
    }

    /** 删除入口：当前使用地址或其绑定多仓来源（均在用）禁止删除；其他多仓源先关闭本弹窗再二次确认；其余直接删除 */
    private fun handleDelete(value: String) {
        if (actualUrl != null && (value == actualUrl || value == highlightUrl)) {
            Toast.makeText(
                context,
                context.getString(uiR.string.toast_cannot_delete_current_source),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (key == AppConstants.API_HISTORY && ApiLineStore.containsSource(appPreferences, value)) {
            dismiss()
            TipDialog(
                context,
                context.getString(uiR.string.confirm_delete_api_line),
                context.getString(uiR.string.dialog_confirm),
                context.getString(uiR.string.dialog_cancel),
                object : TipDialog.OnListener {
                    override fun left() {
                        doDelete(value)
                        showAgain()
                    }

                    override fun right() {
                        showAgain()
                    }

                    override fun cancel() {
                        showAgain()
                    }
                }
            ).show()
        } else {
            doDelete(value)
        }
    }

    /** 确认/取消后重新打开历史弹窗，保证列表状态与删除结果一致 */
    private fun showAgain() {
        ApiHistoryDialog(context, key, title, onSelect).show()
    }

    /** 执行删除：移除历史记录；多仓源同步清除其线路列表 */
    private fun doDelete(value: String) {
        HawkListHelper.removeFromList(key, value)
        listData.remove(value)
        if (key == AppConstants.API_HISTORY) {
            scope.launch { ApiLineStore.removeSource(appPreferences, value) }
        }
        adapter.notifyDataSetChanged()
    }

    class HistoryAdapter(
        private val data: ArrayList<String>,
        private val current: String?,
        private val onSelect: (String) -> Unit,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(uiR.layout.item_dialog_api_history, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val value = data[position]
            holder.tvName.text = value
            val isSelected = value == current
            // 选中（当前使用源）态：主题色加粗文字，聚焦时同样高亮
            holder.tvName.isActivated = isSelected
            applySelectState(holder.tvName, focused = false, selected = isSelected)
            holder.tvName.setOnClickListener { onSelect(value) }
            holder.tvName.setOnFocusChangeListener { view, hasFocus ->
                view.isActivated = hasFocus || isSelected
                applySelectState(view as TextView, focused = hasFocus, selected = isSelected)
            }
            holder.tvDel.setOnClickListener { onDelete(value) }
        }

        /** 聚焦时背景为主题色，文字统一白色保证对比；未聚焦时选中项用主题色加粗 */
        private fun applySelectState(tv: TextView, focused: Boolean, selected: Boolean) {
            tv.setTextColor(if (focused) Color.WHITE else if (selected) themeColor(tv) else Color.WHITE)
            tv.setTypeface(if (focused || selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
        }

        /** 从主题解析 color_theme 主题色 */
        private fun themeColor(view: View): Int {
            val ta = view.context.theme.obtainStyledAttributes(intArrayOf(uiR.attr.color_theme))
            val color = ta.getColor(0, Color.WHITE)
            ta.recycle()
            return color
        }

        override fun getItemCount(): Int = data.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: TextView = itemView.findViewById(uiR.id.tvName)
            val tvDel: ImageView = itemView.findViewById(uiR.id.tvDel)
        }
    }
}
