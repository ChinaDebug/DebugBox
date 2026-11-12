package com.tvbox.kotlin.feature.setting.ui.dialog

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.FileUtils
import com.tvbox.kotlin.core.data.db.AppDataManager
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据备份还原对话框。
 * 备份目录 /sdcard/tvbox_backup/，保留最近 10 个备份；恢复后自动重启。
 */
class BackupDialog(
    context: Context,
    private val appDataManager: AppDataManager,
    private val appPreferences: AppPreferences
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val backupDir = File(
        Environment.getExternalStorageDirectory().absolutePath + "/tvbox_backup"
    )
    private val backupList = mutableListOf<File>()
    private lateinit var adapter: BackupAdapter

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(uiR.layout.dialog_backup)
        setCanceledOnTouchOutside(true)
        val list = findViewById<TvRecyclerView>(uiR.id.list)
        list.layoutManager = V7LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        adapter = BackupAdapter(backupList,
            onRestore = { restore(it) },
            onDelete = { delete(it) }
        )
        list.adapter = adapter
        findViewById<View>(uiR.id.backupNow)?.setOnClickListener { backup() }
        allBackup()
    }

    /** 扫描备份目录，名称倒序，仅保留最近 10 个 */
    private fun allBackup() {
        backupList.clear()
        if (backupDir.exists()) {
            backupDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
                ?.forEach { backupList.add(it) }
            while (backupList.size > 10) {
                val extra = backupList.removeAt(backupList.lastIndex)
                extra.deleteRecursively()
            }
        }
        adapter.notifyDataSetChanged()
    }

    /** 立即备份：数据库 + 偏好数据 */
    private fun backup() {
        scope.launch {
            val time = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.getDefault()).format(Date())
            val dir = File(backupDir, time)
            val ok = withContext(Dispatchers.IO) {
                try {
                    // 先等待 DataStore 挂起写入落盘，确保偏好备份到最新数据
                    appPreferences.flush()
                    dir.mkdirs()
                    val dbOk = appDataManager.backup(File(dir, "sqlite"))
                    val hawkOk = backupPrefs(File(dir, "hawk"))
                    if (!dbOk || !hawkOk) {
                        dir.deleteRecursively()
                    }
                    dbOk && hawkOk
                } catch (t: Throwable) {
                    dir.deleteRecursively()
                    false
                }
            }
            if (ok) {
                Toast.makeText(context, context.getString(uiR.string.toast_backup_ok), Toast.LENGTH_SHORT).show()
                allBackup()
                restartApp()
            } else {
                Toast.makeText(context, context.getString(uiR.string.toast_backup_fail), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 复制 DataStore 偏好文件到备份目录（保留原文件名，支持多个 DataStore 文件共存） */
    private fun backupPrefs(targetDir: File): Boolean {
        val prefsDir = File(context.filesDir, "datastore")
        if (!prefsDir.exists()) return true
        var copied = false
        prefsDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".preferences_pb")) {
                targetDir.mkdirs()
                FileUtils.copyFile(file, File(targetDir, file.name))
                copied = true
            }
        }
        return copied
    }

    /** 恢复备份并重启 */
    private fun restore(dir: File) {
        scope.launch {
            val dbFile = File(dir, "sqlite")
            val result = withContext(Dispatchers.IO) {
                // 校验备份库可打开且版本不高于当前（旧版本自动迁移，损坏/更高版本拒绝，避免崩溃）
                if (!appDataManager.isBackupCompatible(dbFile)) {
                    return@withContext RESULT_VERSION_MISMATCH
                }
                try {
                    val hawkDir = File(dir, "hawk")
                    var ok = dbFile.exists() && appDataManager.restore(dbFile)
                    if (hawkDir.exists()) {
                        ok = ok && restorePrefs(hawkDir)
                    }
                    if (ok) RESULT_OK else RESULT_FAIL
                } catch (t: Throwable) {
                    RESULT_FAIL
                }
            }
            when (result) {
                RESULT_OK -> {
                    Toast.makeText(context, context.getString(uiR.string.toast_restore_ok), Toast.LENGTH_SHORT).show()
                    restartApp()
                }
                RESULT_VERSION_MISMATCH -> {
                    Toast.makeText(
                        context,
                        context.getString(uiR.string.toast_restore_version_mismatch),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                else -> {
                    Toast.makeText(context, context.getString(uiR.string.toast_restore_fail), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 将备份目录下的偏好文件按原文件名写回 DataStore 目录（先写临时文件再原子替换，避免中途失败丢数据） */
    private fun restorePrefs(sourceDir: File): Boolean {
        val prefsDir = File(context.filesDir, "datastore")
        prefsDir.mkdirs()
        val files = sourceDir.listFiles()?.filter { it.name.endsWith(".preferences_pb") } ?: return true
        var restored = false
        files.forEach { src ->
            val target = File(prefsDir, src.name)
            val tmp = File(prefsDir, "${src.name}.tmp")
            FileUtils.copyFile(src, tmp)
            if (target.exists()) {
                target.delete()
            }
            tmp.renameTo(target)
            restored = true
        }
        return restored
    }

    /** 删除备份目录 */
    private fun delete(dir: File) {
        dir.deleteRecursively()
        allBackup()
    }

    private fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent != null) context.startActivity(intent)
        Process.killProcess(Process.myPid())
    }

    companion object {
        private const val RESULT_OK = 1
        private const val RESULT_FAIL = 0
        private const val RESULT_VERSION_MISMATCH = -1
    }

    private class BackupAdapter(
        private val data: MutableList<File>,
        private val onRestore: (File) -> Unit,
        private val onDelete: (File) -> Unit
    ) : RecyclerView.Adapter<BackupAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(uiR.layout.item_dialog_backup, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val file = data[position]
            holder.tvName.text = file.name
            holder.tvName.setOnClickListener { onRestore(file) }
            holder.tvDel.setOnClickListener { onDelete(file) }
        }

        override fun getItemCount(): Int = data.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: TextView = itemView.findViewById(uiR.id.tvName)
            val tvDel: ImageView = itemView.findViewById(uiR.id.tvDel)
        }
    }
}
