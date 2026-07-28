package com.github.tvbox.osc.ui.adapter;

import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.MovieSort;

import java.util.ArrayList;

/**
 * @author pj567
 * @date :2020/12/21
 * @description:
 */
public class SortAdapter extends BaseQuickAdapter<MovieSort.SortData, BaseViewHolder> {
    // 当前激活（对应 ViewPager 正在显示）的分类位置，焦点移入内容区后仍保持高亮
    private int activatedPosition = -1;

    public SortAdapter() {
        super(R.layout.item_home_sort, new ArrayList<>());
    }

    public void setActivatedPosition(int position) {
        int oldPosition = this.activatedPosition;
        if (oldPosition == position) {
            return;
        }
        this.activatedPosition = position;
        if (oldPosition >= 0 && oldPosition < getData().size()) {
            notifyItemChanged(oldPosition);
        }
        if (position >= 0 && position < getData().size()) {
            notifyItemChanged(position);
        }
    }

    public int getActivatedPosition() {
        return activatedPosition;
    }

    @Override
    protected void convert(BaseViewHolder helper, MovieSort.SortData item) {
        helper.setText(R.id.tvTitle, item.name);
        int position = helper.getBindingAdapterPosition();
        boolean isActivated = position == activatedPosition;
        helper.itemView.setActivated(isActivated);
        // 刷新时兜底重置文字颜色与缩放，避免 activated/focused 取消后样式残留
        TextView textView = helper.getView(R.id.tvTitle);
        if (textView != null) {
            boolean highlight = isActivated || helper.itemView.isFocused();
            textView.getPaint().setFakeBoldText(highlight);
            textView.setTextColor(ContextCompat.getColor(mContext, highlight ? R.color.color_FFFFFF : R.color.color_FFFFFF_70));
            textView.invalidate();
        }
        helper.itemView.setScaleX(isActivated ? 1.1f : 1.0f);
        helper.itemView.setScaleY(isActivated ? 1.1f : 1.0f);
    }
}