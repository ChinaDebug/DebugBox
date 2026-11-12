package com.tvbox.kotlin.core.player

/**
 * 单条轨道信息（音频/字幕）。
 *
 * 当前仅服务 Exo（Media3）内核：使用 [renderId] + [trackGroupId] + [trackId]
 * 三元组定位具体 Format。
 *
 */
data class TrackInfoBean(
    /** 轨道在底层播放器中的索引（Exo 为 Format 在 TrackGroup 中的下标） */
    var trackId: Int = INVALID_TRACK_ID,
    /** Exo 渲染器索引 */
    var renderId: Int = 0,
    /** Exo TrackGroup 在 TrackGroupArray 中的下标 */
    var trackGroupId: Int = 0,
    /** 展示名称 */
    var name: String = "",
    /** 语言标识，可能为空 */
    var language: String = "",
    /** 是否当前选中 */
    var selected: Boolean = false
) {
    companion object {
        /** 非法轨道 ID，用于表示关闭轨道或未选中 */
        const val INVALID_TRACK_ID = -1
    }
}

/**
 * 当前媒体包含的音频轨道与字幕轨道集合。
 * 由各播放器实现填充，供控制层渲染轨道选择菜单。
 */
class TrackInfo {
    private val audio: MutableList<TrackInfoBean> = mutableListOf()
    private val subtitle: MutableList<TrackInfoBean> = mutableListOf()

    fun getAudio(): List<TrackInfoBean> = audio

    fun getSubtitle(): List<TrackInfoBean> = subtitle

    fun addAudio(bean: TrackInfoBean) {
        audio.add(bean)
    }

    fun addSubtitle(bean: TrackInfoBean) {
        subtitle.add(bean)
    }

    /**
     * 获取已选中轨道的索引。
     *
     * @param list 轨道集合
     * @param track true 返回底层 [TrackInfoBean.trackId]，false 返回在列表中的位置
     * @return 选中项索引，未选中返回 [TrackInfoBean.INVALID_TRACK_ID]
     */
    fun getSelected(list: List<TrackInfoBean>, track: Boolean): Int {
        list.forEachIndexed { index, bean ->
            if (bean.selected) return if (track) bean.trackId else index
        }
        return TrackInfoBean.INVALID_TRACK_ID
    }

    fun getAudioSelected(track: Boolean): Int = getSelected(audio, track)

    fun getSubtitleSelected(track: Boolean): Int = getSelected(subtitle, track)
}
