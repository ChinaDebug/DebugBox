package com.tvbox.kotlin.core.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.asDrawable
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.crossfade
import coil3.size.Scale
import coil3.svg.SvgDecoder
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tvbox.kotlin.core.common.util.DefaultConfig
import com.tvbox.kotlin.core.common.util.UA
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import java.io.IOException
import java.net.URL

/**
 * Coil 图片加载封装。
 * URL 处理：剥离内嵌请求头语法（@Headers=/@Cookie=/@User-Agent=/@Referer=），
 * 豆瓣图床自动附加 referer 与 UA，未指定 UA 时使用随机 UA，附带 HOST 头。
 */
object CoilUtils {

    /** 内存缓存命中后替换 coil_request_tag 的哨兵对象，使在途旧请求的 stale 检查必然失败 */
    private val MEM_HIT_SENTINEL = Any()

    /** 失败 URL 短期记忆（URL → 失败时间戳），避免失效图床反复重试导致闪回占位图 */
    private val failedUrls = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 失败后重试间隔，间隔内再次加载同 URL 直接显示占位图 */
    private const val FAIL_RETRY_INTERVAL_MS = 5 * 60 * 1000L

    /** 解码尺寸上限，限制图床原图（常见 2000px+）避免内存暴涨与解码卡顿 */
    private const val DEFAULT_MAX_DECODE_SIZE = 768

