package com.tvbox.kotlin.core.common.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import java.io.Serializable

/** 爬虫分类列表 XML 聚合模型 */
@XStreamAlias("rss")
class AbsSortXml : Serializable {
    @XStreamAlias("class")
    var classes: MovieSort? = null

    @XStreamAlias("list")
    var list: Movie? = null

    var videoList: List<Movie.Video>? = null
}
