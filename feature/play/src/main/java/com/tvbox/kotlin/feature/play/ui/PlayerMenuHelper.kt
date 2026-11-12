package com.tvbox.kotlin.feature.play.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.MediaCodecList
import android.os.Build
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.player.ConfigurableExo2PlayerManager
import com.tvbox.kotlin.core.player.MyVideoView
import com.tvbox.kotlin.core.player.TrackInfoBean
import com.tvbox.kotlin.core.player.subtitle.model.Subtitle
import com.tvbox.kotlin.core.player.subtitle.widget.SimpleSubtitleView
import com.tvbox.kotlin.core.ui.R as CoreUiR
import com.tvbox.kotlin.core.ui.dialog.SelectDialog
import tv.danmaku.ijk.media.exo2.Exo2PlayerManager
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer

/**
 * 播放器控制栏功能统一入口，供 [PlayActivity] 与详情页预览 [PlayFragment] 复用。
 */
object PlayerMenuHelper {

    /** 显示倍速选择菜单：点击档位立即切换并关闭；显示期间暂停控制栏自动隐藏（调用方在 dismiss 中恢复） */
    fun showPlaySpeedMenu(
        context: Context,
        controller: PlayerVodControlView,
        viewModel: PlayViewModel
    ): PlayerPopupMenu? {
        val anchor = controller.getSpeedAnchor() ?: return null
        val currentSpeed = viewModel.speed.value
        // 当前速度可能不在档位数组中（如长按临时快进恢复前），取第一个不小于当前速度的档位
        val defaultPos = PlayViewModel.SPEED_OPTIONS
            .indexOfFirst { it >= currentSpeed }
            .takeIf { it >= 0 } ?: 0
        val items = PlayViewModel.SPEED_OPTIONS.map { "x${it}" }
        controller.pauseAutoHide()
        return PlayerPopupMenu.showSingle(
            context, anchor, items, defaultPos,
            context.resources.getDimensionPixelSize(CoreUiR.dimen.vs_150)
        ) { position ->
            viewModel.switchSpeed(PlayViewModel.SPEED_OPTIONS[position])
            // 选择后立即隐藏控制栏
            controller.hideBottom()
        }
    }

    /** 显示屏显设置多选菜单：勾选即时生效并持久化到 AppPreferences，菜单保持打开由返回键关闭 */
    fun showScreenDisplayMenu(
        context: Context,
        controller: PlayerVodControlView
    ): PlayerPopupMenu? {
        val anchor = controller.getScreenDisplayAnchor() ?: return null
        val prefs = AppPreferences.getShared(context)
        val selected = LinkedHashSet<Int>()
        if (prefs.getBoolean(AppConstants.SCREEN_DISPLAY_NET_SPEED, false)) selected.add(0)
        if (prefs.getBoolean(AppConstants.SCREEN_DISPLAY_SEEK_TIME, false)) selected.add(1)
        if (prefs.getBoolean(AppConstants.SCREEN_DISPLAY_SYS_TIME, false)) selected.add(2)
        if (prefs.getBoolean(AppConstants.SCREEN_DISPLAY_BATTERY, false)) selected.add(3)
        val items = listOf(
            context.getString(CoreUiR.string.screen_display_net_speed),
            context.getString(CoreUiR.string.screen_display_seek_time),
            context.getString(CoreUiR.string.screen_display_sys_time),
            context.getString(CoreUiR.string.screen_display_battery)
        )
        controller.pauseAutoHide()
        return PlayerPopupMenu.showMulti(
            context, anchor, items, selected,
            context.resources.getDimensionPixelSize(CoreUiR.dimen.vs_180)
        ) { position, nowSelected ->
            val key = when (position) {
                0 -> AppConstants.SCREEN_DISPLAY_NET_SPEED
                1 -> AppConstants.SCREEN_DISPLAY_SEEK_TIME
                2 -> AppConstants.SCREEN_DISPLAY_SYS_TIME
                else -> AppConstants.SCREEN_DISPLAY_BATTERY
            }
            AppPreferences.getShared(context).putSync(key, nowSelected)
            applyScreenDisplay(context, controller)
        }
    }