    fun load(
        context: Context,
        view: ImageView,
        url: String?,
        placeholder: Int = R.drawable.img_loading_placeholder,
        error: Int = R.drawable.img_loading_placeholder,
        crossfade: Boolean = true,
        scale: Scale = Scale.FILL,
        radius: Int = 0,
        maxDecodeSize: Int = DEFAULT_MAX_DECODE_SIZE,
        onSuccess: ((Drawable) -> Unit)? = null,
        onFail: (() -> Unit)? = null
    ) {
        val (cleanUrl, headers) = parseImageUrl(context, url)
        // 圆角裁剪先于所有分支执行，保证各加载路径圆角一致
        if (radius > 0) {
            applyRoundedOutline(view, radius)
        }
        if (cleanUrl.isBlank()) {
            if (error != 0) view.setImageResource(error)
            onFail?.invoke()
            return
        }
        // 无协议头的相对路径（如 /db.php?url=...）无法加载；本地文件缓存（file://）可加载
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://") &&
            !cleanUrl.startsWith("data:") && !cleanUrl.startsWith("file:")
        ) {
            if (error != 0) view.setImageResource(error)
            onFail?.invoke()
            return
        }
        // 短期内失败过的 URL 不再重试，直接显示占位图
        val failTime = failedUrls[cleanUrl]
        if (failTime != null) {
            if (System.currentTimeMillis() - failTime < FAIL_RETRY_INTERVAL_MS) {
                if (error != 0) view.setImageResource(error)
                onFail?.invoke()
                return
            }
            failedUrls.remove(cleanUrl)
        }
        val loader = ImageLoaderFactory.loader(context)
        // 内存缓存命中时同步显示，复用/回原位零闪烁
        try {
            val memImage = loader.memoryCache?.get(MemoryCache.Key(cleanUrl))?.image
            if (memImage != null) {
                // 取消在途旧请求并替换请求标记，防止旧回调稍后覆盖本次缓存图（错图根源）
                (view.getTag(R.id.coil_request_tag) as? coil3.request.Disposable)?.dispose()
                view.setTag(R.id.coil_request_tag, MEM_HIT_SENTINEL)
                val drawable = memImage.asDrawable(context.resources)
                view.setTag(R.id.img_url_tag, cleanUrl)
                view.setImageDrawable(drawable)
                onSuccess?.invoke(drawable)
                return
            }
        } catch (_: Throwable) {
        }
        val (dw, dh) = resolveDecodeSize(view, maxDecodeSize)
        val requestKey = cleanUrl
        var selfDisposable: coil3.request.Disposable? = null
        val builder = ImageRequest.Builder(context)
            .data(cleanUrl)
            .size(dw, dh)
            .scale(scale)
            .target(
                onStart = {
                    // 数据强一致：仅显示当前条目的海报或占位图，绝不保留复用残留的其他条目图片；
                    // 仅同 URL 重载（view 已显示该图）时保留不闪
                    if (view.getTag(R.id.img_url_tag) != requestKey && placeholder != 0) {
                        view.setImageResource(placeholder)
                    }
                },
                onSuccess = { image ->
                    // 仅当前活跃请求可上图，防止 item 复用后旧请求（在途响应）覆盖新图
                    if (view.getTag(R.id.coil_request_tag) === selfDisposable) {
                        view.setTag(R.id.img_url_tag, requestKey)
                        val drawable = image.asDrawable(context.resources)
                        view.setImageDrawable(drawable)
                        onSuccess?.invoke(drawable)
                    }
                },
                onError = {
                    if (view.getTag(R.id.coil_request_tag) === selfDisposable) {
                        failedUrls[cleanUrl] = System.currentTimeMillis()
                        val currentTag = view.getTag(R.id.img_url_tag)
                        if (currentTag != requestKey) {
                            // 首次加载失败或复用残留旧图失败：显示占位图，避免错图
                            view.setTag(R.id.img_url_tag, null)
                            if (error != 0) view.setImageResource(error)
                        }
                        onFail?.invoke()
                    }
                    // 过期失败回调（item 已复用绑定新请求）直接忽略
                }
            )
        if (headers.isNotEmpty()) {
            val headersBuilder = NetworkHeaders.Builder()
            for ((key, value) in headers) headersBuilder.set(key, value)
            builder.httpHeaders(headersBuilder.build())
        }
        if (crossfade) builder.crossfade(true)
        (view.getTag(R.id.coil_request_tag) as? coil3.request.Disposable)?.dispose()
        val disposable = loader.enqueue(builder.build())
        selfDisposable = disposable
        view.setTag(R.id.coil_request_tag, disposable)
    }

    /** 同步加载 Drawable（供预加载协程使用） */
    suspend fun loadDrawable(
        context: Context,
        url: String?,
        maxDecodeSize: Int = DEFAULT_MAX_DECODE_SIZE
    ): Drawable? {
        val (cleanUrl, headers) = parseImageUrl(context, url)
        if (cleanUrl.isBlank()) return null
        return try {
            val loader = ImageLoaderFactory.loader(context)
            val builder = ImageRequest.Builder(context)
                .data(cleanUrl)
                .size(maxDecodeSize, maxDecodeSize)
                .scale(Scale.FILL)
            if (headers.isNotEmpty()) {
                val headersBuilder = NetworkHeaders.Builder()
                for ((key, value) in headers) headersBuilder.set(key, value)
                builder.httpHeaders(headersBuilder.build())
            }
            val result = loader.execute(builder.build())
            (result as? coil3.request.SuccessResult)?.image?.asDrawable(context.resources)
        } catch (_: Throwable) {
            null
        }
    }

    /** 加载图片，primary 失败时回退到 fallback（频道台标失败改用订阅地址 logo 字段地址） */
    fun loadWithFallback(
        context: Context,
        view: ImageView,
        primary: String?,
        fallback: String?,
        placeholder: Int,
        error: Int,
        maxDecodeSize: Int = DEFAULT_MAX_DECODE_SIZE
    ) {
        val (cleanUrl, headers) = parseImageUrl(context, primary)
        if (cleanUrl.isBlank()) {
            if (fallback != null) {
                loadWithFallback(context, view, fallback, null, placeholder, error, maxDecodeSize)
            } else if (error != 0) {
                view.setImageResource(error)
            }
            return
        }
        val loader = ImageLoaderFactory.loader(context)
        val (dw, dh) = resolveDecodeSize(view, maxDecodeSize)
        val builder = ImageRequest.Builder(context)
            .data(cleanUrl)
            .size(dw, dh)
            .scale(Scale.FILL)
            .target(
                onStart = { if (placeholder != 0 && view.drawable == null) view.setImageResource(placeholder) },
                onSuccess = { image -> view.setImageDrawable(image.asDrawable(context.resources)) },
                onError = {
                    if (fallback != null) {
                        loadWithFallback(context, view, fallback, null, placeholder, error, maxDecodeSize)
                    } else if (error != 0) {
                        view.setImageResource(error)
                    }
                }
            )
        if (headers.isNotEmpty()) {
            val headersBuilder = NetworkHeaders.Builder()
            for ((key, value) in headers) headersBuilder.set(key, value)
            builder.httpHeaders(headersBuilder.build())
        }
        (view.getTag(R.id.coil_request_tag) as? coil3.request.Disposable)?.dispose()
        val disposable = loader.enqueue(builder.build())
        view.setTag(R.id.coil_request_tag, disposable)
    }

    fun loadDrawable(
        context: Context,
        url: String?,
        maxDecodeSize: Int = DEFAULT_MAX_DECODE_SIZE,
        onLoad: (Drawable?) -> Unit
    ) {
        val (cleanUrl, headers) = parseImageUrl(context, url)
        if (cleanUrl.isBlank()) {
            onLoad(null)
            return
        }
        val loader = ImageLoaderFactory.loader(context)
        val builder = ImageRequest.Builder(context)
            .data(cleanUrl)
            .size(maxDecodeSize, maxDecodeSize)
            .scale(Scale.FILL)
            .target(
                onStart = { onLoad(null) },
                onSuccess = { image -> onLoad(image.asDrawable(context.resources)) },
                onError = { onLoad(null) }
            )
        if (headers.isNotEmpty()) {
            val headersBuilder = NetworkHeaders.Builder()
            for ((key, value) in headers) headersBuilder.set(key, value)
            builder.httpHeaders(headersBuilder.build())
        }
        loader.enqueue(builder.build())
    }

    /** 按 ImageView 已测量尺寸计算解码尺寸，未布局时用上限，避免加载原图 */
    private fun resolveDecodeSize(view: View, maxDecodeSize: Int): Pair<Int, Int> {
        val w = if (view.width > 0) view.width.coerceAtMost(maxDecodeSize) else maxDecodeSize
        val h = if (view.height > 0) view.height.coerceAtMost(maxDecodeSize) else maxDecodeSize
        return w to h
    }

    /** 按屏幕像素设置 ImageView 圆角裁剪，与卡片背景 shape 圆角保持一致 */
    private fun applyRoundedOutline(view: ImageView, radius: Int) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                val left = v.paddingLeft
                val top = v.paddingTop
                val right = v.width - v.paddingRight
                val bottom = v.height - v.paddingBottom
                if (right > left && bottom > top) {
                    outline.setRoundRect(left, top, right, bottom, radius.toFloat())
                }
            }
        }
    }

    /** 解析图片 URL：剥离内嵌请求头语法，返回（干净 URL，请求头表） */
    private fun parseImageUrl(context: Context, url: String?): Pair<String, Map<String, String>> {
        if (url.isNullOrBlank()) return "" to emptyMap()
        // 相对路径图片（./config/xxx.jpg）先基于配置目录补全为完整 http URL
        var u = DefaultConfig.fixImageUrl(DefaultConfig.checkReplaceProxy(url.trim()))
        if (u.startsWith("data:")) return u to emptyMap()

        var header: String? = null
        var referer: String? = null
        var ua = UA.random(context)
        var cookie: String? = null

        if (u.contains("doubanio.com") && !u.contains("@Referer=") && !u.contains("@User-Agent=")) {
            u += "@Referer=https://api.douban.com/@User-Agent=$ua"
        }
        if (u.contains("@Headers=")) {
            header = u.split("@Headers=")[1].split("@")[0].let {
                try {
                    java.net.URLDecoder.decode(it, "UTF-8")
                } catch (_: Throwable) {
                    it
                }
            }
        }
        if (u.contains("@Cookie=")) cookie = u.split("@Cookie=")[1].split("@")[0]
        if (u.contains("@User-Agent=")) ua = u.split("@User-Agent=")[1].split("@")[0]
        if (u.contains("@Referer=")) referer = u.split("@Referer=")[1].split("@")[0]
        u = u.split("@")[0]
        if (u.isBlank()) return "" to emptyMap()

        val headers = HashMap<String, String>()
        if (!header.isNullOrBlank()) {
            try {
                val json: JsonObject = Gson().fromJson(header, JsonObject::class.java)
                json.keySet().forEach { key ->
                    json.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.let { headers[key] = it }
                }
            } catch (_: Throwable) {
                // header 非 JSON 时忽略，走默认头
            }
        } else {
            if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
            headers["User-Agent"] = ua
            if (!referer.isNullOrBlank()) headers["Referer"] = referer
        }
        try {
            URL(u).host?.let { headers["Host"] = it }
        } catch (_: Throwable) {
        }
        return u to headers
    }

}

