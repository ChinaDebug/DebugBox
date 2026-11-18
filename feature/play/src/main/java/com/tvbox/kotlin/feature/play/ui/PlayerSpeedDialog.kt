package com.tvbox.kotlin.feature.play.ui

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.feature.play.R
import java.util.Locale

/**
 * 竖屏倍速滑块弹层。
 *
 * 竖屏下底栏倍速按钮不再进入右侧设置面板，直接弹出该滑块；调节即时生效，
 * 拖动期间暂停控制栏自动隐藏，松手恢复。
 */
class PlayerSpeedDialog(
    context: Context,
    private val host: PlayHost
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_player_speed)
        window?.apply {
            setGravity(Gravity.CENTER)
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.85f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setDimAmount(0.3f)
        }

        val options = PlayViewModel.SPEED_OPTIONS
        val seek = findViewById<SeekBar>(R.id.ps_speed)
        val valueText = findViewById<TextView>(R.id.ps_speed_value)
        val initial = options.indexOfFirst { it >= host.viewModel.speed.value }.takeIf { it >= 0 } ?: 0
        seek.max = options.lastIndex
        seek.progress = initial
        valueText.text = speedLabel(options[initial])
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val speed = options[progress.coerceIn(0, options.lastIndex)]
                valueText.text = speedLabel(speed)
                host.viewModel.switchSpeed(speed)
                host.controller.setSpeedText(speed)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                host.controller.pauseAutoHide()
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                host.controller.resumeAutoHide()
            }
        })
        seek.requestFocus()
    }

    private fun speedLabel(speed: Float): String =
        String.format(Locale.getDefault(), "x%.2f", speed)
}
