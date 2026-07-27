package com.github.tvbox.osc.ui.adapter;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.github.tvbox.osc.R;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本地文件选择器列表适配器，同时适配 TV 遥控器焦点与手机触摸点击
 */
public class LocalFilePickerAdapter extends RecyclerView.Adapter<LocalFilePickerAdapter.FileViewHolder> {

    private final List<File> data = new ArrayList<>();
    private OnFileClickListener listener;

    public interface OnFileClickListener {
        void onFileClick(File file, int position);
    }

    public void setOnFileClickListener(OnFileClickListener listener) {
        this.listener = listener;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setData(List<File> newData) {
        data.clear();
        if (newData != null) {
            data.addAll(newData);
        }
        notifyDataSetChanged();
    }

    public File getItem(int position) {
        return data.get(position);
    }

    @NonNull
    @NotNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull @NotNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_local_file, parent, false);
        return new FileViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull @NotNull FileViewHolder holder, @SuppressLint("RecyclerView") int position) {
        File file = data.get(position);
        holder.tvName.setText(file.getName());
        if (file.isDirectory()) {
            holder.ivIcon.setVisibility(View.VISIBLE);
            holder.ivIcon.setImageResource(R.drawable.icon_folder);
        } else {
            holder.ivIcon.setVisibility(View.GONE);
        }
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFileClick(file, position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class FileViewHolder extends RecyclerView.ViewHolder {

        final ImageView ivIcon;
        final TextView tvName;

        FileViewHolder(@NonNull @NotNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.iv_icon);
            tvName = itemView.findViewById(R.id.tv_name);
        }
    }
}
