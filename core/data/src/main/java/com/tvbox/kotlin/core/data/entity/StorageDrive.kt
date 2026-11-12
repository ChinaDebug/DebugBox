package com.tvbox.kotlin.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 网盘配置表，存储各网盘名称、类型与配置 JSON。
 */
@Entity(tableName = "storageDrive")
data class StorageDrive(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,
    @ColumnInfo(name = "name")
    var name: String? = null,
    @ColumnInfo(name = "type")
    var type: Int = 0,
    @ColumnInfo(name = "configJson")
    var configJson: String? = null
)
