package com.tvbox.kotlin.feature.drive.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.owen.tvrecyclerview.widget.TvRecyclerView
import com.tvbox.kotlin.core.common.bean.DriveFolderFile
import com.tvbox.kotlin.core.common.util.StorageDriveType
import com.tvbox.kotlin.feature.drive.R
import com.tvbox.kotlin.core.ui.R as CoreUiR

/**
 * 网盘列表适配器（紧凑行式布局）。
 *
 * 双模式：
 * - 驱动器模式：展示已配置的网盘（名称 + 类型图标）；
 * - 文件模式：展示文件/文件夹（名称 + 类型图标 + 修改日期 + 文件类型角标）。
 *
 * 删除模式：显示 [delDrive] 删除图标。
 */
class DriveAdapter : RecyclerView.Adapter<DriveAdapter.DriveHolder>() {

    private val data = ArrayList<DriveFolderFile>()
    private var deleteMode = false

    private var onItemClickListener: ((DriveFolderFile, Int) -> Unit)? = null
    private var onItemLongClickListener: ((DriveFolderFile, Int) -> Unit)? = null

    fun setNewData(list: List<DriveFolderFile>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    fun remove(position: Int) {
        if (position < 0 || position >= data.size) return
        data.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, data.size - position)
    }

    fun getData(): List<DriveFolderFile> = data

    fun setDeleteMode(enabled: Boolean) {
        if (deleteMode == enabled) return
        deleteMode = enabled
        notifyDataSetChanged()
    }

    fun setOnItemClickListener(listener: (DriveFolderFile, Int) -> Unit) {
        onItemClickListener = listener
    }

    fun setOnItemLongClickListener(listener: (DriveFolderFile, Int) -> Unit) {
        onItemLongClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DriveHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_drive, parent, false)
        return DriveHolder(view)
    }

    override fun onBindViewHolder(holder: DriveHolder, position: Int) {
        holder.bind(data[position], deleteMode, onItemClickListener, onItemLongClickListener)
    }

    override fun getItemCount(): Int = data.size

    class DriveHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val mItemLayout: LinearLayout = itemView.findViewById(R.id.mItemLayout)
        private val imgItem: ImageView = itemView.findViewById(R.id.imgItem)
        private val txtItemName: TextView = itemView.findViewById(R.id.txtItemName)
        private val txtMediaName: TextView = itemView.findViewById(R.id.txtMediaName)
        private val txtModifiedDate: TextView = itemView.findViewById(R.id.txtModifiedDate)
        private val delDrive: ImageView = itemView.findViewById(R.id.delDrive)

        fun bind(
            item: DriveFolderFile,
            deleteMode: Boolean,
            click: ((DriveFolderFile, Int) -> Unit)?,
            longClick: ((DriveFolderFile, Int) -> Unit)?
        ) {
            txtItemName.text = item.name.orEmpty()

            if (item.isDrive()) {
                bindDriveItem(item)
            } else {
                bindFileItem(item)
            }

            delDrive.visibility = if (deleteMode) View.VISIBLE else View.GONE

            mItemLayout.setOnFocusChangeListener { v, hasFocus ->
                txtItemName.isSelected = hasFocus
                (itemView.parent as? TvRecyclerView)?.onFocusChange(itemView, hasFocus)
            }
            mItemLayout.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) click?.invoke(item, pos)
            }
            mItemLayout.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    longClick?.invoke(item, pos)
                    true
                } else false
            }
        }

        private fun bindDriveItem(item: DriveFolderFile) {
            txtMediaName.visibility = View.GONE
            txtModifiedDate.visibility = View.GONE
            val iconRes = when (item.getDriveType()) {
                StorageDriveType.TYPE.WEBDAV -> CoreUiR.drawable.icon_circle_node
                StorageDriveType.TYPE.ALISTWEB -> CoreUiR.drawable.icon_alist
                else -> CoreUiR.drawable.icon_sdcard
            }
            imgItem.setImageResource(iconRes)
        }

        private fun bindFileItem(item: DriveFolderFile) {
            txtMediaName.visibility = View.GONE
            txtModifiedDate.visibility = View.GONE
            if (item.isFile) {
                val iconRes = if (StorageDriveType.isVideoType(item.fileType)) {
                    CoreUiR.drawable.icon_film
                } else {
                    CoreUiR.drawable.icon_file
                }
                imgItem.setImageResource(iconRes)
                if (!item.fileType.isNullOrEmpty()) {
                    txtMediaName.text = item.fileType
                    txtMediaName.visibility = View.VISIBLE
                }
            } else {
                imgItem.setImageResource(CoreUiR.drawable.icon_folder)
            }
            val dateStr = item.getFormattedLastModified()
            if (dateStr.isNotEmpty()) {
                txtModifiedDate.text = dateStr
                txtModifiedDate.visibility = View.VISIBLE
            }
        }
    }
}
