package com.tvbox.kotlin.core.common.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 偏好中的字符串列表读写工具 */
object HawkListHelper {

    private val gson = Gson()
    private val type = object : TypeToken<ArrayList<String>>() {}.type
    private const val MAX_STRING_LENGTH = 2048

    fun getList(key: String): ArrayList<String> {
        val json = AppPreferencesHolder.getString(key, "[]")
        return try {
            gson.fromJson(json, type) ?: ArrayList()
        } catch (_: Exception) {
            ArrayList()
        }
    }

    fun putList(key: String, list: ArrayList<String>?) {
        val safeList = list ?: ArrayList()
        val json = gson.toJson(safeList)
        AppPreferencesHolder.putString(key, json)
    }

    /** 添加到列表头部，去重并限制大小 */
    fun addToList(key: String, value: String?, maxSize: Int) {
        if (value.isNullOrEmpty() || value.trim().isEmpty()) return
        var v = value
        if (v.length > MAX_STRING_LENGTH) v = v.substring(0, MAX_STRING_LENGTH)
        if (maxSize <= 0) return
        val list = getList(key)
        list.remove(v)
        list.add(0, v)
        if (list.size > maxSize) {
            list.removeAt(maxSize)
        }
        putList(key, list)
    }

    fun removeFromList(key: String, value: String?) {
        if (value.isNullOrEmpty()) return
        val list = getList(key)
        list.remove(value)
        putList(key, list)
    }
}
