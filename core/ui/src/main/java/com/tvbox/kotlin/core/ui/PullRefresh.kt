package com.tvbox.kotlin.core.ui

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView

/** 触摸下拉刷新手势触发阈值（dp）：过小会导致正常滑动轻微下拉即误触刷新 */
private const val PULL_TRIGGER_DP = 140f

/** 滚轮单格滚动跳变阈值（dp）：滚轮滚动被模拟器合成为触摸事件，首次 MOVE 即大幅跳变，
 *  真实拖拽/手指下拉是渐进累积，据此区分 */
private const val SCROLL_JUMP_DP = 60f

/**
 * 触摸下拉刷新：列表已滚动到顶部时，用户继续下拉超过阈值触发 [onRefresh]。
 *
 * - 用 OnItemTouchListener：item（卡片）会消费触摸 DOWN，
 *   后者收不到 DOWN 导致 item 上拖拽下拉失效；
 * - 排除模拟器/鼠标滚轮合成触摸事件（无 DOWN 直接 MOVE 或首次 MOVE 大幅跳变），避免浏览时误刷新；
 * - 单次手势只触发一次，返回 false 不拦截，滚动/点击不受影响。
 */
fun RecyclerView.enablePullToRefresh(onRefresh: () -> Unit) {
    val threshold = PULL_TRIGGER_DP * resources.displayMetrics.density
    val jumpThreshold = SCROLL_JUMP_DP * resources.displayMetrics.density
    var downY = 0f
    var downReceived = false
    var firstMove = true
    var consumed = false
    var triggered = false
    addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.y
                    downReceived = true
                    firstMove = true
                    consumed = false
                    triggered = false
                }
                MotionEvent.ACTION_MOVE -> {
                    // 无 DOWN 直接 MOVE（滚轮合成事件）不参与判定
                    if (!downReceived) return false
                    val delta = e.y - downY
                    if (firstMove) {
                        firstMove = false
                        // 首次移动即大幅跳变 = 滚轮滚动（滚一格一跳），本次手势作废
                        if (delta > jumpThreshold) consumed = true
                    }
                    // 已在顶部且手势明显下拉超过阈值时触发一次刷新
                    if (!consumed && !triggered && delta > threshold && !rv.canScrollVertically(-1)) {
                        triggered = true
                        onRefresh()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    downReceived = false
                    triggered = false
                }
            }
            // 不拦截事件，交给 RecyclerView/item 正常处理滚动与点击
            return false
        }
    })
}
