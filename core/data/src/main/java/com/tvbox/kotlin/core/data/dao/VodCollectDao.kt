package com.tvbox.kotlin.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tvbox.kotlin.core.data.entity.VodCollect

/**
 * 影片收藏表操作。
 */
@Dao
interface VodCollectDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: VodCollect): Long

    @Query("select * from vodCollect order by updateTime desc")
    suspend fun getAll(): List<VodCollect>

    /** 当前订阅的收藏：旧数据（apiUrl 为 null）兼容显示，新数据严格按订阅地址过滤 */
    @Query("select * from vodCollect where (apiUrl is null or apiUrl=:apiUrl) order by updateTime desc")
    suspend fun getAllByApi(apiUrl: String): List<VodCollect>

    @Query("select * from vodCollect where `id`=:id")
    suspend fun getVodCollect(id: Int): VodCollect?

    @Query("delete from vodCollect where `id`=:id")
    suspend fun delete(id: Int)

    @Query("select * from vodCollect where `sourceKey`=:sourceKey and `vodId`=:vodId")
    suspend fun getVodCollect(sourceKey: String, vodId: String): VodCollect?

    @Delete
    suspend fun delete(record: VodCollect): Int

    @Query("DELETE FROM vodCollect")
    suspend fun deleteAll()
}
