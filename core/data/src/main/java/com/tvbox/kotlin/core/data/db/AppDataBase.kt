package com.tvbox.kotlin.core.data.db

import androidx.room3.Database
import androidx.room3.RoomDatabase
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
 * 数据库 schema 版本：Room 注解与库文件体检共用同一常量。
 * 以后升级表结构只需改这一处并递增。
 */
const val DB_SCHEMA_VERSION = 4

/**
 * 应用主数据库，聚合缓存、观看记录、收藏、网盘配置、搜索历史五张表。
 * schema KSP 输出至 schemas 目录。
 *
 * 常规表结构变更（加表、加字段）推荐走 Room 自动迁移，无需手写 SQL：
 * 1. 修改实体类；
 * 2. 把 [DB_SCHEMA_VERSION] 加 1（如 4 -> 5）；
 * 3. 在下方增加 autoMigrations = [AutoMigration(from = 4, to = 5)]。
 * 复杂变更（改列名、拆表、数据搬迁）仍需手写 Migration 并在 build() 中注册。
 * 注意：schemas 目录现有 3.json、4.json，AutoMigration 仅对版本 5 及之后生效。
 */
@Database(
    entities = [
        Cache::class,
        VodRecord::class,
        VodCollect::class,
        StorageDrive::class,
        SearchHistory::class
    ],
    version = DB_SCHEMA_VERSION,
    exportSchema = true
)
abstract class AppDataBase : RoomDatabase() {

    abstract fun cacheDao(): CacheDao

    abstract fun vodRecordDao(): VodRecordDao

    abstract fun vodCollectDao(): VodCollectDao

    abstract fun storageDriveDao(): StorageDriveDao

    abstract fun searchDao(): SearchDao
}
