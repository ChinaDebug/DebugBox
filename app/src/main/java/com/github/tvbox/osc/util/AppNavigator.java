package com.github.tvbox.osc.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.Fragment;

import com.github.tvbox.osc.ui.activity.CollectActivity;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.ui.activity.LivePlayActivity;
import com.github.tvbox.osc.ui.activity.PlayActivity;
import com.github.tvbox.osc.ui.activity.PushActivity;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.activity.SplashActivity;
import com.github.tvbox.osc.ui.tv.activity.DriveActivity;
import com.github.tvbox.osc.ui.tv.activity.HistoryActivity;

/**
 * 应用页面跳转统一入口，负责 TV / 手机双端路由分发。
 * 当前手机端复用 TV 页面（项目整体为 TV 优先架构），后续可在 ui.mobile 包中实现手机专用 Activity 后替换。
 */
public class AppNavigator {

    private AppNavigator() {
        // 工具类禁止实例化
    }

    private static void startActivity(Context context, Class<?> clazz) {
        if (context instanceof Activity) {
            ((Activity) context).startActivity(new Intent(context, clazz));
        } else {
            Intent intent = new Intent(context, clazz);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    private static void startActivity(Context context, Class<?> clazz, Bundle bundle) {
        Intent intent = new Intent(context, clazz);
        if (bundle != null) {
            intent.putExtras(bundle);
        }
        if (context instanceof Activity) {
            ((Activity) context).startActivity(intent);
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    private static Activity requireActivity(Fragment fragment) {
        Activity activity = fragment.getActivity();
        if (activity == null) {
            throw new IllegalStateException("Fragment 未关联 Activity");
        }
        return activity;
    }

    /**
     * 启动首页
     */
    public static void startHome(Activity activity) {
        startHome(activity, null);
    }

    public static void startHome(Activity activity, Bundle bundle) {
        Intent intent = new Intent(activity, HomeActivity.class);
        if (bundle != null) {
            intent.putExtras(bundle);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(intent);
    }

    public static void startHome(Fragment fragment) {
        startActivity(requireActivity(fragment), HomeActivity.class);
    }

    /**
     * 启动搜索页
     */
    public static void startSearch(Activity activity) {
        startActivity(activity, SearchActivity.class);
    }

    public static void startSearch(Fragment fragment) {
        startActivity(requireActivity(fragment), SearchActivity.class);
    }

    /**
     * 启动快速搜索页
     */
    public static void startFastSearch(Activity activity) {
        startActivity(activity, FastSearchActivity.class);
    }

    public static void startFastSearch(Fragment fragment) {
        startActivity(requireActivity(fragment), FastSearchActivity.class);
    }

    /**
     * 启动详情页
     */
    public static void startDetail(Activity activity, Bundle bundle) {
        startActivity(activity, DetailActivity.class, bundle);
    }

    public static void startDetail(Fragment fragment, Bundle bundle) {
        startActivity(requireActivity(fragment), DetailActivity.class, bundle);
    }

    /**
     * 启动播放页
     */
    public static void startPlay(Activity activity, Bundle bundle) {
        startActivity(activity, PlayActivity.class, bundle);
    }

    public static void startPlay(Fragment fragment, Bundle bundle) {
        startActivity(requireActivity(fragment), PlayActivity.class, bundle);
    }

    /**
     * 启动直播页
     */
    public static void startLive(Activity activity) {
        startActivity(activity, LivePlayActivity.class);
    }

    public static void startLive(Fragment fragment) {
        startActivity(requireActivity(fragment), LivePlayActivity.class);
    }

    /**
     * 启动历史记录页（TV 端）
     */
    public static void startHistory(Activity activity) {
        startActivity(activity, HistoryActivity.class);
    }

    public static void startHistory(Fragment fragment) {
        startActivity(requireActivity(fragment), HistoryActivity.class);
    }

    /**
     * 启动存储盘页（TV 端）
     */
    public static void startDrive(Activity activity) {
        startActivity(activity, DriveActivity.class);
    }

    public static void startDrive(Fragment fragment) {
        startActivity(requireActivity(fragment), DriveActivity.class);
    }

    /**
     * 启动收藏页
     */
    public static void startCollect(Activity activity) {
        startActivity(activity, CollectActivity.class);
    }

    public static void startCollect(Fragment fragment) {
        startActivity(requireActivity(fragment), CollectActivity.class);
    }

    /**
     * 启动推送页
     */
    public static void startPush(Activity activity) {
        startActivity(activity, PushActivity.class);
    }

    public static void startPush(Fragment fragment) {
        startActivity(requireActivity(fragment), PushActivity.class);
    }

    /**
     * 启动设置页
     */
    public static void startSetting(Activity activity) {
        startActivity(activity, SettingActivity.class);
    }

    public static void startSetting(Fragment fragment) {
        startActivity(requireActivity(fragment), SettingActivity.class);
    }

    /**
     * 启动闪屏页
     */
    public static void startSplash(Context context) {
        Intent intent = new Intent(context, SplashActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
    }
}
