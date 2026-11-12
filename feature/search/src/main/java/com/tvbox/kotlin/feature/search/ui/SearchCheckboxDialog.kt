package com.tvbox.kotlin.feature.search.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.owen.tvrecyclerview.widget.V7GridLayoutManager
import com.tvbox.kotlin.core.common.AppConstants
import com.tvbox.kotlin.core.common.bean.SourceBean
import com.tvbox.kotlin.core.common.util.AppPreferences
import com.tvbox.kotlin.core.spider.ApiConfig
import com.tvbox.kotlin.core.spider.SearchHelper
import com.tvbox.kotlin.core.ui.R as uiR
import com.tvbox.kotlin.core.ui.dialog.BaseDialog
import me.jessyan.autosize.utils.AutoSizeUtils

/**
 * 搜索源筛选弹窗：
 * - 顶部切换普通/聚合搜索模式；
 * - 全选 / 仅首页源 / 全不选 快捷操作；
 * - 网格勾选参与搜索的站点。
 */
class SearchCheckboxDialog(
    context: Context,
    private val sourceList: List<SourceBean>,
    checkedSources: HashMap<String, String>?,
    private val apiConfig: ApiConfig,
    private val searchHelper: SearchHelper,
    private val appPreferences: AppPreferences
) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    private val mCheckSources = checkedSources ?: HashMap()
    private lateinit var mGridView: TvRecyclerView
    private lateinit var checkAll: LinearLayout
    private lateinit var selectHome: LinearLayout
    private lateinit var clearAll: LinearLayout
    private lateinit var checkModeNormal: LinearLayout
    private lateinit var checkModeFast: LinearLayout
    private lateinit var ivModeNormal: ImageView
    private lateinit var ivModeFast: ImageView
    private lateinit var ivCheckAll: ImageView
    private lateinit var ivSelectHome: ImageView
    private lateinit var checkboxSearchAdapter: CheckboxSearchAdapter

    init {
        setCanceledOnTouchOutside(true)
        setCancelable(true)
        setContentView(com.tvbox.kotlin.feature.search.R.layout.dialog_checkbox_search)
        initView()
    }

    private fun initView() {
        mGridView = findViewById(com.tvbox.kotlin.feature.search.R.id.mGridView)
        checkAll = findViewById(com.tvbox.kotlin.feature.search.R.id.checkAll)
        selectHome = findViewById(com.tvbox.kotlin.feature.search.R.id.selectHome)
        clearAll = findViewById(com.tvbox.kotlin.feature.search.R.id.clearAll)
        checkModeNormal = findViewById(com.tvbox.kotlin.feature.search.R.id.checkModeNormal)
        checkModeFast = findViewById(com.tvbox.kotlin.feature.search.R.id.checkModeFast)
        ivModeNormal = findViewById(com.tvbox.kotlin.feature.search.R.id.ivModeNormal)
        ivModeFast = findViewById(com.tvbox.kotlin.feature.search.R.id.ivModeFast)
        ivCheckAll = findViewById(com.tvbox.kotlin.feature.search.R.id.ivCheckAll)
        ivSelectHome = findViewById(com.tvbox.kotlin.feature.search.R.id.ivSelectHome)

        updateModeDisplay()
        updateButtonStates()

        checkboxSearchAdapter = CheckboxSearchAdapter {
            updateButtonStates()
            // 勾选变更立即持久化，每次点击即保存
            searchHelper.putCheckedSources(mCheckSources)
        }
        mGridView.setHasFixedSize(true)

        // 源数/10 决定列数（2~3 列）
        val size = sourceList.size
        var spanCount = (size / 10).toInt()
        if (spanCount <= 1) spanCount = 2
        if (spanCount >= 3) spanCount = 3
        mGridView.layoutManager = V7GridLayoutManager(context, spanCount)

        val root = findViewById<View>(com.tvbox.kotlin.feature.search.R.id.root)
        root.layoutParams = root.layoutParams.apply {
            width = AutoSizeUtils.mm2px(context, (280 + 200 * (spanCount - 1)).toFloat())
        }

        mGridView.adapter = checkboxSearchAdapter
        checkboxSearchAdapter.setData(sourceList, mCheckSources)
        updateGridViewEnabledState()

        var pos = 0
        for (i in sourceList.indices) {
            if (mCheckSources.containsKey(sourceList[i].key)) {
                pos = i
                break
            }
        }
        val scrollPosition = pos
        mGridView.post { mGridView.smoothScrollToPosition(scrollPosition) }

        checkModeNormal.setOnClickListener {
            // 同步写入内存缓存后立即刷新，避免关闭弹窗再打开才生效
            appPreferences.putSync(AppConstants.FAST_SEARCH_MODE, false)
            updateModeDisplay()
            updateGridViewEnabledState()
        }
        checkModeFast.setOnClickListener {
            appPreferences.putSync(AppConstants.FAST_SEARCH_MODE, true)
            updateModeDisplay()
            updateGridViewEnabledState()
        }
        checkAll.setOnClickListener {
            for (sourceBean in sourceList) {
                val key = sourceBean.key ?: continue
                if (!mCheckSources.containsKey(key)) mCheckSources[key] = "1"
            }
            checkboxSearchAdapter.setData(sourceList, mCheckSources)
            updateButtonStates()
            searchHelper.putCheckedSources(mCheckSources)
        }
        selectHome.setOnClickListener {
            val homeSource = apiConfig.homeSourceBean
            if (homeSource == null || !homeSource.isSearchable()) {
                Toast.makeText(context, uiR.string.search_home_not_searchable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            mCheckSources.clear()
            homeSource.key?.let { mCheckSources[it] = "1" }
            checkboxSearchAdapter.setData(sourceList, mCheckSources)
            updateButtonStates()
            searchHelper.putCheckedSources(mCheckSources)
            Toast.makeText(
                context,
                String.format(context.getString(uiR.string.search_selected_home), homeSource.name),
                Toast.LENGTH_SHORT
            ).show()
        }
        clearAll.setOnClickListener {
            if (mCheckSources.isEmpty()) return@setOnClickListener
            mCheckSources.clear()
            checkboxSearchAdapter.setData(sourceList, mCheckSources)
            updateButtonStates()
            searchHelper.putCheckedSources(mCheckSources)
        }
    }

    private fun updateModeDisplay() {
        val isFastMode = appPreferences.getBoolean(AppConstants.FAST_SEARCH_MODE, false)
        ivModeNormal.setImageResource(if (isFastMode) uiR.drawable.shape_search_clearall else uiR.drawable.shape_search_checkall)
        ivModeFast.setImageResource(if (isFastMode) uiR.drawable.shape_search_checkall else uiR.drawable.shape_search_clearall)
    }

    private fun updateGridViewEnabledState() {
        val isFastMode = appPreferences.getBoolean(AppConstants.FAST_SEARCH_MODE, false)
        checkboxSearchAdapter.setEnabled(!isFastMode)
        checkAll.isEnabled = !isFastMode
        checkAll.alpha = if (isFastMode) 0.5f else 1.0f
        selectHome.isEnabled = !isFastMode
        selectHome.alpha = if (isFastMode) 0.5f else 1.0f
        clearAll.isEnabled = !isFastMode
        clearAll.alpha = if (isFastMode) 0.5f else 1.0f
    }

    private fun updateButtonStates() {
        if (sourceList.isEmpty()) {
            ivCheckAll.setImageResource(uiR.drawable.shape_search_clearall)
            ivSelectHome.setImageResource(uiR.drawable.shape_search_clearall)
            return
        }
        var allChecked = true
        var checkedCount = 0
        for (sourceBean in sourceList) {
            if (mCheckSources.containsKey(sourceBean.key)) checkedCount++ else allChecked = false
        }
        ivCheckAll.setImageResource(if (allChecked) uiR.drawable.shape_search_checkall else uiR.drawable.shape_search_clearall)

        val homeSource = apiConfig.homeSourceBean
        val onlyHomeSelected = homeSource != null && homeSource.isSearchable() &&
            checkedCount == 1 && mCheckSources.containsKey(homeSource.key)
        ivSelectHome.setImageResource(if (onlyHomeSelected) uiR.drawable.shape_search_checkall else uiR.drawable.shape_search_clearall)
    }
}

/** 搜索源勾选列表适配器 */
private class CheckboxSearchAdapter(
    private val onCheckedChanged: () -> Unit
) : RecyclerView.Adapter<CheckboxSearchAdapter.Holder>() {

    private val data = ArrayList<SourceBean>()
    private var mCheckedSources = HashMap<String, String>()
    private var isEnabled = true

    fun setData(newData: List<SourceBean>, checkedSources: HashMap<String, String>) {
        data.clear()
        data.addAll(newData)
        // 直接引用外部勾选集合，保证勾选状态与外部一致
        mCheckedSources = checkedSources
        notifyDataSetChanged()
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(com.tvbox.kotlin.core.ui.R.layout.item_dialog_checkbox_search, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val sourceBean = data[position]
        holder.tvSourceName.text = sourceBean.name
        val isChecked = mCheckedSources.containsKey(sourceBean.key)
        holder.ivSourceCheck.setImageResource(
            if (isChecked) com.tvbox.kotlin.core.ui.R.drawable.shape_search_checkall
            else com.tvbox.kotlin.core.ui.R.drawable.shape_search_clearall
        )
        holder.itemSourceLayout.alpha = if (isEnabled) 1.0f else 0.5f
        holder.itemSourceLayout.setOnClickListener {
            if (!isEnabled) return@setOnClickListener
            if (mCheckedSources.containsKey(sourceBean.key)) {
                mCheckedSources.remove(sourceBean.key)
            } else {
                sourceBean.key?.let { mCheckedSources[it] = "1" }
            }
            notifyItemChanged(holder.bindingAdapterPosition)
            onCheckedChanged()
        }
    }

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val itemSourceLayout: LinearLayout = itemView.findViewById(com.tvbox.kotlin.core.ui.R.id.itemSourceLayout)
        val ivSourceCheck: ImageView = itemView.findViewById(com.tvbox.kotlin.core.ui.R.id.ivSourceCheck)
        val tvSourceName: TextView = itemView.findViewById(com.tvbox.kotlin.core.ui.R.id.tvSourceName)
    }
}
