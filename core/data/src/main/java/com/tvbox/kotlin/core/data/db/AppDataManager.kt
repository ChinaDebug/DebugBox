package com.tvbox.kotlin.core.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.util.FileUtils
import com.tvbox.kotlin.core.data.dao.CacheDao
import com.tvbox.kotlin.core.data.dao.SearchDao
import com.tvbox.kotlin.core.data.dao.StorageDriveDao
import com.tvbox.kotlin.core.data.dao.VodCollectDao
import com.tvbox.kotlin.core.data.dao.VodRecordDao
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 数据库管理器：负责构建 AppDataBase、提供 DAO 访问与备份/恢复。
 * 由 [com.tvbox.kotlin.core.data.di.DataModule] 通过 Hilt 提供单例。
 */
class AppDataManager internal constructor(
    private val context: Context
) {

    /** 懒构建实例：构建失败会向调用方抛出明确异常，而非留下不可用实例 */
    @Volatile
    private var appDataBase: AppDataBase? = null

    private fun database(): AppDataBase {
        appDataBase?.let { return it }
        synchronized(this) {
            appDataBase?.let { return it }
            return build(context).also { appDataBase = it }
        }
    }

    fun getCacheDao(): CacheDao = database().cacheDao()

    fun getVodRecordDao(): VodRecordDao = database().vodRecordDao()

    fun getVodCollectDao(): VodCollectDao = database().vodCollectDao()

    fun getStorageDriveDao(): StorageDriveDao = database().storageDriveDao()

    fun getSearchDao(): SearchDao = database().searchDao()

    fun getDatabase(): AppDataBase = database()

    /**
     * 备份数据库到指定文件。
     * 复制前关闭数据库连接保证文件一致；复制后无论成败都重新打开连接，
     * 避免备份失败（不重启）时后续 DAO 访问已关闭的连接而崩溃。
     * 注意：备份/恢复后需重启 App 以重建内存缓存。
     */
    @Throws(IOException::class)
    fun backup(path: File): Boolean {
        val dbFile = context.getDatabasePath(DB_PATH)
        database().close()
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
        database().close()
        return try {
            // 主库与 -wal/-shm/-journal 一并清理，避免残留日志覆盖刚恢复的数据
            DB_FILE_SUFFIXES.forEach { suffix ->
                File(dbFile.path + suffix).takeIf { it.exists() }?.delete()
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
            db.version <= DB_SCHEMA_VERSION
        } catch (t: Throwable) {
            LOG.e("isBackupCompatible", t)
            false
        } finally {
            db?.close()
        }
    }

    /**
     * 关闭后仅置空实例，下次访问 [database] 时按需重建。
     * 不再保留已关闭的旧实例，避免重建失败后后续 DAO 访问抛出 "already-closed object" 造成误判。
     */
    private fun reopenDatabase() {
        synchronized(this) {
            appDataBase = null
        }
    }

    companion object {
        const val DB_NAME = "tvbox"
        const val DB_PATH = "$DB_NAME.db"

        /** 主库与所有附属文件后缀，备份/恢复/隔离时统一处理 */
        private val DB_FILE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

        /** 旧版本曾使用带版本号的库文件名，升级时接管其数据，避免历史/收藏丢失 */
        private val LEGACY_DB_NAME_REGEX = Regex("^$DB_NAME\\.v(\\d+)\\.db$")

        private const val SQLITE_MAGIC = "SQLite format 3\u0000"
        private const val SQLITE_HEADER_SIZE = 64

        /**
         * 读取 SQLite 文件头中的 user_version（偏移 60，大端 4 字节）。
         * 直接读文件而不用 openDatabase，避免启动阶段加锁、触发 WAL 恢复或写回。
         * 返回 null 表示不是合法 SQLite 文件（按损坏处理）。
         */
        private fun readUserVersion(file: File): Int? = try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < SQLITE_HEADER_SIZE) return null
                val magic = ByteArray(16)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != SQLITE_MAGIC) return null
                raf.seek(60)
                val raw = ByteArray(4)
                raf.readFully(raw)
                (raw[0].toInt() and 0xFF shl 24) or
                    (raw[1].toInt() and 0xFF shl 16) or
                    (raw[2].toInt() and 0xFF shl 8) or
                    (raw[3].toInt() and 0xFF)
            }
        } catch (t: Throwable) {
            LOG.e("readUserVersion", t)
            null
        }

        /**
         * 隔离不可用库文件：损坏或 schema 版本高于当前（无法降级）时，
         * 重命名备份并清掉附属文件，交由 Room 重建空库，保证应用始终可启动。
         */
        private fun quarantineUnusableDb(context: Context) {
            val dbFile = context.getDatabasePath(DB_PATH)
            if (!dbFile.exists()) return
            val version = readUserVersion(dbFile)
            val reason = when {
                version == null -> "数据库文件损坏或非 SQLite 格式"
                version > DB_SCHEMA_VERSION -> "schema 版本($version)高于当前($DB_SCHEMA_VERSION)"
                else -> return
            }
            val backup = File(dbFile.parentFile, "$DB_NAME.corrupt.${System.currentTimeMillis()}.db")
            LOG.w("quarantineUnusableDb", "$reason，已备份为 ${backup.name} 并重建")
            if (!dbFile.renameTo(backup)) return
            DB_FILE_SUFFIXES.drop(1).forEach { suffix ->
                File(dbFile.path + suffix).takeIf { it.exists() }?.delete()
            }
        }

        private fun migrateLegacyDbFile(context: Context) {
            val target = context.getDatabasePath(DB_PATH)
            if (target.exists()) return
            val legacy = target.parentFile
                ?.listFiles { f -> LEGACY_DB_NAME_REGEX.matches(f.name) }
                ?.maxByOrNull { LEGACY_DB_NAME_REGEX.find(it.name)!!.groupValues[1].toInt() }
                ?: return
            if (!legacy.renameTo(target)) return
            // WAL/SHM/journal 必须随库文件一起迁移，否则可能丢失未落盘记录
            DB_FILE_SUFFIXES.drop(1).forEach { suffix ->
                File(legacy.path + suffix).takeIf { it.exists() }
                    ?.renameTo(File(target.path + suffix))
            }
        }

        /** 迁移 1->2：创建网盘配置表（IF NOT EXISTS 保证可重复执行） */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("CREATE TABLE IF NOT EXISTS `storageDrive` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, `type` INTEGER NOT NULL, `configJson` TEXT)")
            }
        }

        /** 迁移 2->3：创建搜索历史表与索引（IF NOT EXISTS 保证可重复执行） */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("CREATE TABLE IF NOT EXISTS t_search (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, searchKeyWords TEXT)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_t_search_searchKeyWords ON t_search (searchKeyWords)")
            }
        }

        /** 迁移 3->4：观看记录与收藏表增加 apiUrl 列（先探测列，保证迁移可重复执行且不吞异常） */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.addColumnIfAbsent("vodRecord", "apiUrl")
                connection.addColumnIfAbsent("vodCollect", "apiUrl")
            }
        }

        /** SQLite 不支持 ADD COLUMN IF NOT EXISTS，先查 PRAGMA 再决定是否添加 */
        private suspend fun SQLiteConnection.addColumnIfAbsent(table: String, column: String) {
            if (hasColumn(table, column)) return
            execSQL("ALTER TABLE `$table` ADD COLUMN `$column` TEXT")
        }

        private suspend fun SQLiteConnection.hasColumn(table: String, column: String): Boolean {
            val statement = prepare("PRAGMA table_info(`$table`)")
            return try {
                var found = false
                while (statement.step()) {
                    // PRAGMA table_info 第 2 列（下标 1）为列名
                    if (statement.getText(1)?.equals(column, ignoreCase = true) == true) {
                        found = true
                        break
                    }
                }
                found
            } finally {
                statement.close()
            }
        }

        /** 构建 AppDataBase 实例，日志模式与主线程查询许可 */
        fun build(context: Context): AppDataBase {
            // 顺序：先隔离损坏/高版本库，再接管旧文件名，最后复检接管来的库，
            // 避免目标库损坏时跳过了旧库接管、或旧库本身版本过高导致 Room 崩溃
            quarantineUnusableDb(context)
            migrateLegacyDbFile(context)
            quarantineUnusableDb(context)
            return Room.databaseBuilder(context.applicationContext, AppDataBase::class.java, DB_PATH)
                .setDriver(BundledSQLiteDriver())
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .allowMainThreadQueries()
                .build()
        }
    }
}
