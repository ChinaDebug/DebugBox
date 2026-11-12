package com.tvbox.kotlin.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.tvbox.kotlin.core.data.entity.SearchHistory

/**
 * 搜索历史表操作。
 */
@Dao
interface SearchDao {

    @Insert
    suspend fun insert(trackData: SearchHistory)

    @Delete
    suspend fun delete(trackData: SearchHistory)

    @Query("DELETE FROM T_SEARCH")
    suspend fun deleteAll()

    @Query("SELECT * FROM T_SEARCH")
    suspend fun getAll(): List<SearchHistory>

    @Query("SELECT * FROM T_SEARCH WHERE searchKeyWords=:keyword")
    suspend fun getByKeywords(keyword: String): List<SearchHistory>
}
