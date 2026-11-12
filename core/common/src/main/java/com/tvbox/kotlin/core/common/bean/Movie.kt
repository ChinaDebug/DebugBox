package com.tvbox.kotlin.core.common.bean

import com.thoughtworks.xstream.annotations.XStreamAlias
import com.thoughtworks.xstream.annotations.XStreamAsAttribute
import com.thoughtworks.xstream.annotations.XStreamConverter
import com.thoughtworks.xstream.annotations.XStreamImplicit
import com.thoughtworks.xstream.converters.extended.ToAttributedValueConverter
import java.io.Serializable

/** 爬虫返回的影片列表数据模型 */
@XStreamAlias("list")
class Movie : Serializable {
    @XStreamAsAttribute
    var page: Int = 0

    /** 总页数 */
    @XStreamAsAttribute
    var pagecount: Int = 0

    @XStreamAsAttribute
    var pagesize: Int = 0

    /** 总条数 */
    @XStreamAsAttribute
    var recordcount: Int = 0

    @XStreamImplicit(itemFieldName = "video")
    var videoList: List<Video>? = null

    /** 单条影片信息 */
    @XStreamAlias("video")
    class Video : Serializable {
        /** 时间 */
        @XStreamAlias("last")
        var last: String? = null

        /** 内容 id */
        @XStreamAlias("id")
        var id: String? = null

        /** 父级 id */
        @XStreamAlias("tid")
        var tid: Int = 0

        /** 影片名称 */
        @XStreamAlias("name")
        var name: String? = null

        /** 类型名称 */
        @XStreamAlias("type")
        var type: String? = null

        /** 图片 */
        @XStreamAlias("pic")
        var pic: String? = null

        /** 语言 */
        @XStreamAlias("lang")
        var lang: String? = null

        /** 地区 */
        @XStreamAlias("area")
        var area: String? = null

        /** 年份 */
        @XStreamAlias("year")
        var year: Int = 0

        @XStreamAlias("state")
        var state: String? = null

        /** 描述集数或影片信息 */
        @XStreamAlias("note")
        var note: String? = null

        /** 演员 */
        @XStreamAlias("actor")
        var actor: String? = null

        /** 导演 */
        @XStreamAlias("director")
        var director: String? = null

        @XStreamAlias("dl")
        var urlBean: UrlBean? = null

        @XStreamAlias("des")
        var des: String? = null

        var sourceKey: String? = null

        @XStreamAlias("tag")
        var tag: String? = null

        /** 卡片 action 字段，爬虫返回非空时点击卡片会调用 spider.action(action) */
        @XStreamAlias("action")
        var action: String? = null

        /** 播放线路集合 */
        @XStreamAlias("dl")
        class UrlBean : Serializable {
            @XStreamImplicit(itemFieldName = "dd")
            var infoList: List<UrlInfo>? = null

            /** 单条播放线路 */
            @XStreamAlias("dd")
            @XStreamConverter(value = ToAttributedValueConverter::class, strings = ["urls"])
            class UrlInfo : Serializable {
                /** 线路标识 */
                @XStreamAsAttribute
                var flag: String? = null

                /** 播放地址原始串 */
                var urls: String? = null

                var beanList: List<InfoBean>? = null

                /** 单集信息 */
                class InfoBean(
                    var name: String? = null,
                    var url: String? = null
                ) : Serializable
            }
        }
    }
}
