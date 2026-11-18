package com.tvbox.kotlin.feature.play.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import com.tvbox.kotlin.core.player.effect.anime4k.Anime4kSettings
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.feature.play.R
import java.util.Locale

/**
 * 自定义画面调节（对标 AVBox 的 Custom 预设）：
 * 饱和度 / 对比度 / 亮度 / 伽马 / 色温 / 色相 / 锐度 / 阴影提升，
 * 确认后立即生效并持久化；锐度独立于画质增强开关，通过画面调节效果按帧生效。
 */
class PictureCustomDialog(
    private val host: PlayHost,
    private val onApplied: () -> Unit
) : BaseDialog(host.context, uiR.style.CustomDialogStyle) {

    private lateinit var saturation: SeekBar
    private lateinit var contrast: SeekBar
    private lateinit var brightness: SeekBar
    private lateinit var gamma: SeekBar
    private lateinit var temperature: SeekBar
    private lateinit var hue: SeekBar
    private lateinit var sharpen: SeekBar
    private lateinit var shadow: SeekBar

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_picture_custom)
        window?.setGravity(Gravity.CENTER)
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(0f)
        setCanceledOnTouchOutside(true)

        saturation = findViewById(R.id.sb_saturation)
        contrast = findViewById(R.id.sb_contrast)
        brightness = findViewById(R.id.sb_brightness)
        gamma = findViewById(R.id.sb_gamma)
        temperature = findViewById(R.id.sb_temperature)
        hue = findViewById(R.id.sb_hue)
        sharpen = findViewById(R.id.sb_sharpen)
        shadow = findViewById(R.id.sb_shadow)

        loadCurrent()
        bindBars()
        findViewById<View>(R.id.btn_pc_default).setOnClickListener { resetDefault() }
        findViewById<View>(R.id.btn_pc_cancel).setOnClickListener { dismiss() }
        findViewById<View>(R.id.btn_pc_close).setOnClickListener { dismiss() }
        findViewById<View>(R.id.btn_pc_confirm).setOnClickListener {
            Anime4kSettings.setColor(
                context,
                valueOf(saturation, 0.5f, 2.0f),
                valueOf(contrast, 0.5f, 1.8f),
                valueOf(brightness, -0.2f, 0.2f),
                valueOf(gamma, 0.5f, 2.0f),
                valueOf(temperature, -100f, 100f),
                valueOf(hue, -180f, 180f),
                valueOf(shadow, 0f, 0.6f),
                valueOf(sharpen, 0f, 0.8f),
            )
            onApplied.invoke()
            dismiss()
        }
        saturation.requestFocus()
    }

    /** 绑定滑杆拖动时的数值展示 */
    private fun bindBar(bar: SeekBar, min: Float, max: Float, labelId: Int, format: (Float) -> String) {
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val current = bar ?: return
                findViewById<TextView>(labelId)?.text = format(valueOf(current, min, max))
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
    }

    private fun bindBars() {
        bindBar(saturation, 0.5f, 2.0f, R.id.tv_saturation, ::percent)
        bindBar(contrast, 0.5f, 1.8f, R.id.tv_contrast, ::percent)
        bindBar(brightness, -0.2f, 0.2f, R.id.tv_brightness, ::signedPercent)
        bindBar(gamma, 0.5f, 2.0f, R.id.tv_gamma, ::decimal)
        bindBar(temperature, -100f, 100f, R.id.tv_temperature, ::integer)
        bindBar(hue, -180f, 180f, R.id.tv_hue, ::integer)
        bindBar(sharpen, 0f, 0.8f, R.id.tv_sharpen, ::decimal)
        bindBar(shadow, 0f, 0.6f, R.id.tv_shadow, ::decimal)
    }

    private fun valueOf(bar: SeekBar, min: Float, max: Float): Float =
        min + (max - min) * bar.progress / bar.max

    private fun toProgress(bar: SeekBar, min: Float, max: Float, value: Float): Int =
        (((value - min) / (max - min)) * bar.max).toInt().coerceIn(0, bar.max)

    private fun setValue(bar: SeekBar, labelId: Int, min: Float, max: Float, value: Float, format: (Float) -> String) {
        bar.progress = toProgress(bar, min, max, value)
        findViewById<TextView>(labelId)?.text = format(valueOf(bar, min, max))
    }

    private fun loadCurrent() {
        setValue(saturation, R.id.tv_saturation, 0.5f, 2.0f, Anime4kSettings.saturation(), ::percent)
        setValue(contrast, R.id.tv_contrast, 0.5f, 1.8f, Anime4kSettings.contrast(), ::percent)
        setValue(brightness, R.id.tv_brightness, -0.2f, 0.2f, Anime4kSettings.brightness(), ::signedPercent)
        setValue(gamma, R.id.tv_gamma, 0.5f, 2.0f, Anime4kSettings.gamma(), ::decimal)
        setValue(temperature, R.id.tv_temperature, -100f, 100f, Anime4kSettings.temperature(), ::integer)
        setValue(hue, R.id.tv_hue, -180f, 180f, Anime4kSettings.hue(), ::integer)
        setValue(sharpen, R.id.tv_sharpen, 0f, 0.8f, Anime4kSettings.sharpen(), ::decimal)
        setValue(shadow, R.id.tv_shadow, 0f, 0.6f, Anime4kSettings.shadowLift(), ::decimal)
    }

    private fun resetDefault() {
        setValue(saturation, R.id.tv_saturation, 0.5f, 2.0f, Anime4kSettings.DEFAULT_SATURATION, ::percent)
        setValue(contrast, R.id.tv_contrast, 0.5f, 1.8f, Anime4kSettings.DEFAULT_CONTRAST, ::percent)
        setValue(brightness, R.id.tv_brightness, -0.2f, 0.2f, Anime4kSettings.DEFAULT_BRIGHTNESS, ::signedPercent)
        setValue(gamma, R.id.tv_gamma, 0.5f, 2.0f, Anime4kSettings.DEFAULT_GAMMA, ::decimal)
        setValue(temperature, R.id.tv_temperature, -100f, 100f, Anime4kSettings.DEFAULT_TEMPERATURE, ::integer)
        setValue(hue, R.id.tv_hue, -180f, 180f, Anime4kSettings.DEFAULT_HUE, ::integer)
        setValue(sharpen, R.id.tv_sharpen, 0f, 0.8f, Anime4kSettings.DEFAULT_SHARPEN, ::decimal)
        setValue(shadow, R.id.tv_shadow, 0f, 0.6f, Anime4kSettings.DEFAULT_SHADOW_LIFT, ::decimal)
    }

    private fun percent(value: Float): String = String.format(Locale.US, "%.0f%%", value * 100)

    private fun signedPercent(value: Float): String = String.format(Locale.US, "%+.0f%%", value * 100)

    private fun decimal(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun integer(value: Float): String = String.format(Locale.US, "%.0f", value)
}
