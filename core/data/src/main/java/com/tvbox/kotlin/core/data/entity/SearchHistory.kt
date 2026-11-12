package com.tvbox.kotlin.core.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 搜索历史表，按关键词建立索引便于查重。
 */
@Entity(tableName = SearchHistory.TABLE_NAME, indices = [Index("searchKeyWords")])
data class SearchHistory(
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,
    @ColumnInfo(name = "searchKeyWords")
    var searchKeyWords: String? = null
) {
    companion object {
        const val TABLE_NAME = "t_search"
    }
}
