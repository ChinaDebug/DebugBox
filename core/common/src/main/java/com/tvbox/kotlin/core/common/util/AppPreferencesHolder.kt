package com.tvbox.kotlin.core.common.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AppPreferences 的全局访问入口。
 * 在 Application 启动时由 Hilt 注入的实例通过 [install] 注册，供无法使用构造注入的类（Bean 等）静态访问偏好。
 */
object AppPreferencesHolder {

    @Volatile
    private var prefs: AppPreferences? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 注册 Hilt 注入的 AppPreferences 实例 */
    fun install(prefs: AppPreferences) {
        this.prefs = prefs
    }

    private fun require(): AppPreferences =
        prefs ?: throw IllegalStateException("AppPreferencesHolder 未初始化，请先在 Application 中调用 install")

    fun getInt(key: String, default: Int = 0): Int = require().getInt(key, default)

    fun getBoolean(key: String, default: Boolean = false): Boolean = require().getBoolean(key, default)

    fun getString(key: String, default: String = ""): String = require().getString(key, default)

    fun getFloat(key: String, default: Float = 0f): Float = require().getFloat(key, default)

    /** 异步写入，fire-and-forget */
    fun put(key: String, value: Any?) {
        scope.launch { require().put(key, value) }
    }

    fun putString(key: String, value: String) = put(key, value)

    fun putInt(key: String, value: Int) = put(key, value)

    fun putBoolean(key: String, value: Boolean) = put(key, value)

    /** 同步更新内存缓存并异步落盘：写入后立即 get 可读到新值（消除异步竞态） */
    fun putSync(key: String, value: Any?) = require().putSync(key, value)

    fun putIntSync(key: String, value: Int) = putSync(key, value)

    fun putBooleanSync(key: String, value: Boolean) = putSync(key, value)

    fun putFloatSync(key: String, value: Float) = putSync(key, value)
}
