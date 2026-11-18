package com.tvbox.kotlin.core.data.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

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
