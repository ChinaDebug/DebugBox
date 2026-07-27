package com.github.tvbox.osc.ui.tv.adapter;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.UpdateCheckManager;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 首页热门/历史推荐适配器（TV 焦点版本）
 */
public class HomeHotVodAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
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

    private int defaultWidth;
    private final ImgUtil.Style style;
    private String tvYearValue;

    public HomeHotVodAdapter(ImgUtil.Style style,String tvYear) {
        super(R.layout.item_user_hot_vod, new ArrayList<>());
        if(style!=null){
            this.defaultWidth=ImgUtil.getStyleDefaultWidth(style);
        }
        this.style=style;
        this.tvYearValue=tvYear;
    }

    public void setTitle(String title) {
        this.tvYearValue = title;
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {

        // takagen99: Add Delete Mode
        FrameLayout tvDel = helper.getView(R.id.delFrameLayout);
        if (HawkConfig.hotVodDelete) {
            tvDel.setVisibility(View.VISIBLE);
        } else {
            tvDel.setVisibility(View.GONE);
        }

        // check if set as last watched
        TextView tvYear = helper.getView(R.id.tvYear);
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) {
            tvYear.setVisibility(View.VISIBLE);
            SourceBean source = ApiConfig.get().getSource(item.sourceKey);
            if(source!=null){
                tvYearValue=source.getName();
            }else {
                tvYearValue="搜";

            }
        }
        tvYear.setText(tvYearValue);
        // 首页推荐模式不显示“看到”前缀，仅在历史模式下显示
        boolean isHistoryMode = Hawk.get(HawkConfig.HOME_REC, 0) == 2;
        TextView tvNotePrefix = helper.getView(R.id.tvNotePrefix);
        TextView tvNote = helper.getView(R.id.tvNote);
        if (item.note == null || item.note.isEmpty()) {
            tvNote.setVisibility(View.GONE);
            tvNotePrefix.setVisibility(View.GONE);
        } else {
            tvNote.setText(item.note);
            tvNote.setVisibility(View.VISIBLE);
            // 根据当前 item 焦点状态初始化跑马灯，只有获取焦点时才滚动
            tvNote.setSelected(helper.itemView.hasFocus());
            if (isHistoryMode) {
                // 首页历史记录场景下固定显示“看到”前缀，集名单独滚动
                tvNotePrefix.setText("看到");
                tvNotePrefix.setVisibility(View.VISIBLE);
            } else {
                tvNotePrefix.setVisibility(View.GONE);
            }
        }
        helper.itemView.setOnFocusChangeListener(mNoteFocusListener);
        helper.setText(R.id.tvName, item.name);

        ImageView ivThumb = helper.getView(R.id.ivThumb);

        int newWidth = ImgUtil.defaultWidth;
        int newHeight = ImgUtil.defaultHeight;
        if(style!=null){
            newWidth = defaultWidth;
            newHeight = (int)(newWidth / style.ratio);
        }
        //由于部分电视机使用glide报错
        if (!TextUtils.isEmpty(item.pic)) {
            ImgUtil.load(item.pic, ivThumb, 14);
        } else {
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
        }
        applyStyleToImage(ivThumb);

        TextView tvUpdateBadge = helper.getView(R.id.tvUpdateBadge);
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 2 && !TextUtils.isEmpty(item.sourceKey) && !TextUtils.isEmpty(item.id)) {
            boolean hasUpdate = UpdateCheckManager.get().hasVideoUpdate(item.sourceKey, item.id);
            tvUpdateBadge.setVisibility(hasUpdate ? View.VISIBLE : View.GONE);
        } else {
            tvUpdateBadge.setVisibility(View.GONE);
        }
    }
    /**
     * 根据传入的 style 动态设置 ImageView 的高度：高度 = 宽度 / ratio
     */
    private void applyStyleToImage(final ImageView ivThumb) {
        if(style!=null){
            ViewGroup container = (ViewGroup) ivThumb.getParent();
            int width = defaultWidth;
            int height = (int) (width / style.ratio);
            ViewGroup.LayoutParams containerParams = container.getLayoutParams();
            containerParams.height = AutoSizeUtils.mm2px(mContext, height); // 高度
            containerParams.width = AutoSizeUtils.mm2px(mContext, width); // 宽度
            container.setLayoutParams(containerParams);
        }
    }
}
