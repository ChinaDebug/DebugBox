package com.tvbox.kotlin.feature.drive.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.bean.DriveFolderFile
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.StorageDriveType
import com.tvbox.kotlin.core.ui.AppNavigator
import com.tvbox.kotlin.core.ui.BaseBindingActivity
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.LocalFilePickerDialog
import com.tvbox.kotlin.core.ui.dialog.SelectDialog
import com.tvbox.kotlin.feature.drive.databinding.ActivityDriveBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网盘页：管理 WebDAV/Alist/本地 网盘配置并浏览文件树。
 *
 * - 顶栏：返回首页 / 排序 / 添加网盘 / 删除网盘；
 * - 纵向列表：展示已配置网盘或当前文件夹下的文件（行式布局）；
 * - 文件夹点击进入下级，视频文件点击跳转播放页；
 * - 排序支持按名字/修改时间升降序并持久化；
 * - 返回键：优先返回上级目录，根级则退出。
 */
@AndroidEntryPoint
class DriveActivity : BaseBindingActivity<ActivityDriveBinding>() {

    override val layoutId = com.tvbox.kotlin.feature.drive.R.layout.activity_drive

    private val viewModel: DriveViewModel by viewModels()
    private lateinit var adapter: DriveAdapter

    @Inject
    lateinit var appNavigator: AppNavigator

    @Inject
    lateinit var appPreferences: AppPreferences

