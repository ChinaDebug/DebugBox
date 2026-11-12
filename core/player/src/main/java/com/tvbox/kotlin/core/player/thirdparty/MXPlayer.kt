package com.tvbox.kotlin.core.player.thirdparty

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcelable
import com.tvbox.kotlin.core.common.LOG
import java.net.URLEncoder

/**
 * MX Player 第三方播放器：安装检测与调起。
 */
object MXPlayer {
    private const val TAG = "ThirdParty.MXPlayer"
    private const val PACKAGE_NAME_PRO = "com.mxtech.videoplayer.pro"
    private const val PACKAGE_NAME_AD = "com.mxtech.videoplayer.ad"
    private const val PLAYBACK_ACTIVITY_PRO = "com.mxtech.videoplayer.ActivityScreen"
    private const val PLAYBACK_ACTIVITY_AD = "com.mxtech.videoplayer.ad.ActivityScreen"

    private data class MXPackageInfo(val packageName: String, val activityName: String)

    private val PACKAGES = arrayOf(
        MXPackageInfo(PACKAGE_NAME_PRO, PLAYBACK_ACTIVITY_PRO),
        MXPackageInfo(PACKAGE_NAME_AD, PLAYBACK_ACTIVITY_AD)
    )

    /** @return 已安装且启用的 MX Player 包信息，未安装返回 null */
    private fun getPackageInfo(context: Context): MXPackageInfo? {
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

    /** 调起 MX Player 播放指定地址，headers 按 MX 协议拼接到 URL（url|k=v&...） */
    fun run(
        activity: Activity,
        url: String,
        title: String,
        subtitle: String,
        headers: Map<String, String>
    ): Boolean {
        val packageInfo = getPackageInfo(activity) ?: return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setPackage(packageInfo.packageName)
            intent.setClassName(packageInfo.packageName, packageInfo.activityName)
            var finalUrl = url
            if (headers.isNotEmpty()) {
                finalUrl = url + "|" + headers.entries.joinToString("&") { (k, v) ->
                    k + "=" + URLEncoder.encode(v, "UTF-8")
                }
            }
            intent.setData(Uri.parse(finalUrl))
            intent.putExtra("title", title)
            if (subtitle.isNotEmpty()) {
                val parcels = arrayOf<Parcelable>(Uri.parse(subtitle))
                intent.putExtra("subs", parcels)
                intent.putExtra("subs.enable", parcels)
            }
            activity.startActivity(intent)
            true
        } catch (e: Exception) {
            LOG.e(TAG, "Can't run MX Player", e)
            false
        }
    }
}