/** 预览 body 字节上限：过期占位 SVG 通常不足 1KB */
private const val SVG_PREVIEW_BYTES = 4096

/** 拦截图床返回的"已过期/失效"占位 SVG（小尺寸 + 提示文字），视为加载失败，回落到项目占位图 */
private class ExpiredSvgInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        val contentType = response.header("Content-Type") ?: ""
        if (contentType.contains("svg", ignoreCase = true)) {
            val content = try {
                response.peekBody(SVG_PREVIEW_BYTES.toLong()).string()
            } catch (_: Throwable) {
                ""
            }
            if (isExpiredPlaceholderSvg(content)) {
                response.close()
                throw IOException("expired placeholder svg")
            }
        }
        return response
    }
}

/** 判断是否为过期占位 SVG：小尺寸（宽/高 ≤128px）且包含提示文字 */
private fun isExpiredPlaceholderSvg(content: String): Boolean {
    if (content.isBlank()) return false
    if (!content.contains("<svg", ignoreCase = true)) return false
    if (!content.contains("<text", ignoreCase = true)) return false
    val dims = mutableListOf<Int>()
    Regex("width=\"(\\d+)\"").findAll(content).forEach {
        it.groupValues[1].toIntOrNull()?.let(dims::add)
    }
    Regex("height=\"(\\d+)\"").findAll(content).forEach {
        it.groupValues[1].toIntOrNull()?.let(dims::add)
    }
    if (dims.isEmpty()) {
        Regex("viewBox=\"([^\"]+)\"").find(content)?.groupValues?.get(1)?.trim()
            ?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }
            ?.takeLast(2)?.forEach { dims.add(it.toInt()) }
    }
    val maxDim = dims.maxOrNull() ?: return false
    return maxDim <= 128
}

