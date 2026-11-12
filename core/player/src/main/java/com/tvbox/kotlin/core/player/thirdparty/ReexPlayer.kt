package com.tvbox.kotlin.core.player.thirdparty

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.tvbox.kotlin.core.common.LOG
import org.json.JSONObject

/**
 * Reex Player 第三方播放器：安装检测与调起。
 * 请求头通过 reex.extra.http_header 以 JSON 传递。
 */
object ReexPlayer {
    private const val TAG = "ThirdParty.Reex"
    private const val PACKAGE_NAME = "xyz.re.player.ex"
    private const val PLAYBACK_ACTIVITY = "xyz.re.player.ex.MainActivity"

    private data class ReexPackageInfo(val packageName: String, val activityName: String)

    private val PACKAGES = arrayOf(ReexPackageInfo(PACKAGE_NAME, PLAYBACK_ACTIVITY))

    /** @return 已安装且启用的 Reex 包信息，未安装返回 null */
    private fun getPackageInfo(context: Context): ReexPackageInfo? {
        for (pkg in PACKAGES) {
            try {
                val info = context.packageManager.getApplicationInfo(pkg.packageName, 0)
                if (info.enabled) return pkg
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return null
    }

    fun isInstalled(context: Context): Boolean = getPackageInfo(context) != null

    /** 调起 Reex Player 播放指定地址 */
    fun run(
        activity: Activity,
        url: String,
        title: String,
        subtitle: String,
        headers: Map<String, String>
    ): Boolean {
        val packageInfo = getPackageInfo(activity) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setPackage(packageInfo.packageName)
        intent.setComponent(ComponentName(packageInfo.packageName, packageInfo.activityName))
        intent.setData(Uri.parse(url))
        intent.putExtra("title", title)
        intent.putExtra("name", title)
        intent.putExtra("reex.extra.title", title)
        if (headers.isNotEmpty()) {
            try {
                val json = JSONObject()
                for ((k, v) in headers) json.put(k, v.trim())
                intent.putExtra("reex.extra.http_header", json.toString())
            } catch (e: Exception) {
                LOG.e(TAG, e)
            }
        }
        if (subtitle.isNotEmpty()) {
            intent.putExtra("reex.extra.subtitle", subtitle)
        }
        return try {
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            LOG.e(TAG, "Can't run Reex Player(Pro)", e)
            false
        }
    }
}
