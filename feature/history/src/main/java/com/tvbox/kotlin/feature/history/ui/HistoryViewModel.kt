package com.tvbox.kotlin.feature.history.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.bean.VodInfo
import com.tvbox.kotlin.core.common.event.RefreshEvent
import com.tvbox.kotlin.core.data.VideoUpdateChecker
import com.tvbox.kotlin.core.data.VideoUpdateListener
import com.tvbox.kotlin.core.data.repo.CacheManager
import com.tvbox.kotlin.core.data.repo.RoomDataManger
import com.tvbox.kotlin.core.ui.BaseViewModel
import com.tvbox.kotlin.core.ui.R as uiR
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 历史页 ViewModel：负责加载/删除/清空观看记录与视频更新检测。
 *
 * - 通过 [RoomDataManger] 读写 Room 历史表；
 * - 通过 [CacheManager] 同步清理对应影片的播放进度缓存；
 * - 通过 [VideoUpdateChecker] 查询更新徽章、手动触发检测、删除时清理标记；
 * - 通过 [EventHub] 订阅全局刷新事件（详情页/播放页更新历史后通知本页刷新）。
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val roomDataManger: RoomDataManger,
    private val cacheManager: CacheManager,
    private val videoUpdateChecker: VideoUpdateChecker,
    savedStateHandle: SavedStateHandle
) : BaseViewModel() {

    /** 历史列表，更新徽章优先排序 */
    private val _historyList = MutableStateFlow<List<VodInfo>>(emptyList())
    val historyList: StateFlow<List<VodInfo>> = _historyList.asStateFlow()

    /** 删除模式开关 */
    private val _deleteMode = MutableStateFlow(false)
    val deleteMode: StateFlow<Boolean> = _deleteMode.asStateFlow()

    /** 更新检测进行中状态，驱动按钮转圈/禁用 */
    private val _checking = MutableStateFlow(videoUpdateChecker.isCheckingNow())
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** 检测状态监听，检测完成/出错时复位界面状态并刷新排序 */
    private val checkListener = object : VideoUpdateListener {
        override fun onCheckComplete(hasUpdate: Boolean, updates: Map<String, Boolean>) {
            _checking.value = false
            // 检测完成后按更新状态重新排序刷新（sortAndRefreshData）
            loadHistory()
        }

        override fun onCheckProgress(current: Int, total: Int) {}

        override fun onCheckError(message: String) {
            _checking.value = false
        }
    }

    init {
        // 先注册监听再读取检测状态，避免检测在注册前完成导致 _checking 永远为 true 一直转圈
        videoUpdateChecker.addListener(checkListener)
        _checking.value = videoUpdateChecker.isCheckingNow()
        loadHistory()
        subscribeRefresh()
    }

    override fun onCleared() {
        videoUpdateChecker.removeListener(checkListener)
        super.onCleared()
    }

    /** 拉取历史记录，将 playNote 同步到 note 字段用于展示，并按更新状态排序 */
    fun loadHistory() {
        launchIO {
            val limit = HISTORY_LIMIT
            val list = runCatching { roomDataManger.getAllVodRecord(limit) }.getOrDefault(emptyList())
            list.forEach { vodInfo ->
                if (!vodInfo.playNote.isNullOrEmpty()) {
                    vodInfo.note = vodInfo.playNote
                }
            }
            _historyList.value = list.sortedWith { o1, o2 ->
                val u1 = videoUpdateChecker.hasVideoUpdate(o1.sourceKey, o1.id)
                val u2 = videoUpdateChecker.hasVideoUpdate(o2.sourceKey, o2.id)
                if (u1 != u2) {
                    if (u1) -1 else 1
                } else {
                    0
                }
            }
        }
    }

    /** 查询单条影片是否有更新（徽章显示） */
    fun hasVideoUpdate(item: VodInfo): Boolean =
        videoUpdateChecker.hasVideoUpdate(item.sourceKey, item.id)

    /** 手动触发一次更新检测 */
    fun checkUpdate() {
        if (_checking.value) {
            toast(context.getString(uiR.string.toast_checking))
            return
        }
        launchIO {
            val started = videoUpdateChecker.startCheck(force = true, highConcurrency = true)
            // 仅当检测真正启动后才进入转圈状态，未启动时保持按钮原状态
            if (started) {
                _checking.value = true
                // 检测可能已极快完成（无历史/全部看完等），完成回调与置位存在竞态窗口；
                // 短延迟后按检测器实际状态校准，避免界面一直卡在"检测更新中"
                delay(200)
                _checking.value = videoUpdateChecker.isCheckingNow()
            } else {
                toast(context.getString(uiR.string.toast_checking))
            }
        }
    }

    /** 切换删除模式 */
    fun toggleDeleteMode() {
        _deleteMode.value = !_deleteMode.value
    }

    /** 删除单条历史并同步清理进度缓存与更新标记 */
    fun deleteHistory(item: VodInfo, position: Int) {
        launchIO {
            runCatching { roomDataManger.deleteVodRecord(item.sourceKey ?: "", item) }
            runCatching { cacheManager.deleteVodCache(item.sourceKey, item) }
            videoUpdateChecker.clearVideoUpdate(item.sourceKey, item.id)
            val current = _historyList.value.toMutableList()
            if (position in current.indices && current[position].id == item.id) {
                current.removeAt(position)
            } else {
                current.removeAll { it.id == item.id && it.sourceKey == item.sourceKey }
            }
            _historyList.value = current
            if (current.isEmpty() && _deleteMode.value) {
                _deleteMode.value = false
            }
        }
    }

    /** 清空全部历史，并通知详情/播放页同步状态 */
    fun clearAll() {
        launchIO {
            runCatching { roomDataManger.deleteVodRecordAll() }
            runCatching { videoUpdateChecker.clearCache() }
            _historyList.value = emptyList()
            _deleteMode.value = false
            EventHub.post(RefreshEvent(RefreshEvent.TYPE_HISTORY))
            toast(context.getString(uiR.string.toast_history_cleared))
        }
    }

    /** 订阅全局刷新事件，详情/播放页更新历史后自动刷新 */
    private fun subscribeRefresh() {
        launchIO {
            EventHub.events.collect { event ->
                if (event is RefreshEvent && event.type == RefreshEvent.TYPE_HISTORY) {
                    loadHistory()
                }
            }
        }
    }

    private companion object {
        const val HISTORY_LIMIT = 100
    }
}
