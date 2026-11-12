package com.tvbox.kotlin.feature.drive.ui

import androidx.lifecycle.SavedStateHandle
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.bean.DriveFolderFile
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.StorageDriveType
import com.tvbox.kotlin.core.data.entity.StorageDrive
import com.tvbox.kotlin.core.data.repo.RoomDataManger
import com.tvbox.kotlin.core.ui.BaseViewModel
import com.tvbox.kotlin.core.ui.DriveBrowser
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

/**
 * 网盘页 ViewModel。
 *
 * 职责：
 * - 加载已配置的网盘列表（[loadDrives]）；
 * - 添加/删除网盘配置（[addDrive] / [removeDrive]）；
 * - 浏览网盘文件树（[browseFolder] / [goBack]）；
 * - 列表排序（[setSortType]，按名字/修改时间升降序）；
 * - 通过 [DriveBrowser] 接口委托实际文件浏览给 :app 实现。
 */
@HiltViewModel
class DriveViewModel @Inject constructor(
    private val roomDataManger: RoomDataManger,
    private val driveBrowser: DriveBrowser,
    private val appPreferences: AppPreferences,
    savedStateHandle: SavedStateHandle
) : BaseViewModel() {

    /** 当前展示的节点列表（驱动器列表或文件列表） */
    private val _items = MutableStateFlow<List<DriveFolderFile>>(emptyList())
    val items: StateFlow<List<DriveFolderFile>> = _items.asStateFlow()

    /** 最近一次加载的原始列表，用于排序/过滤后还原 */
    private val _rawItems = ArrayList<DriveFolderFile>()

    /** 当前排序方式：0=名字升序 1=名字降序 2=修改时间升序 3=修改时间降序 */
    private var sortType = 0

    /** 删除模式 */
    private val _deleteMode = MutableStateFlow(false)
    val deleteMode: StateFlow<Boolean> = _deleteMode.asStateFlow()

    /** 当前所在文件夹（null 表示驱动器根列表） */
    private val _currentFolder = MutableStateFlow<DriveFolderFile?>(null)
    val currentFolder: StateFlow<DriveFolderFile?> = _currentFolder.asStateFlow()

    /** 文件夹导航历史栈，用于返回上级 */
    private val folderStack = ArrayDeque<DriveFolderFile>()

    init {
        sortType = appPreferences.getInt(AppConstants.STORAGE_DRIVE_SORT, 0)
        loadDrives()
    }

    /** 加载已配置的网盘列表 */
    fun loadDrives() {
        launchIO {
            val drives = runCatching { roomDataManger.getAllDrives() }.getOrDefault(emptyList())
            val folderFiles = drives.map { drive ->
                DriveFolderFile(DriveFolderFile.DriveInfo(
                    id = drive.id,
                    name = drive.name,
                    type = drive.type,
                    configJson = drive.configJson
                ))
            }
            _currentFolder.value = null
            folderStack.clear()
            updateItems(folderFiles)
        }
    }

    /** 设置排序方式并重新加载当前列表（0=名字升序 1=名字降序 2=修改时间升序 3=修改时间降序） */
    fun setSortType(type: Int) {
        if (sortType == type) return
        sortType = type
        // 排序即时生效：直接对当前列表重排
        _items.value = sort(_rawItems)
    }

    /** 同步更新原始列表与展示列表（按当前排序方式重排） */
    private fun updateItems(list: List<DriveFolderFile>) {
        _rawItems.clear()
        _rawItems.addAll(list)
        _items.value = sort(list)
    }

    /** 按当前排序方式重排列表 */
    private fun sort(list: List<DriveFolderFile>): List<DriveFolderFile> {
        if (list.isEmpty()) return list
        val comparator = Comparator<DriveFolderFile> { o1, o2 ->
            val n1 = o1.name.orEmpty().uppercase(Locale.CHINESE)
            val n2 = o2.name.orEmpty().uppercase(Locale.CHINESE)
            when (sortType) {
                1 -> Collator.getInstance(Locale.CHINESE).compare(n2, n1)
                2 -> (o1.lastModifiedDate ?: 0L).compareTo(o2.lastModifiedDate ?: 0L)
                3 -> (o2.lastModifiedDate ?: 0L).compareTo(o1.lastModifiedDate ?: 0L)
                else -> Collator.getInstance(Locale.CHINESE).compare(n1, n2)
            }
        }
        return list.sortedWith(comparator)
    }

    /**
     * 添加网盘配置。
     * @param name 网盘名称
     * @param type 网盘类型
     * @param configJson 配置 JSON（URL/用户名/密码等），WebDAV/Alist 为 JSON 字符串，本地为路径
     */
    fun addDrive(name: String, type: StorageDriveType.TYPE, configJson: String?) {
        launchIO {
            val config = when {
                configJson.isNullOrEmpty() -> null
                type == StorageDriveType.TYPE.LOCAL ->
                    // 本地目录：原样保存为 {"url": "<path>"}
                    runCatching { JsonObject().apply { addProperty("url", configJson) } }.getOrNull()
                else ->
                    // WebDAV/Alist：直接解析为 JsonObject
                    runCatching { JsonParser.parseString(configJson).asJsonObject }.getOrNull()
            }
            roomDataManger.insertDriveRecord(name, type, config)
            loadDrives()
        }
    }

    /**
     * 删除指定网盘。
     * @param driveId Room 主键
     */
    fun removeDrive(driveId: Int) {
        launchIO {
            roomDataManger.deleteDrive(driveId)
            if (_currentFolder.value == null) {
                loadDrives()
            }
        }
    }

    /**
     * 浏览指定文件夹。
     * @param folder 目标文件夹节点，null 表示返回驱动器列表
     */
    fun browseFolder(folder: DriveFolderFile?) {
        if (folder == null) {
            loadDrives()
            return
        }
        if (folder.isDrive()) {
            folderStack.clear()
            _currentFolder.value = folder
            loadFiles(folder, null)
        } else {
            // 子目录需以网盘根节点传入（配置来源），当前目录由 path 参数表达
            val root = resolveDrive(folder) ?: return
            val current = _currentFolder.value
            if (current != null) {
                folderStack.addLast(current)
            }
            _currentFolder.value = folder
            loadFiles(root, folder)
        }
    }

    /** 返回上级目录 */
    fun goBack() {
        if (folderStack.isEmpty()) {
            if (_currentFolder.value != null) {
                _currentFolder.value = null
                loadDrives()
            }
            return
        }
        val parent = folderStack.removeLast()
        _currentFolder.value = parent
        if (parent.isDrive()) {
            loadFiles(parent, null)
        } else {
            val root = resolveDrive(parent) ?: return
            loadFiles(root, parent)
        }
    }

    /** 向上追溯文件所属的网盘根节点 */
    private fun resolveDrive(node: DriveFolderFile): DriveFolderFile? {
        var current: DriveFolderFile? = node
        while (current != null && !current.isDrive()) {
            current = current.parentFolder
        }
        return current
    }

    /** 是否可以返回上级 */
    fun canGoBack(): Boolean = _currentFolder.value != null

    /** 切换删除模式 */
    fun toggleDeleteMode() {
        _deleteMode.value = !_deleteMode.value
    }

    /** 通过 DriveBrowser 加载文件列表 */
    private fun loadFiles(drive: DriveFolderFile, path: DriveFolderFile?) {
        launchIO {
            val files = runCatching { driveBrowser.listFiles(drive, path) }.getOrDefault(emptyList())
            updateItems(files)
        }
    }
}
