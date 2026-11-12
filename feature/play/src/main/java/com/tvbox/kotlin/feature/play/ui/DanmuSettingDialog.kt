package com.tvbox.kotlin.feature.play.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.util.HawkUtils
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import java.text.DecimalFormat

/**
 * 弹幕设置对话框
 * 提供开关、颜色、速度、大小、行数、透明度设置。
 * 事件语义：开启开关与速度变更需重载弹幕（needReload=true），
 * 颜色/花式/大小/行数/透明度仅应用设置即可生效（needReload=false），
 * 避免无谓重载把弹幕时间线拉回开头。
 * 通过 [onChanged] 通知宿主：参数为 (弹幕开关状态, 是否需要重载)。
 */
class DanmuSettingDialog(
    context: Context,
    private val onChanged: (danmuOpen: Boolean, needReload: Boolean) -> Unit
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(uiR.layout.dialog_danmu_setting)
        setCanceledOnTouchOutside(true)
        initOnOff()
        initFancy()
        initSpeed()
        initSize()
        initLine()
        initAlpha()
    }

    private fun initOnOff() {
        val defaultVal = HawkUtils.getDanmuOpen()
        val defaultPos = if (defaultVal) 0 else 1
        setAdapter(uiR.id.trv_onoff, listOf(context.getString(uiR.string.state_on), context.getString(uiR.string.state_off)), defaultPos) { value, _ ->
            val open = value == context.getString(uiR.string.state_on)
            HawkUtils.setDanmuOpen(open)
            // 开关事件携带开关状态，开启即重载、关闭隐藏
            onChanged(open, open)
        }
    }

    /**
     * 弹幕玩法：关闭 / 随机颜色 / 花式弹幕。
     * 随机颜色与花式弹幕为独立勾选项（可同时勾选、互不排斥），
     * "关闭"为统一清除按钮：同时关闭随机颜色与花式弹幕。
     */
    private fun initFancy() {
        val randomColor = HawkUtils.getDanmuColor()
        val fancy = HawkUtils.getDanmuFancy()
        setMultiAdapter(
            uiR.id.trv_color,
            listOf(context.getString(uiR.string.state_off), context.getString(uiR.string.danmu_random_color), context.getString(uiR.string.danmu_fancy)),
            mutableSetOf<Int>().apply {
                if (randomColor) add(1)
                if (fancy) add(2)
            }
        ) { position, nowSelected ->
            when (position) {
                0 -> { // 关闭：统一关闭随机颜色与花式弹幕
                    HawkUtils.setDanmuColor(false)
                    HawkUtils.setDanmuFancy(false)
                    onChanged(HawkUtils.getDanmuOpen(), false)
                }
                1 -> { // 随机颜色：独立勾选
                    HawkUtils.setDanmuColor(nowSelected)
                    onChanged(HawkUtils.getDanmuOpen(), false)
                }
                2 -> { // 花式弹幕：独立勾选
                    HawkUtils.setDanmuFancy(nowSelected)
                    onChanged(HawkUtils.getDanmuOpen(), false)
                }
            }
        }
    }

    private fun initSpeed() {
        val speeds = listOf(2.4f, 1.8f, 1.5f, 1.0f)
        val names = listOf(context.getString(uiR.string.danmu_speed_very_slow), context.getString(uiR.string.danmu_speed_slow), context.getString(uiR.string.danmu_speed_normal), context.getString(uiR.string.danmu_speed_fast))
        val defaultPos = speeds.indexOf(HawkUtils.getDanmuSpeed()).coerceAtLeast(0)
        setAdapter(uiR.id.speed, names, defaultPos) { _, pos ->
            HawkUtils.setDanmuSpeed(speeds[pos])
            // 弹幕移动时长在 prepare 解析时按速度因子固化，运行时修改对已加载弹幕不生效，
            // 需重载才能应用新速度（调整速度立即生效）
            onChanged(HawkUtils.getDanmuOpen(), true)
        }
    }

    private fun initSize() {
        val sizes = listOf(0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.7f, 1.8f, 1.9f, 2.0f)
        val sizeText = findViewById<TextView>(uiR.id.size)
        val sizeAdd = findViewById<TextView>(uiR.id.sizeAdd)
        val sizeSub = findViewById<TextView>(uiR.id.sizeSub)
        var size = sizes.indexOf(HawkUtils.getDanmuSizeScale()) + 1
        if (size !in 1..sizes.size) size = 1
        sizeText.text = context.getString(uiR.string.danmu_size_fmt, size)
        sizeAdd.setOnClickListener {
            if (size >= sizes.size) return@setOnClickListener
            size++
            HawkUtils.setDanmuSizeScale(sizes[size - 1])
            sizeText.text = context.getString(uiR.string.danmu_size_fmt, size)
            onChanged(HawkUtils.getDanmuOpen(), false)
        }
        sizeSub.setOnClickListener {
            if (size <= 1) return@setOnClickListener
            size--
            HawkUtils.setDanmuSizeScale(sizes[size - 1])
            sizeText.text = context.getString(uiR.string.danmu_size_fmt, size)
            onChanged(HawkUtils.getDanmuOpen(), false)
        }
    }

    private fun initLine() {
        val lineText = findViewById<TextView>(uiR.id.line)
        val lineAdd = findViewById<TextView>(uiR.id.lineAdd)
        val lineSub = findViewById<TextView>(uiR.id.lineSub)
        var line = HawkUtils.getDanmuMaxLine()
        lineText.text = context.getString(uiR.string.danmu_lines_fmt, line)
        lineAdd.setOnClickListener {
            if (line >= 15) return@setOnClickListener
            line++
            HawkUtils.setDanmuMaxLine(line)
            lineText.text = context.getString(uiR.string.danmu_lines_fmt, line)
            onChanged(HawkUtils.getDanmuOpen(), false)
        }
        lineSub.setOnClickListener {
            if (line <= 1) return@setOnClickListener
            line--
            HawkUtils.setDanmuMaxLine(line)
            lineText.text = context.getString(uiR.string.danmu_lines_fmt, line)
            onChanged(HawkUtils.getDanmuOpen(), false)
        }
    }

    private fun initAlpha() {
        val alphaText = findViewById<TextView>(uiR.id.alpha)
        val alphaAdd = findViewById<TextView>(uiR.id.alphaAdd)
        val alphaSub = findViewById<TextView>(uiR.id.alphaSub)
        val df = DecimalFormat("#.0")
        var alpha = (df.format(HawkUtils.getDanmuAlpha()).toFloat() * 100).toInt()
        alphaText.text = "${alpha}%"
        alphaAdd.setOnClickListener {
            if (alpha >= 100) return@setOnClickListener
            alpha += 10
            HawkUtils.setDanmuAlpha(alpha / 100.0f)
            alphaText.text = "${alpha}%"
            onChanged(HawkUtils.getDanmuOpen(), false)
        }
        alphaSub.setOnClickListener {
            if (alpha <= 10) return@setOnClickListener
            alpha -= 10
            HawkUtils.setDanmuAlpha(alpha / 100.0f)
            alphaText.text = "${alpha}%"
            onChanged(HawkUtils.getDanmuOpen(), false)
        }
    }

    /** 绑定横向单选按钮列表（选中项显示 √ 前缀） */
    private fun setAdapter(
        viewId: Int,
        items: List<String>,
        select: Int,
        onSelect: (String, Int) -> Unit
    ) {
        val list = findViewById<TvRecyclerView>(viewId)
        list.layoutManager = V7LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        var selectIdx = select.coerceIn(0, items.lastIndex)
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(uiR.layout.item_button, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val tv = holder.itemView.findViewById<TextView>(uiR.id.tvName)
                tv.text = if (position == selectIdx) "√ " + items[position] else items[position]
                tv.setOnClickListener {
                    // 点击勾选项也触发回调：click 总是执行，
                    // 便于重新确认开关状态并让宿主刷新弹幕
                    notifyItemChanged(selectIdx)
                    selectIdx = position
                    notifyItemChanged(selectIdx)
                    onSelect(items[position], position)
                }
            }

            override fun getItemCount(): Int = items.size
        }
        list.adapter = adapter
        list.setSelectedPosition(selectIdx)
        list.post {
            list.smoothScrollToPosition(selectIdx)
            list.setSelectionWithSmooth(selectIdx)
            // 默认焦点到选中项，
            // 否则遥控确认键可能落在弹窗根部导致设置点击无效
            list.layoutManager?.findViewByPosition(selectIdx)?.requestFocus()
        }
    }

    /**
     * 绑定横向多选按钮列表（独立勾选项显示 √ 前缀，互不排斥）。
     * 位置 0 约定为"关闭"动作按钮：点击清除全部勾选；其余位置独立切换勾选。
     * 勾选状态变更通过 [onToggle] 回调（position, nowSelected）通知宿主持久化并重载。
     */
    private fun setMultiAdapter(
        viewId: Int,
        items: List<String>,
        initialSelected: MutableSet<Int>,
        onToggle: (position: Int, nowSelected: Boolean) -> Unit
    ) {
        val list = findViewById<TvRecyclerView>(viewId)
        list.layoutManager = V7LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        val selected = initialSelected
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(uiR.layout.item_button, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val tv = holder.itemView.findViewById<TextView>(uiR.id.tvName)
                // 位置 0 为"关闭"：当随机颜色与花式弹幕都未勾选时显示 √（当前即关闭状态）；
                // 其余位置按勾选状态显示 √
                val checked = when {
                    position == 0 -> selected.isEmpty()
                    else -> selected.contains(position)
                }
                tv.text = if (checked) "√ " + items[position] else items[position]
                tv.setOnClickListener {
                    when {
                        position == 0 -> { // 关闭：统一清除所有玩法勾选
                            selected.remove(1)
                            selected.remove(2)
                            notifyDataSetChanged()
                            onToggle(0, false)
                        }
                        selected.contains(position) -> { // 取消勾选
                            selected.remove(position)
                            notifyDataSetChanged()
                            onToggle(position, false)
                        }
                        else -> { // 勾选
                            selected.add(position)
                            notifyDataSetChanged()
                            onToggle(position, true)
                        }
                    }
                }
            }

            override fun getItemCount(): Int = items.size
        }
        list.adapter = adapter
        // 初始聚焦到第一个勾选项，否则默认聚焦"关闭"容易误触清除
        val initialFocus = selected.firstOrNull() ?: 1
        list.post {
            list.smoothScrollToPosition(initialFocus)
            list.layoutManager?.findViewByPosition(initialFocus)?.requestFocus()
        }
    }
}
