package com.tvbox.kotlin.core.common.bean

import android.text.TextUtils
import androidx.annotation.NonNull
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.net.InetAddress

/** DoH 服务器配置 */
class Doh {

    @SerializedName("name")
    private var name: String? = null

    @SerializedName("url")
    private var url: String? = null

    @SerializedName("ips")
    private var ips: List<String>? = null

    fun name(name: String): Doh {
        this.name = name
        return this
    }

    fun url(url: String): Doh {
        this.url = url
        return this
    }

    fun getName(): String = if (TextUtils.isEmpty(name)) "" else name!!

    fun getUrl(): String = if (TextUtils.isEmpty(url)) "" else url!!

    fun getIps(): List<String> = ips ?: emptyList()

    /** 转为 InetAddress 列表，失败或为空返回 null */
    fun getHosts(): List<InetAddress>? {
        return try {
            val list = getIps().mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
            if (list.isEmpty()) null else list
        } catch (_: Exception) {
            null
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Doh) return false
        return getUrl() == other.getUrl()
    }

    override fun hashCode(): Int = getUrl().hashCode()

    @NonNull
    override fun toString(): String = Gson().toJson(this)

    companion object {
        private val gson = Gson()

        fun objectFrom(str: String?): Doh = gson.fromJson(str, Doh::class.java) ?: Doh()

        fun arrayFrom(element: JsonElement?): List<Doh> {
            if (element == null) return emptyList()
            val type = object : TypeToken<List<Doh>>() {}.type
            return gson.fromJson(element, type) ?: emptyList()
        }
    }
}
