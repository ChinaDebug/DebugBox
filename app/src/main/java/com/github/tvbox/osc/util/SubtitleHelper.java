package com.github.tvbox.osc.util;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;

import com.github.tvbox.osc.subtitle.widget.SimpleSubtitleView;
import com.orhanobut.hawk.Hawk;

public class SubtitleHelper {

    // 字体颜色选项
    public static final int[] TEXT_COLORS = {
            Color.WHITE,        // 白色
            Color.YELLOW,       // 黄色
            Color.GREEN,        // 绿色
            Color.CYAN,         // 青色
            Color.MAGENTA,      // 紫色
            Color.RED,          // 红色
            Color.parseColor("#FF9800"),  // 橙色
            Color.parseColor("#E5E5E5"),  // 灰色
            Color.parseColor("#FFEB3B"),  // 浅黄
            Color.parseColor("#FFB6C1"),  // 粉色
    };

    // 字体颜色名称
    public static final String[] TEXT_COLOR_NAMES = {
            "白色", "黄色", "绿色", "青色", "紫色", "红色", "橙色", "灰色", "浅黄", "粉色"
    };

    // 描边颜色选项
    public static final int[] STROKE_COLORS = {
            Color.BLACK,        // 黑色
            Color.WHITE,        // 白色
            Color.YELLOW,       // 黄色
            Color.GREEN,        // 绿色
            Color.CYAN,         // 青色
            Color.RED,          // 红色
            Color.parseColor("#FF9800"),  // 橙色
            Color.TRANSPARENT,  // 无描边
    };

    // 描边颜色名称
    public static final String[] STROKE_COLOR_NAMES = {
            "黑边", "白边", "黄边", "绿边", "青边", "红边", "橙边", "无边"
    };

    public static int getSubtitleTextAutoSize(Activity activity) {
        double screenSqrt = ScreenUtils.getSqrt(activity);
        int subtitleTextSize = 20;
        if (screenSqrt > 7.0 && screenSqrt <= 13.0) {
            subtitleTextSize = 24;
        } else if (screenSqrt > 13.0 && screenSqrt <= 50.0) {
            subtitleTextSize = 36;
        } else if (screenSqrt > 50.0) {
            subtitleTextSize = 46;
        }
        return subtitleTextSize;
    }

    public static int getTextSize(Activity activity) {
        int autoSize = getSubtitleTextAutoSize(activity);
        int subtitleConfigSize = Hawk.get(HawkConfig.SUBTITLE_TEXT_SIZE, autoSize);
        return subtitleConfigSize;
    }

    public static void setTextSize(int size) {
        Hawk.put(HawkConfig.SUBTITLE_TEXT_SIZE, size);
    }

    public static int getTimeDelay() {
        int subtitleConfigTimeDelay = Hawk.get(HawkConfig.SUBTITLE_TIME_DELAY, 0);
        return subtitleConfigTimeDelay;
    }

    public static void setTimeDelay(int delay) {
        Hawk.put(HawkConfig.SUBTITLE_TIME_DELAY, delay);
    }

    // 获取字体颜色
    public static int getTextColor() {
        return Hawk.get(HawkConfig.SUBTITLE_TEXT_COLOR, Color.WHITE);
    }

    // 设置字体颜色
    public static void setTextColor(int color) {
        Hawk.put(HawkConfig.SUBTITLE_TEXT_COLOR, color);
    }

    // 获取字体颜色索引
    public static int getTextColorIndex() {
        int currentColor = getTextColor();
        for (int i = 0; i < TEXT_COLORS.length; i++) {
            if (TEXT_COLORS[i] == currentColor) {
                return i;
            }
        }
        return 0;
    }

    // 获取描边颜色
    public static int getStrokeColor() {
        return Hawk.get(HawkConfig.SUBTITLE_STROKE_COLOR, Color.BLACK);
    }

    // 设置描边颜色
    public static void setStrokeColor(int color) {
        Hawk.put(HawkConfig.SUBTITLE_STROKE_COLOR, color);
    }

    // 获取描边颜色索引
    public static int getStrokeColorIndex() {
        int currentColor = getStrokeColor();
        for (int i = 0; i < STROKE_COLORS.length; i++) {
            if (STROKE_COLORS[i] == currentColor) {
                return i;
            }
        }
        return 0;
    }

    // 获取字体粗细
    public static int getTextBold() {
        return Hawk.get(HawkConfig.SUBTITLE_TEXT_BOLD, Typeface.NORMAL);
    }

    // 设置字体粗细
    public static void setTextBold(int bold) {
        Hawk.put(HawkConfig.SUBTITLE_TEXT_BOLD, bold);
    }

    // 获取字体粗细索引
    public static int getTextBoldIndex() {
        int bold = getTextBold();
        switch (bold) {
            case Typeface.NORMAL:
                return 0;
            case Typeface.BOLD:
                return 1;
            default:
                return 0;
        }
    }

    // 应用字幕样式
    public static void applyStyle(SimpleSubtitleView mSubtitleView) {
        int textColor = getTextColor();
        int strokeColor = getStrokeColor();
        int textBold = getTextBold();

        mSubtitleView.setTextColor(textColor);
        // 使用 SimpleSubtitleView 的描边功能
        mSubtitleView.setBackGroundTextColor(strokeColor);
        // 清除阴影
        mSubtitleView.setShadowLayer(0, 0, 0, 0);

        // 设置系统默认字体
        switch (textBold) {
            case Typeface.BOLD:
                mSubtitleView.setTypeface(Typeface.DEFAULT_BOLD);
                break;
            default:
                mSubtitleView.setTypeface(Typeface.DEFAULT);
                break;
        }

        // 背景框功能已移除，使用描边代替
    }

    // 重置所有设置
    public static void resetSettings(Activity activity) {
        Hawk.put(HawkConfig.SUBTITLE_TEXT_SIZE, getSubtitleTextAutoSize(activity));
        Hawk.put(HawkConfig.SUBTITLE_TEXT_COLOR, Color.WHITE);
        Hawk.put(HawkConfig.SUBTITLE_STROKE_COLOR, Color.BLACK);
        Hawk.put(HawkConfig.SUBTITLE_TEXT_BOLD, Typeface.NORMAL);
        Hawk.put(HawkConfig.SUBTITLE_TIME_DELAY, 0);
    }

    // 兼容旧方法
    public static void upTextStyle(SimpleSubtitleView mSubtitleView) {
        applyStyle(mSubtitleView);
    }

    public static void upTextStyle(SimpleSubtitleView mSubtitleView, int style) {
        applyStyle(mSubtitleView);
    }

    public static void applyStyle(SimpleSubtitleView mSubtitleView, int styleIndex) {
        applyStyle(mSubtitleView);
    }

    // 获取样式预览文本
    public static String getStylePreviewText() {
        int textColorIndex = getTextColorIndex();
        int strokeColorIndex = getStrokeColorIndex();
        return TEXT_COLOR_NAMES[textColorIndex] + STROKE_COLOR_NAMES[strokeColorIndex];
    }

    // 以下方法为兼容旧代码
    public static int getTextStyle() {
        return 0;
    }

    public static void setTextStyle(int style) {
        // 不再使用
    }

    public static int getTextStyleSize() {
        return 1;
    }

    public static String getStyleName(int index) {
        return getStylePreviewText();
    }
}
