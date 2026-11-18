package com.tvbox.kotlin.feature.play.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.player.effect.anime4k.Anime4kEffects
import com.tvbox.kotlin.core.player.effect.anime4k.Anime4kSettings
import com.tvbox.kotlin.core.player.effect.anime4k.Anime4kStatus
import com.tvbox.kotlin.core.player.effect.anime4k.Anime4kTier
import com.tvbox.kotlin.feature.play.R
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog

/**
 * 画质增强（Anime4K）设置弹窗。
 *
 * 开关、档位、去模糊保存后立即重建效果链并应用到当前 ExoPlayer，
 * 播放中变更无需重新起播。
 */
class QualitySettingDialog(
    context: Context,
    private val host: PlayHost
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_quality_setting)
        setCanceledOnTouchOutside(true)
        initOnOff()
        initTier()
        initDeblur()
    }

    private fun initOnOff() {
        val on = context.getString(uiR.string.state_on)
        val off = context.getString(uiR.string.state_off)
        val enabled = Anime4kSettings.isEnabledPref(context)
        bindSingleChoice(
            R.id.trv_quality_onoff,
            listOf(on, off),
            if (enabled) 0 else 1
        ) { position, _ ->
            Anime4kSettings.setEnabled(context, position == 0)
            applyEffects()
        }
    }

    private fun initTier() {
        val tiers = Anime4kTier.entries
        val selected = Anime4kTier.current(context).ordinal.coerceIn(0, tiers.lastIndex)
        bindSingleChoice(R.id.trv_quality_tier, tiers.map { it.label }, selected) { position, _ ->
            Anime4kTier.set(context, tiers[position])
            applyEffects()
        }
    }

    private fun initDeblur() {
        val on = context.getString(uiR.string.state_on)
        val off = context.getString(uiR.string.state_off)
        val deblur = Anime4kSettings.isDeblurPref(context)
        bindSingleChoice(
            R.id.trv_quality_deblur,
            listOf(on, off),
            if (deblur) 0 else 1
        ) { position, _ ->
            Anime4kSettings.setDeblur(context, position == 0)
            applyEffects()
        }
    }

    /** 重建效果链并应用到当前播放器；暂停时自动重绘一帧，软解时提示切换硬解 */
    private fun applyEffects() {
        Anime4kEffects.applyTo(host.videoView)
        // 软解直写 Surface，效果链不会被调用；此时提示切换硬解，避免误以为已生效
        if (Anime4kSettings.enabled() && Anime4kStatus.isSoftVideoRenderer(host.videoView.exoPlayer)) {
            Toast.makeText(context, uiR.string.quality_soft_decode_tip, Toast.LENGTH_LONG).show()
        }
    }

    /** 横向单选列表，选中项显示 √ 前缀 */
    private fun bindSingleChoice(
        viewId: Int,
        items: List<String>,
        select: Int,
        onSelect: (position: Int, value: String) -> Unit
    ) {
        val list = findViewById<TvRecyclerView>(viewId)
        list.layoutManager = V7LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        var selectIdx = select.coerceIn(0, items.lastIndex)
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(uiR.layout.item_button, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val tv = holder.itemView.findViewById<TextView>(uiR.id.tvName)
                tv.text = if (position == selectIdx) "√ " + items[position] else items[position]
                tv.setOnClickListener {
                    notifyItemChanged(selectIdx)
                    selectIdx = position
                    notifyItemChanged(selectIdx)
                    onSelect(position, items[position])
                }
            }

            override fun getItemCount(): Int = items.size
        }
        list.adapter = adapter
        list.setSelectedPosition(selectIdx)
        list.post {
            list.smoothScrollToPosition(selectIdx)
            list.setSelectionWithSmooth(selectIdx)
            list.layoutManager?.findViewByPosition(selectIdx)?.requestFocus()
        }
    }
}
