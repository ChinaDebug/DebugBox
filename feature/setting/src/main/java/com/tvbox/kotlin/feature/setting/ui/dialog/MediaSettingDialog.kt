package com.tvbox.kotlin.feature.setting.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import com.tvbox.kotlin.core.ui.dialog.SelectDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 播放器设置对话框
 * 左栏分类（通用/EXO），右栏展示对应设置项，点击弹窗修改。
 * 焦点管理：默认选中并聚焦左侧第一个分类，右侧默认展开其设置项；
 * 左侧焦点/点击切换时同步右侧内容并重置右侧选中到第一项。
 */
class MediaSettingDialog(
    context: Context,
    private val apiConfig: ApiConfig
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val appPreferences = AppPreferences.getShared(context)

    private lateinit var titleAdapter: CategoryAdapter
    private lateinit var contentAdapter: ContentAdapter

    /** 设置项：名称 + 当前值 + 点击行为 */
    private inner class SettingItem(
        val name: String,
        val value: () -> String,
        val onClick: () -> Unit
    )

    private val categories by lazy {
        listOf(context.getString(uiR.string.media_category_general), "EXO")
    }

    /**
     * 点播首选只选内核档（存储 2=Exo、3=MX、4=Reex、5=Kodi）；
     * 历史 0/1/2 统一归一为 Exo。
     */
    private fun vodPreferredNames(): List<String> = listOf(
        "Exo", "MXPlayer", "ReexPlayer", "Kodi"
    )

    /** 存储值 → 选项下标：历史 0/1/2 归一为 0(Exo)，3/4/5 顺延为 1/2/3 */
    private fun storedToVodOption(stored: Int): Int = when {
        stored in 0..2 -> 0
        else -> (stored - 2).coerceIn(1, vodPreferredNames().lastIndex)
    }

    /** 选项下标 → 存储值：Exo 写 2，其余按内核档 3/4/5 写入 */
    private fun vodOptionToStored(pos: Int): Int = pos + 2

    private val generalItems: List<SettingItem> by lazy {
        listOf(
        SettingItem(getContext()!!.getString(uiR.string.media_vod_player_preferred), {
            vodPreferredNames()[storedToVodOption(appPreferences.getInt(AppConstants.VOD_PLAYER_PREFERRED, 0))]
        }) {
            val current = storedToVodOption(appPreferences.getInt(AppConstants.VOD_PLAYER_PREFERRED, 0))
            showSelect(context.getString(uiR.string.media_select_player), vodPreferredNames(), current) { pos ->
                scope.launch { appPreferences.put(AppConstants.VOD_PLAYER_PREFERRED, vodOptionToStored(pos)) }
                refreshContent()
            }
        },
        SettingItem(context.getString(uiR.string.media_render_mode), {
            if (appPreferences.getInt(AppConstants.PLAY_RENDER, 1) == 0) "TextureView" else "SurfaceView"
        }) {
            showSelect(context.getString(uiR.string.media_select_render), listOf("TextureView", "SurfaceView"),
                appPreferences.getInt(AppConstants.PLAY_RENDER, 1)) { pos ->
                scope.launch { appPreferences.put(AppConstants.PLAY_RENDER, pos) }
                refreshContent()
            }
        }
        )
    }

    /** EXO 解码模式设置项：0=硬解优先、1=软解优先、2=仅硬解、3=仅软解 */
    private fun buildExoItems(): List<SettingItem> {
        val items = mutableListOf<SettingItem>()
        items.add(SettingItem(context.getString(uiR.string.media_decode_mode), {
            val names = listOf(
                context.getString(uiR.string.media_decode_hard),
                context.getString(uiR.string.media_decode_soft),
                context.getString(uiR.string.media_decode_hard_only),
                context.getString(uiR.string.media_decode_soft_only)
            )
            names[appPreferences.getInt(AppConstants.EXO_RENDERER_MODE, 0).coerceIn(0, names.lastIndex)]
        }) {
            val names = listOf(
                context.getString(uiR.string.media_decode_hard),
                context.getString(uiR.string.media_decode_soft),
                context.getString(uiR.string.media_decode_hard_only),
                context.getString(uiR.string.media_decode_soft_only)
            )
            showSelect(context.getString(uiR.string.media_select_decode), names,
                appPreferences.getInt(AppConstants.EXO_RENDERER_MODE, 0)) { pos ->
                scope.launch {
                    appPreferences.put(AppConstants.EXO_RENDERER_MODE, pos)
                }
                refreshContent()
            }
        })
        return items
    }

    /** 指定分类对应的设置项（EXO 项随渲染器模式动态构建） */
    private fun contentItems(category: String): List<SettingItem> = when (category) {
        context.getString(uiR.string.media_category_general) -> generalItems
        "EXO" -> buildExoItems()
        else -> emptyList()
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(uiR.layout.dialog_media_setting)
        setCanceledOnTouchOutside(true)
        val titleList = findViewById<TvRecyclerView>(uiR.id.list_media_title)
        val contentList = findViewById<TvRecyclerView>(uiR.id.list_media_content)
        titleList.layoutManager = V7LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        contentList.layoutManager = V7LinearLayoutManager(context, RecyclerView.VERTICAL, false)

        // 左侧分类：默认选中第一个并默认展开其设置项
        titleAdapter = CategoryAdapter(categories, 0) { switchCategory(it) }
        titleList.adapter = titleAdapter
        contentAdapter = ContentAdapter(generalItems) { item -> item.onClick() }
        contentList.adapter = contentAdapter

        // 左侧焦点/点击切换时同步右侧内容并重置右侧选中到第一项
        titleList.setOnItemListener(object : TvRecyclerView.OnItemListener {
            override fun onItemPreSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {}
            override fun onItemSelected(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {
                // 焦点仍在左侧列表内才切换分类；焦点已移出（如移到右侧）时的补偿回调
                // 不触发，避免右侧操作时左侧分类/内容被误切回
                if (view?.hasFocus() != true) return
                switchCategory(position)
            }
            override fun onItemClick(tvRecyclerView: TvRecyclerView?, view: View?, position: Int) {
                switchCategory(position)
                view?.requestFocus()
            }
        })

        // 默认焦点到左侧第一个分类
        titleList.post {
            titleList.layoutManager?.findViewByPosition(0)?.requestFocus()
        }
    }

    /** 切换分类：更新左侧选中态、右侧内容与选中位置 */
    private fun switchCategory(position: Int) {
        if (position == titleAdapter.selected) return
        titleAdapter.setSelected(position)
        contentAdapter.setItems(contentItems(categories[position]))
        findViewById<TvRecyclerView>(uiR.id.list_media_content)?.setSelectedPosition(0)
    }

    private fun showSelect(title: String, items: List<String>, current: Int, onSelect: (Int) -> Unit) {
        SelectDialog(context, title, items, { it }, 1, current) { _, pos -> onSelect(pos) }.show()
    }

    private fun refreshContent() {
        contentAdapter.notifyDataSetChanged()
    }

    private class CategoryAdapter(
        private val items: List<String>,
        initialSelected: Int,
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.Holder>() {

        var selected: Int = initialSelected
            private set

        fun setSelected(pos: Int) {
            if (selected == pos) return
            selected = pos
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(uiR.layout.item_dialog_select, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.tvName.text = items[position]
            applySelectState(holder.tvName, position)
            holder.tvName.setOnClickListener { onClick(position) }
            holder.tvName.setOnFocusChangeListener { view, hasFocus ->
                // 选中项恒显选中背景；聚焦项临时高亮（未选中聚焦时也显示主题色），失焦恢复
                applySelectState(view as TextView, position, focus = hasFocus)
            }
        }

        /** 选中/聚焦项使用独立选中背景（默认态也填充主题色，不依赖焦点）；聚焦时文字用白色保证与主题色背景对比 */
        private fun applySelectState(tv: TextView, position: Int, focus: Boolean = false) {
            val isSelected = position == selected
            tv.setBackgroundResource(
                if (isSelected || focus) uiR.drawable.button_dialog_main_selected else uiR.drawable.button_dialog_main
            )
            tv.setTextColor(if (focus) Color.WHITE else if (isSelected) themeColor(tv) else Color.WHITE)
            tv.setTypeface(if (isSelected || focus) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
        }

        private fun themeColor(view: View): Int {
            val ta = view.context.theme.obtainStyledAttributes(intArrayOf(uiR.attr.color_theme))
            val color = ta.getColor(0, Color.WHITE)
            ta.recycle()
            return color
        }

        override fun getItemCount(): Int = items.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: TextView = itemView.findViewById(uiR.id.tvName)
        }
    }

    private class ContentAdapter(
        private var items: List<SettingItem>,
        private val onClick: (SettingItem) -> Unit
    ) : RecyclerView.Adapter<ContentAdapter.Holder>() {

        private var selected = 0

        fun setItems(list: List<SettingItem>) {
            items = list
            selected = 0
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(uiR.layout.item_dialog_setting_content, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.name
            holder.tvContent.text = item.value()
            val isSelected = position == selected
            holder.root.isActivated = isSelected
            holder.root.setOnClickListener { onClick(item) }
            holder.root.setOnFocusChangeListener { view, hasFocus ->
                view.isActivated = hasFocus || position == selected
            }
        }

        override fun getItemCount(): Int = items.size

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val root: View = itemView
            val tvTitle: TextView = itemView.findViewById(uiR.id.tv_title)
            val tvContent: TextView = itemView.findViewById(uiR.id.tv_content)
        }
    }
}
