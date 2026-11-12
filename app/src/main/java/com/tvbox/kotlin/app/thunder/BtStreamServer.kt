package com.tvbox.kotlin.app.thunder

import com.tvbox.kotlin.core.common.LOG
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

private const val TAG = "BtStreamServer"

/**
 * 磁力任务本地 HTTP 流服务（NanoHTTPD）。
 *
 * 将正在边下边播的 BT 视频文件以标准 HTTP + Range 语义暴露给播放器：
 * - 目标文件数据只有 libtorrent 整片校验通过后才可靠落盘，因此读流按 piece 边界推进，
 *   请求区段对应的 piece 未就绪时阻塞等待下载完成，绝不把文件空洞/未校验数据交给播放器
 *   （否则播放器嗅探/解析会失败），从而支持顺序拉流与 seek 边下边播；
 * - 播放地址携带 bt=1：播放器侧据此对磁力本地流套用宽松读超时，不影响普通视频/音乐。
 */
class BtStreamServer(private val engine: BtEngine) : NanoHTTPD(0) {

    @Volatile
    private var started = false

    /** 确保服务已启动并返回实际监听端口（端口为随机分配，启动后可用） */
    fun ensureStarted(): Int {
        if (!started) {
            start()
            started = true
        }
        return listeningPort
    }

