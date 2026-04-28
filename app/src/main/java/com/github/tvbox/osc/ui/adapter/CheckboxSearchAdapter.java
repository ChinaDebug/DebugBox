package com.github.tvbox.osc.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.SearchHelper;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class CheckboxSearchAdapter extends ListAdapter<SourceBean, CheckboxSearchAdapter.ViewHolder> {

    public interface OnCheckedChangedListener {
        void onCheckedChanged();
    }

    private OnCheckedChangedListener onCheckedChangedListener;
    private boolean isEnabled = true;

    public void setOnCheckedChangedListener(OnCheckedChangedListener listener) {
        this.onCheckedChangedListener = listener;
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        notifyDataSetChanged();
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public CheckboxSearchAdapter(DiffUtil.ItemCallback<SourceBean> diffCallback) {
        super(diffCallback);
    }

    @Override
    public ViewHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_dialog_checkbox_search, parent, false));
    }

    private void setCheckedSource(HashMap<String, String> checkedSources) {
        mCheckedSources = checkedSources;
    }

    private ArrayList<SourceBean> data = new ArrayList<>();
    public HashMap<String, String> mCheckedSources = new HashMap<>();

    public void setData(List<SourceBean> newData, HashMap<String, String> checkedSources, boolean saveToStorage) {
        data.clear();
        data.addAll(newData);
        setCheckedSource(checkedSources);
        notifyDataSetChanged();
        if (saveToStorage) {
            SearchHelper.putCheckedSources(checkedSources);
        }
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        int pos = holder.getAdapterPosition();
        SourceBean sourceBean = data.get(pos);
        holder.tvSourceName.setText(sourceBean.getName());
        boolean isChecked = mCheckedSources != null && mCheckedSources.containsKey(sourceBean.getKey());
        holder.ivSourceCheck.setImageResource(isChecked ? R.drawable.shape_search_checkall : R.drawable.shape_search_clearall);
        holder.itemSourceLayout.setTag(sourceBean);
        
        // 设置视觉状态：禁用时半透明
        holder.itemSourceLayout.setAlpha(isEnabled ? 1.0f : 0.5f);
        
        // 设置点击事件：禁用时清空点击监听
        if (isEnabled) {
            holder.itemSourceLayout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean currentlyChecked = mCheckedSources.containsKey(sourceBean.getKey());
                    if (currentlyChecked) {
                        mCheckedSources.remove(sourceBean.getKey());
                    } else {
                        mCheckedSources.put(sourceBean.getKey(), "1");
                    }
                    SearchHelper.putCheckedSource(sourceBean.getKey(), !currentlyChecked);
                    notifyItemChanged(pos);
                    if (onCheckedChangedListener != null) {
                        onCheckedChangedListener.onCheckedChanged();
                    }
                }
            });
        } else {
            holder.itemSourceLayout.setOnClickListener(null);
        }
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public LinearLayout itemSourceLayout;
        public ImageView ivSourceCheck;
        public TextView tvSourceName;

        public ViewHolder(View view) {
            super(view);
            itemSourceLayout = (LinearLayout) view.findViewById(R.id.itemSourceLayout);
            ivSourceCheck = (ImageView) view.findViewById(R.id.ivSourceCheck);
            tvSourceName = (TextView) view.findViewById(R.id.tvSourceName);
        }
    }
}
