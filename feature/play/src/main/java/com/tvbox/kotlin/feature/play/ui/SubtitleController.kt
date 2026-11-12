package com.tvbox.kotlin.feature.play.ui

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.view.View
import android.widget.Toast
import com.tvbox.kotlin.core.common.util.StoragePermissionUtils
import com.tvbox.kotlin.core.common.util.SubtitleHelper
import com.tvbox.kotlin.core.player.TrackInfo
import com.tvbox.kotlin.core.player.TrackInfoBean
import com.tvbox.kotlin.core.player.subtitle.SubtitleEngine
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.SelectDialog
import com.tvbox.kotlin.core.ui.dialog.LocalFilePickerDialog
import com.tvbox.kotlin.feature.play.ui.subtitle.SearchSubtitleDialog
import com.tvbox.kotlin.feature.play.ui.subtitle.SubtitleBean
import com.tvbox.kotlin.feature.play.ui.subtitle.SubtitleRepository
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import tv.danmaku.ijk.media.exo2.IjkExo2MediaPlayer

/**
 * 字幕管理控制器，屏蔽 PlayFragment/PlayActivity 的宿主差异。
 *
 * 差异收敛点：
 * - [showCloseSubtitleToast]：关闭字幕是否弹「字幕已关闭」提示，PlayActivity 为 true，PlayFragment 为 false；
 * - 权限请求统一走 [PlayHost.activity]（Fragment 用 requireActivity，Activity 用 this），为 null 时跳过；
 * - 存活判定统一走 [PlayHost.isAlive]；主线程切换统一走 [PlayHost.postOnUi]。
 */
