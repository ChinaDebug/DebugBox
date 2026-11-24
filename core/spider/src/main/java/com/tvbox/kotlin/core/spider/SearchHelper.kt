package com.tvbox.kotlin.core.spider

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.util.AppPreferences
import java.text.BreakIterator
import java.util.Locale
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

        /** 序号字符集：Unicode 十进制数字（含全角）与罗马数字（Ⅱ/Ⅲ/ⅱ 等续作常用写法） */
        private const val NUMBER_CHARS = "\\p{Nd}ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫⅰⅱⅲⅳⅴⅵⅶⅷⅸⅹⅺⅻ"

        /**
         * 分词分隔符：空白与中英文标点。
         * 不能用 \W：Java 默认 \w 仅含 ASCII，中文会被当作分隔符整段丢弃，
         * 导致 "名侦探柯南：黑铁的鱼影" 这类词永远拆不出子词。
         */
        private val SPLIT_SEPARATOR =
            Regex("[\\s\\p{Punct}，。、；：？！“”‘’（）《》【】「」『』〈〉～~—…]+")

        /**
         * 中文连接词：仅当连接词两侧都至少有 2 个字符时才拆分。
         * 否则 "双帝之战" 会被切成 "双帝" + "战"（单字被丢弃），丢失完整副标题。
         */
        private val SPLIT_CONNECTOR = Regex("(?<=..)[之·](?=..)")

        /** 名称中间序号：仅作分隔，如 "斗罗大陆2绝世唐门" / "斗罗大陆Ⅱ绝世唐门" */
        private val SPLIT_DIGITS = Regex("[$NUMBER_CHARS]+")

        /** 尾部噪声：季/部/集序号、纯数字、画质与语言/版本标签，剥离后再分词，避免生成无效按钮 */
        private val NOISE_SUFFIX = Regex(
            "(?i)[\\s\\p{Punct}，。、；：？！·—…]*(" +
                "[$NUMBER_CHARS]+|第?[0-9一二三四五六七八九十百零两]+[季部集篇期话回章]|" +
                "\\d{3,4}[pi]|[248]k|hd|fhd|uhd|hdr|blu-?ray|web-?dl|hdtv|dvd|remux|bd|tc|ts|x26[45]|h26[45]|hevc|avc|" +
                "国语|粤语|日语|英语|韩语|中字|中文字幕|简中|繁中|高清|超清|蓝光|完整版|未删减版?|" +
                "年番|剧场版|特别篇|番外篇?" +
                ")$"
        )

        /** 无检索价值的独立词：与尾部噪声同源，另含纯数字与通用篇章词 */
        private val NOISE_WORD = Regex(
            "(?i)^(" +
                "[$NUMBER_CHARS]+|第?[0-9一二三四五六七八九十百零两]+[季部集篇期话回章]|" +
                "\\d{3,4}[pi]|[248]k|hd|fhd|uhd|hdr|blu-?ray|web-?dl|hdtv|dvd|remux|bd|tc|ts|x26[45]|h26[45]|hevc|avc|" +
                "国语|粤语|日语|英语|韩语|中字|中文字幕|简中|繁中|高清|超清|蓝光|完整版|未删减版?|" +
                "年番|剧场版|特别篇|番外篇?" +
                ")$"
        )

        /** 常见虚词：系统词典分词可能切出这类词，作为关键词搜索无意义，直接过滤 */
        private val STOP_WORD = Regex(
            "^(我们|你们|他们|她们|它们|自己|这个|那个|什么|怎么|为什么|可以|就是|没有|不是|不要|不会|不能|" +
                "已经|还是|但是|因为|所以|如果|真的|知道|觉得|应该|可能|只是|这样|那样|这里|那里|时候|现在|" +
                "一个|我的|你的|他的)$"
        )
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
     * 1) 剥离尾部噪声（季/部/集序号、画质与语言标签）后，按空白与中英文标点拆分；
     * 2) 中文连接词与名称中间序号二次拆分，如 "长津湖之水门桥" -> ["长津湖", "水门桥"]；
     * 3) 无显式分隔符时用系统词典兜底分词，如 "斗破苍穹" -> ["斗破", "苍穹"]；
     * 4) 仍无子词时返回完整词，保证顶部栏始终有关键词按钮。
     */
    fun splitWords(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val base = trimNoiseSuffix(trimmed)
        val result = ArrayList<String>()
        val seen = HashSet<String>()
        fun add(word: String) {
            val w = word.trim()
            if (w.length < 2 || w == trimmed || isNoiseWord(w)) return
            if (seen.add(w)) result.add(w)
        }
        // 1)、2) 按分隔符拆分，再对每段做连接词与中间序号拆分
        for (part in base.split(SPLIT_SEPARATOR)) {
            for (piece in part.split(SPLIT_CONNECTOR)) {
                add(piece)
                // 序号既作为分隔（斗罗大陆 / 绝世唐门），也保留在前缀上（斗罗大陆2），
                // 兼容续作常以“名称+序号”命名的站点
                var start = 0
                for (match in SPLIT_DIGITS.findAll(piece)) {
                    add(piece.substring(start, match.range.first))
                    add(piece.substring(start, match.range.last + 1))
                    start = match.range.last + 1
                }
                if (start > 0) add(piece.substring(start))
            }
        }
        // 3) 连续中文片名无分隔符时用系统词典（ICU）分词，至少两个有效词才采用，避免切成单字
        if (result.isEmpty()) {
            val words = segmentByDictionary(base).filter {
                it.length >= 2 && it != trimmed && !isNoiseWord(it)
            }
            if (words.size >= 2) words.forEach { add(it) }
        }
        // 4) 兜底：优先剥离噪声后的词干，否则完整词，保证顶部栏始终有关键词按钮
        if (result.isEmpty() && base != trimmed) add(base)
        if (result.isEmpty()) result.add(trimmed)
        return result.take(MAX_SPLIT_WORDS)
    }

    /** 是否为无检索价值的词：画质/版本/季数标签、纯数字或常见虚词 */
    private fun isNoiseWord(word: String): Boolean =
        NOISE_WORD.matches(word) || STOP_WORD.matches(word)

    /** 循环剥离片名尾部噪声：季/部/集序号、纯数字、画质与语言标签，如 "庆余年 第二季 1080P" -> "庆余年" */
    private fun trimNoiseSuffix(text: String): String {
        var result = text
        while (true) {
            val match = NOISE_SUFFIX.find(result) ?: break
            val next = result.substring(0, match.range.first).trim()
            if (next.length < 2) break
            result = next
        }
        return result
    }

    /** 系统词典分词（ICU）：仅用于无分隔符的连续中文片名，设备不支持或异常时返回空 */
    private fun segmentByDictionary(text: String): List<String> {
        if (text.length < 3) return emptyList()
        return try {
            val iterator = BreakIterator.getWordInstance(Locale.SIMPLIFIED_CHINESE)
            iterator.setText(text)
            val words = ArrayList<String>()
            var start = iterator.first()
            var end = iterator.next()
            while (end != BreakIterator.DONE) {
                val word = text.substring(start, end)
                if (word.any { it.isLetterOrDigit() }) words.add(word)
                start = end
                end = iterator.next()
            }
            words
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
