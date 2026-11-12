package com.tvbox.kotlin.core.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.bean.AbsJson
import com.tvbox.kotlin.core.common.bean.VodInfo
import com.tvbox.kotlin.core.common.event.RefreshEvent
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.common.util.HistoryHelper
import com.tvbox.kotlin.core.data.repo.RoomDataManger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** 视频更新检测配置键（DataStore 存储，键名与 Hawk 保持一致便于迁移） */
object VideoUpdateKeys {
    const val ENABLE = "update_check_enable"
    const val STARTUP = "update_check_startup"
    const val INTERVAL = "update_check_interval"
    const val WIFI_ONLY = "update_check_wifi_only"
    const val LAST_TIME = "update_check_last_time"
}

/** 详情抓取契约，由 :app 实现注入，避免 core:data 反向依赖 core:spider */
interface VideoDetailFetcher {
    /** 返回详情 JSON 字符串，失败返回 null */
    suspend fun fetchDetail(sourceKey: String, vodId: String): String?
}

/** 更新检测结果监听 */
interface VideoUpdateListener {
    fun onCheckComplete(hasUpdate: Boolean, updates: Map<String, Boolean>)
    fun onCheckProgress(current: Int, total: Int)
    fun onCheckError(message: String)
}

/**
 * 历史记录视频更新检测器。
 *
 * - 并发抓取历史影片详情，对比当前集数与已看集数判断是否有更新；
 * - 支持开关/启动检测/检测间隔/仅 WiFi 配置；
 * - 定时调度采用协程 delay；
 * - 结果缓存线程安全，供历史/首页徽章实时查询。
 */
