package com.tvbox.kotlin.core.common.bean

import com.google.gson.annotations.SerializedName
import java.io.Serializable

/** 爬虫分类列表 JSON 模型 */
class AbsSortJson : Serializable {

    @SerializedName("class")
    var classes: ArrayList<AbsJsonClass>? = null

    @SerializedName("list")
    var list: ArrayList<AbsJson.AbsJsonVod>? = null

    /** 转换为 XML 聚合模型 */
    fun toAbsSortXml(): AbsSortXml {
        val absSortXml = AbsSortXml()
        val movieSort = MovieSort()
        val sortList = ArrayList<MovieSort.SortData>()
        classes?.forEach { cls ->
            val sortData = MovieSort.SortData()
            sortData.id = cls.type_id
            sortData.name = cls.type_name
            sortData.flag = cls.type_flag
            sortList.add(sortData)
        }
        movieSort.sortList = sortList
        if (!list.isNullOrEmpty()) {
            val movie = Movie()
            val videos = ArrayList<Movie.Video>()
            list?.forEach { vod ->
                videos.add(vod.toXmlVideo())
            }
            movie.videoList = videos
            absSortXml.list = movie
        } else {
            absSortXml.list = null
        }
        absSortXml.classes = movieSort
        return absSortXml
    }

    /** JSON 分类项 */
    class AbsJsonClass : Serializable {
        var type_id: String? = null
        var type_name: String? = null
        var type_flag: String? = null
    }
}
