package com.tvbox.kotlin.core.data.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

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
