package com.tvbox.kotlin.core.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 播放前台服务。
 *
 * 职责：
 * 1. 启动 [ControlManager] 拉起本地 HTTP 服务，保持进程存活；
 * 2. 维护前台通知（MediaStyle 媒体卡片），显示当前播放信息并提供锁屏/通知栏控制；
 * 3. 创建 [MediaSessionCompat] 承接媒体会话（锁屏卡片、系统媒体中心、耳机按键），
 *    播放控制通过广播分发，由上层播放器注册接收；
 * 4. 通知栏操作（上一首/播放暂停/下一首）通过广播分发，由上层播放器注册接收。
 *
 * 通知不使用自定义 RemoteViews，避免对 ui 模块资源的反向依赖，
 * 视频信息格式沿用 `title&&subtitle`，由 [EXTRA_VIDEO_INFO] 携带。
 */
@AndroidEntryPoint
class PlayService : Service() {

    @Inject
    lateinit var controlManager: ControlManager

    private var videoInfo: String = DEFAULT_VIDEO_INFO

    /** 媒体会话：承载锁屏卡片/系统媒体中心状态，通知与播放状态实时同步 */
    private var mediaSession: MediaSession? = null

    /** 后台音频播放控制广播接收：服务播放器接管时处理播放/暂停/seek（宿主已销毁） */
    private var backgroundControlReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        registerBackgroundControlReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_VIDEO_INFO)?.takeIf { it.isNotEmpty() }?.let {
            videoInfo = it
            sessionVideoInfo = it
        }
        startForegroundWithNotification(buildNotification())
        started = true
        // 若 stop 请求早于本服务完成 startForeground 到达，则挂起待停，启动完成后立即停止，
        // 避免 startForegroundService 后未及时 startForeground 导致系统判定 FGS 超时崩溃。
        if (pendingStop) {
            pendingStop = false
            stopSelf()
            return START_NOT_STICKY
        }
        // 启动本地服务，承载推送/投屏/代理
        controlManager.startServer()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        started = false
        pendingStop = false
        controlManager.stopServer()
        // 释放媒体会话：退出播放/停止服务后锁屏卡片同步消失
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null
        PlayService.session = null
        sessionVideoInfo = null
        // 注销后台控制接收并释放服务内播放器（若存在）
        backgroundControlReceiver?.let { runCatching { unregisterReceiver(it) } }
        backgroundControlReceiver = null
        stopBackgroundAudioInternal()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** 释放服务内后台音频播放器（服务销毁时调用） */
    private fun stopBackgroundAudioInternal() {
        val player = bgPlayer
        bgPlayer = null
        runCatching { player?.release() }
    }

    /** 注册后台音频播放控制广播：服务内播放器活跃时接管播放/暂停/seek */
    private fun registerBackgroundControlReceiver() {
        if (backgroundControlReceiver != null) return
        backgroundControlReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null || intent.action != ACTION_PLAY_CONTROL) return
                val player = bgPlayer ?: return
                when (intent.getIntExtra(EXTRA_CONTROL_ACTION, -1)) {
                    ACTION_PLAY_PAUSE ->
                        if (player.isPlaying) player.pause() else player.play()
                    ACTION_SEEK ->
                        player.seekTo(intent.getLongExtra(EXTRA_SEEK_POSITION, 0L))
                }
            }
        }
        val filter = IntentFilter(ACTION_PLAY_CONTROL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(backgroundControlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(backgroundControlReceiver, filter)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从最近任务滑动关闭应用 = 完全退出：
     * 停止服务内后台播放并停止前台服务，避免后台音乐残留播放（前台服务默认在任务移除后继续运行）。
     * 覆盖 Home 返回退出（doExit）之外的所有退出路径（含画中画关闭后滑动任务）。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopBackgroundAudioInternal()
        stopSelf()
    }

    /** 创建媒体会话：系统媒体中心/锁屏卡片据此显示与控制播放。
     *  使用框架原生 MediaSession（不绑定 Player），由上层播放器经 [syncPlayback] 同步状态 */
    private fun setupMediaSession() {
        val session = MediaSession(this, "TvBoxPlayback")
        session.setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        // 锁屏卡片/耳机按键控制经会话回调分发为播放控制广播，复用现有通知控制链路
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = dispatchControl(ACTION_PLAY_PAUSE)
            override fun onPause() = dispatchControl(ACTION_PLAY_PAUSE)
            override fun onSkipToNext() = dispatchControl(ACTION_NEXT)
            override fun onSkipToPrevious() = dispatchControl(ACTION_PREV)
            override fun onSeekTo(pos: Long) {
                sendBroadcast(
                    Intent(ACTION_PLAY_CONTROL)
                        .setPackage(packageName)
                        .putExtra(EXTRA_CONTROL_ACTION, ACTION_SEEK)
                        .putExtra(EXTRA_SEEK_POSITION, pos)
                )
            }
        })
        // 恢复播放时重放最近一次元数据/播放状态（服务进程重建后 Activity 仍存活的情形）
        sessionVideoInfo?.let { videoInfo = it }
        val cached = cachedPlayback
        if (cached != null) {
            session.setMetadata(buildMetadata(cached.title, cached.subtitle, cached.duration))
            session.setPlaybackState(buildPlaybackState(cached.playing, cached.position))
        }
        session.isActive = true
        mediaSession = session
        PlayService.session = session
    }

    /** 将媒体控制动作通过广播分发给上层播放器 */
    private fun dispatchControl(actionCode: Int) {
        sendBroadcast(
            Intent(ACTION_PLAY_CONTROL)
                .setPackage(packageName)
                .putExtra(EXTRA_CONTROL_ACTION, actionCode)
        )
    }

    /** 创建通知渠道，Android 8.0+ 必需 */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "播放服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持本地服务运行并显示播放状态"
                setShowBadge(false)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /** 启动前台服务，Android 10+ 需指定 foregroundServiceType */
    private fun startForegroundWithNotification(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 构造媒体锁屏卡片通知：MediaStyle 挂载媒体会话（大图 + 控制按钮），操作按钮通过广播分发到上层 */
    private fun buildNotification(): Notification {
        val parts = videoInfo.split("&&")
        val title = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: "TVBox"
        val subtitle = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: ""
        val playing = cachedPlayback?.playing != false

        // 媒体卡片样式：挂载媒体会话使通知栏/锁屏呈现媒体控制卡片（紧凑态显示前 3 个动作）
        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        // 小图标用单色矢量音符：通知/锁屏媒体卡片左上角显示应用标识
        //（彩色应用图标会被系统当通知图标渲染成白色大块；媒体语义图标应留给控制按钮）
        builder.setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(title)
            .setContentText(if (subtitle.isEmpty()) "正在播放" else "正在播放: $subtitle")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(mediaStyle)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_previous, "上一首",
                    buildActionPendingIntent(ACTION_PREV, REQUEST_CODE_PREV)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    "播放/暂停",
                    buildActionPendingIntent(ACTION_PLAY_PAUSE, REQUEST_CODE_PLAY_PAUSE)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next, "下一首",
                    buildActionPendingIntent(ACTION_NEXT, REQUEST_CODE_NEXT)
                ).build()
            )
        // 媒体卡片大图（应用图标兜底，有播放地址时尝试使用封面）
        appArtwork()?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    /** 锁屏卡片封面：优先应用图标（轻量、无需网络），后续可扩展为影片海报 */
    private var artworkCache: Bitmap? = null
    private fun appArtwork(): Bitmap? {
        artworkCache?.let { return it }
        return try {
            val d = packageManager.getApplicationIcon(packageName)
            val bmp = (d as? BitmapDrawable)?.bitmap ?: return null
            artworkCache = bmp
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /** 构造广播 PendingIntent，上层注册接收 [ACTION_PLAY_CONTROL] */
    private fun buildActionPendingIntent(actionCode: Int, requestCode: Int): PendingIntent {
        val intent = Intent(ACTION_PLAY_CONTROL).apply {
            setPackage(packageName)
            putExtra(EXTRA_CONTROL_ACTION, actionCode)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(this, requestCode, intent, flags)
    }

    /** 构建媒体元数据（标题/剧集名/时长/封面），供锁屏卡片与系统媒体中心展示 */
    private fun buildMetadata(title: String, subtitle: String, duration: Long): MediaMetadata {
        return MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle.ifEmpty { title })
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
            .build()
    }

    /** 构建播放状态（播放/暂停/进度），供锁屏卡片与系统媒体中心展示 */
    private fun buildPlaybackState(playing: Boolean, position: Long): PlaybackState {
        return PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SKIP_TO_NEXT
            )
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                position,
                1f
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tvbox_play_service"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_VIDEO_INFO = "TVBox&&第一集"

        private const val REQUEST_CODE_PREV = 10
        private const val REQUEST_CODE_PLAY_PAUSE = 11
        private const val REQUEST_CODE_NEXT = 12

        /** 通知栏播放控制广播 action */
        const val ACTION_PLAY_CONTROL = "com.tvbox.kotlin.core.server.action.PLAY_CONTROL"

        /** 上一首 */
        const val ACTION_PREV = 1

        /** 播放/暂停 */
        const val ACTION_PLAY_PAUSE = 2

        /** 下一首 */
        const val ACTION_NEXT = 3

        /** 跳转进度（媒体会话 seek 回调） */
        const val ACTION_SEEK = 4

        /** Intent extra：视频信息 `title&&subtitle` */
        const val EXTRA_VIDEO_INFO = "video_info"

        /** Intent extra：控制动作码（[ACTION_PREV]/[ACTION_PLAY_PAUSE]/[ACTION_NEXT]/[ACTION_SEEK]） */
        const val EXTRA_CONTROL_ACTION = "control_action"

        /** Intent extra：seek 目标位置（毫秒），随 [ACTION_SEEK] 下发 */
        const val EXTRA_SEEK_POSITION = "seek_position"

        /** 服务是否已完成 startForeground，用于避免 start/stop 竞态 */
        @Volatile
        private var started = false

        /** stop 请求早于 startForeground 到达时置位，待服务启动完成后立即停止 */
        @Volatile
        private var pendingStop = false

        /** 当前媒体会话实例：播放器宿主经静态方法同步播放状态（无需绑定服务） */
        @Volatile
        private var session: MediaSession? = null

        /** 最近一次同步的播放信息，服务进程重建后恢复会话状态用 */
        @Volatile
        private var sessionVideoInfo: String? = null

        /** 最近一次播放状态缓存 */
        private data class PlaybackCache(val title: String, val subtitle: String, val duration: Long, val position: Long, val playing: Boolean)

        @Volatile
        private var cachedPlayback: PlaybackCache? = null

        /** 服务内后台音频播放器：退出详情页/切后台后由服务持播放（宿主播放器已释放） */
        @Volatile
        private var bgPlayer: ExoPlayer? = null

        /** 服务内播放器当前歌曲信息（供通知/会话恢复） */
        @Volatile
        private var bgTitle = ""
        @Volatile
        private var bgSubtitle = ""

        /**
         * 启动后台音频播放（接管宿主播放器，如退出详情页后歌曲保持后台播放）。
         * 服务内 ExoPlayer 续播并实时同步媒体会话/通知；通知按钮播放暂停/seek 由服务接收处理。
         */
        @JvmStatic
        fun startBackgroundAudio(
            context: Context,
            url: String,
            headers: Map<String, String>?,
            position: Long,
            title: String,
            subtitle: String
        ) {
            if (url.isBlank()) return
            bgTitle = title
            bgSubtitle = subtitle
            cachedPlayback = PlaybackCache(title, subtitle, 0L, position, true)
            try {
                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .apply { headers?.let { setDefaultRequestProperties(it) } }
                val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)
                val player = ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory).build()
                player.setMediaItem(
                    MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(
                            androidx.media3.common.MediaMetadata.Builder()
                                .setTitle(title)
                                .setArtist(subtitle.ifEmpty { title })
                                .build()
                        )
                        .build()
                )
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        syncPlayback(
                            context, title, subtitle,
                            player.duration.coerceAtLeast(0), player.currentPosition, isPlaying
                        )
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY, Player.STATE_BUFFERING ->
                                syncPlayback(
                                    context, title, subtitle,
                                    player.duration.coerceAtLeast(0), player.currentPosition, player.isPlaying
                                )
                            Player.STATE_ENDED -> {
                                // 播放完成：同步结束状态并释放服务播放器
                                syncPlayback(
                                    context, title, subtitle,
                                    player.duration.coerceAtLeast(0), player.duration.coerceAtLeast(0), false
                                )
                                bgPlayer?.release()
                                bgPlayer = null
                            }
                        }
                    }
                })
                player.prepare()
                if (position > 0) player.seekTo(position)
                player.playWhenReady = true
                bgPlayer?.release()
                bgPlayer = player
            } catch (_: Throwable) {
                bgPlayer = null
            }
            // 通知与媒体卡片
            start(context, "$title&&$subtitle")
        }

        /** 停止服务内后台音频播放（宿主重新进入播放页/播放新内容时调用，避免与宿主播放器叠加） */
        @JvmStatic
        fun stopBackgroundAudio(context: Context) {
            val player = bgPlayer ?: return
            bgPlayer = null
            runCatching { player.release() }
        }

        /** 服务内播放器是否正在接管播放（供通知/媒体会话判断） */
        @JvmStatic
        fun isBackgroundAudioActive(): Boolean = bgPlayer != null

        /** 启动服务并设置当前播放信息 */
        @JvmStatic
        fun start(context: Context, videoInfo: String) {
            val intent = Intent(context, PlayService::class.java).apply {
                putExtra(EXTRA_VIDEO_INFO, videoInfo)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /** 停止服务；若服务尚未 startForeground 则挂起待停，避免 FGS 超时崩溃 */
        @JvmStatic
        fun stop(context: Context) {
            if (started) {
                context.stopService(Intent(context, PlayService::class.java))
            } else {
                pendingStop = true
            }
        }

        /**
         * 同步媒体会话状态（播放器宿主在播放状态/进度变化时调用）：
         * - 切歌或播放/暂停状态切换时：更新会话元数据/播放状态 + 重建通知（刷新标题与播放/暂停按钮图标），
         *   保证通知栏与锁屏卡片状态一致，避免系统按过期状态发送相反控制导致"莫名停止/恢复"；
         * - 仅进度变化时：只更新会话，锁屏与系统媒体中心即时跟随，不重建通知。
         */
        @JvmStatic
        fun syncPlayback(context: Context, title: String, subtitle: String, duration: Long, position: Long, playing: Boolean) {
            val prevPlaying = cachedPlayback?.playing
            cachedPlayback = PlaybackCache(title, subtitle, duration, position, playing)
            val videoInfo = "$title&&$subtitle"
            val needRebuild = videoInfo != sessionVideoInfo || prevPlaying != playing
            session?.let { s ->
                s.setMetadata(
                    MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle.ifEmpty { title })
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                        .build()
                )
                s.setPlaybackState(
                    PlaybackState.Builder()
                        .setActions(
                            PlaybackState.ACTION_PLAY or
                                PlaybackState.ACTION_PAUSE or
                                PlaybackState.ACTION_PLAY_PAUSE or
                                PlaybackState.ACTION_SEEK_TO or
                                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                                PlaybackState.ACTION_SKIP_TO_NEXT
                        )
                        .setState(
                            if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                            position,
                            1f
                        )
                        .build()
                )
            }
            if (needRebuild) {
                start(context, videoInfo)
            }
        }

        /** 获取当前媒体会话（供系统层/调试使用；普通播放器宿主用 [syncPlayback] 同步即可） */
        @JvmStatic
        fun getMediaSession(): MediaSession? = session
    }
}
