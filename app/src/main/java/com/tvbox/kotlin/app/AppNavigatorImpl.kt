package com.tvbox.kotlin.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.tvbox.kotlin.core.ui.AppNavigator
import com.tvbox.kotlin.feature.collect.ui.CollectActivity
import com.tvbox.kotlin.feature.detail.ui.DetailActivity
import com.tvbox.kotlin.feature.drive.ui.DriveActivity
import com.tvbox.kotlin.feature.history.ui.HistoryActivity
import com.tvbox.kotlin.feature.home.ui.HomeActivity
import com.tvbox.kotlin.feature.live.ui.LivePlayActivity
import com.tvbox.kotlin.feature.play.ui.PlayActivity
import com.tvbox.kotlin.feature.push.ui.PushActivity
import com.tvbox.kotlin.feature.search.ui.FastSearchActivity
import com.tvbox.kotlin.feature.search.ui.SearchActivity
import com.tvbox.kotlin.feature.setting.ui.SettingActivity
import com.tvbox.kotlin.feature.splash.ui.SplashActivity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 导航实现，由 :app 模块提供，聚合并启动各 feature Activity。
 * feature 模块仅依赖 core:ui 的 AppNavigator 接口，不感知具体目标类。
 */
@Singleton
class AppNavigatorImpl @Inject constructor() : AppNavigator {

    /**
     * 统一启动 Activity 并禁用系统默认窗口过渡动画。
     * TV 盒子渲染性能弱，系统默认滑动/缩放过渡会掉帧且显得"跳转有延迟动画"，
     * 重载场景直接禁用过渡（0,0），使界面切换即时无拖尾。
     */
    private fun Context.startActivityNoAnim(intent: Intent) {
        startActivity(intent)
        if (this is Activity) {
            overridePendingTransition(0, 0)
        }
    }

    override fun goHome(context: Context, extras: Intent?) {
        val intent = Intent(context, HomeActivity::class.java)
        extras?.let { intent.putExtras(it) }
        // Home 为 singleTask：NEW_TASK + CLEAR_TOP 时后台已有实例直接复用（onNewIntent 回到原界面），
        // 不重建、不丢数据；首次启动创建新 Task。原 CLEAR_TASK 会强制重建 Home，导致点击图标回到应用时重载
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivityNoAnim(intent)
    }

    override fun goSplash(context: Context, useCache: Boolean) {
        val intent = Intent(context, SplashActivity::class.java)
        if (useCache) intent.putExtra("useCache", true)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivityNoAnim(intent)
    }

    override fun goDetail(context: Context, vodId: String?, sourceKey: String?, extras: Intent?) {
        val intent = Intent(context, DetailActivity::class.java)
        vodId?.let { intent.putExtra("vodId", it) }
        sourceKey?.let { intent.putExtra("sourceKey", it) }
        extras?.let { intent.putExtras(it) }
        context.startActivityNoAnim(intent)
    }

    override fun goPlay(context: Context, url: String, title: String?, position: Long, extras: Intent?) {
        val intent = Intent(context, PlayActivity::class.java)
        intent.putExtra("url", url)
        title?.let { intent.putExtra("title", it) }
        intent.putExtra("position", position)
        extras?.let { intent.putExtras(it) }
        context.startActivityNoAnim(intent)
    }

    override fun goSearch(context: Context, word: String?) {
        val intent = Intent(context, SearchActivity::class.java)
        word?.let { intent.putExtra("word", it) }
        context.startActivityNoAnim(intent)
    }

    override fun goFastSearch(context: Context, word: String?) {
        val intent = Intent(context, FastSearchActivity::class.java)
        word?.let { intent.putExtra("word", it) }
        context.startActivityNoAnim(intent)
    }

    override fun goLive(context: Context) {
        context.startActivityNoAnim(Intent(context, LivePlayActivity::class.java))
    }

    override fun goSetting(context: Context) {
        context.startActivityNoAnim(Intent(context, SettingActivity::class.java))
    }

    override fun goCollect(context: Context) {
        context.startActivityNoAnim(Intent(context, CollectActivity::class.java))
    }

    override fun goHistory(context: Context) {
        context.startActivityNoAnim(Intent(context, HistoryActivity::class.java))
    }

    override fun goDrive(context: Context) {
        context.startActivityNoAnim(Intent(context, DriveActivity::class.java))
    }

    override fun goPush(context: Context) {
        context.startActivityNoAnim(Intent(context, PushActivity::class.java))
    }
}
