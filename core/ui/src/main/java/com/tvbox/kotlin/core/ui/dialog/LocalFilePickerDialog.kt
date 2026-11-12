package com.tvbox.kotlin.core.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.tvbox.kotlin.core.ui.R as uiR
import java.io.File
import java.util.Collections

/**
 * 本地文件/文件夹选择对话框，替代 SAF 文件选择器（自定义 SAF 文件管理器）。
 * 支持文件夹选择（本地盘）和文件选择（字幕/壁纸等），兼容 TV 遥控器焦点与手机触摸。
 * 文件选择模式按扩展名过滤（[extensions]），点击文件即回调路径。
 */
class LocalFilePickerDialog(context: Context) : BaseDialog(context, uiR.style.CustomDialogStyle) {

    /** 选择模式：FOLDER 选择文件夹；FILE 选择文件 */
    enum class PickMode {
        FOLDER, FILE
    }

    interface OnPathSelectedListener {
        fun onPathSelected(path: String)
    }

    var onPathSelectedListener: OnPathSelectedListener? = null

    var mode: PickMode = PickMode.FOLDER
    var startPath: String? = null

    /** 文件模式下的扩展名白名单（不带点），为空时不过滤 */
    var extensions: List<String> = emptyList()

    private var currentDir: File? = null
    private val adapter = FilePickerAdapter { file, _ ->
        if (file.isDirectory) {
            currentDir = file
            refreshFiles()
        } else if (mode == PickMode.FILE) {
            onPathSelectedListener?.onPathSelected(file.absolutePath)
            dismiss()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(uiR.layout.dialog_local_file_picker)
        initView()
        initStartDir()
        refreshFiles()
    }

    private fun initStartDir() {
        startPath?.let {
            val startFile = File(it)
            if (startFile.exists()) {
                currentDir = if (startFile.isDirectory) startFile else startFile.parentFile
            }
        }
        if (currentDir == null) {
            currentDir = Environment.getExternalStorageDirectory()
        }
        if (currentDir == null || !currentDir!!.canRead()) {
            currentDir = context.filesDir
        }
    }

    private fun initView() {
        val recyclerView = findViewById<TvRecyclerView>(uiR.id.rv_files)
        recyclerView.adapter = adapter

        val btnSelectCurrent = findViewById<TextView>(uiR.id.btn_select_current)
        val btnParent = findViewById<TextView>(uiR.id.btn_parent)

        // 文件选择模式下隐藏"选择当前文件夹"按钮，点击文件即确认
        if (mode == PickMode.FILE) {
            btnSelectCurrent.visibility = View.GONE
        } else {
            btnSelectCurrent.setOnClickListener {
                currentDir?.let {
                    onPathSelectedListener?.onPathSelected(it.absolutePath)
                }
                dismiss()
            }
        }
        btnParent.setOnClickListener { navigateToParent() }
    }

    private fun navigateToParent() {
        val parent = currentDir?.parentFile
        if (parent != null && parent.canRead()) {
            currentDir = parent
            refreshFiles()
        } else {
            Toast.makeText(context, context.getString(uiR.string.local_file_picker_parent), Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshFiles() {
        findViewById<TextView>(uiR.id.tv_title).text = currentDir?.absolutePath

        val files = currentDir?.listFiles { file ->
            if (file.isDirectory) {
                true
            } else if (mode == PickMode.FOLDER) {
                false
            } else if (extensions.isEmpty()) {
                true
            } else {
                val name = file.name.lowercase()
                extensions.any { name.endsWith("." + it.lowercase()) }
            }
        }

        val list = files?.toMutableList() ?: mutableListOf()
        // 文件夹排在前面，文件排在后面，同类型按名称排序
        Collections.sort(list) { o1, o2 ->
            when {
                o1.isDirectory && !o2.isDirectory -> -1
                !o1.isDirectory && o2.isDirectory -> 1
                else -> o1.name.compareTo(o2.name, ignoreCase = true)
            }
        }
        adapter.setData(list)
    }
}

/** 本地文件选择器列表适配器，文件夹图标 + 文件名 */
class FilePickerAdapter(
    private val onClick: (File, Int) -> Unit
) : RecyclerView.Adapter<FilePickerAdapter.Holder>() {

    private val data = mutableListOf<File>()

    fun setData(list: List<File>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(uiR.layout.item_local_file, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val file = data[position]
        holder.tvName.text = file.name
        if (file.isDirectory) {
            holder.ivIcon.visibility = View.VISIBLE
            holder.ivIcon.setImageResource(uiR.drawable.icon_folder)
        } else {
            holder.ivIcon.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onClick(file, position) }
    }

    override fun getItemCount(): Int = data.size

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(uiR.id.iv_icon)
        val tvName: TextView = itemView.findViewById(uiR.id.tv_name)
    }
}
