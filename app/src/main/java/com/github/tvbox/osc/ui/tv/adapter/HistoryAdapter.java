package com.github.tvbox.osc.ui.tv.adapter;

import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.UpdateCheckManager;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class HistoryAdapter extends BaseQuickAdapter<VodInfo, BaseViewHolder> {
    // 监听 item 焦点变化，仅当卡片获取焦点时才让集名跑马灯滚动
    private static final View.OnFocusChangeListener mNoteFocusListener = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            // 保留 TvRecyclerView 原有的焦点处理，避免覆盖后导致片名无法滚动
            if (v.getParent() instanceof TvRecyclerView) {
                ((TvRecyclerView) v.getParent()).onFocusChange(v, hasFocus);
            }
            TextView tvNote = v.findViewById(R.id.tvNote);
            if (tvNote != null && tvNote.getVisibility() == View.VISIBLE) {
                // 使用 hasFocus() 兼容焦点在子控件上的情况
                tvNote.setSelected(v.hasFocus());
            }
        }
    };

    public HistoryAdapter() {
        super(R.layout.item_grid, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, VodInfo item) {
        // takagen99: Add Delete Mode
        FrameLayout tvDel = helper.getView(R.id.delFrameLayout);
        if (HawkConfig.hotVodDelete) {
            tvDel.setVisibility(View.VISIBLE);
        } else {
            tvDel.setVisibility(View.GONE);
        }

        TextView tvYear = helper.getView(R.id.tvYear);
        SourceBean source = ApiConfig.get().getSource(item.sourceKey);
        tvYear.setText(source != null ? source.getName() : "");

        helper.setVisible(R.id.tvLang, false);
        helper.setVisible(R.id.tvArea, false);
        TextView tvNotePrefix = helper.getView(R.id.tvNotePrefix);
        TextView tvNote = helper.getView(R.id.tvNote);
        if (item.note == null || item.note.isEmpty()) {
            helper.setVisible(R.id.tvNote, false);
            tvNotePrefix.setVisibility(View.GONE);
        } else {
            helper.setVisible(R.id.tvNote, true);
            tvNote.setText(item.note);
            // 根据当前 item 焦点状态初始化跑马灯，只有获取焦点时才滚动
            tvNote.setSelected(helper.itemView.hasFocus());
            // 历史记录场景下固定显示“看到”前缀，集名单独滚动
            tvNotePrefix.setText("看到");
            tvNotePrefix.setVisibility(View.VISIBLE);
        }
        helper.itemView.setOnFocusChangeListener(mNoteFocusListener);
        helper.setText(R.id.tvName, item.name);
        TextView tvUpdateBadge = helper.getView(R.id.tvUpdateBadge);
        boolean hasUpdate = UpdateCheckManager.get().hasVideoUpdate(item.sourceKey, item.id);
        tvUpdateBadge.setVisibility(hasUpdate ? View.VISIBLE : View.GONE);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        //由于部分电视机使用glide报错
        if (!TextUtils.isEmpty(item.pic)) {
            // takagen99 : Use Glide instead
            ImgUtil.load(item.pic, ivThumb, 14); 
        } else {
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
        }
    }
}
