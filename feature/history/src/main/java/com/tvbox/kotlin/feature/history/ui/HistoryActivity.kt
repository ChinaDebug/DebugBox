package com.tvbox.kotlin.feature.history.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.BounceInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.tvbox.kotlin.core.common.bean.VodInfo
import com.tvbox.kotlin.core.data.VideoUpdateChecker
import com.tvbox.kotlin.core.data.VideoUpdateListener
import com.tvbox.kotlin.core.ui.AppNavigator
import com.tvbox.kotlin.core.ui.BaseBindingActivity
import com.tvbox.kotlin.core.ui.SourceInfoProvider
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.ConfirmClearDialog
import com.tvbox.kotlin.core.ui.pauseImagesOnScroll
import com.tvbox.kotlin.feature.history.databinding.ActivityHistoryBinding
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 历史页：网格展示观看记录。
 *
 * - 删除模式：长按或点击右上角删除按钮切换，删除模式下点击条目即移除历史；
 * - 清空按钮：弹出 [ConfirmClearDialog] 二次确认后清空全部历史；
 * - 检测更新：通过 [VideoUpdateChecker] 手动触发检测，完成后刷新徽章并提示；
 * - 跳转详情：通过 [AppNavigator] 由 :app 注入实现。
 *
 * 列表使用 [TvRecyclerView] + [V7GridLayoutManager]，列数与首页一致为 5。
 */
@AndroidEntryPoint
class HistoryActivity : BaseBindingActivity<ActivityHistoryBinding>() {

    override val layoutId = com.tvbox.kotlin.feature.history.R.layout.activity_history

    private val viewModel: HistoryViewModel by viewModels()

    @Inject
    lateinit var sourceInfoProvider: SourceInfoProvider

    @Inject
    lateinit var navigator: AppNavigator

    @Inject
    lateinit var videoUpdateChecker: VideoUpdateChecker

    private lateinit var updateCheckListener: VideoUpdateListener
    private lateinit var adapter: HistoryAdapter
    private lateinit var rotateAnimation: Animation

    override fun initView(savedInstanceState: Bundle?) {
        adapter = HistoryAdapter(sourceInfoProvider) { item -> viewModel.hasVideoUpdate(item) }

        with(binding.mGridView) {
            setHasFixedSize(true)
            layoutManager = V7GridLayoutManager(this@HistoryActivity, SPAN_COUNT)
            setAdapter(this@HistoryActivity.adapter)
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

        binding.tvDelete.setOnClickListener {
            if (adapter.getData().isEmpty()) {
                Toast.makeText(this, getString(uiR.string.toast_no_history), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            toggleDeleteMode()
        }
        binding.tvClear.setOnClickListener {
            if (adapter.getData().isEmpty()) {
                Toast.makeText(this, getString(uiR.string.toast_no_history), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showClearConfirm()
        }
        binding.tvCheckUpdate.setOnClickListener {
            if (adapter.getData().isEmpty()) {
                Toast.makeText(this, getString(uiR.string.toast_no_history), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.checkUpdate()
        }

        rotateAnimation = AnimationUtils.loadAnimation(this, uiR.anim.rotate_refresh)
        // 检测完成/进行中监听：完成后刷新列表并给出数量提示
        updateCheckListener = object : VideoUpdateListener {
            override fun onCheckComplete(hasUpdate: Boolean, updates: Map<String, Boolean>) {
                adapter.notifyDataSetChanged()
                if (hasUpdate) {
                    val count = updates.values.count { it }
                    Toast.makeText(
                        this@HistoryActivity,
                        getString(uiR.string.update_found, count),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@HistoryActivity,
                        uiR.string.update_no_found,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onCheckProgress(current: Int, total: Int) {
                if (total > 0) {
                    binding.tvCheckUpdateText.text = getString(uiR.string.update_checking, current, total)
                }
            }

            override fun onCheckError(message: String) {
                Toast.makeText(this@HistoryActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
        videoUpdateChecker.addListener(updateCheckListener)

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
        collectStarted(viewModel.historyList) { list -> adapter.setNewData(list) }
        collectStarted(viewModel.deleteMode) { enabled ->
            binding.tvDelTip.visibility = if (enabled) View.VISIBLE else View.GONE
            adapter.setDeleteMode(enabled)
        }
        // 检测状态变化：开始检测时图标旋转并禁用点击，结束时恢复
        collectStarted(viewModel.checking) { checking -> setCheckingState(checking) }
        collectStarted(viewModel.toast) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** 切换检测中状态：图标旋转动画、按钮禁用与文案切换 */
    private fun setCheckingState(checking: Boolean) {
        binding.tvCheckUpdate.isClickable = !checking
        if (checking) {
            binding.ivRefreshIcon.startAnimation(rotateAnimation)
            binding.tvCheckUpdateText.setText(uiR.string.update_checking_start)
        } else {
            binding.ivRefreshIcon.clearAnimation()
            binding.tvCheckUpdateText.setText(uiR.string.update_check_btn)
        }
    }

    private fun onItemClick(item: VodInfo, position: Int) {
        if (viewModel.deleteMode.value) {
            adapter.remove(position)
            viewModel.deleteHistory(item, position)
            return
        }
        navigator.goDetail(this, item.id, item.sourceKey, Intent().putExtra("pic", item.pic))
    }

    private fun toggleDeleteMode() {
        viewModel.toggleDeleteMode()
    }

    private fun showClearConfirm() {
        ConfirmClearDialog(this)
            .title(getString(uiR.string.confirm_clear_all_history))
            .onConfirm { viewModel.clearAll() }
            .show()
    }

    override fun onDestroy() {
        if (::updateCheckListener.isInitialized) {
            videoUpdateChecker.removeListener(updateCheckListener)
        }
        super.onDestroy()
    }

    private companion object {
        const val SPAN_COUNT = 5
    }
}
