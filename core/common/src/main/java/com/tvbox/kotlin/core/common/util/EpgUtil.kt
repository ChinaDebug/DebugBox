package com.tvbox.kotlin.core.common.util

import android.content.Context
import android.content.res.AssetManager
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tvbox.kotlin.core.common.LOG
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/** EPG 节目单数据加载与查询 */
object EpgUtil {

    @Volatile
    private var epgDoc: JsonObject? = null
    private val epgHashMap = HashMap<String, JsonObject>()
    private var initialized = false

    /** 初始化加载 assets 下的 epg_data.json */
    @Synchronized
    fun init(context: Context? = null) {
        if (initialized) return
        initialized = true
        val ctx = context ?: return
        try {
            val assetManager: AssetManager = ctx.assets
            val inputStreamReader = InputStreamReader(assetManager.open("epg_data.json"), "UTF-8")
            val br = BufferedReader(inputStreamReader)
            val builder = StringBuilder()
            var line: String?
            while (br.readLine().also { line = it } != null) {
                builder.append(line)
            }
            br.close()
            inputStreamReader.close()
            if (builder.isNotEmpty()) {
                val doc = Gson().fromJson(builder.toString(), JsonObject::class.java)
                epgDoc = doc
                doc.get("epgs").asJsonArray.forEach { opt ->
                    val obj = opt as JsonObject
                    val name = obj.get("name").asString.trim()
                    name.split(",").forEach { sub ->
                        // 过滤空串：epg_data.json 存在 name 以逗号结尾（如 "CCTV4中文国际,"）产生的空 key，
                        // 空 key 会让 findMatch 的 contains 判断恒为 true，导致任意频道都误命中该条目的 epgid
                        val trimmed = sub.trim()
                        if (trimmed.isNotEmpty()) epgHashMap[trimmed] = obj
                    }
                }
            }
        } catch (e: IOException) {
            LOG.e(e)
        }
    }

    // 返回 epg_data.json 中匹配的 [epgid]，找不到返回 null
    fun getEpgid(channelName: String): String? {
        return try {
            val matched = epgHashMap[channelName] ?: findMatch(channelName)
            matched?.get("epgid")?.asString
        } catch (e: Exception) {
            LOG.e(e)
            null
        }
    }

    private fun findMatch(channelName: String): JsonObject? {
        for (key in epgHashMap.keys) {
            if (channelName.contains(key) || key.contains(channelName)) {
                return epgHashMap[key]
            }
        }
        return null
    }
}
