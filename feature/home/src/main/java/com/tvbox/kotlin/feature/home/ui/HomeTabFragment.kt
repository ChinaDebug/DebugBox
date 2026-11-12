package com.tvbox.kotlin.feature.home.ui

/**
 * 首页 ViewPager 各 Tab Fragment 的统一契约。
 * 由 HomeActivity 调用，屏蔽 GridFragment 与 UserFragment 的差异。
 */
interface HomeTabFragment {

    /** 首次可见时加载数据，已加载则跳过 */
    fun loadDataIfNotLoaded()

    /**
     * 预加载数据：相邻分类在非前台状态也执行请求，提前准备数据供快速切换秒显。
     * 与 [loadDataIfNotLoaded] 的区别：不要求 Fragment 处于前台，且不展示 loading。
     */
    fun preloadData()

    /** 滚动到顶部 */
    fun scrollTop()

    /** 内容是否已滚动到顶部，供 HomeActivity 处理返回键逻辑 */
    fun isTop(): Boolean

    /** 是否已加载完成，供 HomeActivity 判断焦点是否允许下移 */
    fun isLoad(): Boolean

    /** 重置筛选状态，切换分类时调用 */
    fun resetFilterState()

    /** 强制刷新当前数据 */
    fun forceRefresh()

    /** 弹出分类筛选对话框（无筛选能力的页面为空实现） */
    fun showFilter()

    /** 尝试恢复之前保存的视图状态 */
    fun restoreView(): Boolean
}