@Singleton
class VideoUpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val roomDataManger: RoomDataManger,
    private val detailFetcher: VideoDetailFetcher
) {

    private val gson = Gson()

    /** 检测结果缓存：sourceKey_vodId -> 是否有更新 */
    private val updateCache = ConcurrentHashMap<String, Boolean>()

    /** 监听器集合 */
    private val listeners = CopyOnWriteArrayList<VideoUpdateListener>()

    /** 主线程通知作用域 */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 后台检测作用域 */
    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 检测中标记，避免并发触发 */
    private val isChecking = AtomicInteger(0)

    /** 定时调度任务 */
    private var scheduledJob: Job? = null

    // ------------------------------------------------------------------
    // 配置读写
    // ------------------------------------------------------------------

    fun isEnable(): Boolean = preferences.getBoolean(VideoUpdateKeys.ENABLE, true)

    suspend fun setEnable(enable: Boolean) {
        preferences.put(VideoUpdateKeys.ENABLE, enable)
        if (!enable) {
            stopScheduledCheck()
            clearCache()
        } else if (getCheckInterval() != INTERVAL_OFF) {
            startScheduledCheck()
        }
    }

    fun isStartupCheck(): Boolean = preferences.getBoolean(VideoUpdateKeys.STARTUP, true)

    suspend fun setStartupCheck(startup: Boolean) {
        preferences.put(VideoUpdateKeys.STARTUP, startup)
    }

    fun getCheckInterval(): Int = preferences.getInt(VideoUpdateKeys.INTERVAL, INTERVAL_OFF)

    suspend fun setCheckInterval(intervalMinutes: Int) {
        val oldInterval = getCheckInterval()
        preferences.put(VideoUpdateKeys.INTERVAL, intervalMinutes)
        if (intervalMinutes == INTERVAL_OFF) {
            stopScheduledCheck()
        } else if (oldInterval == INTERVAL_OFF && isEnable()) {
            startScheduledCheck()
        }
    }

    fun isWifiOnly(): Boolean = preferences.getBoolean(VideoUpdateKeys.WIFI_ONLY, false)

    suspend fun setWifiOnly(wifiOnly: Boolean) {
        preferences.put(VideoUpdateKeys.WIFI_ONLY, wifiOnly)
    }

    private suspend fun getLastCheckTime(): Long =
        preferences.getLong(VideoUpdateKeys.LAST_TIME, 0L)

    private fun getMinIntervalMs(): Long {
        val interval = getCheckInterval()
        return if (interval == INTERVAL_OFF) Long.MAX_VALUE else interval * 60_000L
    }

    // ------------------------------------------------------------------
    // 结果查询
    // ------------------------------------------------------------------

    /** 是否已检测出任意影片更新（首页历史徽章用） */
    fun hasUpdate(): Boolean = updateCache.values.any { it }

    fun isCheckingNow(): Boolean = isChecking.get() != 0

    fun hasVideoUpdate(sourceKey: String?, vodId: String?): Boolean {
        if (sourceKey.isNullOrEmpty() || vodId.isNullOrEmpty()) return false
        return updateCache["${sourceKey}_$vodId"] == true
    }

    /** 播放页记录"已看到最新集"或"仍有更新"状态 */
    fun setVideoUpdate(sourceKey: String?, vodId: String?, hasUpdate: Boolean) {
        if (sourceKey.isNullOrEmpty() || vodId.isNullOrEmpty()) return
        updateCache["${sourceKey}_$vodId"] = hasUpdate
    }

    /** 删除历史时同步清除对应更新标记 */
    fun clearVideoUpdate(sourceKey: String?, vodId: String?) {
        if (sourceKey.isNullOrEmpty() || vodId.isNullOrEmpty()) return
        updateCache.remove("${sourceKey}_$vodId")
    }

    // ------------------------------------------------------------------
    // 监听器
    // ------------------------------------------------------------------

    fun addListener(listener: VideoUpdateListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: VideoUpdateListener) {
        listeners.remove(listener)
    }

    // ------------------------------------------------------------------
    // 检测调度
    // ------------------------------------------------------------------

    /** 是否具备检测条件：开关打开且（非仅 WiFi 或当前为 WiFi） */
    fun canCheck(): Boolean {
        if (!isEnable()) return false
        if (isWifiOnly() && !isWifiConnected()) return false
        return true
    }

    /** 距上次检测是否超过配置间隔 */
    suspend fun shouldCheck(): Boolean {
        if (!canCheck()) return false
        return System.currentTimeMillis() - getLastCheckTime() >= getMinIntervalMs()
    }

    /**
     * 触发一次检测。
     * @return true 表示本次检测已启动
     */
    suspend fun startCheck(force: Boolean = false, highConcurrency: Boolean = false): Boolean {
        if (!force && !shouldCheck()) return false
        if (!isChecking.compareAndSet(0, 1)) return false
        // 看门狗：整体检测超时（历史较多或个别源响应慢）后强制复位"检测中"标记并通知，
        // 避免界面一直显示"正在检测更新"（首页历史图标一直转圈）
        val watchdog = workerScope.launch {
            delay(CHECK_WATCHDOG_MS)
            if (isChecking.compareAndSet(1, 0)) {
                notifyComplete(updateCache.values.any { it }, HashMap(updateCache))
            }
        }
        workerScope.launch {
            try {
                doCheck(highConcurrency)
            } catch (e: Exception) {
                LOG.e(e)
                val errMsg = e.message ?: context.getString(com.tvbox.kotlin.core.data.R.string.unknown_error_fallback)
                notifyError(context.getString(com.tvbox.kotlin.core.data.R.string.update_check_failed, errMsg))
            } finally {
                // 仅复位本次检测占用的标记：若看门狗已提前复位（doCheck 卡在不可取消的阻塞调用），
                // 不要误清后续新检测刚置位的标记
                isChecking.compareAndSet(1, 0)
                watchdog.cancel()
            }
        }
        return true
    }

    /** 启动定时检测（按配置间隔循环，间隔关闭时每分钟探测一次配置） */
    fun startScheduledCheck() {
        if (scheduledJob?.isActive == true) return
        scheduledJob = workerScope.launch {
            while (isActive) {
                val interval = getCheckInterval()
                if (interval == INTERVAL_OFF || !canCheck()) {
                    delay(60_000L)
                    continue
                }
                val elapsed = System.currentTimeMillis() - getLastCheckTime()
                val intervalMs = interval * 60_000L
                if (elapsed >= intervalMs) {
                    startCheck(force = true)
                    delay(intervalMs)
                } else {
                    delay(intervalMs - elapsed)
                }
            }
        }
    }

    fun stopScheduledCheck() {
        scheduledJob?.cancel()
        scheduledJob = null
    }

    /** 清除结果缓存并重置检测时间，切换源/清空历史时调用 */
    suspend fun clearCache() {
        updateCache.clear()
        preferences.put(VideoUpdateKeys.LAST_TIME, 0L)
        EventHub.post(RefreshEvent(RefreshEvent.TYPE_HISTORY))
    }

    /** 重置检测中标记与检测时间，允许更换接口后立即检测 */
    suspend fun resetCheckState() {
        isChecking.set(0)
        preferences.put(VideoUpdateKeys.LAST_TIME, 0L)
    }

    /**
     * 释放资源：停止定时调度、复位检测中标记并清空监听器。
     *
     * 注意：不取消 [workerScope]/[mainScope]。本类是进程级单例，Activity 退出
     * （finishAffinity）后进程可能仍存活；若在此取消协程作用域，重新打开 App 时
     * 检测协程（doCheck/看门狗）与通知（notifyComplete）全部失效，表现为
     * isChecking=1 但检测不执行、界面一直卡"正在检测更新"且看门狗 60 秒也不触发。
     * 作用域由进程回收时自然终止。
     */
    fun shutdown() {
        stopScheduledCheck()
        // 复位检测中标记：退出时检测未完成的话 isChecking 残留 1，
        // 下次打开 App startCheck 的 CAS 会失败导致检测永不启动
        isChecking.set(0)
        listeners.clear()
    }

    // ------------------------------------------------------------------
    // 检测核心
    // ------------------------------------------------------------------

    private suspend fun doCheck(highConcurrency: Boolean) {
        val limit = HistoryHelper.getHisNum(preferences.getInt(AppConstants.HOME_NUM, 0))
        val historyList = roomDataManger.getAllVodRecord(limit)
        if (historyList.isEmpty()) {
            // 无历史记录时仍记录检测时间并清空结果缓存，避免每次启动/定时都重复触发检测
            updateCache.clear()
            preferences.put(VideoUpdateKeys.LAST_TIME, System.currentTimeMillis())
            finishCheck()
            return
        }
        val toCheckList = historyList.filterNot { isFinished(it) }
        if (toCheckList.isEmpty()) {
            updateCache.clear()
            preferences.put(VideoUpdateKeys.LAST_TIME, System.currentTimeMillis())
            finishCheck()
            return
        }
        // 先记录检测时间，避免定时调度在检测期间再次触发
        preferences.put(VideoUpdateKeys.LAST_TIME, System.currentTimeMillis())

        val total = toCheckList.size
        val completed = AtomicInteger(0)
        val newCache = ConcurrentHashMap<String, Boolean>()
        val semaphore = Semaphore(if (highConcurrency) CONCURRENCY_HIGH else CONCURRENCY_DEFAULT)

        // 抓取任务放入 workerScope（非结构化）：详情抓取可能阻塞在不可取消的同步调用上，
        // 结构化等待会让总超时后的收尾被在途子协程拖住；在超时窗口内 join，超时后立即收尾
        val jobs = toCheckList.map { vodInfo ->
            workerScope.launch {
                semaphore.withPermit {
                    val key = "${vodInfo.sourceKey}_${vodInfo.id}"
                    // null 表示抓取/解析失败，不写入缓存，保留旧状态避免误标"无更新"
                    val updated: Boolean? = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                        runCatching { checkVideoUpdate(vodInfo) }.getOrNull()
                    }
                    if (updated != null) {
                        newCache[key] = updated
                    }
                    notifyProgress(completed.incrementAndGet(), total)
                }
            }
        }

        withTimeoutOrNull(TOTAL_CHECK_TIMEOUT_MS) {
            jobs.forEach { it.join() }
        }
        // 总超时后取消残余任务（在取消点中断，不再回调进度），
        // 避免其稍后完成时再次触发 UI 转圈/进度提示
        jobs.forEach { it.cancel() }

        // 仅更新本次成功检测的记录，失败记录保留原缓存状态
        newCache.forEach { (key, updated) -> updateCache[key] = updated }
        finishCheck()
    }

    private suspend fun finishCheck() {
        val hasUpdate = updateCache.values.any { it }
        EventHub.post(RefreshEvent(RefreshEvent.TYPE_HISTORY))
        notifyComplete(hasUpdate, HashMap(updateCache))
    }

    /** 抓取详情并对比集数，判定当前影片是否有更新。null 表示抓取/解析失败无法判定 */
    private suspend fun checkVideoUpdate(savedVodInfo: VodInfo): Boolean? {
        val sourceKey = savedVodInfo.sourceKey ?: return null
        val vodId = savedVodInfo.id ?: return null
        val json = detailFetcher.fetchDetail(sourceKey, vodId) ?: return null

        val absJson = runCatching {
            gson.fromJson<AbsJson>(json, object : TypeToken<AbsJson>() {}.type)
        }.getOrNull() ?: return null
        val jsonVod = absJson.list?.firstOrNull() ?: return null
        if (jsonVod.vod_play_url.isNullOrEmpty()) return null

        // 优先按集名数字对比，过滤特别篇/预告等干扰
        val currentEpisodeNumber = parseCurrentEpisodeNumber(
            jsonVod, savedVodInfo.playFlag, savedVodInfo.playEpisodeName
        )
        if (currentEpisodeNumber > 0) {
            val playedEpisodeNumber = extractEpisodeNumberRaw(savedVodInfo.playEpisodeName)
            if (playedEpisodeNumber > 0) {
                return currentEpisodeNumber > playedEpisodeNumber
            }
        }

        // 回退到按总集数对比
        val currentTotalEpisodes = parseTotalEpisodes(jsonVod, savedVodInfo.playFlag)
        if (currentTotalEpisodes <= 0) return null

        var playIndex = maxOf(savedVodInfo.playIndex, savedVodInfo.playEpisodeIndex)
        if (playIndex <= 0 && !savedVodInfo.playNote.isNullOrEmpty()) {
            playIndex = extractEpisodeNumber(savedVodInfo.playNote)
        }
        if (playIndex >= 0) {
            return currentTotalEpisodes > playIndex + 1
        }
        val savedTotalEpisodes = savedVodInfo.totalEpisodes
        return savedTotalEpisodes > 0 && currentTotalEpisodes > savedTotalEpisodes
    }

    /** 判断记录是否已看完（从未播放过的记录不参与检测） */
    private fun isFinished(vodInfo: VodInfo): Boolean {
        var playIndex = maxOf(vodInfo.playIndex, vodInfo.playEpisodeIndex)
        if (playIndex <= 0 && !vodInfo.playNote.isNullOrEmpty()) {
            playIndex = extractEpisodeNumber(vodInfo.playNote)
            // 有播放进度但无法解析出集数时按"未看完"处理，避免漏检
            if (playIndex < 0) return false
        }
        return playIndex < 0
    }

    // ------------------------------------------------------------------
    // 集数解析
    // ------------------------------------------------------------------

    private fun extractEpisodeNumber(playNote: String?): Int {
        if (playNote.isNullOrEmpty()) return -1
        val number = extractEpisodeNumberRaw(playNote)
        return if (number > 0) number - 1 else -1
    }

    /** 解析线路中当前最新集数，按已看集名推断命名模式并过滤干扰项 */
    private fun parseCurrentEpisodeNumber(
        jsonVod: AbsJson.AbsJsonVod,
        playFlag: String?,
        playedEpisodeName: String?
    ): Int {
        try {
            val playUrl = jsonVod.vod_play_url
            val playFrom = jsonVod.vod_play_from
            if (playFlag.isNullOrEmpty() || playFrom.isNullOrEmpty() || playUrl.isNullOrEmpty()) return 0
            val playUrls = playUrl.split("\\$\\$\\$".toRegex())
            val playFlags = playFrom.split("\\$\\$\\$".toRegex())
            val targetPlayUrl = playFlags.indices.firstOrNull { playFlags[it] == playFlag }
                ?.let { playUrls.getOrNull(it) } ?: return 0
            if (targetPlayUrl.isEmpty()) return 0

            val pattern = inferEpisodePattern(playedEpisodeName)
            val episodes = if (targetPlayUrl.contains("#")) targetPlayUrl.split("#") else listOf(targetPlayUrl)
            var maxNumber = 0
            for (episode in episodes) {
                val episodeName = extractEpisodeName(episode) ?: continue
                if (pattern != null && !pattern.matches(episodeName)) continue
                if (pattern == null && isNoiseEpisode(episodeName)) continue
                val number = extractEpisodeNumberRaw(episodeName)
                if (number > maxNumber) maxNumber = number
            }
            return maxNumber
        } catch (e: Exception) {
            LOG.e(e)
            return 0
        }
    }

    private fun parseTotalEpisodes(jsonVod: AbsJson.AbsJsonVod, playFlag: String?): Int {
        try {
            val playUrl = jsonVod.vod_play_url
            val playFrom = jsonVod.vod_play_from
            if (playUrl.isNullOrEmpty() || playFlag.isNullOrEmpty() || playFrom.isNullOrEmpty()) {
                return 0
            }
            val playUrls = playUrl.split("\\$\\$\\$".toRegex())
            val playFlags = playFrom.split("\\$\\$\\$".toRegex())
            for (i in playFlags.indices) {
                if (i < playUrls.size && playFlags[i] == playFlag) {
                    return countEpisodes(playUrls[i])
                }
            }
            return 0
        } catch (e: Exception) {
            LOG.e(e)
            return 0
        }
    }

    private fun countEpisodes(playUrl: String): Int {
        if (playUrl.isEmpty()) return 0
        val episodes = if (playUrl.contains("#")) playUrl.split("#") else listOf(playUrl)
        return episodes.count { it.isNotEmpty() }
    }

    private fun extractEpisodeName(episodeUrl: String?): String? {
        if (episodeUrl.isNullOrEmpty()) return null
        val index = episodeUrl.indexOf('$')
        return if (index >= 0) episodeUrl.substring(0, index) else episodeUrl
    }

    /** 根据已看集名推断命名模式，避免片名中的数字被误判 */
    private fun inferEpisodePattern(episodeName: String?): Regex? {
        if (episodeName.isNullOrEmpty()) return null
        return when {
            Regex(".*第[\\d零一二两三四五六七八九十百千万]+[集话章节回期].*").matches(episodeName) ->
                Regex(".*第[\\d零一二两三四五六七八九十百千万]+[集话章节回期].*")
            Regex("(?i).*EP\\d+.*").matches(episodeName) ->
                Regex("(?i).*EP\\d+.*")
            Regex("(?i).*\\bE\\d+\\b.*").matches(episodeName) ->
                Regex("(?i).*\\bE\\d+\\b.*")
            Regex("(?i).*S\\d+E\\d+.*").matches(episodeName) ->
                Regex("(?i).*S\\d+E\\d+.*")
            Regex("(?i).*Vol(?:ume)?\\.?\\s*\\d+.*").matches(episodeName) ->
                Regex("(?i).*Vol(?:ume)?\\.?\\s*\\d+.*")
            Regex(".*_\\d+.*").matches(episodeName) ->
                Regex(".*_\\d+.*")
            Regex("^\\s*-?\\d+\\s*$").matches(episodeName) ->
                Regex("^\\s*-?\\d+\\s*$")
            Regex(".*\\.\\d+\\s*$").matches(episodeName) ->
                Regex(".*\\.\\d+\\s*$")
            Regex(".*\\s\\d+\\s*$").matches(episodeName) ->
                Regex(".*\\s\\d+\\s*$")
            Regex(".*-\\d+\\s*$").matches(episodeName) ->
                Regex(".*-\\d+\\s*$")
            else -> null
        }
    }

    private fun isNoiseEpisode(episodeName: String): Boolean {
        val lower = episodeName.lowercase()
        return EPISODE_NOISE_KEYWORDS.any { lower.contains(it) }
    }

    /** 提取集数数字，支持中文数字，排除疑似年份 */
    private fun extractEpisodeNumberRaw(name: String?): Int {
        if (name.isNullOrEmpty()) return 0
        val trimmed = name.trim()
        for (pattern in EPISODE_NUMBER_PATTERNS) {
            val matcher = pattern.find(trimmed) ?: continue
            val group = matcher.groupValues.getOrNull(1) ?: continue
            if (group.isEmpty()) continue
            val first = group.first()
            return if (first in '0'..'9') group.toIntOrNull() ?: 0
            else chineseNumberToInt(group)
        }
        // 兜底：取最后一个连续数字，排除年份
        var lastNumber = 0
        Regex("\\d+").findAll(trimmed).forEach { match ->
            val number = match.value.toIntOrNull() ?: return@forEach
            if (number in 1900..2099) return@forEach
            lastNumber = number
        }
        return lastNumber
    }

    /** 中文数字转阿拉伯数字，支持零到万级 */
    private fun chineseNumberToInt(chinese: String): Int {
        if (chinese.isEmpty()) return 0
        var result = 0
        var current = 0
        for (c in chinese) {
            val digit = CHINESE_DIGITS[c]
            if (digit != null) {
                current = digit
            } else {
                val unit = CHINESE_UNITS[c]
                if (unit != null) {
                    if (current == 0) current = 1
                    result += current * unit
                    current = 0
                }
            }
        }
        return result + current
    }

    /** 是否处于 WiFi 网络 */
    private fun isWifiConnected(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            LOG.e(e)
            false
        }
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun notifyComplete(hasUpdate: Boolean, updates: Map<String, Boolean>) {
        mainScope.launch {
            listeners.forEach { it.onCheckComplete(hasUpdate, updates) }
        }
    }

    private fun notifyProgress(current: Int, total: Int) {
        mainScope.launch {
            listeners.forEach { it.onCheckProgress(current, total) }
        }
    }

    private fun notifyError(message: String) {
        mainScope.launch {
            listeners.forEach { it.onCheckError(message) }
        }
    }

    companion object {
        const val INTERVAL_OFF = 0
        const val INTERVAL_30MIN = 30
        const val INTERVAL_1HOUR = 60
        const val INTERVAL_2HOURS = 120

        private const val CONCURRENCY_DEFAULT = 5
        private const val CONCURRENCY_HIGH = 8
        private const val CHECK_TIMEOUT_MS = 5_000L
        /** 整体检测总时长上限 45 秒，超时立即收尾复位检测中标记 */
        private const val TOTAL_CHECK_TIMEOUT_MS = 45_000L
        /** 看门狗总时长：超过则强制收尾复位检测中标记，避免历史较多/源响应慢时图标一直转圈 */
        private const val CHECK_WATCHDOG_MS = 60_000L
        private const val POLL_INTERVAL_MS = 60_000L

        /** 集数数字提取正则，按优先级排列 */
        private val EPISODE_NUMBER_PATTERNS = listOf(
            Regex("第([\\d零一二两三四五六七八九十百千万]+)[集话章节回期]"),
            Regex("(?i)EP(\\d+)"),
            Regex("(?i)\\bE(\\d+)\\b"),
            Regex("(?i)S\\d+E(\\d+)"),
            Regex("(?i)Vol(?:ume)?\\.?\\s*(\\d+)"),
            Regex("_(\\d+)\\s*$"),
            Regex("_(\\d+)"),
            Regex("\\.(\\d+)\\s*$"),
            Regex("\\s(\\d+)\\s*$"),
            Regex("^[\\s\\-]*(\\d+)\\s*$"),
            Regex("-(\\d+)\\s*$")
        )

        /** 非主线集数关键字 */
        private val EPISODE_NOISE_KEYWORDS = listOf(
            "预告", "片花", "花絮", "彩蛋", "总集", "ova", "oad", "sp", "pv"
        )

        private val CHINESE_DIGITS = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
            '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )

        private val CHINESE_UNITS = mapOf('十' to 10, '百' to 100, '千' to 1000, '万' to 10000)
    }
}
