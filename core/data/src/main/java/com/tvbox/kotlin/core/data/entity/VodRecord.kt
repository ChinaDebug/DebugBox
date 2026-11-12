package com.tvbox.kotlin.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * 影片观看记录表，以 JSON 形式持久化 VodInfo 详情。
 */
@Entity(tableName = "vodRecord")
data class VodRecord(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,
    @ColumnInfo(name = "vodId")
    var vodId: String? = null,
    @ColumnInfo(name = "updateTime")
    var updateTime: Long = 0L,
    @ColumnInfo(name = "sourceKey")
    var sourceKey: String? = null,
    @ColumnInfo(name = "dataJson")
    var dataJson: String? = null,
    /** 产生该记录的订阅地址（API_URL），用于按当前订阅过滤历史；旧数据为 null 时兼容显示 */
    @ColumnInfo(name = "apiUrl")
    var apiUrl: String? = null
) : Serializable
