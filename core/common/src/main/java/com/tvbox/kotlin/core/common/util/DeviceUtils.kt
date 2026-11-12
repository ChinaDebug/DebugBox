package com.tvbox.kotlin.core.common.util

import android.content.Context
import android.content.res.Configuration

/** 设备类型判断工具 */
object DeviceUtils {

    /** 判断当前设备是否为 TV（电视/机顶盒/投影仪） */
    fun isTvDevice(context: Context?): Boolean {
        if (context == null) return false
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }
}
