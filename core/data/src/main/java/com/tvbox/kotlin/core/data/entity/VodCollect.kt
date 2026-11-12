package com.tvbox.kotlin.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * 影片收藏表，记录站点源与影片 ID 的收藏关系。
 */
@Entity(tableName = "vodCollect")
data class VodCollect(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,
    @ColumnInfo(name = "vodId")
    var vodId: String? = null,
    @ColumnInfo(name = "updateTime")
    var updateTime: Long = 0L,
    @ColumnInfo(name = "sourceKey")
    var sourceKey: String? = null,
    @ColumnInfo(name = "name")
    var name: String? = null,
    @ColumnInfo(name = "pic")
    var pic: String? = null,
    /** 产生该收藏的订阅地址（API_URL），用于按当前订阅过滤收藏；旧数据为 null 时兼容显示 */
    @ColumnInfo(name = "apiUrl")
    var apiUrl: String? = null
) : Serializable