    /** 从偏好读取屏显配置并应用到控制器右上角容器 */
    fun applyScreenDisplay(context: Context, controller: PlayerVodControlView) {
        val prefs = AppPreferences.getShared(context)
        controller.applyScreenDisplay(
            prefs.getBoolean(AppConstants.SCREEN_DISPLAY_NET_SPEED, false),
            prefs.getBoolean(AppConstants.SCREEN_DISPLAY_SEEK_TIME, false),
            prefs.getBoolean(AppConstants.SCREEN_DISPLAY_SYS_TIME, false),
            prefs.getBoolean(AppConstants.SCREEN_DISPLAY_BATTERY, false)
        )
    }

    /** 横竖屏切换 */
    fun switchOrientation(activity: Activity, controller: PlayerVodControlView) {
        val current = activity.requestedOrientation
        val toPortrait = current == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
            current == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
            current == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        activity.requestedOrientation = if (toPortrait) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        controller.updateOrientationIcon(toPortrait)
        // 切换方向后同步刷新底部按钮显隐（竖屏隐藏播放控制/片头片尾等）
        controller.updateBottomButtonsForOrientation(toPortrait)
    }

    // ------------------------------------------------------------------
    // 音轨选择
    // ------------------------------------------------------------------

    /**
     * 显示音轨选择对话框（Exo 内核）：MappedTrackInfo 列出音频轨道，
     * 渲染器级 SelectionOverride 切换；[playKey] 非空时切换音轨会持久化记忆。
     */
    fun showAudioTrackDialog(context: Context, videoView: MyVideoView, playKey: String? = null) {
        val ijkPlayer = getIjkPlayer(videoView) as? IjkExo2MediaPlayer ?: return
        val trackInfo = buildExoAudioTracks(ijkPlayer)
        if (trackInfo.isEmpty()) {
            Toast.makeText(context, context.getString(CoreUiR.string.vod_no_audio), Toast.LENGTH_SHORT).show()
            return
        }
        val selectedIdx = trackInfo.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: 0
        SelectDialog(
            context,
            context.getString(CoreUiR.string.vod_audio),
            trackInfo,
            { bean ->
                val lang = bean.language.ifBlank { null }
                val detail = bean.name.ifBlank { null }
                // 展示顺序：语言 / 声道 / 编码
                listOfNotNull(lang, detail).joinToString(" / ")
                    .ifEmpty { context.getString(CoreUiR.string.vod_audio) }
            },
            select = selectedIdx,
            autoWidth = true
        ) { bean, _ ->
            switchExoAudioTrack(ijkPlayer, bean, videoView, playKey)
        }.show()
    }

    /** 根据播放器内核类型取到底层播放器实例（当前仅 Exo 内核装配） */
    fun getIjkPlayer(videoView: MyVideoView): Any? {
        return videoView.mediaPlayer?.let { manager ->
            when (manager) {
                is Exo2PlayerManager -> manager.getMediaPlayer()
                else -> null
            }
        }
    }

    // ------------------------------------------------------------------
    // 视轨选择
    // ------------------------------------------------------------------

    /**
     * 显示视轨选择对话框（Exo 内核）：MappedTrackInfo 列出视频轨道，
     * 渲染器级 SelectionOverride 切换。
     */
    fun showVideoTrackDialog(context: Context, videoView: MyVideoView) {
        val ijkPlayer = getIjkPlayer(videoView) as? IjkExo2MediaPlayer ?: return
        val trackInfo = buildExoVideoTracks(ijkPlayer)
        if (trackInfo.isEmpty()) {
            Toast.makeText(context, context.getString(CoreUiR.string.vod_no_video), Toast.LENGTH_SHORT).show()
            return
        }
        val selectedIdx = trackInfo.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: 0
        SelectDialog(
            context,
            context.getString(CoreUiR.string.vod_video),
            trackInfo,
            { bean -> bean.name.ifEmpty { context.getString(CoreUiR.string.vod_video) } },
            select = selectedIdx,
            autoWidth = true
        ) { bean, _ ->
            switchExoVideoTrack(ijkPlayer, bean, videoView)
        }.show()
    }

