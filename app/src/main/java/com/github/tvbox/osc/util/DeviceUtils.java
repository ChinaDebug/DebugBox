package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.res.Configuration;

/**
 * 设备类型判断工具类，用于区分 TV 端与手机端
 */
public class DeviceUtils {

    private DeviceUtils() {
        // 工具类禁止实例化
    }

    /**
     * 判断当前设备是否为 TV（电视/机顶盒/投影仪）
     */
    public static boolean isTvDevice(Context context) {
        if (context == null) {
            return false;
        }
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK)
                == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    /**
     * 判断当前设备是否为手机/平板等手持设备
     */
    public static boolean isMobileDevice(Context context) {
        return !isTvDevice(context);
    }
}
