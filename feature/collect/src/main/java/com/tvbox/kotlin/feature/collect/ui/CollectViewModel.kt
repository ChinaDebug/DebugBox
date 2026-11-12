package com.tvbox.kotlin.feature.collect.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.tvbox.kotlin.core.common.EventHub
import com.tvbox.kotlin.core.common.event.RefreshEvent
import com.tvbox.kotlin.core.data.entity.VodCollect
import com.tvbox.kotlin.core.data.repo.RoomDataManger
import com.tvbox.kotlin.core.ui.BaseViewModel
import com.tvbox.kotlin.core.ui.R as uiR
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 收藏页 ViewModel：负责加载/删除/清空收藏列表。
 *
 * - 通过 [RoomDataManger] 读写 Room 收藏表；
 * - 通过 [EventHub] 订阅全局刷新事件（详情页收藏变更后通知本页刷新）。
 */
@HiltViewModel
class CollectViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val roomDataManger: RoomDataManger,
    savedStateHandle: SavedStateHandle
) : BaseViewModel() {

    /** 收藏列表，按 updateTime 倒序展示 */
    private val _collectList = MutableStateFlow<List<VodCollect>>(emptyList())
    val collectList: StateFlow<List<VodCollect>> = _collectList.asStateFlow()

    /** 删除模式开关 */
    private val _deleteMode = MutableStateFlow(false)
    val deleteMode: StateFlow<Boolean> = _deleteMode.asStateFlow()

    init {
        loadCollects()
        subscribeRefresh()
    }

    /** 拉取全部收藏，按更新时间倒序 */
    fun loadCollects() {
        launchIO {
            val list = runCatching { roomDataManger.getAllVodCollect() }.getOrDefault(emptyList())
            _collectList.value = list.sortedByDescending { it.updateTime }
        }
    }

    /** 切换删除模式 */
    fun toggleDeleteMode() {
        _deleteMode.value = !_deleteMode.value
    }

    /** 删除单条收藏，并同步刷新列表 */
    fun deleteCollect(id: Int) {
        launchIO {
            runCatching { roomDataManger.deleteVodCollect(id) }
            loadCollects()
            EventHub.post(RefreshEvent(RefreshEvent.TYPE_COLLECT))
        }
    }

    /** 清空全部收藏，并通知详情页等同步收藏按钮状态 */
    fun clearAll() {
        launchIO {
            runCatching { roomDataManger.deleteVodCollectAll() }
            _collectList.value = emptyList()
            EventHub.post(RefreshEvent(RefreshEvent.TYPE_COLLECT))
            toast(context.getString(uiR.string.toast_collect_cleared))
        }
    }

    /** 订阅全局刷新事件，详情页收藏/取消收藏后自动刷新 */
    private fun subscribeRefresh() {
        launchIO {
            EventHub.events.collect { event ->
                if (event is RefreshEvent && event.type == RefreshEvent.TYPE_COLLECT) {
                    loadCollects()
                }
            }
        }
    }
}
