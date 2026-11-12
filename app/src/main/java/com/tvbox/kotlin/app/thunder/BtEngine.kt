package com.tvbox.kotlin.app.thunder

import com.frostwire.jlibtorrent.AnnounceEntry
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.swig.add_torrent_params
import com.frostwire.jlibtorrent.swig.error_code
import java.io.File

/**
 * 开源 BT 引擎封装（frostwire-jlibtorrent，MIT）。
 *
 * 磁力流程：添加任务 → 等待元数据 → 选中体积最大的视频文件（其余 IGNORE），
 * 顺序下载并加急头尾 piece，数据经 [BtStreamServer] 以"整片校验通过才输出"的
 * 方式边下边播。无版权审查，起播快慢取决于做种情况。
 */
object BtEngine {

    /** 元数据获取上限：容忍 DHT/tracker 冷启动慢源 */
    private const val META_TIMEOUT_MS = 120_000L

    /** piece 下载 deadline（秒），到期前 libtorrent 优先补齐该片 */
    private const val PIECE_DEADLINE_SECONDS = 20

    /** 磁力选片用的视频扩展名 */
    private val VIDEO_EXTS = listOf(
        ".mkv", ".mp4", ".rmvb", ".avi", ".flv", ".rm", ".vob", ".wmv",
        ".mov", ".3gp", ".asf", "mpg", "mpeg", "mpe", ".ts", ".m2ts"
    )