    override fun initView(savedInstanceState: Bundle?) {
        adapter = DriveAdapter()

        with(binding.mGridView) {
            setHasFixedSize(true)
            layoutManager = V7LinearLayoutManager(this@DriveActivity, V7LinearLayoutManager.VERTICAL, false)
            setAdapter(this@DriveActivity.adapter)
            setOnItemListener(object : TvRecyclerView.OnItemListener {
                override fun onItemPreSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {}
                override fun onItemSelected(parent: TvRecyclerView?, itemView: View?, position: Int) {}
                override fun onItemClick(parent: TvRecyclerView?, itemView: View?, position: Int) {}
            })
        }

        adapter.setOnItemClickListener { item, position -> onItemClick(item, position) }
        adapter.setOnItemLongClickListener { _, _ -> viewModel.toggleDeleteMode() }

        binding.btnAddServer.setOnClickListener { showAddDialog() }
        binding.btnRemoveServer.setOnClickListener {
            if (adapter.getData().isEmpty()) {
                Toast.makeText(this, getString(uiR.string.toast_no_drive), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.toggleDeleteMode()
        }
        // 仅当处于文件浏览态（不在驱动器根列表）时显示排序按钮
        binding.btnSort.setOnClickListener { showSortDialog() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.deleteMode.value) {
                    viewModel.toggleDeleteMode()
                } else if (viewModel.canGoBack()) {
                    viewModel.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun initData() {
        collectStarted(viewModel.items) { list -> adapter.setNewData(list) }
        collectStarted(viewModel.deleteMode) { enabled ->
            adapter.setDeleteMode(enabled)
            binding.btnRemoveServer.alpha = if (enabled) 0.6f else 1.0f
        }
        collectStarted(viewModel.currentFolder) { folder ->
            binding.textView.text = folder?.name ?: getString(
                com.tvbox.kotlin.core.ui.R.string.act_drive
            )
            // 仅在文件浏览态显示排序按钮
            binding.btnSort.visibility = if (folder == null) View.GONE else View.VISIBLE
        }
        collectStarted(viewModel.toast) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onItemClick(item: DriveFolderFile, position: Int) {
        if (viewModel.deleteMode.value) {
            if (item.isDrive()) {
                val driveData = item.getDriveData() ?: return
                adapter.remove(position)
                viewModel.removeDrive(driveData.id)
                Toast.makeText(this, getString(uiR.string.toast_drive_deleted, item.name), Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (item.isDrive()) {
            viewModel.browseFolder(item)
        } else if (!item.isFile) {
            viewModel.browseFolder(item)
        } else {
            if (StorageDriveType.isVideoType(item.fileType)) {
                playVideoFile(item)
            } else {
                Toast.makeText(this, getString(uiR.string.toast_preview_unsupported), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 播放网盘视频文件，WebDAV 网盘携带 Basic 认证头 */
    private fun playVideoFile(file: DriveFolderFile) {
        val url = file.fileUrl
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, getString(uiR.string.toast_file_addr_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val extras = buildPlayExtras(file)
        appNavigator.goPlay(this, url, file.name, extras = extras)
    }

    /** 向上追溯文件所属的网盘根节点 */
    private fun resolveDrive(file: DriveFolderFile): DriveFolderFile? {
        var node: DriveFolderFile? = file
        while (node != null && !node.isDrive()) {
            node = node.parentFolder
        }
        return node
    }

    /** 按网盘类型构造播放器配置：WebDAV 附带 Basic 认证请求头 */
    private fun buildPlayExtras(file: DriveFolderFile): Intent? {
        val drive = resolveDrive(file) ?: return null
        if (drive.getDriveType() != StorageDriveType.TYPE.WEBDAV) return null
        val credential = drive.getWebDAVBase64Credential() ?: return null
        return try {
            val headers = JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "authorization")
                    put("value", "Basic $credential")
                })
            }
            val cfg = JSONObject().apply { put("headers", headers) }
            Intent().putExtra("playerCfg", cfg.toString())
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 添加网盘：先弹出存盘类型选择（SelectDialog"请选择存盘类型"），
     * 再按类型打开对应配置弹窗：
     * - 本地目录 → 本地文件夹选择器，选中后直接添加；
     * - WebDAV / Alist 网页 → 对应配置弹窗，确认后添加。
     */
    private fun showAddDialog() {
        val types = StorageDriveType.TYPE.entries
        SelectDialog(
            this,
            getString(uiR.string.drive_select_type),
            types,
            { StorageDriveType.getTypeNames()[it.ordinal] },
            spanCount = 1
        ) { type, _ ->
            when (type) {
                StorageDriveType.TYPE.LOCAL -> {
                    LocalFilePickerDialog(this).apply {
                        onPathSelectedListener = object : LocalFilePickerDialog.OnPathSelectedListener {
                            override fun onPathSelected(path: String) {
                                // 本地目录以路径作为网盘名称
                                viewModel.addDrive(path, StorageDriveType.TYPE.LOCAL, path)
                                Toast.makeText(this@DriveActivity, getString(uiR.string.toast_added_local_dir), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.show()
                }
                StorageDriveType.TYPE.WEBDAV -> WebdavDialog(this)
                    .onConfirm { name, type, configJson ->
                        viewModel.addDrive(name, type, configJson)
                        Toast.makeText(this, getString(uiR.string.toast_added_drive, name), Toast.LENGTH_SHORT).show()
                    }
                    .show()
                StorageDriveType.TYPE.ALISTWEB -> AlistDriveDialog(this)
                    .onConfirm { name, type, configJson ->
                        viewModel.addDrive(name, type, configJson)
                        Toast.makeText(this, getString(uiR.string.toast_added_drive, name), Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }.show()
    }

    /** 显示排序方式选择对话框（按名字/修改时间升降序，持久化） */
    private fun showSortDialog() {
        val options = listOf(
            getString(uiR.string.drive_sort_name_asc),
            getString(uiR.string.drive_sort_name_desc),
            getString(uiR.string.drive_sort_time_asc),
            getString(uiR.string.drive_sort_time_desc)
        )
        val current = appPreferences.getInt(AppConstants.STORAGE_DRIVE_SORT, 0)
        SelectDialog(
            this,
            getString(uiR.string.drive_select_sort),
            options,
            { it },
            select = current
        ) { _, pos ->
            appPreferences.putSync(AppConstants.STORAGE_DRIVE_SORT, pos)
            viewModel.setSortType(pos)
        }
    }
}
