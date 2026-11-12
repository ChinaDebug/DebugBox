package com.tvbox.kotlin.core.player.subtitle

import android.content.Context
import android.text.TextUtils
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.player.MyVideoView
import com.tvbox.kotlin.core.player.subtitle.model.Subtitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * 默认字幕引擎实现。
 *
 * 使用协程完成字幕加载（IO）与定时刷新（主线程），
 * 字幕切换通过 [UIRenderTask] 投递主线程回调。网络字幕落盘缓存到应用缓存目录，
 * 路径记忆写入 SharedPreferences，避免重复选择。
 *
 * @param context 用于缓存目录与 SharedPreferences
 */
class DefaultSubtitleEngine(context: Context) : SubtitleEngine {

    companion object {
        private const val TAG = "DefaultSubtitleEngine"
        private const val REFRESH_INTERVAL = 100L
        private const val ZIMU_DIR = "zimu"
        private const val CACHE_PREF = "subtitle_cache"
    }

    private val appContext = context.applicationContext
    /** 引擎协程作用域：destroy 时取消并重建，保证关闭字幕/视图卸载后仍可重新加载字幕 */
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val uiRenderTask = UIRenderTask(null)
    private val cachePref = appContext.getSharedPreferences(CACHE_PREF, Context.MODE_PRIVATE)

    private var loadJob: Job? = null
    private var refreshJob: Job? = null
    private var subtitles: List<Subtitle>? = null
    private var mediaPlayer: MyVideoView? = null
    private var cacheKey: String? = null

    private var onPrepared: SubtitleEngine.OnSubtitlePreparedListener? = null
    private var onChange: SubtitleEngine.OnSubtitleChangeListener? = null
        set(value) {
            field = value
            uiRenderTask.updateListener(value)
        }
    private var onLoad: SubtitleEngine.OnSubtitleLoadListener? = null

    override fun bindToPlayer(mediaPlayer: MyVideoView?) {
        this.mediaPlayer = mediaPlayer
    }

    override fun setSubtitlePath(path: String?) {
        reset()
        if (path.isNullOrEmpty()) {
            onLoad?.onError("字幕地址无效")
            return
        }
        loadJob = scope.launch {
            val result = SubtitleLoader.loadSubtitle(path)
            val tto = result?.timedTextObject
            val captions = tto?.captions
            if (result == null || tto == null || captions.isNullOrEmpty()) {
                // 加载失败/内容为空时回调 onError，否则搜索弹窗的加载层永不消失（卡在"加载字幕"）
                onLoad?.onError("字幕加载失败")
                return@launch
            }
            subtitles = ArrayList(captions.values)
            notifyPrepared()
            notifyLoadSuccess()
            cacheSubtitle(result, path)
        }
    }

    override fun setSubtitleDelay(milliseconds: Int) {
        if (milliseconds == 0) return
        val list = subtitles ?: return
        list.forEach { subtitle ->
            subtitle.start.mseconds = (subtitle.start.mseconds + milliseconds).coerceAtLeast(0)
            subtitle.end.mseconds = (subtitle.end.mseconds + milliseconds).coerceAtLeast(0)
        }
    }

    override fun setPlaySubtitleCacheKey(cacheKey: String?) {
        this.cacheKey = cacheKey
    }

    override fun getPlaySubtitleCacheKey(): String? = cacheKey

    override fun reset() {
        stop()
        subtitles = null
    }

    override fun start() {
        if (mediaPlayer == null) return
        stop()
        refreshJob = scope.launch {
            while (isActive) {
                val pos = mediaPlayer?.currentPosition ?: break
                val sub = SubtitleFinder.find(pos, subtitles)
                uiRenderTask.execute(sub)
                val next = if (sub != null) {
                    (sub.end.mseconds.toLong() - pos).coerceAtLeast(50L)
                } else {
                    REFRESH_INTERVAL
                }
                delay(next)
            }
        }
    }

    override fun pause() = stop()

    override fun resume() = start()

    override fun stop() {
        refreshJob?.cancel()
        refreshJob = null
    }

    override fun destroy() {
        loadJob?.cancel()
        refreshJob?.cancel()
        loadJob = null
        refreshJob = null
        subtitles = null
        scope.cancel()
        // 重建作用域，保证"关闭字幕后再选字幕"仍能正常加载
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    override fun setOnSubtitlePreparedListener(listener: SubtitleEngine.OnSubtitlePreparedListener?) {
        onPrepared = listener
    }

    override fun setOnSubtitleChangeListener(listener: SubtitleEngine.OnSubtitleChangeListener?) {
        onChange = listener
    }

    override fun setOnSubtitleLoadListener(listener: SubtitleEngine.OnSubtitleLoadListener?) {
        onLoad = listener
    }

    /** 清除当前缓存键对应的字幕记忆并清理网络字幕临时文件 */
    fun clearCache() {
        val key = cacheKey
        if (!key.isNullOrEmpty()) {
            cachePref.edit().remove(md5(key)).apply()
        }
        cacheKey = ""
        runCatching {
            File(appContext.cacheDir, ZIMU_DIR).deleteRecursively()
        }.onFailure { LOG.e(TAG, "清理字幕缓存目录失败", it) }
    }

    private fun cacheSubtitle(result: SubtitleLoadSuccessResult, path: String) {
        val key = cacheKey
        if (key.isNullOrEmpty()) return
        val md5 = md5(key)
        if (path.startsWith("http://") || path.startsWith("https://")) {
            val dir = File(appContext.cacheDir, ZIMU_DIR).apply { if (!exists()) mkdirs() }
            val cacheFile = File(dir, result.fileName.substringAfterLast("/"))
            runCatching {
                cacheFile.writeBytes(result.content.toByteArray())
                cachePref.edit().putString(md5, cacheFile.absolutePath).apply()
            }.onFailure { LOG.e(TAG, "落盘网络字幕失败", it) }
        } else {
            cachePref.edit().putString(md5, path).apply()
        }
    }

    /** 根据 cacheKey 读取记忆的字幕路径，未缓存返回 null */
    fun getCachedSubtitlePath(key: String?): String? {
        if (key.isNullOrEmpty()) return null
        return cachePref.getString(md5(key), null)
    }

    private fun notifyPrepared() {
        onPrepared?.onSubtitlePrepared(subtitles)
    }

    private fun notifyLoadSuccess() {
        onLoad?.onSuccess()
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
