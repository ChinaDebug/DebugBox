package com.tvbox.kotlin.core.common.bean

/** 站点源配置 */
class SourceBean {
    var key: String? = null
    var name: String? = null
    var api: String? = null

    /** 0 xml 1 json 3 Spider */
    var type: Int = 0

    /** 是否可搜索 */
    var searchable: Int = 0

    /** 是否可以快速搜索 */
    var quickSearch: Int = 0

    /** 可筛选 */
    var filterable: Int = 0

    /** 设置选择列表里隐藏 */
    var hide: Int = 0

    /** 站点解析 Url */
    var playerUrl: String? = null

    /** 扩展数据 */
    var ext: String? = null

    /** 自定义 jar */
    var jar: String? = null

    /** 分类与排序 */
    var categories: ArrayList<String>? = null

    /** 0 system 2 exo 10 mxplayer -1 以参数设置页面为准 */
    var playerType: Int = 0

    /** 需要点击播放的嗅探站点 selector */
    var clickSelector: String? = null

    /** 展示风格 */
    var style: String? = null

    fun isSearchable(): Boolean = searchable != 0

    fun isQuickSearch(): Boolean = quickSearch != 0
}
