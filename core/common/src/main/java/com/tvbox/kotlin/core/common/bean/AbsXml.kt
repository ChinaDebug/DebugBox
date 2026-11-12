package com.tvbox.kotlin.core.common.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import java.io.Serializable

/** 爬虫 XML 返回结果聚合模型 */
@XStreamAlias("rss")
class AbsXml : Serializable {
    @XStreamAlias("list")
    var movie: Movie? = null

    @XStreamAlias("msg")
    var msg: String? = null

    /** 搜索结果对应的源 key，非序列化字段 */
    @Transient
    var sourceKey: String? = null
}
