package com.tvbox.kotlin.feature.play.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.tvbox.kotlin.core.ui.R as CoreUiR
import com.tvbox.kotlin.feature.play.R

/**
 * 播放器控制栏 PopupWindow 风格菜单工具类。
 *
 * 在按钮位置向上弹出半透明圆角浮窗菜单，复用 shape_dialog_bg_main 容器风格
 * 与 button_dialog_main 选项风格，TV 端 D-pad 焦点自带白色描边。
 */
class PlayerPopupMenu private constructor(
    context: Context,
    private val anchor: View,
    private val itemWidth: Int
) {

    /** 单选回调：点击立即触发并关闭菜单 */
    fun interface OnSingleSelectCallback {
        fun onSelect(position: Int)
    }

    /** 多选回调：每次切换状态触发，菜单不关闭，由用户按返回键关闭 */
    fun interface OnMultiToggleCallback {
        fun onToggle(position: Int, nowSelected: Boolean)
    }

    private val popupWindow: PopupWindow
    private val container: LinearLayout

    init {
        container = LayoutInflater.from(context)
            .inflate(R.layout.player_popup_menu, null) as LinearLayout
        popupWindow = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // 设置焦点可获取，TV 端 D-pad 才能导航到菜单项
        popupWindow.isFocusable = true
        // 外部点击关闭
        popupWindow.isOutsideTouchable = true
        // 背景透明，由容器自身的 shape_dialog_bg_main 提供视觉效果
        popupWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    companion object {
        /**
         * 显示单选菜单：当前选中项前缀 √，点击立即触发回调并关闭
         *
         * @param context     上下文
         * @param anchor      锚定按钮
         * @param items       选项文案列表
         * @param selectedPos 当前选中位置
         * @param itemWidth   选项宽度（像素）
         * @param callback    选中回调
         */
        @JvmStatic
        fun showSingle(
            context: Context,
            anchor: View,
            items: List<String>,
            selectedPos: Int,
            itemWidth: Int,
            callback: OnSingleSelectCallback?
        ): PlayerPopupMenu {
            val menu = PlayerPopupMenu(context, anchor, itemWidth)
            items.forEachIndexed { i, text ->
                val itemRoot = createItemBase(menu, text, i == selectedPos, itemWidth)
                itemRoot.setOnClickListener { menu.performSingleSelect(callback, i) }
                itemRoot.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        menu.performSingleSelect(callback, i)
                        true
                    } else {
                        false
                    }
                }
                menu.container.addView(itemRoot)
            }
            val focusPos = if (selectedPos in items.indices) selectedPos else 0
            menu.showUpward(focusPos)
            return menu
        }

        /**
         * 显示多选菜单：已选项前缀 √，点击只切换状态不关闭，由用户按返回键关闭
         *
         * @param context           上下文
         * @param anchor            锚定按钮
         * @param items             选项文案列表
         * @param selectedPositions 已选位置集合
         * @param itemWidth         选项宽度（像素）
         * @param callback          切换回调
         */
        @JvmStatic
        fun showMulti(
            context: Context,
            anchor: View,
            items: List<String>,
            selectedPositions: Set<Int>?,
            itemWidth: Int,
            callback: OnMultiToggleCallback?
        ): PlayerPopupMenu {
            val menu = PlayerPopupMenu(context, anchor, itemWidth)
            items.forEachIndexed { i, text ->
                val checked = selectedPositions?.contains(i) == true
                val itemRoot = createItemBase(menu, text, checked, itemWidth)
                val checkView = itemRoot.findViewById<TextView>(R.id.tv_popup_check)
                itemRoot.setOnClickListener { menu.performMultiToggle(checkView, callback, i) }
                itemRoot.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            menu.clearOtherFocus(itemRoot)
                            itemRoot.requestFocus()
                            false
                        }
                        MotionEvent.ACTION_UP -> {
                            menu.performMultiToggle(checkView, callback, i)
                            true
                        }
                        else -> false
                    }
                }
                menu.container.addView(itemRoot)
            }
            var focusPos = 0
            if (!selectedPositions.isNullOrEmpty()) {
                items.indices.firstOrNull { selectedPositions.contains(it) }?.let { focusPos = it }
            }
            menu.showUpward(focusPos)
            return menu
        }

        /** 创建并设置菜单项通用样式，返回根 View */
        private fun createItemBase(
            menu: PlayerPopupMenu,
            text: String,
            checked: Boolean,
            itemWidth: Int
        ): View {
            val context = menu.anchor.context
            val itemRoot = LayoutInflater.from(context)
                .inflate(R.layout.item_player_popup_menu, menu.container, false)
            val checkView = itemRoot.findViewById<TextView>(R.id.tv_popup_check)
            val textView = itemRoot.findViewById<TextView>(R.id.tv_popup_item)
            // 左侧勾选标记独立控制，√ 与文字不再挤压在同一行
            checkView.visibility = if (checked) View.VISIBLE else View.INVISIBLE
            textView.text = text
            // 设置固定宽度保证菜单整齐
            val lp = LinearLayout.LayoutParams(
                if (itemWidth > 0) itemWidth else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = context.resources.getDimensionPixelSize(CoreUiR.dimen.vs_5)
            itemRoot.layoutParams = lp
            return itemRoot
        }
    }

    /** 单选：触发回调并关闭菜单 */
    private fun performSingleSelect(callback: OnSingleSelectCallback?, pos: Int) {
        popupWindow.dismiss()
        callback?.onSelect(pos)
    }

    /** 多选：切换勾选状态并触发回调 */
    private fun performMultiToggle(checkView: TextView, callback: OnMultiToggleCallback?, pos: Int) {
        val nowChecked = checkView.visibility != View.VISIBLE
        checkView.visibility = if (nowChecked) View.VISIBLE else View.INVISIBLE
        callback?.onToggle(pos, nowChecked)
    }

    /** 多选：触摸/鼠标切换前清除其它 item 的焦点，避免多个 item 同时高亮 */
    private fun clearOtherFocus(current: View) {
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child !== current && child.isFocused) {
                child.clearFocus()
            }
        }
    }

    /**
     * 锚定到按钮上方弹出：通过 showAtLocation 计算屏幕坐标，避免动态高度问题
     *
     * @param focusPosition 菜单显示后默认聚焦的位置索引
     */
    private fun showUpward(focusPosition: Int) {
        // 先测量容器实际尺寸
        container.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = container.measuredWidth
        val popupHeight = container.measuredHeight

        // 获取锚点按钮在屏幕上的位置
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val anchorX = anchorLoc[0]
        val anchorY = anchorLoc[1]
        val anchorWidth = anchor.width

        // 按钮水平居中，垂直方向在按钮上方
        var x = anchorX + (anchorWidth - popupWidth) / 2
        val y = anchorY - popupHeight
        // 防止 x 越界（屏幕左/右边）
        if (x < 0) x = 0
        val dm = anchor.context.resources.displayMetrics
        val screenWidth = dm.widthPixels
        if (x + popupWidth > screenWidth) x = screenWidth - popupWidth

        popupWindow.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)

        // 聚焦指定位置的菜单项，TV 端可直接 D-pad 导航
        val pos = if (focusPosition in 0 until container.childCount) focusPosition else 0
        // 先让容器获取焦点，确保 PopupWindow 窗口焦点进入菜单区域，
        // 再延迟聚焦到目标子项，避免触摸/鼠标点击后焦点仍留在底层控制栏
        container.requestFocus()
        container.post {
            if (container.childCount > pos) {
                container.getChildAt(pos)?.requestFocus()
            }
        }
    }

    /** 主动关闭菜单 */
    fun dismiss() {
        if (popupWindow.isShowing) {
            popupWindow.dismiss()
        }
    }

    fun isShowing(): Boolean = popupWindow.isShowing

    /** 设置菜单关闭监听，供调用方在菜单 dismiss 时恢复控制栏自动隐藏倒计时 */
    fun setOnDismissListener(listener: PopupWindow.OnDismissListener?) {
        popupWindow.setOnDismissListener(listener)
    }
}