    /** 生成当前任务的可播放地址（需在 [ensureStarted] 之后调用）。
     *  携带 bt=1 查询参数：播放器据此识别磁力本地流并套用宽松读超时（不影响其它视频/音乐）。 */
    fun playUrl(): String = "http://127.0.0.1:$listeningPort/bt?bt=1"

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.GET && session.method != NanoHTTPD.Method.HEAD) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
                NanoHTTPD.MIME_PLAINTEXT,
                ""
            )
        }
        val task = engine.current
        if (task == null || task.fileIndex < 0 || task.fileSize <= 0L) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                NanoHTTPD.MIME_PLAINTEXT,
                "no task"
            )
        }
        val size = task.fileSize
        val rangeHeader = session.headers.entries
            .firstOrNull { it.key.equals("range", true) }
            ?.value
        // 解析 bytes=start-end / bytes=-suffix / bytes=start-
        var start = 0L
        var end = size - 1
        var isRange = false
        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
            val spec = rangeHeader.substring(6).trim()
            val idx = spec.indexOf('-')
            if (idx >= 0) {
                val a = spec.substring(0, idx).trim()
                val b = spec.substring(idx + 1).trim()
                val rangeStart = a.toLongOrNull()
                if (rangeStart != null) {
                    start = rangeStart
                    val rangeEnd = b.toLongOrNull()
                    end = if (rangeEnd != null) rangeEnd else size - 1
                } else if (b.isNotEmpty()) {
                    // 后缀范围 bytes=-n：取最后 n 字节
                    val n = b.toLongOrNull()
                    if (n != null && n > 0) {
                        start = (size - n).coerceAtLeast(0)
                        end = size - 1
                    }
                }
                isRange = true
            }
        }
        if (start < 0) start = 0
        if (start > end) start = end
        end = end.coerceAtMost(size - 1)
        if (start >= size) {
            val out = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE,
                NanoHTTPD.MIME_PLAINTEXT,
                ""
            )
            out.addHeader("Content-Range", "bytes */$size")
            return out
        }
        val length = end - start + 1
        // 携带有效 Range 头一律 206，否则 200 全量
        val status = if (isRange) {
            NanoHTTPD.Response.Status.PARTIAL_CONTENT
        } else {
            NanoHTTPD.Response.Status.OK
        }
        // 数据等待在读流内处理，serve 立即返回响应头
        val input: InputStream? = try {
            RangeInputStream(task, task.targetFile, start, length)
        } catch (e: Throwable) {
            LOG.e(TAG, "打开磁力缓存文件失败：${task.targetFile.absolutePath}", e)
            null
        }
        if (input == null) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                NanoHTTPD.MIME_PLAINTEXT,
                ""
            )
        }
        val resp = NanoHTTPD.newFixedLengthResponse(status, MIME_VIDEO_OCTET, input, length)
        resp.addHeader("Accept-Ranges", "bytes")
        resp.addHeader("Content-Disposition", "inline; filename=\"${task.fileName}\"")
        if (isRange && status == NanoHTTPD.Response.Status.PARTIAL_CONTENT) {
            resp.addHeader("Content-Range", "bytes $start-$end/$size")
        }
        return resp
    }

    /**
     * 按 piece 边界的阻塞读流：请求起点所在 piece 完整校验通过才输出该段数据；
     * 未就绪时轮询等待下载推进（推送 deadline），长时间无进展判定源失效后终止流。
     */
    private class RangeInputStream(
        private val task: BtEngine.BtTask,
        private val file: java.io.File,
        start: Long,
        length: Long
    ) : InputStream() {
        private val pieceLength = task.pieceLength
        private var pos = start
        private val endExclusive = start + length
        private var raf: RandomAccessFile? = null
        private var lastDone = -1L
        private var lastGrowAt = System.currentTimeMillis()
        private var warned = false

        /** 惰性打开：文件由 libtorrent 在首个 block 落盘时才创建，等待其真实存在后再打开 */
        private fun ensureOpen(): Boolean {
            raf?.let { return true }
            val deadline = System.currentTimeMillis() + FILE_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                if (!runCatching { task.handle.isValid() }.getOrDefault(false)) return false
                if (file.exists()) {
                    val opened = try {
                        RandomAccessFile(file, "r").also { it.seek(pos) }
                    } catch (_: Throwable) {
                        null
                    }
                    if (opened != null) {
                        raf = opened
                        return true
                    }
                }
                Thread.sleep(150)
            }
            LOG.e(TAG, "等待文件创建超时（${FILE_WAIT_MS / 1000}s）：${file.absolutePath}")
            return false
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            if (pos >= endExclusive) return -1
            if (!ensureOpen()) return -1
            val raf = raf!!
            while (true) {
                val piece = (pos / pieceLength).toInt()
                // 只输出完整校验通过的 piece：libtorrent 整片校验前数据不落盘，
                // 提前读取会命中文件空洞(0x00)，导致播放器嗅探/解析失败(NoDeclaredBrand)。
                if (!task.havePiece(piece)) {
                    if (!waitForProgress()) return -1
                    continue
                }
                val pieceEnd = ((piece + 1L) * pieceLength).coerceAtMost(endExclusive)
                val diskLen = try { raf.length() } catch (_: Throwable) { pieceEnd }
                val hi = pieceEnd.coerceAtMost(diskLen)
                if (hi <= pos) {
                    if (!waitForProgress()) return -1
                    continue
                }
                val max = ((hi - pos).coerceAtMost(len.toLong())).toInt()
                val n = try {
                    raf.read(b, off, max)
                } catch (e: IOException) {
                    LOG.e(TAG, "读文件 IO 异常 pos=$pos len=$max", e)
                    -1
                }
                if (n <= 0) {
                    if (!waitForProgress()) return -1
                    continue
                }
                pos += n
                return n
            }
        }

        /** 等待下载推进：可读字节持续无增长视为下载停滞（无可用源），返回 false 表示应终止 */
        private fun waitForProgress(): Boolean {
            // 任务被移除/失效（换集或停止）时立即退出等待
            if (!runCatching { task.handle.isValid() }.getOrDefault(false)) return false
            val piece = (pos / pieceLength).toInt()
            task.setPieceDeadline(piece)
            val done = BtEngine.readableBytes(task)
            val now = System.currentTimeMillis()
            if (done != lastDone) {
                lastDone = done
                lastGrowAt = now
            } else if (now - lastGrowAt > STALL_TIMEOUT_MS) {
                if (!warned) {
                    warned = true
                    LOG.w(TAG, "下载停滞超过 ${STALL_TIMEOUT_MS / 1000}s（done=$done pos=$pos）→ 终止流")
                }
                return false
            }
            Thread.sleep(150)
            return true
        }

        override fun close() {
            runCatching { raf?.close() }
            raf = null
        }

        companion object {
            /** 等待 libtorrent 创建目标文件的上限：须小于磁力播放器 120s 读超时 */
            private const val FILE_WAIT_MS = 90_000L

            /** 可读数据持续无增长的最大时长：接近播放器 120s 读超时上限，容忍慢速/长冷启动源 */
            private const val STALL_TIMEOUT_MS = 110_000L
        }
    }

    companion object {
        private const val MIME_VIDEO_OCTET = "application/octet-stream"
    }
}
