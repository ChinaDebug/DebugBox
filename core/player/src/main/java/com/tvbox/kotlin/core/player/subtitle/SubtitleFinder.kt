package com.tvbox.kotlin.core.player.subtitle

import com.tvbox.kotlin.core.player.subtitle.model.Subtitle

/**
 * 字幕二分查找工具。在按开始时间有序的列表中定位指定时刻命中的字幕。
 */
object SubtitleFinder {

    /**
     * 查找 [positionMs] 时刻应显示的字幕。
     *
     * @param positionMs 当前播放位置（毫秒）
     * @param subtitles 已按开始时间升序排列的字幕列表
     * @return 命中的字幕，未命中返回 null
     */
    fun find(positionMs: Long, subtitles: List<Subtitle>?): Subtitle? {
        if (subtitles.isNullOrEmpty()) return null
        var start = 0
        var end = subtitles.size - 1
        while (start <= end) {
            val middle = (start + end) / 2
            val mid = subtitles[middle]
            val s = mid.start.mseconds.toLong()
            val e = mid.end.mseconds.toLong()
            when {
                positionMs < s -> {
                    // 处理字幕间空隙：若当前位置在某字幕结束后、下一条开始前，返回上一条
                    if (positionMs > e) return mid
                    end = middle - 1
                }
                positionMs > e -> {
                    if (positionMs < s) return mid
                    start = middle + 1
                }
                else -> return mid
            }
        }
        return null
    }
}
