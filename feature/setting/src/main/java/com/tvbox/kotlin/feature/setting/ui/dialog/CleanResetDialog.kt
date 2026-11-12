package com.tvbox.kotlin.feature.setting.ui.dialog

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.view.View
import android.view.Window
import android.widget.Toast
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.event.RefreshEvent
import com.tvbox.kotlin.core.common.util.FileUtils
import com.tvbox.kotlin.core.data.VideoUpdateChecker
import com.tvbox.kotlin.core.data.repo.CacheManager
import com.tvbox.kotlin.core.data.repo.RoomDataManger
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.ui.ImageLoaderFactory
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.core.ui.dialog.ConfirmClearDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 清理-重置对话框。
 * 清理数据：清空爬虫/磁盘/数据库缓存；重置应用：清空全部数据后重启。
 */
class CleanResetDialog(
    context: Context,
    private val apiConfig: ApiConfig,
    private val cacheManager: CacheManager,
    private val videoUpdateChecker: VideoUpdateChecker,
    private val roomDataManger: RoomDataManger
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(uiR.layout.dialog_clean_reset)
        setCanceledOnTouchOutside(true)
        findViewById<View>(uiR.id.btnClean)?.setOnClickListener {
            dismiss()
            ConfirmClearDialog(context).title(context.getString(uiR.string.confirm_clear_all_data), 0xFFB30000.toInt())
                .onConfirm { doClear() }
                .show()
        }
        findViewById<View>(uiR.id.btnReset)?.setOnClickListener {
            dismiss()
            ConfirmClearDialog(context).title(context.getString(uiR.string.confirm_reset_app), 0xFFB30000.toInt())
                .onConfirm { doReset() }
                .show()
        }
    }

    /** 清理全部缓存与记录 */
    private fun doClear() {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { apiConfig.clearAllCache() }
                runCatching { FileUtils.recursiveDelete(context.cacheDir) }
                runCatching { FileUtils.recursiveDelete(context.externalCacheDir) }
                runCatching { ImageLoaderFactory.clearMemoryCache() }
                runCatching { cacheManager.deleteAll() }
                runCatching { videoUpdateChecker.clearCache() }
                runCatching { roomDataManger.deleteVodRecordAll() }
                runCatching { roomDataManger.deleteVodCollectAll() }
                runCatching { roomDataManger.deleteSearchHistoryAll() }
                runCatching { roomDataManger.deleteStorageDriveAll() }
            }
            EventHub.post(RefreshEvent(RefreshEvent.TYPE_APP_REFRESH))
            Toast.makeText(context, context.getString(uiR.string.toast_all_cleared), Toast.LENGTH_SHORT).show()
        }
    }

    /** 重置应用：清空外部与私有数据（保留 lib）后重启 */
    private fun doReset() {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { clearPublic() }
                runCatching { clearPrivate() }
            }
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent != null) context.startActivity(intent)
            Process.killProcess(Process.myPid())
        }
    }

    private fun clearPublic() {
        val appContext = context.applicationContext
        appContext.getExternalFilesDir(null)?.parentFile?.let { parent ->
            parent.listFiles()?.forEach { FileUtils.recursiveDelete(it) }
        }
        File("/sdcard/${appContext.packageName}/").let { dir ->
            if (dir.exists()) dir.listFiles()?.forEach { FileUtils.recursiveDelete(it) }
        }
    }

    private fun clearPrivate() {
        val appContext = context.applicationContext
        File(appContext.filesDir.parentFile?.absolutePath ?: "").let { root ->
            if (!root.exists()) return
            root.listFiles()?.forEach { file ->
                if (file.name != "lib") FileUtils.recursiveDelete(file)
            }
        }
    }
}
