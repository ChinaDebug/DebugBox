package com.tvbox.kotlin.core.common.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.tencent.mmkv.MMKV
import com.tvbox.kotlin.core.common.LOG
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 旧版 DataStore 存储，仅用于首次启动时一次性迁移到 MMKV */
private val Context.legacyPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "tvbox_settings")

/**
 * 基于 MMKV 的偏好存储，读取与写入均为同步内存操作并同步落盘。
 * key 沿用 [com.tvbox.kotlin.core.common.AppConstants] 中的常量。
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val mmkv: MMKV = run {
        MMKV.initialize(context)
        requireNotNull(MMKV.mmkvWithID(ID, MMKV.SINGLE_PROCESS_MODE)) { "MMKV 初始化失败" }
    }

    init {
        migrateLegacyDataStore()
    }

    /** 同步获取值，按默认值的类型读取，类型不匹配或不存在返回默认值 */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, default: T): T = when (default) {
        is Boolean -> getBoolean(key, default)
        is Int -> getInt(key, default)
        is Long -> getLong(key, default)
        is Float -> getFloat(key, default)
        is String -> getString(key, default)
        else -> (mmkv.decodeString(key) as? T) ?: default
    } as T

    fun getInt(key: String, default: Int = 0): Int = mmkv.decodeInt(key, default)

    fun getBoolean(key: String, default: Boolean = false): Boolean = mmkv.decodeBool(key, default)

    fun getString(key: String, default: String = ""): String = mmkv.decodeString(key, default) ?: default

    fun getLong(key: String, default: Long = 0L): Long = mmkv.decodeLong(key, default)

    fun getFloat(key: String, default: Float = 0f): Float = mmkv.decodeFloat(key, default)

    fun contains(key: String): Boolean = mmkv.containsKey(key)

    /** 同步写入并落盘，调用后立即生效 */
    fun putSync(key: String, value: Any?) {
        encode(key, value)
    }

    /** 等待所有写入落盘（备份前调用，确保备份到最新数据） */
    suspend fun flush() {
        mmkv.sync()
    }

    /** 写入任意类型值 */
    suspend fun put(key: String, value: Any?) {
        encode(key, value)
    }

    /** 移除单个 key */
    suspend fun remove(key: String) {
        mmkv.removeValueForKey(key)
    }

    /** 按前缀批量移除 key（如删除历史时清空该影片全部集的进度缓存） */
    suspend fun removeKeysByPrefix(prefix: String) {
        if (prefix.isEmpty()) return
        mmkv.allKeys()?.filter { it.startsWith(prefix) }?.forEach { mmkv.removeValueForKey(it) }
    }

    /** 清空全部偏好 */
    suspend fun clear() {
        mmkv.clearAll()
    }

    private fun encode(key: String, value: Any?) {
        when (value) {
            is Boolean -> mmkv.encode(key, value)
            is Int -> mmkv.encode(key, value)
            is Long -> mmkv.encode(key, value)
            is Float -> mmkv.encode(key, value)
            is String -> mmkv.encode(key, value)
            is Set<*> -> mmkv.encode(key, value.filterIsInstance<String>().toSet())
            null -> mmkv.removeValueForKey(key)
            else -> mmkv.encode(key, value.toString())
        }
    }

    /** 将旧版 DataStore 中的偏好一次性导入 MMKV，导入后写入标记避免重复执行 */
    private fun migrateLegacyDataStore() {
        if (mmkv.containsKey(KEY_LEGACY_MIGRATED)) return
        try {
            val legacyFile = File(context.filesDir, "datastore/$LEGACY_STORE_NAME.preferences_pb")
            if (legacyFile.exists()) {
                val prefs = runBlocking { context.legacyPreferencesDataStore.data.first() }
                prefs.asMap().forEach { (key, value) -> encode(key.name, value) }
            }
        } catch (t: Throwable) {
            LOG.e("migrateLegacyDataStore", t)
        }
        mmkv.encode(KEY_LEGACY_MIGRATED, true)
    }

    companion object {
        private const val ID = "tvbox_settings"
        private const val LEGACY_STORE_NAME = "tvbox_settings"
        private const val KEY_LEGACY_MIGRATED = "__legacy_datastore_migrated"

        @Volatile
        private var shared: AppPreferences? = null

        /** 注册全局共享实例，由 Application 注入完成后调用，供非注入场景同步读取偏好 */
        fun setShared(preferences: AppPreferences) {
            shared = preferences
        }

        /** 获取全局共享实例，未注册时按需创建 */
        fun getShared(context: Context): AppPreferences {
            shared?.let { return it }
            return synchronized(this) {
                shared ?: AppPreferences(context.applicationContext).also { shared = it }
            }
        }
    }
}