/** 图片加载器工厂，按需缓存单例。Coil 3.x 默认不支持网络加载，需显式装配 OkHttp 网络组件 */
object ImageLoaderFactory {

    /** PauseInterceptor 挂起等待上限，超过后即使暂停标志残留也强制放行 */
    private const val PAUSE_MAX_WAIT_MS = 3_000L

    @Volatile
    private var cached: ImageLoader? = null

    /** 全局图片请求暂停标志：置 true 时新请求挂起等待，置 false 恢复 */
    @Volatile
    var paused: Boolean = false
        private set

    fun setPaused(value: Boolean) {
        paused = value
    }

    /** 清空内存缓存（已解码位图），配合磁盘缓存清理使用 */
    fun clearMemoryCache() {
        cached?.memoryCache?.clear()
    }

    fun loader(context: Context): ImageLoader {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val loader = ImageLoader.Builder(context.applicationContext)
                .components {
                    // 独立 OkHttpClient：传 UA/Referer/Host 等头，并拦截"已过期"占位图
                    add(OkHttpNetworkFetcherFactory(
                        OkHttpClient.Builder()
                            .addInterceptor(ExpiredSvgInterceptor())
                            .build()
                    ))
                    // 列表快速滚动时暂停新请求，停止后恢复，避免滚动过程持续解码
                    add(PauseInterceptor())
                    // 图床常见返回 SVG，Coil 3 默认不含该解码器
                    add(SvgDecoder.Factory())
                }
                .memoryCache {
                    // 网格一屏 40+ 张海报，默认容量会被 LRU 频繁逐出导致回原位重载闪回；
                    // 扩大后复用/回原位直接命中同步显示
                    MemoryCache.Builder()
                        .maxSizePercent(context, 0.3)
                        .build()
                }
                .diskCache {
                    // 内存缓存被逐出时从磁盘解码（几十毫秒）而非重新走网络，减少闪回
                    DiskCache.Builder()
                        .directory(context.applicationContext.cacheDir.resolve("image_cache").absolutePath.toPath())
                        .maxSizeBytes(512L * 1024 * 1024)
                        .build()
                }
                .crossfade(true)
                .build()
            cached = loader
            return loader
        }
    }

    /** 拦截器：全局暂停标志为 true 时阻塞新请求直到恢复（已开始的请求不受影响） */
    private class PauseInterceptor : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            // 暂停标志残留时（页面滚动中切走仅 onStop 未触发 detach）请求会无限挂起，
            // 加超时兜底强制放行，避免图片永久停在占位图
            val start = System.currentTimeMillis()
            while (paused && System.currentTimeMillis() - start < PAUSE_MAX_WAIT_MS) {
                delay(30)
            }
            return chain.proceed()
        }
    }
}

/**
 * 列表滚动时暂停全局图片加载（新请求挂起等待），停止后延迟恢复。
 * 配合 [ImageLoaderFactory] 的 PauseInterceptor，避免快速滚动时持续解码导致卡顿。
 */
fun RecyclerView.pauseImagesOnScroll() {
    var resumeRunnable: Runnable? = null

    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            resumeRunnable?.let { recyclerView.removeCallbacks(it) }
            resumeRunnable = null
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                // 延迟恢复：滚动动画未完全结束前立即恢复会触发屏幕边缘 item 加载，
                // 用户继续滚动时又变回占位图，造成闪烁
                val r = Runnable { ImageLoaderFactory.setPaused(false) }
                resumeRunnable = r
                recyclerView.postDelayed(r, 120)
            } else {
                ImageLoaderFactory.setPaused(true)
            }
        }
    })
    // 页面销毁/切后台时立即恢复暂停标志并取消延迟任务，
    // 否则滚动中销毁页面会残留 paused=true，后续所有页面图片只显示占位图
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            resumeRunnable?.let { v.removeCallbacks(it) }
            resumeRunnable = null
            ImageLoaderFactory.setPaused(false)
        }
    })
}
