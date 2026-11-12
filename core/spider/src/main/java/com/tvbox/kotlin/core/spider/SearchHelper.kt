package com.tvbox.kotlin.core.spider

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.util.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 搜索站点选择助手，按当前订阅地址维护可搜索站点的勾选状态。
 * 勾选状态经 [AppPreferences] 持久化，首次进入时自动勾选全部可搜索站点。
 */
@Singleton
class SearchHelper @Inject constructor(
    private val apiConfig: ApiConfig,
    private val appPreferences: AppPreferences
) {

    companion object {
        private val GSON = Gson()
        private val TYPE_ALL_SOURCES = object : TypeToken<HashMap<String, HashMap<String, String>>>() {}.type

        /** 推荐分词最多生成的按钮数，避免过多关键词刷屏 */
        private const val MAX_SPLIT_WORDS = 6
    }

    private fun readAll(): HashMap<String, HashMap<String, String>> = try {
        val json = appPreferences.getString(AppConstants.SOURCES_FOR_SEARCH, "")
        if (json.isNotEmpty()) GSON.fromJson(json, TYPE_ALL_SOURCES) else HashMap()
    } catch (_: Throwable) {
        HashMap()
    }

    private fun writeAll(all: HashMap<String, HashMap<String, String>>) {
        // putSync：同步更新内存缓存 + 异步落盘。
        // 勾选后立即关闭/重开弹窗或立即搜索时能读到最新状态，避免状态回退导致"勾选状态不一致"。
        // 同时 GSON 序列化在调用线程执行，避免 IO 线程遍历被 UI 并发修改的 map。
        appPreferences.putSync(AppConstants.SOURCES_FOR_SEARCH, GSON.toJson(all))
    }

    private fun getApi(): String =
        appPreferences.getString(AppConstants.API_URL, AppConstants.DEFAULT_API_URL)

    /** 获取当前接口下参与搜索的站点映射，首次自动勾选全部可搜索站点 */
    fun getSourcesForSearch(): HashMap<String, String>? {
        val api = getApi()
        if (api.isEmpty()) return null
        val all = readAll()
        var checked = all[api]
        if (!all.containsKey(api)) {
            checked = HashMap()
            for (bean in apiConfig.getSourceBeanList()) {
                if (bean.isSearchable()) checked!![bean.key ?: ""] = "1"
            }
            all[api] = checked!!
            writeAll(all)
        }
        return checked ?: HashMap()
    }

    /** 持久化当前接口下的搜索站点勾选状态 */
    fun putCheckedSources(checkedSources: HashMap<String, String>) {
        val api = getApi()
        if (api.isEmpty()) return
        val all = readAll()
        all[api] = checkedSources
        writeAll(all)
    }

    /**
     * 拆分搜索词为可点击的子关键词，供聚合搜索顶部栏按钮使用。
     * 拆分优先级（由语义完整到兜底）：
     * 1) 按分隔符（空格/标点/ASCII 边界）拆分，如 "John Wick 3" -> ["John", "Wick"]，
     *    纯数字 token 无搜索价值直接丢弃；
     * 2) 中文连接标点拆分（之/·/：等），如 "长津湖之水门桥" -> ["长津湖", "水门桥"]；
     * 3) 中文尾部序号剥离，如 "速度与激情10" -> "速度与激情"、"庆余年第一季" -> "庆余年"；
     * 4) 仍无子词时兜底返回完整词，保证顶部栏始终有关键词按钮。
     */
    fun splitWords(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        val seen = HashSet<String>()
        fun add(word: String) {
            val w = word.trim()
            if (w.isNotEmpty() && w != trimmed && w.length >= 2 && seen.add(w)) result.add(w)
        }
        // 1) 分隔符拆分（英文/数字/标点边界）
        for (part in trimmed.split("\\W+".toRegex())) {
            if (part.length >= 2 && !part.all { it.isDigit() }) add(part)
        }
        // 2) 中文连接标点拆分
        for (part in trimmed.split(Regex("[之·：:\\、]"))) {
            add(part)
        }
        // 3) 中文尾部序号剥离（result 为空时才执行，避免与 1/2 步结果重复）
        if (result.isEmpty()) {
            val base = trimSeriesSuffix(trimmed)
            if (base != trimmed) add(base)
        }
        // 4) 兜底完整词
        if (result.isEmpty()) result.add(trimmed)
        return result.take(MAX_SPLIT_WORDS)
    }

    /** 剥离片名尾部序号：速度与激情10 -> 速度与激情；庆余年第一季 -> 庆余年；原样返回若无序号 */
    private fun trimSeriesSuffix(text: String): String {
        // 第X季/部/集（X 可为数字或中文数字）
        val season = Regex("^(.*?)(第[0-9一二三四五六七八九十百]+[季部集])$").find(text)
        if (season != null && season.groupValues[1].length >= 2) return season.groupValues[1]
        // 纯数字后缀
        val num = Regex("^(.*?)(\\d+)$").find(text)
        if (num != null && num.groupValues[1].length >= 2) return num.groupValues[1]
        return text
    }
}
