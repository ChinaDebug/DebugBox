package com.tvbox.kotlin.feature.push.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.tvbox.kotlin.core.ui.BaseBindingActivity
import com.tvbox.kotlin.core.ui.QRCodeGen
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.feature.push.databinding.ActivityPushBinding
import dagger.hilt.android.AndroidEntryPoint
import me.jessyan.autosize.utils.AutoSizeUtils

/**
 * 推屏页：仅展示局域网推屏地址二维码与地址文本。
 *
 * 手机/电脑扫描二维码或直接浏览器访问地址即可向本机推送 URL，
 * 收到推送 URL 后由 [com.tvbox.kotlin.feature.home.ui.HomeActivity] 全局处理并跳转播放。
 */
@AndroidEntryPoint
class PushActivity : BaseBindingActivity<ActivityPushBinding>() {

    override val layoutId = com.tvbox.kotlin.feature.push.R.layout.activity_push

    private val viewModel: PushViewModel by viewModels()

    override fun initView(savedInstanceState: Bundle?) {
        // 顶部返回回调
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    override fun initData() {
        collectStarted(viewModel.address) { address -> renderAddress(address) }
        collectStarted(viewModel.toast) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        collectStarted(viewModel.error) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** 渲染二维码与地址文本 */
    private fun renderAddress(address: String) {
        if (address.isEmpty()) {
            binding.ivQRCode.setImageDrawable(null)
            binding.tvAddress.text = getString(uiR.string.push_addr_failed)
            return
        }
        binding.tvAddress.text = getString(uiR.string.api_scan_hint, address)
        val size = AutoSizeUtils.mm2px(this, 300f)
        val bitmap = QRCodeGen.generateBitmap(address, size, size, 4)
        binding.ivQRCode.setImageBitmap(bitmap)
    }
}
