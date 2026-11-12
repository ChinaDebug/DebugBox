package com.tvbox.kotlin.feature.play.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ImageSpan
import android.util.LruCache
import com.tvbox.kotlin.core.common.LOG
import com.tvbox.kotlin.core.common.bean.Danmu
import com.tvbox.kotlin.core.common.util.ColorHelper
import com.tvbox.kotlin.core.common.util.HawkUtils
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.DanmakuFactory
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.model.android.SpannedCacheStuffer
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.danmaku.util.DanmakuUtils
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random

/**
 * 弹幕加载与解析辅助。
 *
 * 支持：
 * - HTTP/HTTPS 远程弹幕（XML 或 JSON）；
 * - 本地 base64 编码弹幕（直接嵌入在 playerContent 返回的 danmu 字段）；
 * - 纯 XML/JSON 文本内容。
 *
 * XML 采用 B 站弹幕格式（`<d p="time,mode,size,color">text</d>`），
 * JSON 采用 `{"data":[{"t":..,"m":..,"c":..,"s":..,"n":..}]}` 结构，
 * 解析后按播放器尺寸缩放填充到 [IDanmakuView]。
 */
object DanmuLoader {

    private const val TAG = "DanmuLoader"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 5
    private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36"

    /** 弹幕图片内存缓存（URL → Bitmap，按字节计，上限 8MB），避免重复下载 */
    private val imageCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    /** 表情/图片弹幕异步加载线程池：解析阶段只放占位，图片后台下载不阻塞弹幕解析（避免播放卡顿） */
    private val imageExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

    /** 加载失败图片黑名单（进程内）：链接失效/网络不可用时不再重复请求，避免反复下载浪费 */
    private val failedImages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 主线程 Handler：图片加载完成后在主线程替换 Drawable 并刷新弹幕视图 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 待异步加载的弹幕图片：解析时记录，加载完成后更新对应 ImageSpan 的占位 Drawable 并重建弹幕缓存 */
    private class PendingImage(
        val url: String,
        val span: ImageSpan,
        val targetH: Int,
        val danmaku: BaseDanmaku
    )

