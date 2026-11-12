package com.tvbox.kotlin.core.player.thirdparty

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.tvbox.kotlin.core.common.LOG
import java.net.URLEncoder

/**
 * Kodi 第三方播放器：安装检测与调起。
 * headers 按 Kodi 协议拼接到 URL（url|k=v&...），多个入口 Activity 依次尝试。
 */
object Kodi {
    private const val TAG = "ThirdParty.Kodi"
    private const val PACKAGE_NAME = "org.xbmc.kodi"

    /** 已知的 Kodi 入口 Activity：优先 Kodi 20+ 新入口，失败回退旧入口 */
    private val KODI_ACTIVITIES = arrayOf(
        "org.xbmc.kodi.Main",     // Kodi 20 及以后的入口
        "org.xbmc.kodi.Splash"    // 旧版入口
    )

    /** @return 已安装且启用的 Kodi 包信息，未安装返回 null */
    fun isInstalled(context: Context): Boolean = try {
        val info = context.packageManager.getApplicationInfo(PACKAGE_NAME, 0)
        info.enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** 调起 Kodi 播放指定地址，逐个尝试入口 Activity */
    fun run(
        activity: Activity,
        url: String,
        title: String,
        subtitle: String,
        headers: Map<String, String>
    ): Boolean {
        if (!isInstalled(activity)) return false
        var finalUrl = url
        if (headers.isNotEmpty()) {
            finalUrl = url + "|" + headers.entries.joinToString("&") { (k, v) ->
                k + "=" + URLEncoder.encode(v, "UTF-8")
            }
        }
        var lastError: Exception? = null
        for (activityName in KODI_ACTIVITIES) {
            try {
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setPackage(PACKAGE_NAME)
                intent.setClassName(PACKAGE_NAME, activityName)
                intent.setData(Uri.parse(finalUrl))
                intent.putExtra("title", title)
                intent.putExtra("name", title)
                if (subtitle.isNotEmpty()) {
                    intent.putExtra("subs", subtitle)
                }
                activity.startActivity(intent)
                return true
            } catch (e: Exception) {
                lastError = e
                // 继续尝试下一个入口
            }
        }
        // 全部入口失败才汇总一条，避免逐个入口各打一条刷屏
        if (lastError != null) LOG.e(TAG, "无法调起 Kodi 播放: ${lastError.message}", lastError)
        return false
    }
}
