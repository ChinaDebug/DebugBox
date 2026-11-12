package com.whl.quickjs.wrapper

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.lang.reflect.Array as ReflectArray

/**
 * QuickJS 值类型转换工具，桥接 Java/Kotlin 对象与 JSArray/JSObject/JSValue。
 * 保留 com.whl.quickjs.wrapper 包名以兼容 wang.harlon.quickjs:wrapper 依赖的补丁扩展。
 */
class JSUtils<T> {

    companion object {
        @JvmStatic
        fun isEmpty(obj: Any?): Boolean {
            return when (obj) {
                null -> true
                is CharSequence -> obj.length == 0
                is Collection<*> -> obj.isEmpty()
                is Map<*, *> -> obj.isEmpty()
                else -> obj.javaClass.isArray && ReflectArray.getLength(obj) == 0
            }
        }

        @JvmStatic
        fun isNotEmpty(str: CharSequence?): Boolean = !isEmpty(str)

        @JvmStatic
        fun isNotEmpty(obj: Any?): Boolean = !isEmpty(obj)

        @JvmStatic
        fun toJsonObject(`object`: JSObject?): JSONObject {
            if (`object` == null) return JSONObject()
            return try {
                JSONObject(`object`.stringify())
            } catch (_: JSONException) {
                JSONObject()
            }
        }

        @JvmStatic
        fun toJsonArray(array: JSArray?): JSONArray {
            if (array == null) return JSONArray()
            return try {
                JSONArray(array.stringify())
            } catch (_: JSONException) {
                JSONArray()
            }
        }
    }

    fun toArray(ctx: QuickJSContext, items: List<*>?): JSArray {
        val array = ctx.createNewJSArray()
        if (items.isNullOrEmpty()) return array
        for (i in items.indices) array.set(toJSValue(ctx, items[i]), i)
        return array
    }

    fun toArray(ctx: QuickJSContext, bytes: ByteArray?): JSArray {
        val array = ctx.createNewJSArray()
        if (bytes == null || bytes.isEmpty()) return array
        for (i in bytes.indices) array.set(bytes[i].toInt(), i)
        return array
    }

    fun toArray(ctx: QuickJSContext, arrays: Array<T?>?): JSArray {
        val array = ctx.createNewJSArray()
        if (arrays == null || arrays.size == 0) return array
        for (i in arrays.indices) array.set(toJSValue(ctx, arrays[i]), i)
        return array
    }

    fun toObj(ctx: QuickJSContext, map: Map<*, *>?): JSObject {
        val obj = ctx.createNewJSObject()
        if (map.isNullOrEmpty()) return obj
        for ((key, value) in map) {
            if (key == null) continue
            ctx.setProperty(obj, key.toString(), toJSValue(ctx, value))
        }
        return obj
    }

    fun toJSValue(ctx: QuickJSContext, value: Any?): Any? {
        if (value == null) return null
        if (value is JSObject || value is JSCallFunction) return value
        if (value is Map<*, *>) return toObj(ctx, value)
        if (value is List<*>) return toArray(ctx, value)
        if (value is ByteArray) return toArray(ctx, value)
        val valueClass = value.javaClass
        if (valueClass.isArray) {
            val array = ctx.createNewJSArray()
            val length = ReflectArray.getLength(value)
            for (i in 0 until length) {
                array.set(toJSValue(ctx, ReflectArray.get(value, i)), i)
            }
            return array
        }
        return value
    }
}
