package com.tvbox.kotlin.core.common.bean

import com.tvbox.kotlin.core.common.util.StringUtils
import java.io.Serializable

/** 爬虫 JSON 返回结果模型 */
class AbsJson : Serializable {
    var code: Int = 0
    var limit: String? = null
    var list: ArrayList<AbsJsonVod>? = null
    var msg: String? = null
    var page: Int = 0
    var pagecount: Int = 0
    var total: Int = 0

    /** 单条 JSON 影片信息 */
    class AbsJsonVod : Serializable {
        var group_id: Int = 0
        var type_id: Int = 0
        var type_id_1: Int = 0
        var type_name: String? = null
        var vod_actor: String? = null
        var vod_area: String? = null
        var vod_author: String? = null
        var vod_behind: String? = null
        var vod_blurb: String? = null
        var vod_class: String? = null
        var vod_color: String? = null
        var vod_content: String? = null
        var vod_copyright: String? = null
        var vod_director: String? = null
        var vod_douban_id: String? = null
        var vod_douban_score: String? = null
        var vod_down: String? = null
        var vod_down_from: String? = null
        var vod_down_note: String? = null
        var vod_down_server: String? = null
        var vod_down_url: String? = null
        var vod_duration: String? = null
        var vod_en: String? = null
        var vod_hits: String? = null
        var vod_hits_day: String? = null
        var vod_hits_month: String? = null
        var vod_hits_week: String? = null
        var vod_id: String? = null
        var vod_isend: String? = null
        var vod_jumpurl: String? = null
        var vod_lang: String? = null
        var vod_letter: String? = null
        var vod_level: String? = null
        var vod_lock: String? = null
        var vod_name: String? = null
        var vod_pic: String? = null
        var vod_pic_screenshot: String? = null
        var vod_pic_slide: String? = null
        var vod_pic_thumb: String? = null
        var vod_play_from: String? = null
        var vod_play_note: String? = null
        var vod_play_server: String? = null
        var vod_play_url: String? = null
        var vod_plot: String? = null
        var vod_plot_detail: String? = null
        var vod_plot_name: String? = null
        var vod_points: String? = null
        var vod_points_down: String? = null
        var vod_points_play: String? = null
        var vod_pubdate: String? = null
        var vod_pwd: String? = null
        var vod_pwd_down: String? = null
        var vod_pwd_down_url: String? = null
        var vod_pwd_play: String? = null
        var vod_pwd_play_url: String? = null
        var vod_pwd_url: String? = null
        var vod_rel_art: String? = null
        var vod_rel_vod: String? = null
        var vod_remarks: String? = null
        var vod_reurl: String? = null
        var vod_score: String? = null
        var vod_score_all: String? = null
        var vod_score_num: String? = null
        var vod_serial: String? = null
        var vod_state: String? = null
        var vod_status: String? = null
        var vod_sub: String? = null
        var vod_tag: String? = null
        var vod_time: String? = null
        var vod_time_add: String? = null
        var vod_time_hits: String? = null
        var vod_time_make: String? = null
        var vod_total: String? = null
        var vod_tpl: String? = null
        var vod_tpl_down: String? = null
        var vod_tpl_play: String? = null
        var vod_trysee: String? = null
        var vod_tv: String? = null
        var vod_up: String? = null
        var vod_version: String? = null
        var vod_weekday: String? = null
        var vod_writer: String? = null
        var vod_year: String? = null

        /** 卡片 action 字段，由 jar 内爬虫返回 */
        var action: String? = null

        /** 转换为 XML 模型 */
        fun toXmlVideo(): Movie.Video {
            val video = Movie.Video()
            video.tag = vod_tag
            video.last = vod_time
            video.id = vod_id
            video.tid = type_id
            video.name = vod_name
            video.type = type_name
            video.pic = vod_pic
            video.lang = vod_lang
            video.area = vod_area
            video.year = try {
                vod_year?.toIntOrNull() ?: 0
            } catch (_: Throwable) {
                0
            }
            video.state = vod_state
            video.note = vod_remarks
            video.actor = vod_actor
            video.director = vod_director
            val urlBean = Movie.Video.UrlBean()
            val playFrom = vod_play_from
            val playUrl = vod_play_url
            if (!playFrom.isNullOrEmpty() && !playUrl.isNullOrEmpty()) {
                val playFlags = playFrom.split("\\$\\$\\$".toRegex()).toTypedArray()
                val playUrls = playUrl.split("\\$\\$\\$".toRegex()).toTypedArray()
                val infoList = ArrayList<Movie.Video.UrlBean.UrlInfo>()
                for (i in playUrls.indices) {
                    val urlInfo = Movie.Video.UrlBean.UrlInfo()
                    if (StringUtils.isEmpty(playUrls[i])) continue
                    urlInfo.flag = if (i > playFlags.size) {
                        "线路$i"
                    } else {
                        if (StringUtils.isEmpty(playFlags[i])) "线路$i" else playFlags[i]
                    }
                    urlInfo.urls = playUrls[i]
                    infoList.add(urlInfo)
                }
                urlBean.infoList = infoList
            }
            video.urlBean = urlBean
            video.des = vod_content
            video.tag = vod_tag
            video.action = action
            return video
        }
    }

    /** 转换为 XML 聚合模型 */
    fun toAbsXml(): AbsXml {
        val xml = AbsXml()
        val movie = Movie()
        movie.page = page
        movie.pagecount = pagecount
        movie.pagesize = try {
            limit?.toIntOrNull() ?: 0
        } catch (_: Throwable) {
            0
        }
        movie.recordcount = total
        val videoList = ArrayList<Movie.Video>()
        list?.forEach { vod ->
            try {
                videoList.add(vod.toXmlVideo())
            } catch (_: Throwable) {
                movie.pagesize = 0
            }
        }
        movie.videoList = videoList
        xml.movie = movie
        xml.msg = msg
        return xml
    }
}
