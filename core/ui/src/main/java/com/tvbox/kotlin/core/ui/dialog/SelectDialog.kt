package com.tvbox.kotlin.core.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.text.TextPaint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.tvbox.kotlin.core.ui.R as uiR
import me.jessyan.autosize.utils.AutoSizeUtils

/**
 * 通用网格选择对话框，item 复用 item_dialog_select。
 * [widthMm] 指定弹窗宽度（mm），-1 使用布局默认宽度，站点弹窗按列数动态计算。
 * [widthPx] 指定弹窗宽度（像素），>0 时优先于 [widthMm]（mm2px 依赖 autosize 换算，
 * 在宽屏设备上偏小，长文本场景直接按像素设宽更可靠）。
 * [autoWidth] 为 true 时按最长选项文本测量动态设宽（上下限 默认宽度 vs_320 ~ 屏幕 92%），
 * 优先于 [widthPx]/[widthMm]，用于长文本选项不被截断。
 */
class SelectDialog<T>(
    context: Context,
    private val tip: String,
    private val items: List<T>,
    private val display: (T) -> String,
    private val spanCount: Int = 1,
    private val select: Int = 0,
    private val widthMm: Int = -1,
    private val widthPx: Int = -1,
    private val autoWidth: Boolean = false,
    private val footerText: String? = null,
    private val onFooterClick: (() -> Unit)? = null,
    private val onSelect: (T, Int) -> Unit
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(uiR.layout.dialog_select)
        setCanceledOnTouchOutside(true)
        findViewById<TextView>(uiR.id.title)?.text = tip
        val list = findViewById<TvRecyclerView>(uiR.id.list)
        list.layoutManager = V7GridLayoutManager(context, spanCount)
        val selectIdx = if (select in items.indices) select else 0
        val names = items.map { display(it) }.toTypedArray()
        // 按列数动态设置弹窗宽度
        if (autoWidth) {
            applyAutoWidth(names)
        } else if (widthPx > 0) {
            val clRoot = findViewById<View>(uiR.id.cl_root)
            clRoot.layoutParams = clRoot.layoutParams.apply { width = widthPx }
        } else if (widthMm > 0) {
            val clRoot = findViewById<View>(uiR.id.cl_root)
            clRoot.layoutParams = clRoot.layoutParams.apply { width = AutoSizeUtils.mm2px(context, widthMm.toFloat()) }
        }
        val adapter = SelectAdapter(names, selectIdx) { pos ->
            onSelect(items[pos], pos)
            dismiss()
        }
        list.adapter = adapter
        list.setSelectedPosition(selectIdx)
        if (selectIdx < 10) {
            list.setSelection(selectIdx)
        } else {
            list.post { list.smoothScrollToPosition(selectIdx) }
        }
        // 底部附加操作按钮（如"多仓"入口）：footerText 为空时不显示
        val btnFooter = findViewById<TextView>(uiR.id.btnFooter)
        if (footerText.isNullOrEmpty()) {
            btnFooter.visibility = View.GONE
        } else {
            btnFooter.visibility = View.VISIBLE
            btnFooter.text = footerText
            btnFooter.setOnClickListener {
                onFooterClick?.invoke()
                dismiss()
            }
        }
    }

    /**
     * 按最长选项文本动态设置弹窗宽度。
     * 用选中态加粗样式测量（最宽情形），加上 item 内边距与列表左右内边距，
     * 下限为布局默认宽度 vs_320，上限为屏幕宽度的 92%，防止长文本被省略号截断。
     */
    private fun applyAutoWidth(names: Array<String>) {
        val clRoot = findViewById<View>(uiR.id.cl_root) ?: return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = context.resources.getDimension(uiR.dimen.ts_22)
            typeface = Typeface.DEFAULT_BOLD
        }
        val maxTextWidth = names.maxOfOrNull { paint.measureText(it) } ?: 0f
        val itemPadding = AutoSizeUtils.mm2px(context, 16f) // vs_8 × 2
        val listPadding = AutoSizeUtils.mm2px(context, 60f) // vs_30 × 2
        val defaultWidth = AutoSizeUtils.mm2px(context, 320f) // 布局默认 vs_320
        val screenWidth = context.resources.displayMetrics.widthPixels
        val target = (maxTextWidth + itemPadding + listPadding).toInt()
            .coerceIn(defaultWidth, (screenWidth * 0.92f).toInt())
        clRoot.layoutParams = clRoot.layoutParams.apply { width = target }
    }
}

/** 选择列表适配器（非泛型，选项为显示名数组） */
private class SelectAdapter(
    private val items: Array<String>,
    private var current: Int,
    private val onSelect: (Int) -> Unit
) : RecyclerView.Adapter<SelectAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(uiR.layout.item_dialog_select, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.tvName.text = items[position]
        val isSelected = position == current
        // 选中态：激活背景 + 主题色加粗文字（触摸模式同样清晰可辨）
        holder.tvName.isActivated = isSelected
        applyMarquee(holder.tvName, holder.tvName.isFocused)
        applySelectState(holder.tvName, focused = holder.tvName.isFocused, selected = isSelected)
        holder.tvName.setOnClickListener { onSelect(position) }
        holder.tvName.setOnFocusChangeListener { view, hasFocus ->
            view.isActivated = hasFocus || isSelected
            applyMarquee(view as TextView, hasFocus)
            applySelectState(view, focused = hasFocus, selected = isSelected)
        }
    }

    /**
     * 聚焦时启用跑马灯滚动展示超长文本（无限循环），失焦恢复省略号截断。
     * 跑马灯依赖 selected 状态 + ellipsize=marquee，非聚焦时显式复位避免复用错乱。
     */
    private fun applyMarquee(tv: TextView, focused: Boolean) {
        tv.isSelected = focused
        tv.ellipsize = if (focused) TextUtils.TruncateAt.MARQUEE else TextUtils.TruncateAt.END
        if (focused) tv.marqueeRepeatLimit = -1
    }

    /**
     * 文字着色：
     * - 聚焦时背景为主题色（半透明），文字统一白色保证对比度，不受选中态叠加影响；
     * - 未聚焦时选中项用主题色加粗区分，其余白色常规。
     */
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

    override fun getItemCount(): Int = items.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(uiR.id.tvName)
    }
}
