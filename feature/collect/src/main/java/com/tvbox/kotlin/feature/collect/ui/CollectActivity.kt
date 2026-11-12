package com.tvbox.kotlin.feature.collect.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.BounceInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.tvbox.kotlin.core.data.entity.VodCollect
import com.tvbox.kotlin.core.ui.AppNavigator
import com.tvbox.kotlin.core.ui.BaseBindingActivity
import com.tvbox.kotlin.core.ui.SourceInfoProvider
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.ConfirmClearDialog
import com.tvbox.kotlin.core.ui.pauseImagesOnScroll
import com.tvbox.kotlin.feature.collect.databinding.ActivityCollectBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 收藏页：网格展示已收藏影片。
 *
 * - 删除模式：长按或点击右上角删除按钮切换，删除模式下点击条目即移除收藏；
 * - 清空按钮：弹出 [ConfirmClearDialog] 二次确认后清空全部收藏；
 * - 跳转详情：通过 [AppNavigator] 由 :app 注入实现，源失效则降级搜索。
 *
 * 列表使用 [TvRecyclerView] + [V7GridLayoutManager]，列数与首页一致为 5。
 */
@AndroidEntryPoint
class CollectActivity : BaseBindingActivity<ActivityCollectBinding>() {

    override val layoutId = com.tvbox.kotlin.feature.collect.R.layout.activity_collect

    private val viewModel: CollectViewModel by viewModels()

    @Inject
    lateinit var sourceInfoProvider: SourceInfoProvider

    @Inject
    lateinit var navigator: AppNavigator

    private lateinit var adapter: CollectAdapter

    override fun initView(savedInstanceState: Bundle?) {
        adapter = CollectAdapter(sourceInfoProvider)

        with(binding.mGridView) {
            setHasFixedSize(true)
            layoutManager = V7GridLayoutManager(this@CollectActivity, SPAN_COUNT)
            setAdapter(this@CollectActivity.adapter)
            // 快速滚动时暂停图片加载，停止后恢复
            pauseImagesOnScroll()
            setOnInBorderKeyEventListener(object : TvRecyclerView.OnInBorderKeyEventListener {
                override fun onInBorderKeyEvent(direction: Int, focused: View?): Boolean {
                    if (direction == View.FOCUS_UP) {
                        binding.tvDelete.isFocusable = true
                        binding.tvClear.isFocusable = true
                        binding.tvDelete.requestFocus()
                    }
                    return false
                }
            })
            setOnItemListener(object : TvRecyclerView.OnItemListener {
                override fun onItemPreSelected(
                    parent: TvRecyclerView?,
                    itemView: View?,
                    position: Int
                ) {
                    itemView?.animate()?.scaleX(1.0f)?.scaleY(1.0f)
                        ?.setDuration(300)?.setInterpolator(BounceInterpolator())?.start()
                }

                override fun onItemSelected(
                    parent: TvRecyclerView?,
                    itemView: View?,
                    position: Int
                ) {
                    itemView?.animate()?.scaleX(1.1f)?.scaleY(1.1f)
                        ?.setDuration(300)?.setInterpolator(BounceInterpolator())?.start()
                }

                override fun onItemClick(
                    parent: TvRecyclerView?,
                    itemView: View?,
                    position: Int
                ) {
                }
            })
        }

        adapter.setOnItemClickListener { item, position -> onItemClick(item, position) }
        adapter.setOnItemLongClickListener { _, _ -> toggleDeleteMode() }

        binding.tvDelete.setOnClickListener { toggleDeleteMode() }
        binding.tvClear.setOnClickListener { showClearConfirm() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.deleteMode.value) {
                    toggleDeleteMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun initData() {
        collectStarted(viewModel.collectList) { list -> adapter.setNewData(list) }
        collectStarted(viewModel.deleteMode) { enabled ->
            binding.tvDelTip.visibility = if (enabled) View.VISIBLE else View.GONE
            adapter.setDeleteMode(enabled)
        }
        collectStarted(viewModel.toast) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onItemClick(item: VodCollect, position: Int) {
        if (viewModel.deleteMode.value) {
            adapter.remove(position)
            viewModel.deleteCollect(item.id)
            return
        }
        if (sourceInfoProvider.isSourceAvailable(item.sourceKey)) {
            navigator.goDetail(this, item.vodId, item.sourceKey, Intent().putExtra("pic", item.pic))
        } else {
            navigator.goSearch(this, item.name)
        }
    }

    private fun toggleDeleteMode() {
        viewModel.toggleDeleteMode()
    }

    private fun showClearConfirm() {
        ConfirmClearDialog(this)
            .title(getString(uiR.string.confirm_clear_all_collect))
            .onConfirm { viewModel.clearAll() }
            .show()
    }

    private companion object {
        const val SPAN_COUNT = 5
    }
}
