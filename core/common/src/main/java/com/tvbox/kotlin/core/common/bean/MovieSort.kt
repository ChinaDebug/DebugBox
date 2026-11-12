package com.tvbox.kotlin.core.common.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import com.thoughtworks.xstream.annotations.XStreamAsAttribute
import com.thoughtworks.xstream.annotations.XStreamConverter
import com.thoughtworks.xstream.annotations.XStreamImplicit
import com.thoughtworks.xstream.converters.extended.ToAttributedValueConverter
import java.io.Serializable

/** 首页分类数据模型 */
@XStreamAlias("class")
class MovieSort : Serializable {
    @XStreamImplicit(itemFieldName = "ty")
    var sortList: List<SortData>? = null

    /** 单个分类项 */
    @XStreamAlias("ty")
    @XStreamConverter(value = ToAttributedValueConverter::class, strings = ["name"])
    class SortData : Serializable, Comparable<SortData> {
        @XStreamAsAttribute
        var id: String? = null

        var name: String? = null

        var sort: Int = -1

        var select: Boolean = false

        var filters: ArrayList<SortFilter> = ArrayList()

        var filterSelect: HashMap<String, String> = HashMap()

        /** 类型 */
        var flag: String? = null

        constructor()

        constructor(id: String?, name: String?) {
            this.id = id
            this.name = name
        }

        /** 已选筛选项数量 */
        fun filterSelectCount(): Int {
            return filterSelect.values.count { it != null }
        }

        override fun compareTo(other: SortData): Int = this.sort - other.sort
    }

    /** 分类筛选项 */
    class SortFilter : Serializable {
        var key: String? = null
        var name: String? = null
        var values: LinkedHashMap<String, String>? = null
    }
}
