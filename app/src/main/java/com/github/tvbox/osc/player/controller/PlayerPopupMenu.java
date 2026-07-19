package com.github.tvbox.osc.player.controller;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.github.tvbox.osc.R;

import java.util.List;
import java.util.Set;

/**
 * 播放器控制栏 PopupWindow 风格菜单工具类
 * <p>
 * 在按钮位置向上弹出半透明圆角浮窗菜单，复用 shape_dialog_bg_main 容器风格
 * 与 button_dialog_main 选项风格，TV 端 D-pad 焦点自带白色描边
 */
public class PlayerPopupMenu {

    /** 单选回调：点击立即触发并关闭菜单 */
    public interface OnSingleSelectCallback {
        void onSelect(int position);
    }

    /** 多选回调：每次切换状态触发，菜单不关闭，由用户按返回键关闭 */
    public interface OnMultiToggleCallback {
        void onToggle(int position, boolean nowSelected);
    }

    private final PopupWindow mPopupWindow;
    private final LinearLayout mContainer;
    private final View mAnchor;
    private final int mItemWidth;

    private PlayerPopupMenu(Context context, View anchor, int itemWidth) {
        this.mAnchor = anchor;
        this.mItemWidth = itemWidth;
        mContainer = (LinearLayout) LayoutInflater.from(context).inflate(R.layout.player_popup_menu, null);
        mPopupWindow = new PopupWindow(mContainer,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        // 设置焦点可获取，TV 端 D-pad 才能导航到菜单项
        mPopupWindow.setFocusable(true);
        // 外部点击关闭
        mPopupWindow.setOutsideTouchable(true);
        // 背景透明，由容器自身的 shape_dialog_bg_main 提供视觉效果
        mPopupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        // 关闭时刷新底层 View 状态，避免按钮焦点残留
        mPopupWindow.setOnDismissListener(null);
    }

    /**
     * 显示单选菜单：当前选中项前缀 √，点击立即触发回调并关闭
     *
     * @param context         上下文
     * @param anchor          锚定按钮
     * @param items           选项文案列表
     * @param selectedPos     当前选中位置
     * @param itemWidth       选项宽度（像素）
     * @param callback        选中回调
     */
    public static PlayerPopupMenu showSingle(Context context, View anchor,
                                             List<String> items, int selectedPos,
                                             int itemWidth,
                                             OnSingleSelectCallback callback) {
        PlayerPopupMenu menu = new PlayerPopupMenu(context, anchor, itemWidth);
        for (int i = 0; i < items.size(); i++) {
            final int pos = i;
            TextView item = (TextView) LayoutInflater.from(context)
                    .inflate(R.layout.item_player_popup_menu, menu.mContainer, false);
            // 选中项前缀 √
            item.setText((pos == selectedPos ? "√ " : "") + items.get(pos));
            // 设置固定宽度保证菜单整齐
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(itemWidth > 0 ? itemWidth
                    : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = context.getResources().getDimensionPixelSize(R.dimen.vs_5);
            item.setLayoutParams(lp);
            item.setOnClickListener(v -> {
                menu.mPopupWindow.dismiss();
                if (callback != null) {
                    callback.onSelect(pos);
                }
            });
            menu.mContainer.addView(item);
        }
        // 单选：默认聚焦当前选中项
        int focusPos = (selectedPos >= 0 && selectedPos < items.size()) ? selectedPos : 0;
        menu.showUpward(focusPos);
        return menu;
    }

    /**
     * 显示多选菜单：已选项前缀 √，点击只切换状态不关闭，由用户按返回键关闭
     *
     * @param context          上下文
     * @param anchor           锚定按钮
     * @param items            选项文案列表
     * @param selectedPositions 已选位置集合
     * @param itemWidth        选项宽度（像素）
     * @param callback         切换回调
     */
    public static PlayerPopupMenu showMulti(Context context, View anchor,
                                            List<String> items, Set<Integer> selectedPositions,
                                            int itemWidth,
                                            OnMultiToggleCallback callback) {
        PlayerPopupMenu menu = new PlayerPopupMenu(context, anchor, itemWidth);
        for (int i = 0; i < items.size(); i++) {
            final int pos = i;
            final TextView item = (TextView) LayoutInflater.from(context)
                    .inflate(R.layout.item_player_popup_menu, menu.mContainer, false);
            boolean checked = selectedPositions != null && selectedPositions.contains(pos);
            item.setText((checked ? "√ " : "") + items.get(pos));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(itemWidth > 0 ? itemWidth
                    : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = context.getResources().getDimensionPixelSize(R.dimen.vs_5);
            item.setLayoutParams(lp);
            item.setOnClickListener(v -> {
                boolean nowChecked = !item.getText().toString().startsWith("√");
                item.setText((nowChecked ? "√ " : "") + items.get(pos));
                if (callback != null) {
                    callback.onToggle(pos, nowChecked);
                }
            });
            menu.mContainer.addView(item);
        }
        // 多选：默认聚焦第一个已选项，未选任何项则聚焦第一个
        int focusPos = 0;
        if (selectedPositions != null && !selectedPositions.isEmpty()) {
            for (int pos : selectedPositions) {
                focusPos = pos;
                break;
            }
        }
        menu.showUpward(focusPos);
        return menu;
    }

    /**
     * 锚定到按钮上方弹出：通过 showAtLocation 计算屏幕坐标，避免动态高度问题
     *
     * @param focusPosition 菜单显示后默认聚焦的位置索引
     */
    private void showUpward(int focusPosition) {
        // 先测量容器实际尺寸
        mContainer.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupWidth = mContainer.getMeasuredWidth();
        int popupHeight = mContainer.getMeasuredHeight();

        // 获取锚点按钮在屏幕上的位置
        int[] anchorLoc = new int[2];
        mAnchor.getLocationOnScreen(anchorLoc);
        int anchorX = anchorLoc[0];
        int anchorY = anchorLoc[1];
        int anchorWidth = mAnchor.getWidth();

        // 水平居中对齐按钮，垂直方向在按钮上方
        int x = anchorX + (anchorWidth - popupWidth) / 2;
        int y = anchorY - popupHeight;
        // 防止 x 越界（屏幕左/右边）
        if (x < 0) x = 0;
        android.content.Context ctx = mAnchor.getContext();
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        if (x + popupWidth > screenWidth) x = screenWidth - popupWidth;

        mPopupWindow.showAtLocation(mAnchor, Gravity.NO_GRAVITY, x, y);

        // 聚焦指定位置的菜单项，TV 端可直接 D-pad 导航
        int pos = (focusPosition >= 0 && focusPosition < mContainer.getChildCount())
                ? focusPosition : 0;
        if (mContainer.getChildCount() > pos) {
            mContainer.getChildAt(pos).requestFocus();
        }
    }

    /** 主动关闭菜单 */
    public void dismiss() {
        if (mPopupWindow.isShowing()) {
            mPopupWindow.dismiss();
        }
    }

    public boolean isShowing() {
        return mPopupWindow.isShowing();
    }

    /**
     * 设置菜单关闭监听，供调用方在菜单 dismiss 时恢复控制栏自动隐藏倒计时
     */
    public void setOnDismissListener(PopupWindow.OnDismissListener listener) {
        mPopupWindow.setOnDismissListener(listener);
    }
}
