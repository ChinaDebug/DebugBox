package com.tvbox.kotlin.core.ui

import android.content.Context
import android.content.Intent

/**
 * 全局导航契约接口。
 * feature 模块间禁止直接依赖，统一通过该接口发起页面跳转，
 * 由 :app 模块提供实现并通过 Hilt 注入，确保单向依赖。
 */
interface AppNavigator {

    fun goHome(context: Context, extras: Intent? = null)

    /** 重新进入启动页，useCache=true 表示保留缓存直接回首页 */
    fun goSplash(context: Context, useCache: Boolean = false)

    fun goDetail(context: Context, vodId: String?, sourceKey: String?, extras: Intent? = null)

    fun goPlay(context: Context, url: String, title: String?, position: Long = 0, extras: Intent? = null)

    fun goSearch(context: Context, word: String? = null)

    fun goFastSearch(context: Context, word: String? = null)

    fun goLive(context: Context)

    fun goSetting(context: Context)

    fun goCollect(context: Context)

    fun goHistory(context: Context)

    fun goDrive(context: Context)

    /** 跳转推屏页（仅展示二维码与地址，推送 URL 由首页全局处理） */
    fun goPush(context: Context)
}
