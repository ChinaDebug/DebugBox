package com.tvbox.kotlin.core.common.bean

import com.google.gson.Gson
import com.tvbox.kotlin.core.common.util.MD5
import com.tvbox.kotlin.core.common.util.RegexUtils
import java.io.Serializable

/** 详情页影片信息 */
class VodInfo : Serializable {
    var last: String? = null
    var id: String? = null
    var tid: Int = 0
    var name: String? = null
    var type: String? = null
    var dt: String? = null
    var pic: String? = null
    var lang: String? = null
    var area: String? = null
    var year: Int = 0
    var state: String? = null
    var note: String? = null
    var actor: String? = null
    var director: String? = null
    var seriesFlags: ArrayList<VodSeriesFlag>? = null
    var seriesMap: LinkedHashMap<String, List<VodSeries>>? = null
    var des: String? = null
    var playFlag: String? = null
    var playIndex: Int = 0
    var playGroup: Int = 0
    var playGroupCount: Int = 0
    var playNote: String = ""
    var sourceKey: String? = null
    var playerCfg: String = ""
    var reverseSort: Boolean = false
    var playEpisodeIndex: Int = 0
    var playEpisodeName: String = ""
    var totalEpisodes: Int = 0

    /** 当前播放进度缓存键（progressKey 或播放地址），随历史记录持久化，删除历史时用于清理进度缓存 */
    var playProgressKey: String = ""

    /** 从 Video 填充字段并构建剧集分组 */
    fun setVideo(video: Movie.Video) {
        last = video.last
        id = video.id
        tid = video.tid
        name = video.name
        type = video.type
        pic = video.pic
        lang = video.lang
        area = video.area
        year = video.year
        state = video.state
        note = video.note
        actor = video.actor
        director = video.director
        des = video.des
        val infoList = video.urlBean?.infoList
        if (!infoList.isNullOrEmpty()) {
            val tempSeriesMap = LinkedHashMap<String, List<VodSeries>>()
            seriesFlags = ArrayList()
            infoList.forEach { urlInfo ->
                val beans = urlInfo.beanList
                if (!beans.isNullOrEmpty()) {
                    val seriesList = beans.map { VodSeries(it.name, it.url) }
                    tempSeriesMap[urlInfo.flag ?: ""] = seriesList
                    seriesFlags!!.add(VodSeriesFlag(urlInfo.flag))
                }
            }
            seriesMap = LinkedHashMap()
            seriesFlags?.forEach { flag ->
                val list = tempSeriesMap[flag.name ?: ""] ?: return@forEach
                val mutable = list.toMutableList()
                if (seriesFlags!!.size <= 3 && isReverse(mutable)) {
                    mutable.reverse()
                }
                seriesMap!![flag.name ?: ""] = mutable
            }
        }
    }

    private fun extractNumber(name: String): Int {
        val matcher = RegexUtils.getPattern("\\d+").matcher(name)
        return if (matcher.find()) matcher.group().toIntOrNull() ?: 0 else 0
    }

    private fun isReverse(list: List<VodSeries>): Boolean {
        if (list.size > 300) return false
        var ascCount = 0
        var descCount = 0
        val limit = minOf(list.size - 1, 6)
        for (i in 0 until limit) {
            val current = extractNumber(list[i].name ?: "")
            val next = extractNumber(list[i + 1].name ?: "")
            if (current < next) {
                ascCount++
                if (ascCount == 2) return false
            } else if (current > next) {
                descCount++
                if (descCount == 2) return true
            }
        }
        return false
    }

    /** 反转所有线路的剧集顺序 */
    fun reverse() {
        val map = seriesMap ?: return
        map.values.forEach { list ->
            if (list is java.util.ArrayList) {
                java.util.Collections.reverse(list)
            }
        }
    }

    /** 当前播放位置的绝对索引 */
    fun getplayIndex(): Int = playGroup * maxOf(playGroupCount, 1) + playIndex

    /** 根据剧集索引更新播放位置 */
    fun updatePlayPositionFromEpisodeIndex() {
        val map = seriesMap ?: return
        val flag = playFlag ?: return
        if (!map.containsKey(flag)) return
        val seriesList = map[flag] ?: return
        if (seriesList.isEmpty()) return
        if (playEpisodeIndex >= 0 && playEpisodeIndex < seriesList.size) {
            playGroup = playEpisodeIndex / maxOf(playGroupCount, 1)
            playIndex = playEpisodeIndex % maxOf(playGroupCount, 1)
        }
    }

    /** 根据剧集名查找索引 */
    fun findEpisodeIndexByName(episodeName: String?): Int {
        val map = seriesMap ?: return -1
        val flag = playFlag ?: return -1
        if (!map.containsKey(flag) || episodeName == null) return -1
        val seriesList = map[flag] ?: return -1
        seriesList.forEachIndexed { i, vodSeries ->
            if (episodeName == vodSeries.name) return i
        }
        return -1
    }

    /** 保存当前播放剧集名 */
    fun saveCurrentEpisodeInfo() {
        val map = seriesMap ?: return
        val flag = playFlag ?: return
        if (!map.containsKey(flag)) return
        val seriesList = map[flag] ?: return
        if (seriesList.isEmpty()) return
        if (playEpisodeIndex >= 0 && playEpisodeIndex < seriesList.size) {
            playEpisodeName = seriesList[playEpisodeIndex].name ?: ""
        }
    }

    /** 深拷贝 */
    public fun clone(): Any {
        return try {
            val gson = Gson()
            val json = gson.toJson(this)
            gson.fromJson(json, VodInfo::class.java)
        } catch (_: Exception) {
            this
        }
    }

    fun isSeriesEmpty(): Boolean = seriesMap?.isEmpty() ?: true

    fun getFlagSeries(playFlag: String): List<VodSeries> {
        return if (!isSeriesEmpty()) seriesMap!![playFlag] ?: ArrayList() else ArrayList()
    }

    fun isFlagSeriesEmpty(playFlag: String): Boolean = getFlagSeries(playFlag).isEmpty()

    fun getVodSeries(playFlag: String, playIndex: Int): VodSeries? {
        val list = getFlagSeries(playFlag)
        return if (list.isNotEmpty() && playIndex in 0 until list.size) list[playIndex] else null
    }

    /** 线路标识 */
    class VodSeriesFlag : Serializable {
        var name: String? = null
        var selected: Boolean = false

        constructor()

        constructor(name: String?) {
            this.name = name
        }
    }

    /** 单集信息 */
    class VodSeries : Serializable {
        var name: String? = null
        var url: String? = null
        var episodeId: String? = null
            get() {
                if (field == null && name != null) {
                    field = MD5.string2MD5(name)
                }
                return field
            }
        var selected: Boolean = false

        constructor()

        constructor(name: String?, url: String?) {
            this.name = name
            this.url = url
            this.episodeId = MD5.string2MD5(name)
        }
    }
}
