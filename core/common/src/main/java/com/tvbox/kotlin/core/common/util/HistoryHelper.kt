package com.tvbox.kotlin.core.common.util

import android.content.Context
import com.tvbox.kotlin.core.common.R

/** 历史记录条数配置 */
object HistoryHelper {

    private val hisNumArray = intArrayOf(20, 40, 60, 80, 100)

    @JvmStatic
    fun getHomeRecName(context: Context, index: Int): String =
        context.getString(R.string.his_count_fmt, getHisNum(index))

    @JvmStatic
    fun getHisNum(index: Int): Int {
        return if (index in hisNumArray.indices) hisNumArray[index]
        else hisNumArray[0]
    }
}
