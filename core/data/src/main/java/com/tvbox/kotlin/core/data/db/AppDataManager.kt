package com.tvbox.kotlin.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.util.FileUtils
import com.tvbox.kotlin.core.data.dao.CacheDao
import com.tvbox.kotlin.core.data.dao.SearchDao
import com.tvbox.kotlin.core.data.dao.StorageDriveDao
import com.tvbox.kotlin.core.data.dao.VodCollectDao
import com.tvbox.kotlin.core.data.dao.VodRecordDao
import java.io.File
import java.io.IOException

/**
 * 数据库管理器：负责构建 AppDataBase、提供 DAO 访问与备份/恢复。
 * 由 [com.tvbox.kotlin.core.data.di.DataModule] 通过 Hilt 提供单例。
 */
class AppDataManager internal constructor(
    private val context: Context,
    private val appDataBase: AppDataBase
) {

    fun getCacheDao(): CacheDao = appDataBase.cacheDao()

    fun getVodRecordDao(): VodRecordDao = appDataBase.vodRecordDao()

    fun getVodCollectDao(): VodCollectDao = appDataBase.vodCollectDao()

    fun getStorageDriveDao(): StorageDriveDao = appDataBase.storageDriveDao()

    fun getSearchDao(): SearchDao = appDataBase.searchDao()

    fun getDatabase(): AppDataBase = appDataBase

    /**
     * 备份数据库到指定文件。
     * 复制前关闭数据库连接保证文件一致；复制后无论成败都重新打开连接，
     * 避免备份失败（不重启）时后续 DAO 访问已关闭的连接而崩溃。
     * 注意：备份/恢复后需重启 App 以重建内存缓存。
     */
    @Throws(IOException::class)
    fun backup(path: File): Boolean {
        val dbFile = context.getDatabasePath(DB_PATH)
        if (appDataBase.isOpen) {
            appDataBase.close()
        }
        return try {
            if (dbFile.exists()) {
                FileUtils.copyFile(dbFile, path)
                true
            } else {
                false
            }
        } finally {
            reopenDatabase()
        }
    }

    /**
     * 恢复数据库，覆盖当前数据库文件。
     * 恢复前校验备份文件可正常打开且版本不高于当前版本：旧版本备份由 Room 自动迁移，
     * 新版本备份（无法降级）与损坏文件会被拒绝，避免启动崩溃。
     */
    @Throws(IOException::class)
    fun restore(path: File): Boolean {
        if (!isBackupCompatible(path)) {
            throw IllegalStateException("backup version incompatible")
        }
        val dbFile = context.getDatabasePath(DB_PATH)
        if (appDataBase.isOpen) {
            appDataBase.close()
        }
        return try {
            if (dbFile.exists()) {
                dbFile.delete()
            }
            dbFile.parentFile?.takeIf { !it.exists() }?.mkdirs()
            FileUtils.copyFile(path, dbFile)
            true
        } finally {
            reopenDatabase()
        }
    }

    /**
     * 校验备份数据库文件：可正常打开且 schema 版本（user_version）不高于当前版本。
     * - 版本等于当前：直接恢复；
     * - 版本低于当前（旧版 App 备份）：恢复后由 Room 迁移链自动升级，数据保留；
     * - 版本高于当前（新版备份恢复到旧版 App）或文件损坏：拒绝，避免崩溃。
     */
    fun isBackupCompatible(path: File): Boolean {
        if (!path.exists()) return false
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            db.version <= DB_FILE_VERSION
        } catch (t: Throwable) {
            LOG.e("isBackupCompatible", t)
            false
        } finally {
            db?.close()
        }
    }

    /** 重新打开数据库连接，失败仅记录日志（正常情况下由重启流程兜底） */
    private fun reopenDatabase() {
        try {
            appDataBase.openHelper.writableDatabase
        } catch (t: Throwable) {
            LOG.e("reopenDatabase", t)
        }
    }

    companion object {
        const val DB_FILE_VERSION = 4
        const val DB_NAME = "tvbox"
        const val DB_PATH = "$DB_NAME.v$DB_FILE_VERSION.db"

        /** 迁移 1->2：创建网盘配置表 */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `storageDrive` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `type` INTEGER NOT NULL, `configJson` TEXT)")
                } catch (e: SQLiteException) {
                    LOG.e("MIGRATION_1_2", e)
                }
            }
        }

        /** 迁移 2->3：创建搜索历史表与索引 */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE TABLE IF NOT EXISTS t_search (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, searchKeyWords TEXT)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_t_search_searchKeyWords ON t_search (searchKeyWords)")
                } catch (e: SQLiteException) {
                    LOG.e("MIGRATION_2_3", e)
                }
            }
        }

        /** 迁移 3->4：观看记录与收藏表增加 apiUrl 列（订阅地址归属，旧数据为 null 兼容显示） */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE `vodRecord` ADD COLUMN `apiUrl` TEXT")
                    db.execSQL("ALTER TABLE `vodCollect` ADD COLUMN `apiUrl` TEXT")
                } catch (e: SQLiteException) {
                    LOG.e("MIGRATION_3_4", e)
                }
            }
        }

        /** 构建 AppDataBase 实例，日志模式与主线程查询许可 */
        fun build(context: Context): AppDataBase =
            Room.databaseBuilder(context.applicationContext, AppDataBase::class.java, DB_PATH)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .allowMainThreadQueries()
                .build()
    }
}
