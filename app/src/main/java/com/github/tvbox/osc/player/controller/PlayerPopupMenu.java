package com.github.tvbox.osc.player.controller;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
     * @param context     上下文
     * @param anchor      锚定按钮
     * @param items       选项文案列表
     * @param selectedPos 当前选中位置
     * @param itemWidth   选项宽度（像素）
     * @param callback    选中回调
     */
    public static PlayerPopupMenu showSingle(Context context, View anchor,
                                             List<String> items, int selectedPos,
                                             int itemWidth,
                                             OnSingleSelectCallback callback) {
        PlayerPopupMenu menu = new PlayerPopupMenu(context, anchor, itemWidth);
        for (int i = 0; i < items.size(); i++) {
            final int pos = i;
            View itemRoot = createItemBase(menu, items.get(pos), itemWidth,
                    pos == selectedPos);
            itemRoot.setOnClickListener(v -> menu.performSingleSelect(callback, pos));
            itemRoot.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    menu.performSingleSelect(callback, pos);
                    return true;
                }
                return false;
            });
            menu.mContainer.addView(itemRoot);
        }
        int focusPos = (selectedPos >= 0 && selectedPos < items.size()) ? selectedPos : 0;
        menu.showUpward(focusPos);
        return menu;
    }

    /**
     * 显示多选菜单：已选项前缀 √，点击只切换状态不关闭，由用户按返回键关闭
     *
     * @param context           上下文
     * @param anchor            锚定按钮
     * @param items             选项文案列表
     * @param selectedPositions 已选位置集合
     * @param itemWidth         选项宽度（像素）
     * @param callback          切换回调
     */
    public static PlayerPopupMenu showMulti(Context context, View anchor,
                                            List<String> items, Set<Integer> selectedPositions,
                                            int itemWidth,
                                            OnMultiToggleCallback callback) {
        PlayerPopupMenu menu = new PlayerPopupMenu(context, anchor, itemWidth);
        for (int i = 0; i < items.size(); i++) {
            final int pos = i;
            boolean checked = selectedPositions != null && selectedPositions.contains(pos);
            View itemRoot = createItemBase(menu, items.get(pos), itemWidth, checked);
            final TextView checkView = itemRoot.findViewById(R.id.tv_popup_check);
            itemRoot.setOnClickListener(v -> menu.performMultiToggle(checkView, callback, pos));
            itemRoot.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    menu.clearOtherFocus(v);
                    v.requestFocus();
                    return false;
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    menu.performMultiToggle(checkView, callback, pos);
                    return true;
                }
                return false;
            });
            menu.mContainer.addView(itemRoot);
        }
        int focusPos = 0;
        if (selectedPositions != null && !selectedPositions.isEmpty()) {
            for (int i = 0; i < items.size(); i++) {
                if (selectedPositions.contains(i)) {
                    focusPos = i;
                    break;
                }
            }
        }
        menu.showUpward(focusPos);
        return menu;
    }

    /** 创建并设置菜单项通用样式，返回根 View */
    private static View createItemBase(PlayerPopupMenu menu, String text, int itemWidth, boolean checked) {
        Context context = menu.mAnchor.getContext();
        View itemRoot = LayoutInflater.from(context)
                .inflate(R.layout.item_player_popup_menu, menu.mContainer, false);
        TextView checkView = itemRoot.findViewById(R.id.tv_popup_check);
        TextView textView = itemRoot.findViewById(R.id.tv_popup_item);
        // 左侧勾选标记独立控制，√ 与文字不再挤压在同一行
        checkView.setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
        textView.setText(text);
        // 设置固定宽度保证菜单整齐
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(itemWidth > 0 ? itemWidth
                : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = context.getResources().getDimensionPixelSize(R.dimen.vs_5);
        itemRoot.setLayoutParams(lp);
        return itemRoot;
    }

    /** 单选：触发回调并关闭菜单 */
    private void performSingleSelect(OnSingleSelectCallback callback, int pos) {
        mPopupWindow.dismiss();
        if (callback != null) {
            callback.onSelect(pos);
        }
    }

    /** 多选：切换勾选状态并触发回调 */
    private void performMultiToggle(TextView checkView, OnMultiToggleCallback callback, int pos) {
        boolean nowChecked = checkView.getVisibility() != View.VISIBLE;
        checkView.setVisibility(nowChecked ? View.VISIBLE : View.INVISIBLE);
        if (callback != null) {
            callback.onToggle(pos, nowChecked);
        }
    }

    /** 多选：触摸/鼠标切换前清除其它 item 的焦点，避免多个 item 同时高亮 */
    private void clearOtherFocus(View current) {
        for (int i = 0; i < mContainer.getChildCount(); i++) {
            View child = mContainer.getChildAt(i);
            if (child != current && child.isFocused()) {
                child.clearFocus();
            }
        }
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
        final int pos = (focusPosition >= 0 && focusPosition < mContainer.getChildCount())
                ? focusPosition : 0;
        // 先让容器获取焦点，确保 PopupWindow 窗口焦点进入菜单区域，
        // 再延迟聚焦到目标子项，避免触摸/鼠标点击后焦点仍留在底层控制栏
        mContainer.requestFocus();
        mContainer.post(() -> {
            if (mContainer.getChildCount() > pos) {
                View target = mContainer.getChildAt(pos);
                if (target != null) {
                    target.requestFocus();
                }
            }
        });
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