    /**
     * 读取 Exo 内核视频轨道：同分辨率多个 TrackGroup 合并为单个视轨，
     * 避免杜比双层 MKV（BL+EL 或 DV 主轨+HDR10 回退轨）被误判为多视轨而意外显示切换图标。
     */
    fun buildExoVideoTracks(mp: IjkExo2MediaPlayer): List<TrackInfoBean> {
        val result = mutableListOf<TrackInfoBean>()
        try {
            val mapped = mp.getTrackSelector()?.getCurrentMappedTrackInfo() ?: return result
            // 当前选中视频轨道：按 TrackGroup 引用匹配 currentTracks，标记默认选中项
            val selectedGroups = mutableSetOf<TrackGroup>()
            try {
                for (group in mp.getCurrentTracks().groups) {
                    if (!group.isSelected) continue
                    for (i in 0 until group.length) {
                        if (!group.isTrackSelected(i)) continue
                        val format = group.getTrackFormat(i)
                        if (MimeTypes.isVideo(format.sampleMimeType)) {
                            selectedGroups.add(group.mediaTrackGroup)
                        }
                    }
                }
            } catch (_: Throwable) {
                // 读取当前选中轨道失败忽略，不影响列表展示
            }
            // 同分辨率多组视为同一画面，只保留第一组
            val merged = linkedMapOf<VideoGroupKey, TrackInfoBean>()
            for (renderIndex in 0 until mapped.rendererCount) {
                if (mapped.getRendererType(renderIndex) != C.TRACK_TYPE_VIDEO) continue
                val groupArray = mapped.getTrackGroups(renderIndex)
                for (groupIndex in 0 until groupArray.length) {
                    val group = groupArray.get(groupIndex)
                    val entry = (0 until group.length).asSequence()
                        .map { it to group.getFormat(it) }
                        .firstOrNull { MimeTypes.isVideo(it.second.sampleMimeType) } ?: continue
                    val format = entry.second
                    val hasSize = format.width > 0 && format.height > 0
                    val key = VideoGroupKey(
                        if (hasSize) format.width else -1,
                        if (hasSize) format.height else -1,
                        // 分辨率未知时按编码区分，避免误合并
                        if (hasSize) null else format.codecs
                    )
                    if (merged.containsKey(key)) continue
                    merged[key] = TrackInfoBean().apply {
                        trackId = entry.first
                        trackGroupId = groupIndex
                        renderId = renderIndex
                        name = buildVideoDetail(format)
                        selected = selectedGroups.contains(group)
                    }
                }
            }
            result.addAll(merged.values)
        } catch (_: Throwable) {
            // 轨道读取失败返回空列表，由调用方提示无视轨
        }
        return result
    }

    /** 视频轨道分组键：宽高相同视为同一画面（杜比双层合并）；分辨率未知时按编码区分 */
    private data class VideoGroupKey(val width: Int, val height: Int, val codec: String?)

    /**
     * 组装视频轨道详情：分辨率 + 编码/杜比视界标识。
     * 与音频轨道共用 [friendlyCodec] 识别规则，杜比视界独立标注便于识别。
     */
    private fun buildVideoDetail(format: Format): String {
        val parts = mutableListOf<String>()
        if (format.width > 0 && format.height > 0) {
            parts.add("${format.width} x ${format.height}")
        }
        val codec = format.codecs?.takeIf { it.isNotBlank() }
            ?: format.sampleMimeType?.substringAfter('/', "")?.takeIf { it.isNotBlank() }
        if (isDolbyVisionFormat(format)) {
            parts.add("杜比视界")
        } else {
            friendlyCodec(codec).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        }
        return parts.joinToString(" / ")
    }

    /** 是否为杜比视界格式：编码标识 dvhe/dvh1 或 MIME 含 dovi/dolby-vision */
    private fun isDolbyVisionFormat(format: Format): Boolean {
        val codec = format.codecs?.lowercase().orEmpty()
        val mime = format.sampleMimeType?.lowercase().orEmpty()
        return codec.startsWith("dvhe") || codec.startsWith("dvh1") ||
            mime.contains("dovi") || mime.contains("dolby-vision")
    }

