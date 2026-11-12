package com.tvbox.kotlin.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tvbox.kotlin.core.data.dao.CacheDao
import com.tvbox.kotlin.core.data.dao.SearchDao
import com.tvbox.kotlin.core.data.dao.StorageDriveDao
import com.tvbox.kotlin.core.data.dao.VodCollectDao
import com.tvbox.kotlin.core.data.dao.VodRecordDao
import com.tvbox.kotlin.core.data.entity.Cache
import com.tvbox.kotlin.core.data.entity.SearchHistory
import com.tvbox.kotlin.core.data.entity.StorageDrive
import com.tvbox.kotlin.core.data.entity.VodCollect
import com.tvbox.kotlin.core.data.entity.VodRecord

/**
 * 应用主数据库，聚合缓存、观看记录、收藏、网盘配置、搜索历史五张表。
 * schema KSP 输出至 schemas 目录。
 */
@Database(
    entities = [
        Cache::class,
        VodRecord::class,
        VodCollect::class,
        StorageDrive::class,
        SearchHistory::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDataBase : RoomDatabase() {

    abstract fun cacheDao(): CacheDao

    abstract fun vodRecordDao(): VodRecordDao

    abstract fun vodCollectDao(): VodCollectDao

    abstract fun storageDriveDao(): StorageDriveDao

    abstract fun searchDao(): SearchDao
}
