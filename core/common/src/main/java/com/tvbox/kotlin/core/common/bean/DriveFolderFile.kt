package com.tvbox.kotlin.core.common.bean

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tvbox.kotlin.core.common.util.StorageDriveType
import java.text.Format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale

/** 网盘文件树节点 */
class DriveFolderFile {

    var parentFolder: DriveFolderFile? = null
    var name: String? = null
    var version: Int = 0
    var isFile: Boolean = false
    var fileType: String? = null

    private var driveData: DriveInfo? = null

    var lastModifiedDate: Long? = null
    var isSelected: Boolean = false
    var isDelMode: Boolean = false
    var fileUrl: String? = null

    private var accessingPath: Array<String> = emptyArray()
    private var children: List<DriveFolderFile>? = null
    private var config: JsonObject? = null

    constructor(driveData: DriveInfo) {
        this.driveData = driveData
        this.name = driveData.name
        if (!driveData.configJson.isNullOrEmpty()) {
            this.config = JsonParser.parseString(driveData.configJson).asJsonObject
        }
    }

    constructor(
        parent: DriveFolderFile?,
        name: String?,
        version: Int,
        isFile: Boolean,
        fileType: String?,
        lastModifiedDate: Long?
    ) {
        if (parent != null) {
            val path = LinkedList<String>()
            var currentParent: DriveFolderFile? = parent
            // 网盘根节点为虚拟目录，name 不对应真实路径，不参与拼接
            while (currentParent != null && !currentParent.isDrive()) {
                path.add(0, currentParent.name ?: "")
                currentParent = currentParent.parentFolder
            }
            accessingPath = path.toTypedArray()
        }
        this.parentFolder = parent
        this.name = name
        this.version = version
        this.isFile = isFile
        if (fileType != null) this.fileType = fileType.uppercase(Locale.ROOT)
        this.lastModifiedDate = lastModifiedDate
    }

    fun getAccessingPath(): Array<String> = accessingPath.copyOf()

    fun setAccessingPath(accessingPath: Array<String>) {
        this.accessingPath = accessingPath
    }

    fun getAccessingPathStr(): String = accessingPath.joinToString("") { "$it/" }

    fun isDrive(): Boolean = driveData != null && !driveData?.name.isNullOrEmpty()

    fun getDriveType(): StorageDriveType.TYPE? {
        val data = driveData ?: return null
        return StorageDriveType.TYPE.entries.getOrNull(data.type)
    }

    fun getDriveData(): DriveInfo? = driveData

    fun setDriveData(driveData: DriveInfo?) {
        this.driveData = driveData
    }

    fun getChildren(): List<DriveFolderFile>? = children

    fun setChildren(children: List<DriveFolderFile>?) {
        this.children = children
    }

    fun getFormattedLastModified(): String {
        val ts = lastModifiedDate ?: return ""
        val date = Date(ts)
        val fmt: Format = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.getDefault())
        return fmt.format(date)
    }

    fun getConfig(): JsonObject? = config

    fun setConfig(config: JsonObject?) {
        this.config = config
    }

    /** WebDAV Basic 凭证 */
    fun getWebDAVBase64Credential(): String? {
        return try {
            val cfg = config ?: return null
            if (cfg.has("username") && cfg.has("password")) {
                val raw = "${cfg.get("username").asString}:${cfg.get("password").asString}"
                Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /** 网盘基础信息（替代原 StorageDrive） */
    class DriveInfo(
        var id: Int = 0,
        var name: String? = null,
        var type: Int = 0,
        var configJson: String? = null
    )
}