    /**
     * 切换 Exo 内核视频轨道：通过 TrackSelector 渲染器级 SelectionOverride 强制选择目标轨道。
     * 与音轨切换同一路径，切换后由内核自动重建解码器并保持播放。
     */
    fun switchExoVideoTrack(mp: IjkExo2MediaPlayer, bean: TrackInfoBean, videoView: MyVideoView) {
        try {
            val trackSelector = mp.getTrackSelector() as? DefaultTrackSelector ?: return
            val mappedInfo = trackSelector.getCurrentMappedTrackInfo() ?: return
            if (bean.renderId !in 0 until mappedInfo.rendererCount) return
            if (mappedInfo.getRendererType(bean.renderId) != C.TRACK_TYPE_VIDEO) return
            val groups = mappedInfo.getTrackGroups(bean.renderId)
            if (bean.trackGroupId !in 0 until groups.length) return
            if (bean.trackId !in 0 until groups.get(bean.trackGroupId).length) return
            val override = DefaultTrackSelector.SelectionOverride(bean.trackGroupId, bean.trackId)
            val builder = trackSelector.buildUponParameters()
                .setRendererDisabled(bean.renderId, false)
                .clearSelectionOverrides(bean.renderId)
                .setSelectionOverride(bean.renderId, groups, override)
            trackSelector.setParameters(builder.build())
        } catch (e: Throwable) {
            Toast.makeText(videoView.context, "视轨切换失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 读取 Exo 内核音频轨道 */
    fun buildExoAudioTracks(mp: IjkExo2MediaPlayer): List<TrackInfoBean> {
        val result = mutableListOf<TrackInfoBean>()
        try {
            val mapped = mp.getTrackSelector()?.getCurrentMappedTrackInfo() ?: return result
            // 当前选中音频轨道：按 TrackGroup 引用匹配 currentTracks（不依赖 format.id，
            // 内嵌音轨可能无 id 导致选中状态失效），组内记录选中轨道索引
            val selectedAudioGroups = mutableMapOf<TrackGroup, MutableSet<Int>>()
            try {
                for (group in mp.getCurrentTracks().groups) {
                    if (!group.isSelected) continue
                    for (i in 0 until group.length) {
                        if (!group.isTrackSelected(i)) continue
                        val format = group.getTrackFormat(i)
                        if (MimeTypes.isAudio(format.sampleMimeType)) {
                            selectedAudioGroups.getOrPut(group.mediaTrackGroup) { mutableSetOf() }.add(i)
                        }
                    }
                }
            } catch (_: Throwable) {
                // 读取当前选中轨道失败忽略，不影响列表展示
            }
            for (renderIndex in 0 until mapped.rendererCount) {
                if (mapped.getRendererType(renderIndex) != C.TRACK_TYPE_AUDIO) continue
                val groupArray = mapped.getTrackGroups(renderIndex)
                for (groupIndex in 0 until groupArray.length) {
                    val group = groupArray.get(groupIndex)
                    for (formatIndex in 0 until group.length) {
                        val format = group.getFormat(formatIndex)
                        if (!MimeTypes.isAudio(format.sampleMimeType)) continue
                        result.add(TrackInfoBean().apply {
                            trackId = formatIndex
                            trackGroupId = groupIndex
                            renderId = renderIndex
                            // 语言友好化（zh→国语、yue→粤语等），label/编码文本参与识别语言缺失的情况
                            language = friendlyLanguage(format.language, "${format.label.orEmpty()} ${format.codecs.orEmpty()}")
                            name = buildAudioDetail(format)
                            // 选中标记：currentTracks 中同引用（内容）的轨道组内该索引被选中
                            selected = selectedAudioGroups[group]?.contains(formatIndex) == true
                        })
                    }
                }
            }
        } catch (_: Throwable) {
            // 轨道读取失败返回空列表，由调用方提示无音轨
        }
        return result
    }

    /**
     * 切换 Exo 内核音频轨道：经 TrackSelector SelectionOverride 切换，[playKey] 非空时持久化所选轨道。
     * 音频为单渲染器 FFmpeg 软解，无解码失败风险。
     */
    fun switchExoAudioTrack(mp: IjkExo2MediaPlayer, bean: TrackInfoBean, videoView: MyVideoView, playKey: String? = null) {
        try {
            val trackSelector = mp.getTrackSelector() as? DefaultTrackSelector ?: return
            val mappedInfo = trackSelector.getCurrentMappedTrackInfo() ?: return
            if (bean.renderId !in 0 until mappedInfo.rendererCount) return
            if (mappedInfo.getRendererType(bean.renderId) != C.TRACK_TYPE_AUDIO) return
            val groups = mappedInfo.getTrackGroups(bean.renderId)
            if (bean.trackGroupId !in 0 until groups.length) return
            if (bean.trackId !in 0 until groups.get(bean.trackGroupId).length) return
            // 仅硬解模式（OFF）无 FFmpeg 音频软解兜底：目标音轨为杜比/DTS 家族且设备无对应硬件解码器时
            // 拦截切换（播放保持现状不中断），避免切换后解码失败报 4001
            val targetFormat = groups.get(bean.trackGroupId).getFormat(bean.trackId)
            if (ConfigurableExo2PlayerManager.isOnlyHardwareMode &&
                ConfigurableExo2PlayerManager.isDolbyOrDts(targetFormat.sampleMimeType) &&
                !hasHardwareDecoder(targetFormat.sampleMimeType)
            ) {
                Toast.makeText(
                    videoView.context,
                    "当前仅硬解模式不支持该音轨（杜比/DTS），请切换硬解优先或软解优先",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            // 直接 setParameters 切换：音频为单渲染器 FFmpeg 软解（无解码失败风险），无需暂停等待解码器停稳
            videoView.audioTrackSwitching = true
            val override = DefaultTrackSelector.SelectionOverride(bean.trackGroupId, bean.trackId)
            val builder = trackSelector.buildUponParameters()
                .setRendererDisabled(bean.renderId, false)
                .clearSelectionOverrides(bean.renderId)
                .setSelectionOverride(bean.renderId, groups, override)
            trackSelector.setParameters(builder.build())
            playKey?.let { AudioTrackMemory.saveExo(videoView.context, it, bean.renderId, bean.trackGroupId, bean.trackId) }
        } catch (e: Throwable) {
            Toast.makeText(videoView.context, "音轨切换失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 设备是否具备指定 MIME 的硬件解码器（排除软件解码器）。
     * 用于仅硬解模式下的音轨能力预检：杜比/DTS 家族格式若设备无硬件解码器则无法硬解。
     */
    private fun hasHardwareDecoder(mime: String?): Boolean {
        if (mime.isNullOrBlank()) return false
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            codecList.codecInfos.any { info ->
                if (info.isEncoder || !info.supportedTypes.contains(mime)) return@any false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    !info.isSoftwareOnly
                } else {
                    // API 29 以下无 isSoftwareOnly，按名称前缀排除软件解码器（OMX.google / c2.android）
                    val name = info.name.lowercase()
                    !name.contains("google") && !name.contains("android.")
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 语言友好化：语言代码/编码文本识别为中文显示名（zh→国语、yue→粤语、en→英语等） */
    private fun friendlyLanguage(language: String?, raw: String?): String {
        val text = "${language.orEmpty()} ${raw.orEmpty()}".lowercase()
        return when {
            listOf("yue", "cantonese", "粤", "广东").any { text.contains(it) } -> "粤语"
            listOf("zh", "chi", "zho", "chs", "cht", "cmn", "中", "国语", "普通话").any { text.contains(it) } -> "国语"
            listOf("en", "eng", "english", "英").any { text.contains(it) } -> "英语"
            listOf("ja", "jpn", "japanese", "日").any { text.contains(it) } -> "日语"
            listOf("ko", "kor", "korean", "韩").any { text.contains(it) } -> "韩语"
            listOf("tha", "thai", "泰").any { text.contains(it) } -> "泰语"
            else -> ""
        }
    }

    /**
     * 组装音频轨道详情（声道/编码/标签，按 语言 → 声道 → 编码 展示顺序）。
     * 编码经 [friendlyCodec] 映射为简明解码类型（Dolby/DTS 家族）；
     * 声道经 [channelLabel] 映射为环绕声标签；跳过 und/未知 等无效 label。
     */
    private fun buildAudioDetail(format: Format): String {
        val parts = mutableListOf<String>()
        format.label?.takeIf { it.isNotBlank() && !it.equals("und", true) && it != "未知" }?.let { parts.add(it) }
        val codec = format.codecs?.takeIf { it.isNotBlank() }
            ?: format.sampleMimeType?.substringAfter('/', "")?.takeIf { it.isNotBlank() }
        channelLabel(format.channelCount).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        friendlyCodec(codec).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        return parts.joinToString(" / ")
    }

    /**
     * 解码类型识别：codecs/MIME 子类型映射为简明大写编码名。
     * 覆盖主流杜比/DTS 家族（AC3/EAC3/DTS/DTS-HD/TrueHD）与常见格式（AAC/MP3/FLAC/OPUS/VORBIS），
     * 未知编码原样大写兜底。
     */
    private fun friendlyCodec(codec: String?): String {
        if (codec.isNullOrBlank()) return ""
        // 取点号前主标识（如 "mp4a.40.2" → "mp4a"）
        val key = codec.substringBefore('.').lowercase()
        return when (key) {
            "ac-3", "ac3" -> "AC3"
            "ec-3", "eac3" -> "EAC3"
            "dts-hd", "dtshd" -> "DTS-HD"
            "dts" -> "DTS"
            "truehd" -> "TrueHD"
            "mp4a", "aac" -> "AAC"
            "mp3" -> "MP3"
            "flac" -> "FLAC"
            "opus" -> "OPUS"
            "vorbis" -> "VORBIS"
            else -> codec.uppercase()
        }
    }

    /** 声道数映射：1=单声道、2=立体声、6=5.1 环绕声、8=7.1 环绕声，其余>2 输出 n 声道 */
    private fun channelLabel(channelCount: Int): String {
        return when (channelCount) {
            1 -> "单声道"
            2 -> "立体声"
            6 -> "5.1 环绕声"
            8 -> "7.1 环绕声"
            in 3..Int.MAX_VALUE -> "$channelCount 声道"
            else -> ""
        }
    }

    /**
     * 恢复记忆的音频轨道（onPrepared 后调用）：无记忆或记忆索引失效（媒体源结构变化）时保持默认音轨。
     */
    fun loadDefaultTrack(context: Context, videoView: MyVideoView, playKey: String?) {
        if (playKey.isNullOrEmpty()) return
        val player = getIjkPlayer(videoView) as? IjkExo2MediaPlayer ?: return
        val mem = AudioTrackMemory.loadExo(context, playKey) ?: return
        applyExoMemoryTrack(player, mem.first, mem.second, mem.third)
    }

    /** 应用记忆的 Exo 音频轨道：校验索引后 setParameters（与手动切换同路径）；仅硬解模式记忆为不可解杜比时跳过 */
    private fun applyExoMemoryTrack(mp: IjkExo2MediaPlayer, renderId: Int, groupIndex: Int, trackId: Int) {
        try {
            val trackSelector = mp.getTrackSelector() as? DefaultTrackSelector ?: return
            val mappedInfo = trackSelector.getCurrentMappedTrackInfo() ?: return
            if (renderId !in 0 until mappedInfo.rendererCount) return
            if (mappedInfo.getRendererType(renderId) != C.TRACK_TYPE_AUDIO) return
            val groups = mappedInfo.getTrackGroups(renderId)
            if (groupIndex !in 0 until groups.length) return
            if (trackId !in 0 until groups.get(groupIndex).length) return
            // 仅硬解模式且记忆为无硬件解码器的杜比/DTS 音轨：恢复必然解码失败，跳过保持默认
            val mime = groups.get(groupIndex).getFormat(trackId).sampleMimeType
            if (ConfigurableExo2PlayerManager.isOnlyHardwareMode &&
                ConfigurableExo2PlayerManager.isDolbyOrDts(mime) &&
                !hasHardwareDecoder(mime)
            ) {
                return
            }
            val override = DefaultTrackSelector.SelectionOverride(groupIndex, trackId)
            val builder = trackSelector.buildUponParameters()
                .setRendererDisabled(renderId, false)
                .clearSelectionOverrides(renderId)
                .setSelectionOverride(renderId, groups, override)
            trackSelector.setParameters(builder.build())
        } catch (_: Throwable) {
            // 记忆恢复失败保持默认音轨
        }
    }

    // ------------------------------------------------------------------
    // 内置字幕轨道
    // ------------------------------------------------------------------

    /** 读取 Exo 内核字幕轨道（TRACK_TYPE_TEXT 渲染器下的文本轨道） */
    fun buildExoSubtitleTracks(mp: IjkExo2MediaPlayer): List<TrackInfoBean> {
        val result = mutableListOf<TrackInfoBean>()
        try {
            val mapped = mp.getTrackSelector()?.getCurrentMappedTrackInfo() ?: return result
            // 当前选中字幕轨道的 Format.id 集合，用于标记默认选中项
            val selectedIds = mutableSetOf<String>()
            try {
                for (group in mp.getCurrentTracks().groups) {
                    if (!group.isSelected) continue
                    for (i in 0 until group.length) {
                        if (!group.isTrackSelected(i)) continue
                        val format = group.getTrackFormat(i)
                        if (MimeTypes.isText(format.sampleMimeType)) {
                            format.id?.let { selectedIds.add(it) }
                        }
                    }
                }
            } catch (_: Throwable) {
                // 读取当前选中轨道失败忽略，不影响列表展示
            }
            for (renderIndex in 0 until mapped.rendererCount) {
                if (mapped.getRendererType(renderIndex) != C.TRACK_TYPE_TEXT) continue
                val groupArray = mapped.getTrackGroups(renderIndex)
                for (groupIndex in 0 until groupArray.length) {
                    val group = groupArray.get(groupIndex)
                    for (formatIndex in 0 until group.length) {
                        val format = group.getFormat(formatIndex)
                        if (!MimeTypes.isText(format.sampleMimeType)) continue
                        result.add(TrackInfoBean().apply {
                            trackId = formatIndex
                            trackGroupId = groupIndex
                            renderId = renderIndex
                            language = format.language ?: ""
                            name = format.codecs ?: "字幕 ${result.size + 1}"
                            selected = selectedIds.contains(format.id ?: "")
                        })
                    }
                }
            }
        } catch (_: Throwable) {
            // 轨道读取失败返回空列表，由调用方提示无内置字幕
        }
        return result
    }

    /**
     * 切换 Exo 内核字幕轨道。
     * 通过反射读取内部 ExoPlayer 后使用 TrackSelectionOverride 强制选择目标文本轨道；
     * 参数基于当前生效参数增量修改，避免从零重建导致音轨/视轨的 SelectionOverride 被清空。
     */
    fun switchExoSubtitleTrack(mp: IjkExo2MediaPlayer, bean: TrackInfoBean, videoView: MyVideoView) {
        try {
            val mapped = mp.getTrackSelector()?.getCurrentMappedTrackInfo() ?: return
            val group = mapped.getTrackGroups(bean.renderId).get(bean.trackGroupId)
            val override = TrackSelectionOverride(group, listOf(bean.trackId))
            val field = IjkExo2MediaPlayer::class.java.getDeclaredField("mInternalPlayer")
            field.isAccessible = true
            val player = field.get(mp) as? ExoPlayer ?: return
            // 增量修改：保留音轨/视轨等既有 override，仅调整文本轨道
            val params = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(override)
                .build()
            player.setTrackSelectionParameters(params)
        } catch (e: Throwable) {
            Toast.makeText(videoView.context, "字幕切换失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 挂载内置字幕监听：内部 ExoPlayer 的 onCues 回调将字幕推送到 [SimpleSubtitleView]，
     * 仅在其 [SimpleSubtitleView.isInternal] 为 true 时渲染，避免与外置字幕串扰。
     */
    fun setupInternalSubtitleListener(videoView: MyVideoView, subtitleView: SimpleSubtitleView) {
        val player = getIjkPlayer(videoView) as? IjkExo2MediaPlayer ?: return
        try {
            val field = IjkExo2MediaPlayer::class.java.getDeclaredField("mInternalPlayer")
            field.isAccessible = true
            val exo = field.get(player) as? ExoPlayer ?: return
            exo.addListener(object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) {
                    val content = if (cueGroup.cues.isNotEmpty()) {
                        cueGroup.cues[0].text?.toString() ?: ""
                    } else {
                        ""
                    }
                    val subtitle = Subtitle().apply { this.content = content }
                    if (subtitleView.isInternal) subtitleView.onSubtitleChanged(subtitle)
                }
            })
        } catch (_: Throwable) {
            // 内部 ExoPlayer 不可用时忽略，不影响外置字幕
        }
    }
}
