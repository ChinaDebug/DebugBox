package com.tvbox.kotlin.feature.setting.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.TypedEvent
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.FileUtils
import com.tvbox.kotlin.core.common.util.WallpaperManager
import com.tvbox.kotlin.core.network.OkHttpHelper
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.core.ui.dialog.LocalFilePickerDialog
import com.tvbox.kotlin.feature.setting.ui.SettingViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 壁纸设置对话框。
 * 更换壁纸：下载站点配置的 wallpaper 到 filesDir/wp；本地文件：自定义 SAF 文件管理器
 * 选择本地图片作壁纸（按图片扩展名过滤）；壁纸虚化开关；重置壁纸。
 *
 * [host] 为宿主 Activity（显式传入而非从 Dialog context 强转，
 * Dialog.getContext() 可能返回 ContextThemeWrapper 包装导致 as? 转换失败、
 * 壁纸应用逻辑静默跳过，表现为"提示更换成功但设置页看不到效果"）。
 */
class WallpaperDialog(
    private val host: com.tvbox.kotlin.core.ui.BaseBindingActivity<*>?,
    context: Context,
    private val apiConfig: ApiConfig,
    private val okHttpHelper: OkHttpHelper
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val appPreferences = AppPreferences.getShared(context)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(uiR.layout.dialog_wallpaper)
        setCanceledOnTouchOutside(true)
        val tvWallBlur = findViewById<TextView>(uiR.id.tvWallBlur)
        val llWallChange = findViewById<View>(uiR.id.llWallChange)
        val llWallBlur = findViewById<View>(uiR.id.llWallBlur)
        val llWallLocal = findViewById<View>(uiR.id.llWallLocal)
        val llWallRecovery = findViewById<View>(uiR.id.llWallRecovery)

        fun refreshBlur() {
            tvWallBlur.text = if (appPreferences.getBoolean(AppConstants.WALLPAPER_BLUR, true)) {
                context.getString(uiR.string.state_on)
            } else {
                context.getString(uiR.string.state_off)
            }
        }
        refreshBlur()

        llWallChange.setOnClickListener {
            val url = apiConfig.wallpaper
            if (url.isBlank()) {
                Toast.makeText(context, context.getString(uiR.string.toast_wallpaper_not_configured), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(context, context.getString(uiR.string.toast_wallpaper_changing), Toast.LENGTH_SHORT).show()
            scope.launch {
                val target = File(context.filesDir, "wp")
                // 下载放 IO 线程，避免主线程同步阻塞
                val ok = withContext(Dispatchers.IO) {
                    try {
                        okHttpHelper.download(url, target)
                        true
                    } catch (t: Throwable) {
                        false
                    }
                }
                if (ok && target.exists()) {
                    WallpaperManager.getInstance().invalidate()
                    EventHub.post(TypedEvent(SettingViewModel.EVENT_WALLPAPER_CHANGED))
                    // 异步加载模糊壁纸应用到窗口背景，避免更换瞬间闪一下原图
                    host?.changeWallpaper(true)
                    Toast.makeText(context, context.getString(uiR.string.toast_wallpaper_ok), Toast.LENGTH_SHORT).show()
                    // 关闭弹窗让设置页背景立即可见
                    dismiss()
                } else {
                    Toast.makeText(context, context.getString(uiR.string.toast_wallpaper_fail), Toast.LENGTH_SHORT).show()
                }
            }
        }
        llWallBlur.setOnClickListener {
            scope.launch {
                appPreferences.put(AppConstants.WALLPAPER_BLUR,
                    !appPreferences.getBoolean(AppConstants.WALLPAPER_BLUR, true))
            }
            WallpaperManager.getInstance().invalidate()
            EventHub.post(TypedEvent(SettingViewModel.EVENT_WALLPAPER_CHANGED))
            // 虚化开关切换后立即重新加载壁纸
            host?.changeWallpaper(true)
            refreshBlur()
        }
        // 本地文件：自定义 SAF 文件管理器选择本地图片（过滤图片格式），选中后复制为壁纸并应用
        llWallLocal.setOnClickListener {
            val dialog = LocalFilePickerDialog(context)
            dialog.mode = LocalFilePickerDialog.PickMode.FILE
            dialog.startPath = "/storage/emulated/0/Download"
            dialog.extensions = listOf("jpg", "jpeg", "png", "webp", "gif")
            dialog.onPathSelectedListener = object : LocalFilePickerDialog.OnPathSelectedListener {
                override fun onPathSelected(path: String) {
                    applyLocalWallpaper(File(path))
                }
            }
            dialog.show()
        }
        // 重置壁纸：删除本地壁纸文件并恢复默认背景
        llWallRecovery.setOnClickListener {
            val wp = File(context.filesDir, "wp")
            if (wp.exists()) wp.delete()
            WallpaperManager.getInstance().invalidate()
            EventHub.post(TypedEvent(SettingViewModel.EVENT_WALLPAPER_CHANGED))
            host?.changeWallpaper(true)
        }
    }

    /** 将本地图片复制为壁纸文件并立即应用 */
    private fun applyLocalWallpaper(file: File) {
        scope.launch {
            val target = File(context.filesDir, "wp")
            val ok = withContext(Dispatchers.IO) {
                try {
                    FileUtils.copyFile(file, target)
                    true
                } catch (t: Throwable) {
                    false
                }
            }
            if (ok && target.exists()) {
                WallpaperManager.getInstance().invalidate()
                EventHub.post(TypedEvent(SettingViewModel.EVENT_WALLPAPER_CHANGED))
                host?.changeWallpaper(true)
                Toast.makeText(context, context.getString(uiR.string.toast_wallpaper_ok), Toast.LENGTH_SHORT).show()
                dismiss()
            } else {
                Toast.makeText(context, context.getString(uiR.string.toast_wallpaper_fail), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
