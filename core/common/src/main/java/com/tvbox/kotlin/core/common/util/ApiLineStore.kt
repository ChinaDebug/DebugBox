package com.tvbox.kotlin.core.common.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tvbox.kotlin.core.common.AppConstants

/**
 * 多仓（接口合集）线路列表存储。
 * 线路列表按来源订阅地址分别保存（[AppConstants.API_LINE_MAP]），
 * 支持多个多仓源共存：读取时按当前接口地址取对应线路，删除某来源时只清该源的线路。
 * 兼容旧版单值存储（[AppConstants.API_LINE_LIST]）。
 */
object ApiLineStore {

    private val GSON = Gson()
    private val TYPE_LINE_MAP = object : TypeToken<Map<String, List<Pair<String, String>>>>() {}.type
    private val TYPE_LINES = object : TypeToken<List<Pair<String, String>>>() {}.type

    /**
     * 读取当前接口地址对应的多仓线路列表。
     * 按绑定关系匹配：当前地址为多仓来源、或为某多仓源的线路之一时返回对应线路；
     * 当前地址与多仓无关（非多仓源）时返回空，避免残留的多仓列表被误弹出。
     * 均无则回退旧版单值数据。
     */
    fun readLines(prefs: AppPreferences): List<Pair<String, String>> {
        val currentUrl = prefs.getString(AppConstants.API_URL)
        val mapJson = prefs.getString(AppConstants.API_LINE_MAP)
        if (mapJson.isNotEmpty()) {
            try {
                val map: Map<String, List<Pair<String, String>>> =
                    GSON.fromJson(mapJson, TYPE_LINE_MAP) ?: return emptyList()
                // 当前地址即多仓来源 → 返回其线路
                map[currentUrl]?.let { return it }
                // 当前地址是多仓线路之一（绑定来源）→ 返回该来源线路
                map.entries.firstOrNull { (_, lines) -> lines.any { it.second == currentUrl } }
                    ?.value?.let { return it }
                // 当前地址与多仓无关 → 空
                return emptyList()
            } catch (_: Throwable) {
            }
        }
        // 兼容旧版单值存储
        val json = prefs.getString(AppConstants.API_LINE_LIST)
        if (json.isEmpty()) return emptyList()
        return try {
            GSON.fromJson<List<Pair<String, String>>>(json, TYPE_LINES) ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 反查某线路地址所属的多仓来源订阅地址；不属于任何多仓线路时返回 null（同步读取） */
    fun findSourceOfLine(prefs: AppPreferences, lineUrl: String): String? {
        if (lineUrl.isEmpty()) return null
        val mapJson = prefs.getString(AppConstants.API_LINE_MAP)
        if (mapJson.isEmpty()) return null
        return try {
            val map: Map<String, List<Pair<String, String>>> =
                GSON.fromJson(mapJson, TYPE_LINE_MAP) ?: return null
            map.entries.firstOrNull { (_, lines) -> lines.any { it.second == lineUrl } }?.key
        } catch (_: Throwable) {
            null
        }
    }

    /** 判断某来源订阅地址是否保存过多仓线路（同步读取，用于删除前二次确认判断） */
    fun containsSource(prefs: AppPreferences, sourceUrl: String): Boolean {
        val mapJson = prefs.getString(AppConstants.API_LINE_MAP)
        if (mapJson.isEmpty()) return false
        return try {
            val map: Map<String, List<Pair<String, String>>> =
                GSON.fromJson(mapJson, TYPE_LINE_MAP) ?: return false
            map.containsKey(sourceUrl)
        } catch (_: Throwable) {
            false
        }
    }

    /** 保存某个来源订阅地址的多仓线路列表（suspend，需在协程内调用） */
    suspend fun putLines(prefs: AppPreferences, sourceUrl: String, lines: List<Pair<String, String>>) {
        val mapJson = prefs.getString(AppConstants.API_LINE_MAP)
        val map = try {
            GSON.fromJson<Map<String, List<Pair<String, String>>>>(mapJson, TYPE_LINE_MAP)?.toMutableMap()
        } catch (_: Throwable) {
            null
        }
        val merged = (map ?: mutableMapOf()).apply { this[sourceUrl] = lines }
        prefs.put(AppConstants.API_LINE_MAP, GSON.toJson(merged))
    }

    /**
     * 删除某个来源订阅地址的多仓线路。
     * @return true 表示该来源确实存在多仓线路并被清除
     */
    suspend fun removeSource(prefs: AppPreferences, sourceUrl: String): Boolean {
        val mapJson = prefs.getString(AppConstants.API_LINE_MAP)
        if (mapJson.isEmpty()) return false
        val map = try {
            GSON.fromJson<Map<String, List<Pair<String, String>>>>(mapJson, TYPE_LINE_MAP)?.toMutableMap()
        } catch (_: Throwable) {
            null
        } ?: return false
        if (map.remove(sourceUrl) == null) return false
        if (map.isEmpty()) {
            prefs.put(AppConstants.API_LINE_MAP, "")
            prefs.remove(AppConstants.API_LINE_LIST)
            prefs.remove(AppConstants.API_LINE_SOURCE)
        } else {
            prefs.put(AppConstants.API_LINE_MAP, GSON.toJson(map))
        }
        return true
    }
}
