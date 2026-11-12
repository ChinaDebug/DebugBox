package com.tvbox.kotlin.core.common.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "tvbox_settings")

/**
 * 基于 DataStore Preferences 的偏好存储，启动时一次性加载全量到内存缓存，
 * 同步 get 走内存，异步 put 写 DataStore 并更新缓存。
 * key 沿用 [com.tvbox.kotlin.core.common.AppConstants] 中的常量。
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val cache = ConcurrentHashMap<String, Any?>()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 启动时一次性同步加载，保证后续同步 get 可用
        runBlocking {
            val prefs = context.appPreferencesDataStore.data.first()
            prefs.asMap().forEach { (key, value) ->
                cache[key.name] = value
            }
        }
    }

    /** 同步获取值，类型不匹配或不存在返回默认值 */
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String, default: T): T {
        val cached = cache[key] ?: return default
        return (cached as? T) ?: default
    }

    /**
     * 读取 Int。注意：泛型 [get] 的 `as? T` 经类型擦除后不做运行时检查，
     * 若存储类型与读取类型不一致会把错误类型直接返回（如存 Int 读 Boolean），
     * 因此各类型 getter 必须显式 when 匹配，避免调用方 ClassCastException。
     */
    fun getInt(key: String, default: Int = 0): Int {
        return when (val v = cache[key]) {
            is Int -> v
            is Long -> v.toInt()
            is Float -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        return when (val v = cache[key]) {
            is Boolean -> v
            is Int -> v != 0
            is Long -> v != 0L
            is String -> v.toBooleanStrictOrNull() ?: default
            else -> default
        }
    }

    fun getString(key: String, default: String = ""): String {
        return when (val v = cache[key]) {
            is String -> v
            is Int -> v.toString()
            is Long -> v.toString()
            is Float -> v.toString()
            is Boolean -> v.toString()
            else -> default
        }
    }

    fun getLong(key: String, default: Long = 0L): Long {
        return when (val v = cache[key]) {
            is Long -> v
            is Int -> v.toLong()
            is Float -> v.toLong()
            is String -> v.toLongOrNull() ?: default
            else -> default
        }
    }

    fun getFloat(key: String, default: Float = 0f): Float {
        return when (val v = cache[key]) {
            is Float -> v
            is Int -> v.toFloat()
            is Long -> v.toFloat()
            is String -> v.toFloatOrNull() ?: default
            else -> default
        }
    }

    fun contains(key: String): Boolean = cache.containsKey(key)

    /** 同步更新内存缓存并异步落盘，保证调用后立即生效 */
    fun putSync(key: String, value: Any?) {
        cache[key] = value
        ioScope.launch {
            try {
                context.appPreferencesDataStore.edit { prefs -> prefs.applyValue(key, value) }
            } catch (_: Exception) {
            }
        }
    }

    /** 等待 DataStore 中所有挂起写入落盘完成（备份前调用，确保备份到最新数据） */
    suspend fun flush() {
        context.appPreferencesDataStore.data.first()
    }

    /** 异步写入任意类型值 */
    suspend fun put(key: String, value: Any?) {
        cache[key] = value
        context.appPreferencesDataStore.edit { prefs -> prefs.applyValue(key, value) }
    }

    /** 异步移除单个 key */
    suspend fun remove(key: String) {
        cache.remove(key)
        context.appPreferencesDataStore.edit { prefs ->
            prefs.remove(intPreferencesKey(key))
            prefs.remove(booleanPreferencesKey(key))
            prefs.remove(stringPreferencesKey(key))
            prefs.remove(longPreferencesKey(key))
            prefs.remove(floatPreferencesKey(key))
            prefs.remove(stringSetPreferencesKey(key))
        }
    }

    /** 按前缀批量移除 key（如删除历史时清空该影片全部集的进度缓存） */
    suspend fun removeKeysByPrefix(prefix: String) {
        if (prefix.isEmpty()) return
        val matched = cache.keys.filter { it.startsWith(prefix) }
        if (matched.isEmpty()) return
        matched.forEach { cache.remove(it) }
        context.appPreferencesDataStore.edit { prefs ->
            matched.forEach { key ->
                prefs.remove(intPreferencesKey(key))
                prefs.remove(booleanPreferencesKey(key))
                prefs.remove(stringPreferencesKey(key))
                prefs.remove(longPreferencesKey(key))
                prefs.remove(floatPreferencesKey(key))
                prefs.remove(stringSetPreferencesKey(key))
            }
        }
    }

    /** 异步清空全部偏好 */
    suspend fun clear() {
        cache.clear()
        context.appPreferencesDataStore.edit { it.clear() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutablePreferences.applyValue(key: String, value: Any?) {
        when (value) {
            is Int -> this[intPreferencesKey(key)] = value
            is Boolean -> this[booleanPreferencesKey(key)] = value
            is String -> this[stringPreferencesKey(key)] = value
            is Long -> this[longPreferencesKey(key)] = value
            is Float -> this[floatPreferencesKey(key)] = value
            is Set<*> -> this[stringSetPreferencesKey(key)] = value as Set<String>
            else -> this[stringPreferencesKey(key)] = value?.toString() ?: ""
        }
    }

    companion object {
        @Volatile
        private var shared: AppPreferences? = null

        /** 注册全局共享实例，由 Application 注入完成后调用，供非注入场景同步读取偏好 */
        fun setShared(preferences: AppPreferences) {
            shared = preferences
        }

        /** 获取全局共享实例，未注册时按需创建（可能触发一次 DataStore 同步加载） */
        fun getShared(context: Context): AppPreferences {
            shared?.let { return it }
            return synchronized(this) {
                shared ?: AppPreferences(context.applicationContext).also { shared = it }
            }
        }
    }
}
