package com.tvbox.kotlin.core.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tvbox.kotlin.core.ui.R

class SearchKeyboard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val keys: List<String> = buildList {
        add(context.getString(R.string.act_search_rem))
        add(context.getString(R.string.act_search_del))
        addAll(listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
    }

    private var searchKeyListener: OnSearchKeyListener? = null

    private val focusChangeListener = OnFocusChangeListener { itemView, hasFocus ->
        if (itemView != null) {
            itemView.isSelected = hasFocus
        }
    }

    init {
        initView()
    }

    private fun initView() {
        val view = LayoutInflater.from(context).inflate(R.layout.layout_keyborad, this)
        val recyclerView = view.findViewById<RecyclerView>(R.id.mRecyclerView)
        val manager = GridLayoutManager(context, 6)
        recyclerView.layoutManager = manager
        val adapter = KeyboardAdapter(keys) { position ->
            searchKeyListener?.onSearchKey(position, keys[position])
        }
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (position == 0 || position == 1) 3 else 1
            }
        }
        recyclerView.adapter = adapter
    }

    fun setOnSearchKeyListener(listener: OnSearchKeyListener?) {
        searchKeyListener = listener
    }

    private inner class KeyboardAdapter(
        private val data: List<String>,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<KeyboardAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_keyboard, parent, false)
            return ViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(data[position])
        }

        override fun getItemCount(): Int = data.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val keyName: TextView = itemView.findViewById(R.id.keyName)

            init {
                if (itemView.isFocusable && itemView.onFocusChangeListener == null) {
                    itemView.onFocusChangeListener = focusChangeListener
                }
                itemView.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        onItemClick(pos)
                    }
                }
            }

            fun bind(key: String) {
                keyName.text = key
            }
        }
    }

    interface OnSearchKeyListener {
        fun onSearchKey(pos: Int, key: String)
    }
}