class SubtitleController(
    private val host: PlayHost,
    private val subtitleRepository: SubtitleRepository
) {

    /** 关闭字幕时是否弹出「字幕已关闭」提示（PlayActivity 为 true，PlayFragment 为 false） */
    var showCloseSubtitleToast: Boolean = false

    /** 在线搜索字幕弹窗，加载结果时需通知其关闭 */
    private var searchSubtitleDialog: SearchSubtitleDialog? = null

    /** 内置字幕/音轨切换后恢复播放位置的任务 */
    private val mTrackSwitchRunnable = Runnable {
        host.videoView.seekTo(mTrackSwitchProgress)
        host.videoView.start()
    }
    /** 内置字幕/音轨切换时记录的进度 */
    private var mTrackSwitchProgress = 0L

    /** 当前播放项的字幕缓存键 */
    private val subtitleCacheKey: String
        get() = "${host.viewModel.getProgressKey()}-subt"

    /** 播放器准备完成后初始化字幕（当前仅支持外置字幕，内置轨道待底层接入） */
    fun initSubtitle() {
        // 绑定播放器：字幕引擎刷新任务需读取播放位置，
        // 不绑定会导致 DefaultSubtitleEngine.start() 因 mediaPlayer 为空而无法刷新字幕
        host.subtitleView?.bindToPlayer(host.videoView)
        val subtitle = host.viewModel.getSubtitle()
        if (subtitle.isNotEmpty()) {
            host.subtitleView?.visibility = View.VISIBLE
            host.subtitleView?.setSubtitlePath(subtitle)
        }
        // 挂载内置字幕轨道 cue 监听：选中内置轨道后由内部 ExoPlayer onCues 推送渲染
        host.subtitleView?.let { PlayerMenuHelper.setupInternalSubtitleListener(host.videoView, it) }
    }

    /** 显示字幕设置弹窗（来源选择与样式设置） */
    fun showSubtitleDialog() {
        PlaySubtitleDialog(host.context, getCurrentTrackInfo() ?: TrackInfo())
            .onInternalSubtitle {
                selectMyInternalSubtitle()
            }
            .onSubtitleSelected { trackId ->
                // 仅"关闭字幕"会走此回调
                if (trackId == TrackInfoBean.INVALID_TRACK_ID) closeSubtitle()
            }
            .onPickExternal {
                pickExternalSubtitle()
            }
            .onSearchSubtitle {
                showSearchSubtitleDialog()
            }
            .onTextSizeChange { size ->
                host.subtitleView?.setTextSize(size.toFloat())
            }
            .onStyleChange {
                applySubtitleStyle()
            }
            .onTimeDelayChange { milliseconds ->
                host.subtitleView?.setSubtitleDelay(milliseconds)
            }
            .show()
    }

    /** 应用字幕样式（字号/颜色/描边/粗细）到字幕视图，与设置界面预览保持一致 */
    fun applySubtitleStyle() {
        val view = host.subtitleView ?: return
        host.activity?.let { view.setTextSize(SubtitleHelper.getTextSize(it).toFloat()) }
        view.setTextColor(SubtitleHelper.getTextColor())
        view.setBackGroundTextColor(SubtitleHelper.getStrokeColor())
        view.setShadowLayer(0f, 0f, 0f, 0)
        view.setTypeface(if (SubtitleHelper.getTextBold() == Typeface.BOLD) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
    }

    /** 获取当前播放器的内置轨道信息（Exo 内核） */
    fun getCurrentTrackInfo(): TrackInfo? {
        val player = PlayerMenuHelper.getIjkPlayer(host.videoView) as? IjkExo2MediaPlayer ?: return null
        val trackInfo = TrackInfo()
        val tracks = PlayerMenuHelper.buildExoSubtitleTracks(player)
        if (tracks.isEmpty()) return null
        tracks.forEach { trackInfo.addSubtitle(it) }
        return trackInfo
    }

    /** 选择内置字幕轨道 */
    fun selectMyInternalSubtitle() {
        val trackInfo = getCurrentTrackInfo()
        if (trackInfo == null) {
            Toast.makeText(host.context, host.context.getString(uiR.string.subtitle_no_internal), Toast.LENGTH_SHORT).show()
            return
        }
        val beans = trackInfo.getSubtitle()
        if (beans.isEmpty()) {
            Toast.makeText(host.context, host.context.getString(uiR.string.vod_sub_na), Toast.LENGTH_SHORT).show()
            return
        }
        val player = PlayerMenuHelper.getIjkPlayer(host.videoView) as? IjkExo2MediaPlayer
        if (player == null) {
            Toast.makeText(host.context, host.context.getString(uiR.string.subtitle_no_internal), Toast.LENGTH_SHORT).show()
            return
        }
        val selectedIdx = beans.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: 0
        SelectDialog(
            host.context,
            host.context.getString(uiR.string.vod_sub_sel),
            beans,
            { bean -> bean.name + (if (bean.language.isNullOrEmpty()) "" else " ${bean.language}") },
            select = selectedIdx
        ) { bean, _ ->
            host.subtitleView?.visibility = View.VISIBLE
            beans.forEach { it.selected = it.trackId == bean.trackId }
            val progress = host.videoView.currentPosition
            // 切换前销毁外置字幕引擎，切换后置为内置字幕模式
            host.subtitleView?.destroy()
            host.subtitleView?.clearSubtitleCache()
            host.subtitleView?.isInternal = true
            PlayerMenuHelper.switchExoSubtitleTrack(player, bean, host.videoView)
            // 暂停 → 800ms 后 seek 回原位置恢复播放
            host.videoView.pause()
            mTrackSwitchProgress = progress
            host.mainHandler.removeCallbacks(mTrackSwitchRunnable)
            host.mainHandler.postDelayed(mTrackSwitchRunnable, 800)
        }.show()
    }

    /** 关闭字幕：停止引擎、清理缓存、重置状态、隐藏字幕视图 */
    fun closeSubtitle() {
        host.subtitleView?.apply {
            destroy()
            clearSubtitleCache()
            setText("")
            visibility = View.GONE
            isInternal = false
            hasInternal = false
        }
        // 差异保留：PlayActivity 版本有关闭提示，PlayFragment 版本无
        if (showCloseSubtitleToast) {
            Toast.makeText(host.context, host.context.getString(uiR.string.vod_sub_off), Toast.LENGTH_SHORT).show()
        }
    }

    /** 设置字幕（本地路径或远程地址） */
    fun setSubtitle(path: String) {
        if (path.isEmpty()) {
            Toast.makeText(host.context, host.context.getString(uiR.string.subtitle_invalid_url), Toast.LENGTH_SHORT).show()
            searchSubtitleDialog?.notifyLoadResult(false, host.context.getString(uiR.string.subtitle_invalid_url))
            return
        }
        host.subtitleView?.visibility = View.GONE
        host.subtitleView?.setOnSubtitleLoadListener(object : SubtitleEngine.OnSubtitleLoadListener {
            override fun onSuccess() {
                Toast.makeText(host.context, host.context.getString(uiR.string.subtitle_load_success), Toast.LENGTH_SHORT).show()
                host.subtitleView?.setOnSubtitleLoadListener(null)
                searchSubtitleDialog?.notifyLoadResult(true, null)
            }

            override fun onError(error: String) {
                Toast.makeText(
                    host.context,
                    host.context.getString(uiR.string.subtitle_load_fail, error),
                    Toast.LENGTH_SHORT
                ).show()
                host.subtitleView?.setOnSubtitleLoadListener(null)
                searchSubtitleDialog?.notifyLoadResult(false, host.context.getString(uiR.string.subtitle_load_fail, error))
            }
        })
        // 重新设置缓存键，避免关闭字幕后 key 为空导致保存缓存时崩溃
        host.subtitleView?.setPlaySubtitleCacheKey(subtitleCacheKey)
        host.subtitleView?.setSubtitlePath(path)
        host.subtitleView?.visibility = View.VISIBLE
    }

    /** 显示在线搜索字幕弹窗 */
    fun showSearchSubtitleDialog() {
        val dialog = SearchSubtitleDialog(host.context)
        // 注入字幕搜索仓库，否则在线搜索直接提示"搜索错误，请重试"
        dialog.setSubtitleRepository(subtitleRepository)
        dialog.setSubtitleLoader(object : SearchSubtitleDialog.SubtitleLoader {
            override fun loadSubtitle(subtitle: SubtitleBean) {
                host.postOnUi {
                    val zimuUrl = subtitle.url
                    if (zimuUrl.isEmpty()) {
                        onLoadError(host.context.getString(uiR.string.subtitle_no_url))
                        return@postOnUi
                    }
                    setSubtitle(zimuUrl)
                }
            }

            override fun onLoadSuccess() {
                searchSubtitleDialog?.notifyLoadResult(true, null)
            }

            override fun onLoadError(error: String) {
                searchSubtitleDialog?.notifyLoadResult(false, error)
            }
        })
        searchSubtitleDialog = dialog
        // 统一使用影片名作为搜索词
        dialog.setSearchWord(host.viewModel.vodInfo.value?.name)
        dialog.setOnDismissListener { searchSubtitleDialog = null }
        dialog.show()
    }

    /** 选择外置字幕文件：申请存储权限后打开自定义文件管理器弹窗 */
    fun pickExternalSubtitle() {
        val activity = host.activity ?: return
        // Android 11+ 检查 MANAGE_EXTERNAL_STORAGE 特殊权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            activity.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.R
        ) {
            if (Environment.isExternalStorageManager()) {
                openLocalFileChooser()
            } else {
                AlertDialog.Builder(host.context)
                    .setTitle(host.context.getString(uiR.string.perm_title))
                    .setMessage(host.context.getString(uiR.string.subtitle_perm_msg))
                    .setPositiveButton(host.context.getString(uiR.string.perm_go_settings)) { _, _ ->
                        XXPermissions.startPermissionActivity(
                            activity,
                            XXPermissions.getDeniedPermissions(activity, storagePermissionList())
                        )
                    }
                    .setNegativeButton(host.context.getString(uiR.string.dialog_cancel), null)
                    .show()
            }
            return
        }
        // Android 10 及以下使用传统存储权限
        if (hasStoragePermission()) {
            openLocalFileChooser()
        } else {
            XXPermissions.with(activity)
                .permissions(storagePermissionList())
                .request(object : OnPermissionCallback {
                    override fun onPermissionResult(
                        granted: MutableList<IPermission>,
                        denied: MutableList<IPermission>
                    ) {
                        // 回调触发时宿主可能已销毁，避免空指针或非法状态
                        if (!host.isAlive()) return
                        if (denied.isEmpty()) {
                            openLocalFileChooser()
                            return
                        }
                        val neverAskAgain = denied.any { it.isDoNotAskAgainPermission(activity) }
                        if (neverAskAgain) {
                            AlertDialog.Builder(host.context)
                                .setTitle(host.context.getString(uiR.string.perm_title))
                                .setMessage(host.context.getString(uiR.string.subtitle_perm_denied_msg))
                                .setPositiveButton(host.context.getString(uiR.string.perm_go_settings)) { _, _ ->
                                    XXPermissions.startPermissionActivity(activity, denied)
                                }
                                .setNegativeButton(host.context.getString(uiR.string.dialog_cancel), null)
                                .show()
                        } else {
                            Toast.makeText(
                                host.context,
                                host.context.getString(uiR.string.vod_sub_no_permission),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                })
        }
    }

    /** 存储权限是否已授予：Android 11+ 检查 MANAGE_EXTERNAL_STORAGE，低版本检查写权限 */
    private fun hasStoragePermission(): Boolean {
        val activity = host.activity ?: return false
        return StoragePermissionUtils.hasManageExternalStorage(activity)
    }

    /** 存储权限列表：Android 11+ 为所有文件访问权限，低版本为写权限 */
    private fun storagePermissionList(): List<IPermission> {
        val activity = host.activity ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            activity.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.R
        ) {
            listOf(PermissionLists.getManageExternalStoragePermission())
        } else {
            listOf(PermissionLists.getWriteExternalStoragePermission())
        }
    }

    /** 显示自定义本地文件选择器（字幕文件模式） */
    private fun openLocalFileChooser() {
        val dialog = LocalFilePickerDialog(host.context)
        dialog.mode = LocalFilePickerDialog.PickMode.FILE
        dialog.startPath = "/storage/emulated/0/Download"
        dialog.extensions = listOf("srt", "ass", "scc", "stl", "ttml")
        dialog.onPathSelectedListener = object : LocalFilePickerDialog.OnPathSelectedListener {
            override fun onPathSelected(path: String) {
                setSubtitle(path)
            }
        }
        dialog.show()
    }
}
