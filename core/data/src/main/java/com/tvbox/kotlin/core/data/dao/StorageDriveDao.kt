package com.tvbox.kotlin.core.data.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
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