    /**
     * 可变图片 Drawable：弹幕表情/图片加载前以透明占位（保证布局尺寸），
     * 后台下载完成后直接替换 [bitmap] 并更新 bounds，无需重建 ImageSpan。
     */
    private class ImageDrawable(bitmap: Bitmap?) : Drawable() {
        var bitmap: Bitmap? = bitmap
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun draw(canvas: Canvas) {
            bitmap?.let { canvas.drawBitmap(it, null, bounds, paint) }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * 图文混排缓存渲染器：为图片弹幕预留 padding 吸收 ALIGN_BASELINE 上溢（防顶部裁剪）；
     * 花式模式（[fancyEnabled]）额外按弹幕序号确定性随机挑选部分弹幕叠加"背景+边框"。
     */
    private class FancyCacheStuffer(private val fancyEnabled: Boolean) : SpannedCacheStuffer() {

        /** 花式着色概率：约 35% 的弹幕加背景+边框 */
        private val FANCY_RATIO = 0.35f

        /** 背景/边框相对文字的外扩边距（px） */
        private val FANCY_MARGIN = 6f

        /** 描边宽（px）：setDanmakuPaintWidthAndHeight 仅在 paintWidth 上加描边宽，计算文字区域宽度时须减去 */
        private val FANCY_STROKE_WIDTH = 4f

        /** 文字判定：弹幕文本是否含真实文字（字母/数字/汉字）；纯 emoji/纯符号弹幕不设置背景边框 */
        private val TEXT_CHAR = Regex("[\\p{L}\\p{N}]")

        /** 高饱和调色板（红/橙/黄/绿/青/蓝/紫/粉/蓝灰） */
        private val palette = intArrayOf(
            0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(),
            0xFF43A047.toInt(), 0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(),
            0xFF8E24AA.toInt(), 0xFFF06292.toInt(), 0xFF607D8B.toInt()
        )

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val radius = 6f

        /** 预留 padding：图片弹幕吸收 ALIGN_BASELINE 上溢；花式文字弹幕给背景外扩留空间；
         *  普通文字弹幕吸收描边左溢（padding=0 时左侧描边越出缓存被裁剪） */
        override fun measure(danmaku: BaseDanmaku, paint: TextPaint, fromWorkerThread: Boolean) {
            val text = danmaku.text?.toString() ?: ""
            if (hasImageSpan(danmaku)) {
                danmaku.padding = (danmaku.textSize * 0.35f).coerceIn(12f, 24f).toInt()
            } else if (TEXT_CHAR.containsMatchIn(text)) {
                danmaku.padding = if (fancyEnabled) {
                    (danmaku.textSize * 0.28f).coerceIn(12f, 20f).toInt()
                } else {
                    4
                }
            }
            super.measure(danmaku, paint, fromWorkerThread)
            // 图片弹幕：缓存高度须容纳图片（ALIGN_BASELINE 基线、顶部可能超行高），并留余量吸收取整误差
            if (hasImageSpan(danmaku)) {
                val imgH = (danmaku.tag as? Number)?.toInt() ?: 0
                val minH = imgH + danmaku.padding * 2f
                if (danmaku.paintHeight < minH) {
                    danmaku.paintHeight = minH
                }
                danmaku.paintWidth += 4f
                danmaku.paintHeight += 4f
            }
        }

        /** 弹幕文本是否含外链图片（[img]/[表情名] → ImageSpan） */
        private fun hasImageSpan(danmaku: BaseDanmaku): Boolean {
            val text = danmaku.text ?: return false
            return text is Spanned && text.getSpans(0, text.length, ImageSpan::class.java).isNotEmpty()
        }

        /** murmur3 fmix64 强哈希：相邻整数输入哈希完全无关，避免相邻弹幕序号随机序列相关（花式背景扎堆） */
        private fun fmix64(k: Long): Long {
            var h = k
            h = (h xor (h ushr 33)) * -0x61c88647L
            h = (h xor (h ushr 33)) * -0x61c88647L
            h = h xor (h ushr 33)
            return h
        }

        override fun drawBackground(danmaku: BaseDanmaku, canvas: Canvas, left: Float, top: Float) {
            if (!fancyEnabled) return
            if (danmaku.paintWidth <= 0f || danmaku.paintHeight <= 0f) return
            // 图片弹幕垂直几何与文字不同（图片可能高于文字行），不叠加背景，避免错位与上下行重叠
            if (hasImageSpan(danmaku)) return
            val text = danmaku.text?.toString() ?: return
            if (danmaku.type == BaseDanmaku.TYPE_SPECIAL) return
            // 判定+选色结果缓存（命中直接返回，避免每帧重建 Random/选色的 GC 压力）
            val color = fancyColor(danmaku, text)
            if (color == 0) return
            // 库几何：paintWidth = 文字宽 + 2*padding + 描边宽，paintHeight = 文字高 + 2*padding，
            // 文字起点 = (left+padding, top+padding)；文字区域宽须减去描边宽，否则背景右侧多出描边、文字偏左
            val pad = danmaku.padding.toFloat()
            // padding 未预留时背景外扩必然超出缓存被裁剪，不画背景
            if (pad <= 0f) return
            val textLeft = left + pad
            val textTop = top + pad
            val textW = (danmaku.paintWidth - 2f * pad - FANCY_STROKE_WIDTH).coerceAtLeast(1f)
            val textH = (danmaku.paintHeight - 2f * pad).coerceAtLeast(1f)
            // 背景/边框 = 文字区域均匀外扩，并限制在弹幕边界内（避免超出缓存被裁剪）
            val l = (textLeft - FANCY_MARGIN).coerceAtLeast(left)
            val t = (textTop - FANCY_MARGIN).coerceAtLeast(top)
            val r = (textLeft + textW + FANCY_MARGIN).coerceAtMost(left + danmaku.paintWidth)
            val b = (textTop + textH + FANCY_MARGIN).coerceAtMost(top + danmaku.paintHeight)
            if (r <= l || b <= t) return
            bgPaint.color = (color and 0x00FFFFFF) or 0x66000000
            canvas.drawRoundRect(l, t, r, b, radius, radius, bgPaint)
            strokePaint.color = color
            canvas.drawRoundRect(l, t, r, b, radius, radius, strokePaint)
        }

        /** 花式背景颜色（缓存到弹幕 tag，避免每帧重建 Random/选色的 GC 压力）；0 表示不画背景 */
        private fun fancyColor(danmaku: BaseDanmaku, text: String): Int {
            (danmaku.tag as? Number)?.toInt()?.let { return it }
            if (!TEXT_CHAR.containsMatchIn(text)) return 0
            val rand = Random(fmix64(danmaku.index.toLong()))
            if (rand.nextFloat() > FANCY_RATIO) return 0
            val color = pickColorAvoidingText(rand, danmaku.textColor)
            danmaku.tag = color
            return color
        }

        /** 图文混排弹幕的描边：库的 SpannedCacheStuffer.drawStroke 对 Spanned 弹幕不画描边，
         *  这里用描边 paint 重建 StaticLayout 补画，随后库再画填充文字，形成"描边+填充"效果 */
        override fun drawStroke(
            danmaku: BaseDanmaku,
            text: String?,
            canvas: Canvas,
            left: Float,
            top: Float,
            paint: Paint
        ) {
            if (danmaku.text is Spanned && paint is TextPaint) {
                val layout = StaticLayout(
                    danmaku.text, paint,
                    danmaku.paintWidth.toInt().coerceAtLeast(1),
                    Layout.Alignment.ALIGN_NORMAL, 1f, 0f, true
                )
                canvas.save()
                canvas.translate(left, top + paint.ascent())
                layout.draw(canvas)
                canvas.restore()
                return
            }
            super.drawStroke(danmaku, text, canvas, left, top, paint)
        }

        /** 从调色板随机选色，RGB 距离过近（<300）则换一个，避免与文字颜色接近 */
        private fun pickColorAvoidingText(rand: Random, textColor: Int): Int {
            val tr = Color.red(textColor)
            val tg = Color.green(textColor)
            val tb = Color.blue(textColor)
            for (i in 0 until palette.size) {
                val c = palette[rand.nextInt(palette.size)]
                val dist = kotlin.math.abs(Color.red(c) - tr) +
                    kotlin.math.abs(Color.green(c) - tg) +
                    kotlin.math.abs(Color.blue(c) - tb)
                if (dist > 300) return c
            }
            return palette[rand.nextInt(palette.size)]
        }
    }

    /** 后台批量下载弹幕图片并替换占位：成功更新 bounds 并重建缓存；失败移除占位 */
    private fun loadImagesAsync(images: List<PendingImage>, view: IDanmakuView) {
        if (images.isEmpty()) return
        imageExecutor.execute {
            var failed = 0
            for (img in images) {
                val bmp = loadImageBitmap(img.url)
                if (bmp == null) {
                    failed++
                    mainHandler.post {
                        val d = img.span.drawable
                        if (d is ImageDrawable) {
                            d.bitmap = null
                            d.setBounds(0, 0, 0, 0)
                            // 占位宽 targetH → 0，同步缩小 paintWidth 恢复为纯文字宽度
                            img.danmaku.paintWidth -= img.targetH.toFloat()
                            img.danmaku.measureResetFlag = img.danmaku.measureResetFlag - 1
                            view.invalidateDanmaku(img.danmaku, true)
                        }
                    }
                    continue
                }
                mainHandler.post {
                    val d = img.span.drawable
                    if (d is ImageDrawable) {
                        d.bitmap = bmp
                        val targetH = img.targetH
                        val scale = targetH.toFloat() / bmp.height.coerceAtLeast(1)
                        val targetW = (bmp.width * scale).toInt().coerceAtLeast(1)
                        d.setBounds(0, 0, targetW, targetH)
                        // 占位阶段 paintWidth 按占位宽 targetH 计算，图片实际宽 targetW 更大时缓存尺寸不足、
                        // 图片右下被裁剪；立即按差值修正 paintWidth（不依赖渲染线程重测——暂停/已显示时不重测）
                        img.danmaku.paintWidth += (targetW - targetH).toFloat()
                        img.danmaku.measureResetFlag = img.danmaku.measureResetFlag - 1
                        view.invalidateDanmaku(img.danmaku, true)
                        // 缓存重建是异步的，延迟再失效一次确保按新尺寸渲染
                        mainHandler.postDelayed({
                            view.invalidateDanmaku(img.danmaku, true)
                        }, 300)
                    }
                }
            }
            // 逐张失败日志会刷屏，汇总为一条；失败图已进黑名单，下次加载直接跳过
            if (failed > 0) LOG.w(TAG, "弹幕图片加载失败: $failed/${images.size} 张，已回退纯文本")
        }
    }

    /**
     * 下载并解码弹幕图片（带重定向跟随与内存缓存）。
     * 弹幕解析在 IO 线程执行，同步加载可接受；同一 URL 命中缓存避免重复请求。
     * 加载失败记入黑名单，后续直接跳过（链接失效/网络差时避免反复下载）。
     * 失败返回 null（调用方回退为纯文本弹幕）。
     */
    private fun loadImageBitmap(url: String): Bitmap? {
        if (url.isBlank() || url.length > 512) return null
        if (failedImages.contains(url)) return null
        imageCache.get(url)?.let { return it }
        val bmp = try {
            val bytes = downloadBytes(url) ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        }
        if (bmp != null) {
            imageCache.put(url, bmp)
        } else {
            failedImages.add(url)
        }
        return bmp
    }

    /** 下载字节数组，跟随重定向 */
    private fun downloadBytes(url: String): ByteArray? {
        var currentUrl = url
        var redirects = 0
        while (redirects < MAX_REDIRECTS) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
            }
            try {
                conn.connect()
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return null
                    conn.disconnect()
                    currentUrl = location
                    redirects++
                    continue
                }
                if (code != 200) {
                    conn.disconnect()
                    return null
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                return bytes
            } catch (_: Throwable) {
                conn.disconnect()
                return null
            }
        }
        return null
    }