    /** 公共 tracker：磁力常缺 tracker，补充可提升发现做种者 */
    private val PUBLIC_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "https://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce"
    )

    /** 错误类别：供上层映射用户可读文案 */
    enum class ErrorKind { META_TIMEOUT, NO_VIDEO_FILE, OTHER }

    class BtException(val kind: ErrorKind, message: String) : Exception(message)

    @Volatile
    private var manager: SessionManager? = null

    private val sessionLock = Any()

    /** 当前活动任务（同一时刻仅一个磁力播放任务） */
    @Volatile
    var current: BtTask? = null
        private set

    class BtTask internal constructor(
        val handle: TorrentHandle,
        val saveDir: File
    ) {
        /** 元数据，磁力解析完成后可用 */
        var info: TorrentInfo? = null
            internal set

        /** 选中的视频文件索引 */
        var fileIndex: Int = -1
            internal set

        private val fs by lazy { info!!.files() }

        val fileSize: Long get() = if (fileIndex >= 0) fs.fileSize(fileIndex) else 0L

        val fileRelativePath: String
            get() = if (fileIndex >= 0) fs.filePath(fileIndex) else ""

        val fileName: String
            get() = if (fileIndex >= 0) fs.fileName(fileIndex) else ""

        val pieceLength: Int get() = info!!.pieceLength()

        val numPieces: Int get() = info!!.numPieces()

        /** 本地落盘文件（libtorrent 边下边写） */
        val targetFile: File
            get() = File(saveDir, fileRelativePath)

        /** 该 piece 是否已完整下载并通过哈希校验 */
        fun havePiece(piece: Int): Boolean =
            runCatching { piece in 0 until numPieces && handle.havePiece(piece) }.getOrDefault(false)

        /** 推送下载 deadline，令 libtorrent 优先补齐该片 */
        fun setPieceDeadline(piece: Int) {
            if (piece in 0 until numPieces) {
                runCatching { handle.setPieceDeadline(piece, PIECE_DEADLINE_SECONDS) }
            }
        }
    }

    /** 懒加载启动 session（幂等，线程安全）；监听随机端口避免固定端口占用 */
    private fun ensureManager(): SessionManager {
        manager?.let { return it }
        synchronized(sessionLock) {
            manager?.let { return it }
            val sm = SessionManager()
            val params = SessionParams()
            params.getSettings().listenInterfaces("0.0.0.0:0")
            sm.start(params)
            manager = sm
            return sm
        }
    }

    /**
     * 添加磁力任务并完成选片。任务就绪即开始下载，可播放由 [BtStreamServer] 保证。
     * @throws BtException 元数据超时 / 无可播视频 / 任务失效
     */
    fun startMagnet(magnet: String, saveRoot: File): BtTask {
        val sm = ensureManager()
        stopCurrent() // 移除旧任务，避免同 hash 重复添加

        val ec = error_code()
        val params = add_torrent_params.parse_magnet_uri(magnet, ec)
        if (ec.value() != 0) throw BtException(ErrorKind.OTHER, "magnet 解析失败：${ec.message()}")
        params.setSave_path(saveRoot.absolutePath)

        val nativeHandle = sm.swig().add_torrent(params, ec)
        if (ec.value() != 0) throw BtException(ErrorKind.OTHER, "任务添加失败：${ec.message()}")
        val task = BtTask(TorrentHandle(nativeHandle), saveRoot)
        current = task

        val ti = waitMetadata(task) ?: throw BtException(ErrorKind.META_TIMEOUT, "解析下载超时")
        task.info = ti
        val index = pickVideoFile(ti) ?: throw BtException(ErrorKind.NO_VIDEO_FILE, "种子内未找到可播放的视频文件")
        task.fileIndex = index
        prioritize(ti, index)
        enableSequentialAndTrackers(task)
        return task
    }

    /** 轮询至元数据就绪，超时返回 null */
    private fun waitMetadata(task: BtTask): TorrentInfo? {
        val deadline = System.currentTimeMillis() + META_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!runCatching { task.handle.isValid() }.getOrDefault(false)) return null
            val ti = runCatching { task.handle.torrentFile() }.getOrNull()
            if (ti != null && ti.isValid() && ti.numFiles() > 0) return ti
            Thread.sleep(300)
        }
        return null
    }

    /** 挑选体积最大的视频文件作为播放目标 */
    private fun pickVideoFile(ti: TorrentInfo): Int? {
        val fs = ti.files()
        var best = -1
        var bestSize = 0L
        for (i in 0 until fs.numFiles()) {
            val name = fs.fileName(i).lowercase()
            if (VIDEO_EXTS.none { name.endsWith(it) }) continue
            val size = fs.fileSize(i)
            if (size > bestSize) {
                bestSize = size
                best = i
            }
        }
        return if (best < 0) null else best
    }

    /** 仅下载选中文件：其余 IGNORE，目标文件最高优先级 */
    private fun prioritize(ti: TorrentInfo, fileIndex: Int) {
        val fs = ti.files()
        val priorities = Array(fs.numFiles()) { Priority.IGNORE }
        if (fileIndex in priorities.indices) priorities[fileIndex] = Priority.SEVEN
        runCatching { current?.handle?.prioritizeFiles(priorities) }
    }

    /** 顺序下载 + 头尾片加急（起播/兼容 moov 在尾部的 mp4）+ 公共 tracker 提升找源 */
    private fun enableSequentialAndTrackers(task: BtTask) {
        runCatching { task.handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) }
        val last = task.numPieces - 1
        for (p in listOf(0, 1, last.coerceAtLeast(0), (last - 1).coerceAtLeast(0)).distinct()) {
            runCatching { task.handle.setPieceDeadline(p, PIECE_DEADLINE_SECONDS) }
        }
        for (url in PUBLIC_TRACKERS) {
            runCatching { task.handle.addTracker(AnnounceEntry(url)) }
        }
        runCatching { task.handle.forceReannounce() }
        runCatching { task.handle.forceDHTAnnounce() }
    }

    /**
     * 目标文件已下载字节数（fileProgress，含已接收未校验的块）。
     * 供读流停滞诊断；整片校验前这些数据未必落盘，不可作可读依据。
     */
    fun readableBytes(task: BtTask?): Long {
        if (task == null || task.fileIndex < 0) return 0L
        return try {
            val p = task.handle.fileProgress()
            if (p != null && task.fileIndex < p.size) p[task.fileIndex].coerceAtLeast(0L) else 0L
        } catch (_: Throwable) {
            0L
        }
    }

    /** 停止当前任务（保留已下载缓存供同源复用） */
    fun stopCurrent() {
        val task = current ?: return
        current = null
        val sm = manager ?: return
        if (runCatching { task.handle.isValid() }.getOrDefault(false)) {
            runCatching { sm.remove(task.handle) }
        }
    }
}
