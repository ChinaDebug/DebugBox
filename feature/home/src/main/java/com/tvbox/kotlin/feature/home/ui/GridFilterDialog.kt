package com.tvbox.kotlin.feature.home.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.bean.MovieSort
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.feature.home.R

/**
 * 分类筛选弹窗，动态渲染每个筛选条件的横向选项行。
 * 焦点跨行移动与首选项聚焦逻辑保持一致。
 */
class GridFilterDialog(context: Context) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    val filterRoot: LinearLayout

    init {
        setCanceledOnTouchOutside(true)
        setCancelable(true)
        setContentView(uiR.layout.dialog_grid_filter)
        filterRoot = findViewById(uiR.id.filterRoot)
        findViewById<View>(uiR.id.root).setOnClickListener { dismiss() }
    }

    /** 动态构建筛选行，并返回各行的 TvRecyclerView 供跨行焦点移动 */
    fun setData(sortData: MovieSort.SortData) {
        filterRoot.removeAllViews()
        val selectedColor = context.themeColor()
        val defaultColor = ContextCompat.getColor(context, uiR.color.color_FFFFFF)
        val filterRows = ArrayList<TvRecyclerView>()
        for (filter in sortData.filters) {
            val key = filter.key ?: continue
            val line = LayoutInflater.from(context).inflate(R.layout.item_grid_filter, filterRoot, false)
            line.findViewById<TextView>(R.id.filterName).text = filter.name
            val gridView = line.findViewById<TvRecyclerView>(R.id.mFilterKv)
            gridView.setHasFixedSize(true)
            gridView.layoutManager = V7LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            val adapter = GridFilterKVAdapter()
            gridView.adapter = adapter
            val values = ArrayList(filter.values?.keys ?: emptyList())
            val keys = ArrayList(filter.values?.values ?: emptyList())
            var previousSelectedView: View? = null
            adapter.onItemClickListener = { view, position ->
                val currentSelection = sortData.filterSelect[key]
                val newSelection = keys[position]
                if (currentSelection == null || currentSelection != newSelection) {
                    sortData.filterSelect[key] = newSelection
                    adapter.selectedPosition = position
                    updateViewStyle(view, selectedColor, true)
                    previousSelectedView?.let { updateViewStyle(it, defaultColor, false) }
                    previousSelectedView = view
                } else {
                    sortData.filterSelect.remove(key)
                    adapter.selectedPosition = -1
                    previousSelectedView?.let { updateViewStyle(it, defaultColor, false) }
                    previousSelectedView = null
                }
                onFilterChanged?.invoke()
            }
            adapter.setNewData(values)
            filterRoot.addView(line)
            filterRows.add(gridView)
        }
        // 上下键跨行移动焦点并保持横向位置
        for (i in filterRows.indices) {
            val rowIndex = i
            val row = filterRows[i]
            row.setOnInBorderKeyEventListener(object : TvRecyclerView.OnInBorderKeyEventListener {
                override fun onInBorderKeyEvent(direction: Int, view: View?): Boolean {
                    if (direction == View.FOCUS_DOWN && rowIndex < filterRows.size - 1) {
                        moveFocusToFilterRow(row, filterRows[rowIndex + 1])
                        return true
                    }
                    if (direction == View.FOCUS_UP && rowIndex > 0) {
                        moveFocusToFilterRow(row, filterRows[rowIndex - 1])
                        return true
                    }
                    return false
                }
            })
        }
    }

    /** 筛选条件变化回调（触发列表刷新） */
    var onFilterChanged: (() -> Unit)? = null

    private fun updateViewStyle(view: View, color: Int, isBold: Boolean) {
        val valueTv = view.findViewById<TextView>(R.id.filterValue)
        valueTv.paint.isFakeBoldText = isBold
        valueTv.setTextColor(color)
    }

    /** 将焦点移动到指定筛选行并保持横向位置，带重试机制 */
    private fun moveFocusToFilterRow(fromRow: TvRecyclerView, targetRow: TvRecyclerView) {
        if (targetRow.layoutManager == null) return
        var targetPosition = 0
        if (fromRow.layoutManager != null) {
            val focusedChild = fromRow.focusedChild
            if (focusedChild != null) {
                val currentPosition = fromRow.getChildAdapterPosition(focusedChild)
                if (currentPosition >= 0 && targetRow.adapter != null) {
                    val targetCount = targetRow.adapter?.itemCount ?: 0
                    targetPosition = currentPosition.coerceAtMost((targetCount - 1).coerceAtLeast(0))
                }
            }
        }
        val finalPosition = targetPosition
        targetRow.post(object : Runnable {
            var retryCount = 0
            override fun run() {
                if (targetRow.layoutManager == null) return
                val item = targetRow.layoutManager?.findViewByPosition(finalPosition)
                if (item != null) {
                    item.requestFocus()
                    return
                }
                targetRow.scrollToPosition(finalPosition)
                targetRow.setSelectedPosition(finalPosition)
                if (++retryCount < 5) {
                    targetRow.post(this)
                }
            }
        })
    }

    override fun show() {
        super.show()
        window?.let { window ->
            val params = window.attributes
            params.gravity = Gravity.BOTTOM
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            window.decorView.setPadding(0, 0, 0, 0)
            window.attributes = params
        }
        filterRoot.post { requestFirstFilterFocus() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            filterRoot.post { requestFirstFilterFocus() }
        }
    }

    /** 将焦点落到第一个筛选行的第一个选项上 */
    private fun requestFirstFilterFocus() {
        if (filterRoot.childCount == 0 || filterRoot.hasFocus()) return
        val firstLine = filterRoot.getChildAt(0) ?: return
        val firstRow = firstLine.findViewById<TvRecyclerView>(R.id.mFilterKv) ?: return
        if (firstRow.layoutManager == null) return
        firstRow.post(object : Runnable {
            var retryCount = 0
            override fun run() {
                if (firstRow.layoutManager == null) return
                val item = firstRow.layoutManager?.findViewByPosition(0)
                if (item != null) {
                    item.requestFocus()
                    return
                }
                firstRow.setSelectedPosition(0)
                if (++retryCount < 5) {
                    firstRow.post(this)
                }
            }
        })
    }
}

/** 从当前主题解析主色调 */
private fun Context.themeColor(): Int {
    val attrs = intArrayOf(uiR.attr.color_theme)
    val typedArray = obtainStyledAttributes(attrs)
    val color = typedArray.getColor(0, Color.RED)
    typedArray.recycle()
    return color
}
