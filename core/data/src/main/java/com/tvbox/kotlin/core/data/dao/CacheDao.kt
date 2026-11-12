package com.tvbox.kotlin.core.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tvbox.kotlin.core.data.entity.Cache

/**
 * 缓存表操作：插入、查询、删除、按前缀批量删除。
 */
@Dao
interface CacheDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(cache: Cache): Long

    @Query("select * from cache where `key`=:key")
    suspend fun getCache(key: String): Cache?

    @Delete
    suspend fun delete(cache: Cache): Int

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(cache: Cache): Int

    @Query("delete from cache")
    suspend fun deleteAll()

    @Query("delete from cache where `key` like :keyPrefix || '%'")
    suspend fun deleteByKeyPrefix(keyPrefix: String)
}
