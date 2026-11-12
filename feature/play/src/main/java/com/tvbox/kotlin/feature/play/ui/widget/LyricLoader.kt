package com.tvbox.kotlin.feature.play.ui.widget

import android.net.Uri
import com.tvbox.kotlin.core.network.OkHttpHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 歌词加载工具：按播放地址生成同名 .lrc 并异步加载解析。
 * 供 PlayActivity（全屏播放）与 PlayFragment（详情页预览窗/全屏）复用。
 */
object LyricLoader {

    /** 构造同名歌词地址：原 URL 路径扩展名替换为 .lrc（无扩展名则追加），并去掉 query */
    fun buildLrcUrl(url: String): String? {
        return runCatching {
            val parsed = Uri.parse(url)
            if (parsed.scheme.isNullOrEmpty() || parsed.host.isNullOrEmpty()) return null
            val path = parsed.path ?: return null
            val slash = path.lastIndexOf('/')
            val dot = path.lastIndexOf('.')
            val newPath = if (dot > slash) path.substring(0, dot) + ".lrc" else "$path.lrc"
            parsed.buildUpon().path(newPath).clearQuery().build().toString()
        }.getOrNull()
    }

    /**
     * 异步加载同名 LRC 歌词并解析，成功后回调解析结果（主线程）。
     * 加载失败/无歌词时回调空列表，不抛异常（多数源不提供歌词，静默即可）。
     * @param scope 宿主协程作用域（lifecycleScope），返回的 Job 供宿主在销毁/切集时取消
     */
    fun load(
        scope: CoroutineScope,
        okHttpHelper: OkHttpHelper,
        headers: Map<String, String>?,
        url: String,
        onLoaded: (List<LrcLine>) -> Unit
    ): Job {
        return scope.launch(Dispatchers.IO) {
            val lrcUrl = buildLrcUrl(url)
            val text = if (lrcUrl == null) "" else try {
                val builder = Request.Builder().url(lrcUrl).get()
                headers?.forEach { (k, v) -> builder.header(k, v) }
                okHttpHelper.client().newCall(builder.build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
                }
            } catch (_: Throwable) {
                ""
            }
            val parsed = if (text.isBlank()) emptyList() else LrcParser.parse(text)
            withContext(Dispatchers.Main) {
                onLoaded(parsed)
            }
        }
    }
}
