package com.tvbox.kotlin.core.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tvbox.kotlin.core.data.entity.StorageDrive

/**
 * 网盘配置表操作。
 */
@Dao
interface StorageDriveDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(drive: StorageDrive): Long

    @Query("select * from storageDrive order by id")
    suspend fun getAll(): List<StorageDrive>

    @Query("delete from storageDrive where `id`=:id")
    suspend fun delete(id: Int)
}