    /**
     * 弹幕加载互斥锁：强制重载时旧任务可能阻塞在 IO 中无法被协程取消，
     * 串行化避免新旧任务并发调用爬虫互相干扰，配合 [loadInto] 的 isLatest 代际校验丢弃过期结果。
     */
    private val loadMutex = Mutex()

    /**
     * 加载弹幕并注入到目标视图。
     *
     * @param danmu 弹幕来源：HTTP(S) URL、base64 字符串或纯 XML/JSON 内容
     * @param view 目标 DanmakuView
     * @param context 弹幕上下文；默认新建，传入共享实例时复用（单例语义，
     * 速度/大小/行数/透明度等设置变更后对已加载弹幕即时生效，无需重载）
     * @param startPosition 加载完成后弹幕起始位置（毫秒）提供器，弹幕准备完成时调用，
     * 取当时播放进度以同步弹幕与视频
     * @param clockPosition 线程安全的播放位置推算值提供器（毫秒），用于每帧校准弹幕时钟。
     * DFM 自带时钟按实时 1x 推进，倍速播放会持续漂移；返回 0 表示位置未就绪，跳过本次校准
     * @param remoteFetcher 远程内容拉取器。本地代理地址（127.0.0.1/localhost）优先使用
     * 该拉取器（实现层直调爬虫 proxyLocal，与端口占位无关，Parser.getXmlContent），
     * 未提供时回退 HTTP 拉取
     * @param onContent 成功获取弹幕内容后的回调（调用方缓存内容，首次下载后保存回
     * danmuText，后续重载直接解析缓存不再请求网络）
     * @param isLatest 当前加载是否仍是最新请求（调用方在强制重载时递增代际）；
     * 返回 false 表示任务已被更新的加载取代，须丢弃结果不缓存、不操作视图
     * @return 加载结果，调用方按结果提示
     */
    suspend fun loadInto(
        danmu: String,
        view: IDanmakuView,
        context: DanmakuContext? = null,
        startPosition: () -> Long = { 0L },
        videoPlaying: () -> Boolean = { true },
        clockPosition: () -> Long = { 0L },
        remoteFetcher: (suspend (String) -> String?)? = null,
        onContent: ((String) -> Unit)? = null,
        isLatest: () -> Boolean = { true }
    ): DanmuLoadResult = withContext(Dispatchers.IO) {
        // 确保表情词表就绪（进程内只拉取一次；失败由内置表兜底）
        DanmuEmote.ensureLoaded()
        // 前置校验（纯计算，无锁）
        if (danmu.isEmpty()) return@withContext DanmuLoadResult.Empty
        // 规范化协议头（去除首尾空白、全角冒号转半角），避免源写法不标准导致误判
        val normalized = normalizeDanmuSource(danmu)
        if (normalized.isEmpty()) return@withContext DanmuLoadResult.Empty
        // URL 含无效端口（爬虫未启动）时无需下载；本地代理地址由 remoteFetcher 直调爬虫，不依赖端口
        if (normalized.startsWith("http", ignoreCase = true) && normalized.contains(":-1") &&
            !normalized.contains("127.0.0.1") && !normalized.contains("localhost")
        ) {
            return@withContext DanmuLoadResult.InvalidPort
        }
        // 网络拉取放锁外：旧任务阻塞在 HTTP 中时，新任务不必等锁
        // （否则换集后新集弹幕会被旧集未完成的网络请求阻塞最多一个超时周期）
        val content = fetchContent(normalized, remoteFetcher)
        // 解析与视图注入串行执行（避免并发操作 DanmakuView 状态）
        loadMutex.withLock {
            // 任务已被更新的加载取代或取消时不再继续（取消不是失败，不提示）
            if (!isLatest() || !coroutineContext.isActive) return@withLock DanmuLoadResult.Cancelled
            if (content.isNullOrEmpty()) return@withLock DanmuLoadResult.Failed
            // 成功获取后回调调用方缓存（后续重载直接解析缓存，不再请求网络）
            onContent?.invoke(content)
            val items = try {
                parseItems(content)
            } catch (e: Throwable) {
                emptyList()
            }
            // 解析为空不提示失败（照常显示）；任务被取代或取消时不再操作视图
            if (!isLatest() || !coroutineContext.isActive) return@withLock DanmuLoadResult.Cancelled
            try {
                // 重新加载前释放旧弹幕资源
                try {
                    view.release()
                } catch (_: Throwable) {
                    // 未初始化时释放可能抛异常，忽略
                }
                val ctx = context ?: DanmakuContext.create()
                applyDanmakuSettings(ctx)
                val parser = DanmakuXmlParser(items)
                view.setCallback(object : DrawHandler.Callback {
                    override fun prepared() {
                        // prepare 异步完成，此时 pendingImages 才填充完毕，在此异步加载图片
                        loadImagesAsync(parser.pendingImages, view)
                        // prepared 运行在弹幕渲染线程，而 startPosition/videoPlaying 会读取播放内核，
                        // Media3 Exo 强制要求主线程访问（非主线程抛 IllegalStateException 崩溃），
                        // 故统一切主线程执行
                        mainHandler.post {
                            if (!isLatest()) return@post
                            if (HawkUtils.getDanmuOpen()) {
                                val pos = startPosition()
                                view.show()
                                view.start(pos)
                                // 加载完成时视频处于暂停：暂停弹幕时钟并校准到暂停位置，
                                // 避免弹幕在暂停期间空跑导致恢复播放后与视频错位（恢复由 onVideoResume 续播）
                                if (!videoPlaying()) {
                                    view.pause()
                                    view.seekTo(pos)
                                }
                            } else {
                                view.hide()
                            }
                        }
                    }

                    override fun updateTimer(timer: master.flame.danmaku.danmaku.model.DanmakuTimer?) {
                        // 每帧按播放位置校准弹幕时钟：倍速播放时弹幕随视频推进，
                        // 并顺带消化缓冲/seek 造成的时钟偏差（位置由宿主线程安全推算，不访问内核）
                        val pos = clockPosition()
                        if (pos > 0L) timer?.update(pos)
                    }

                    override fun danmakuShown(danmaku: master.flame.danmaku.danmaku.model.BaseDanmaku?) = Unit

                    override fun drawingFinished() = Unit
                })
                view.prepare(parser, ctx)
                view.showFPS(false)
                DanmuLoadResult.Success
            } catch (e: Throwable) {
                LOG.e(e)
                DanmuLoadResult.Failed
            }
        }
    }

    /** 弹幕加载结果 */
    sealed class DanmuLoadResult {
        object Success : DanmuLoadResult()
        object Empty : DanmuLoadResult()
        object InvalidPort : DanmuLoadResult()
        object Failed : DanmuLoadResult()
        object Cancelled : DanmuLoadResult()
    }

    /** 将弹幕偏好应用到 [DanmakuContext] */
    fun applyDanmakuSettings(ctx: DanmakuContext) {
        val maxLine = HawkUtils.getDanmuMaxLine()
        val maxLines = HashMap<Int, Int>().apply {
            put(BaseDanmaku.TYPE_FIX_TOP, maxLine)
            put(BaseDanmaku.TYPE_SCROLL_RL, maxLine)
            put(BaseDanmaku.TYPE_SCROLL_LR, maxLine)
            put(BaseDanmaku.TYPE_FIX_BOTTOM, maxLine)
            put(BaseDanmaku.TYPE_SPECIAL, maxLine)
        }
        val fancy = HawkUtils.getDanmuFancy()
        ctx.setMaximumLines(maxLines)
            .setScrollSpeedFactor(HawkUtils.getDanmuSpeed())
            .setDanmakuTransparency(HawkUtils.getDanmuAlpha())
            .setScaleTextSize(HawkUtils.getDanmuSizeScale())
            // 描边宽度两种模式统一（4px），保证文字轮廓/观感一致
            .setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 4f)
            .setDanmakuMargin(8)
            // 图文混排渲染器：普通模式也走 FancyCacheStuffer（图片弹幕需 padding 防顶部裁剪），仅花式模式叠加背景
            .setCacheStuffer(FancyCacheStuffer(fancy), null)
    }

    /**
     * 规范化弹幕来源串：去除首尾空白、全角冒号转半角。
     * 部分弹幕源协议头写法不标准（前后空格、`http：//` 全角冒号、大写协议等），
     * 不规范化会导致 http/file 协议识别失败或 URL 解析异常；
     * 大小写兼容由调用方 startsWith(ignoreCase=true) 覆盖。
     */
    private fun normalizeDanmuSource(source: String): String {
        // 先移除 UTF-8 BOM（部分源文件头带 BOM），再去除首尾空白、全角冒号转半角
        var s = source.removePrefix("\uFEFF").trim()
        if (s.indexOf('：') >= 0) s = s.replace('：', ':')
        return s
    }

    /** 获取弹幕内容，自动识别 base64 / HTTP URL / 本地文件 / 纯文本 */
    private suspend fun fetchContent(
        danmu: String,
        remoteFetcher: (suspend (String) -> String?)?
    ): String? {
        val source = normalizeDanmuSource(danmu)
        if (source.isEmpty()) return null
        // 本地文件（file:// 协议），Parser.getXmlContent 的 file 分支
        if (source.startsWith("file", ignoreCase = true)) {
            return runCatching {
                val path = URL(source).path
                java.io.File(path).takeIf { it.exists() }?.readText(Charsets.UTF_8)
            }.getOrNull()
        }
        // 远程 URL：宽松判断，优先走直调拉取器（实现层用 OkHttp 更健壮），非本地代理地址失败时回退 HttpURLConnection 兜底
        if (source.startsWith("http", ignoreCase = true)) {
            // URL 含无效端口（-1）时 HTTP 回退必然失败
            val hasInvalidPort = source.contains(":-1")
            if (remoteFetcher != null) {
                val fetched = remoteFetcher(source)
                if (!fetched.isNullOrEmpty()) return fetched
                if (hasInvalidPort) return null
                // /proxy 端点的 HTTP 回退与直调最终落到同一爬虫调用
                //（RemoteServer /proxy → ApiConfig.proxyLocal），回退只会让源 jar 被重复调用，
                // 造成重复抓取与源 jar 重复的加载提示，故不回退；
                // 其余本地地址（如弹幕服务 /danmu）回退 HTTP，避免直调失败即误判加载失败
                if (isLocalProxyEndpoint(source)) return null
            }
            return fetchRemoteContent(source)
        }
        // base64 编码数据（B 站弹幕常见格式）
        val decoded = try {
            android.util.Base64.decode(source, android.util.Base64.DEFAULT)
        } catch (_: Throwable) {
            null
        }
        if (decoded != null && decoded.isNotEmpty()) {
            val text = String(decoded, Charsets.UTF_8)
            if (text.isNotBlank()) return text
        }
        // 纯 XML/JSON 文本内容
        return source
    }

    /** 是否为本地代理转发的 /proxy 端点：该端点 HTTP 回退与直调爬虫等价，需避免重复请求 */
    private fun isLocalProxyEndpoint(source: String): Boolean {
        if (!source.contains("127.0.0.1") && !source.contains("localhost")) return false
        return source.substringBefore('?').trimEnd('/').endsWith("/proxy")
    }

    /** 拉取远程弹幕内容，跟随重定向。阻塞的 connect/read 无法被协程取消中断，
     *  循环间隙检查 [kotlinx.coroutines.coroutineContext] 活跃状态响应取消 */
    private suspend fun fetchRemoteContent(url: String): String? {
        var currentUrl = url
        var redirects = 0
        while (redirects < MAX_REDIRECTS) {
            if (!coroutineContext.isActive) return null
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
            }
            try {
                conn.connect()
                if (!coroutineContext.isActive) {
                    conn.disconnect()
                    return null
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return null
                    conn.disconnect()
                    currentUrl = location
                    redirects++
                    continue
                }
                if (code != 200) {
                    conn.disconnect()
                    return null
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (!coroutineContext.isActive) return null
                return String(bytes, Charsets.UTF_8)
            } catch (e: Throwable) {
                LOG.e(e)
                conn.disconnect()
                return null
            }
        }
        return null
    }

    /**
     * 解析弹幕内容为统一条目列表。
     * XML（B 站格式）或 JSON 结构均可解析。
     */
    private fun parseItems(content: String): List<DanmakuItem> {
        val trimmed = content.trimStart()
        return when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> parseJsonItems(trimmed)
            else -> parseXmlItems(content)
        }
    }

    /** 解析 B 站 XML 弹幕 */
    private fun parseXmlItems(content: String): List<DanmakuItem> {
        val danmu = Danmu.fromXml(content)
        return danmu.getData().mapNotNull { data ->
            val param = data.param
            if (param.isBlank()) return@mapNotNull null
            DanmakuItem(param, data.text)
        }
    }

    /** 解析 JSON 弹幕：{"data":[{"t":time,"m":mode,"c":color,"s":size,"n":text}]} */
    private fun parseJsonItems(content: String): List<DanmakuItem> {
        return try {
            val array = if (content.trimStart().startsWith("[")) {
                JSONArray(content)
            } else {
                val obj = JSONObject(content)
                obj.optJSONArray("data") ?: return emptyList()
            }
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val t = item.optDouble("t", item.optDouble("time", -1.0))
                    val m = item.optInt("m", item.optInt("mode", 1))
                    val c = item.optLong("c", item.optLong("color", 0xFFFFFFL))
                    val s = item.optString("s", item.optString("size", "25"))
                    val n = item.optString("n", item.optString("text", ""))
                    if (t < 0 || n.isEmpty()) continue
                    add(DanmakuItem("$t,$m,$s,$c", n))
                }
            }
        } catch (e: Throwable) {
            LOG.e(TAG, "解析 JSON 弹幕失败", e)
            emptyList()
        }
    }

    /** 单条弹幕：param 为 B 站 p 属性格式，text 为弹幕文本 */
    private data class DanmakuItem(val param: String, val text: String)

    /** 弹幕解析器：将条目列表转换为 Danmakus 集合，支持普通与高级（特殊）弹幕 */
    private class DanmakuXmlParser(private val items: List<DanmakuItem>) : BaseDanmakuParser() {

        /** 解析过程中收集的待异步加载弹幕图片，解析完成后由 loadInto 后台加载 */
        val pendingImages = ArrayList<PendingImage>()

        private var scaleX = 1f
        private var scaleY = 1f
        private var index = 0

        override fun setDisplayer(display: IDisplayer): BaseDanmakuParser {
            super.setDisplayer(display)
            scaleX = mDispWidth / DanmakuFactory.BILI_PLAYER_WIDTH
            scaleY = mDispHeight / DanmakuFactory.BILI_PLAYER_HEIGHT
            return this
        }

        override fun parse(): IDanmakus {
            val result = Danmakus(IDanmakus.ST_BY_TIME)
            for (item in items) {
                val values = item.param.split(",")
                if (values.size < 4) continue
                val danmaku = buildDanmaku(values, item.text) ?: continue
                synchronized(result.obtainSynchronizer()) {
                    result.addItem(danmaku)
                }
            }
            return result
        }

        private fun buildDanmaku(values: List<String>, text: String): BaseDanmaku? {
            val type = values[1].toIntOrNull() ?: return null
            val time = (values[0].toFloatOrNull() ?: 0f) * 1000
            // 低密度设备（density<1.5）字号会过小导致描边糊、文字不清晰，设密度下限保证最小可读字号
            val size = (values[2].toFloatOrNull() ?: 25f) * (mDispDensity.coerceAtLeast(1.5f) - 0.6f)
            val color = if (HawkUtils.getDanmuColor()) {
                ColorHelper.getCN()
            } else {
                ((values[3].toLongOrNull() ?: 0xFFFFFFL) and 0xFFFFFFL).toInt() or 0xFF000000.toInt()
            }
            // 去除首尾空白（XML 条目可能带换行/空格），高级弹幕 JSON 数组判定依赖首尾括号，
            // 不 trim 会导致 [..] 判定失败、SpecialDanmaku 无坐标而渲染不出来
            val trimmedText = text.trim()
            val item = mContext.mDanmakuFactory.createDanmaku(type, mContext)
            item.setTime(time.toLong())
            item.setTimer(mTimer)
            item.textSize = size
            item.textColor = color
            item.textShadowColor = if (color <= Color.BLACK) Color.WHITE else Color.BLACK
            item.flags = mContext.mGlobalFlagValues
            item.index = index++
            val decoded = decodeXmlString(trimmedText)
            // BAS（mode=7）文本是坐标 JSON 数组而非表情标记，不参与富文本处理
            val isBasArray = item.type == BaseDanmaku.TYPE_SPECIAL &&
                trimmedText.startsWith("[") && trimmedText.endsWith("]")
            // 富文本标记：[img]url[/img] / [img=url] 图片或 [表情名]/【表情名】/［表情名］ 表情 → ImageSpan；
            // 未映射的标记省略；中文/全角括号同样处理（否则原文显示）
            val hasRichToken = !isBasArray &&
                (trimmedText.contains("[") || trimmedText.contains("【") || trimmedText.contains("［"))
            val imageSpannable = if (hasRichToken) {
                buildImageSpannable(item, decoded, size)
            } else {
                null
            }
            // 无渲染内容直接丢弃：纯空文本，或富文本标记全部映射失败且无文字（独立表情）——不能回退原文
            if (imageSpannable == null && (decoded.isBlank() || hasRichToken)) return null
            if (imageSpannable != null) {
                DanmakuUtils.fillText(item, imageSpannable)
            } else {
                DanmakuUtils.fillText(item, decoded)
            }
            // 高级弹幕（B 站 BAS）：mode=7，文本为 [x,y,alpha,time,text,...] 形式的 JSON 数组
            if (item.type == BaseDanmaku.TYPE_SPECIAL && trimmedText.startsWith("[") && trimmedText.endsWith("]")) {
                applySpecial(item, trimmedText)
            }
            return item
        }

        /**
         * 构造图文混排弹幕：将 [img]url[/img] / [img=url] 图片标记 或 [表情名]/【表情名】/［表情名］
         * 标记替换为 ImageSpan。采用直接扫描而非正则（正则会漏匹配嵌套/全角括号导致原文残留），
         * 按"开括号 → 首个闭合括号"取整段处理，映射成功 → 图片，失败 → 省略为空。
         * 图片不在此下载，仅放透明占位并收集到 [pendingImages] 由 loadInto 后台加载。
         * 返回：含图片 → 图文混排 Spannable；仅省略标记 → 纯文本；全部省略为空 → null。
         */
        private fun buildImageSpannable(
            danmaku: BaseDanmaku,
            text: String,
            textSize: Float
        ): CharSequence? {
            val sb = SpannableStringBuilder()
            var hasImage = false
            var last = 0
            var i = 0
            val len = text.length
            // 图片高取字号 1.2 倍（偏上突出风格）；不能按缩放后字号计算，否则图片会缩小
            val targetH = (textSize * 1.2f).coerceAtLeast(16f).toInt()
            while (i < len) {
                val c = text[i]
                // 孤立闭合括号（]】］）：标记残缺/省略后的残留，直接省略避免原文残留
                if (c == ']' || c == '】' || c == '］') {
                    sb.append(text, last, i)
                    last = i + 1
                    i++
                    continue
                }
                val close: Char = when (c) {
                    '[' -> ']'
                    '【' -> '】'
                    '［' -> '］'
                    else -> {
                        i++
                        continue
                    }
                }
                // 取"开括号 → 首个闭合括号"整段作为标记；无闭合则视为普通文本保留
                val end = text.indexOf(close, i + 1)
                if (end < 0) {
                    i++
                    continue
                }
                sb.append(text, last, i)
                val token = text.substring(i, end + 1)
                var next = end + 1
                val url = if (token.equals("[img]", ignoreCase = true)) {
                    // [img]url[/img]：闭合段仅为 [img]，URL 在 [/img] 内，联动取整段
                    val closeImg = text.indexOf("[/img]", next, ignoreCase = true)
                    if (closeImg >= 0) {
                        val u = text.substring(next, closeImg).trim()
                        next = closeImg + "[/img]".length
                        u.ifBlank { null }
                    } else {
                        null
                    }
                } else {
                    resolveRichToken(token)
                }
                if (url != null) {
                    hasImage = true
                    // 透明占位保证布局尺寸，图片异步加载完成后替换为实际图片
                    val placeholder = ImageDrawable(null).apply { setBounds(0, 0, targetH, targetH) }
                    val span = ImageSpan(placeholder, ImageSpan.ALIGN_BASELINE)
                    sb.append("\uFFFC")
                    sb.setSpan(span, sb.length - 1, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    pendingImages.add(PendingImage(url, span, targetH, danmaku))
                }
                // url == null → 标记省略（替换为空），仅保留周围文字
                last = next
                i = next
            }
            sb.append(text, last, len)
            if (hasImage) {
                // 仅图片弹幕占用 tag 记录图片高度（measure 据此保障缓存高度）；
                // 文字弹幕的 tag 由 FancyCacheStuffer 缓存花式背景颜色，互不冲突
                danmaku.tag = targetH
                return sb
            }
            // 无任何图片映射：标记已省略，返回剩余纯文本（空则调用方丢弃）
            return sb.takeIf { it.isNotEmpty() }?.toString()
        }

        /** [img=url]（= 两侧允许空格）图片标记的 URL 提取 */
        private val IMG_URL = Regex("^img\\s*=\\s*(.+)$", RegexOption.IGNORE_CASE)

        /** 解析单个富文本标记（含括号）为图片地址：`[img=url]` 直接取 URL；
         *  `[表情名]`/`【表情名】`/`［表情名］` 查表情词表（键统一为半角方括号格式），无映射返回 null */
        private fun resolveRichToken(token: String): String? {
            if (token.length < 2) return null
            val inner = token.substring(1, token.length - 1).trim()
            if (inner.isEmpty()) return null
            val url = IMG_URL.matchEntire(inner)?.groupValues?.get(1)?.trim()
            if (!url.isNullOrBlank()) return url
            return DanmuEmote.getUrl("[$inner]")
        }

        /** 处理高级弹幕（B 站 JSON 数组格式） */
        private fun applySpecial(item: BaseDanmaku, text: String) {
            val arr = try {
                JSONArray(item.text)
            } catch (_: Throwable) {
                return
            }
            val textArr = Array(arr.length()) { arr.optString(it) }
            if (textArr.size < 5 || textArr[4].isEmpty()) return
            // 文本做 XML 实体解码；含标记时走富文本处理（全部省略置空，不能保留 item 中原 JSON 文本）
            val basText = decodeXmlString(textArr[4])
            if (basText.contains("[") || basText.contains("【") || basText.contains("［")) {
                DanmakuUtils.fillText(item, buildImageSpannable(item, basText, item.textSize) ?: "")
            } else {
                DanmakuUtils.fillText(item, basText)
            }
            val beginX = textArr[0].toFloatOrNull() ?: return
            val beginY = textArr[1].toFloatOrNull() ?: return
            var endX = beginX
            var endY = beginY
            val alphaArr = textArr[2].split("-")
            val beginAlpha = (master.flame.danmaku.danmaku.model.AlphaValue.MAX *
                (alphaArr[0].toFloatOrNull() ?: 1f)).toInt().coerceIn(0, master.flame.danmaku.danmaku.model.AlphaValue.MAX)
            var endAlpha = beginAlpha
            if (alphaArr.size > 1) {
                endAlpha = (master.flame.danmaku.danmaku.model.AlphaValue.MAX *
                    (alphaArr[1].toFloatOrNull() ?: 1f)).toInt().coerceIn(0, master.flame.danmaku.danmaku.model.AlphaValue.MAX)
            }
            val alphaDuration = (textArr[3].toFloatOrNull() ?: 0f) * 1000
            var translationDuration = alphaDuration
            var translationStartDelay = 0L
            var rotateY = 0f
            var rotateZ = 0f
            if (textArr.size >= 7) {
                rotateZ = textArr[5].toFloatOrNull() ?: 0f
                rotateY = textArr[6].toFloatOrNull() ?: 0f
            }
            if (textArr.size >= 11) {
                endX = textArr[7].toFloatOrNull() ?: endX
                endY = textArr[8].toFloatOrNull() ?: endY
                if (textArr[9].isNotEmpty()) {
                    translationDuration = textArr[9].toFloatOrNull() ?: translationDuration
                }
                if (textArr[10].isNotEmpty()) {
                    translationStartDelay = (textArr[10].toFloatOrNull() ?: 0f).toLong()
                }
            }
            var bx = beginX
            var by = beginY
            var ex = endX
            var ey = endY
            if (isPercentage(textArr[0])) bx *= DanmakuFactory.BILI_PLAYER_WIDTH
            if (isPercentage(textArr[1])) by *= DanmakuFactory.BILI_PLAYER_HEIGHT
            if (textArr.size >= 8 && isPercentage(textArr[7])) ex *= DanmakuFactory.BILI_PLAYER_WIDTH
            if (textArr.size >= 9 && isPercentage(textArr[8])) ey *= DanmakuFactory.BILI_PLAYER_HEIGHT
            item.duration = master.flame.danmaku.danmaku.model.Duration(alphaDuration.toLong())
            item.rotationZ = rotateZ
            item.rotationY = rotateY
            mContext.mDanmakuFactory.fillTranslationData(
                item, bx, by, ex, ey, translationDuration.toLong(),
                translationStartDelay, scaleX, scaleY
            )
            mContext.mDanmakuFactory.fillAlphaData(item, beginAlpha, endAlpha, alphaDuration.toLong())
            if (textArr.size >= 12 && textArr[11].equals("true", ignoreCase = true)) {
                item.textShadowColor = Color.TRANSPARENT
            }
        }

        private fun isPercentage(number: String): Boolean = number.contains(".")

        private fun decodeXmlString(title: String): String {
            var result = title
            if (result.contains("&amp;")) result = result.replace("&amp;", "&")
            if (result.contains("&quot;")) result = result.replace("&quot;", "\"")
            if (result.contains("&gt;")) result = result.replace("&gt;", ">")
            if (result.contains("&lt;")) result = result.replace("&lt;", "<")
            if (result.contains("&apos;")) result = result.replace("&apos;", "'")
            // 数字实体兜底解码（含 UTF-16 代理对），复用 Danmu 实现避免逻辑分叉
            if (result.contains("&#")) {
                result = Danmu.decodeNumericEntities(result)
            }
            return result
        }
    }
}
