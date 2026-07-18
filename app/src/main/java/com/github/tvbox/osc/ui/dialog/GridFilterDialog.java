package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.ui.adapter.GridFilterKVAdapter;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

public class GridFilterDialog extends BaseDialog {
    public LinearLayout filterRoot;

    public GridFilterDialog(@NonNull @NotNull Context context) {
        super(context);
        setCanceledOnTouchOutside(true);
        setCancelable(true);
        setContentView(R.layout.dialog_grid_filter);
        filterRoot = findViewById(R.id.filterRoot);
        View rootView = findViewById(R.id.root);
        rootView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
    }

    public interface Callback {
        void change();
    }

    public void setOnDismiss(Callback callback) {
        setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                if (selectChange) {
                    callback.change();
                }
            }
        });
    }

    public void setData(MovieSort.SortData sortData) {
        ArrayList<MovieSort.SortFilter> filters = sortData.filters;
        for (MovieSort.SortFilter filter : filters) {
            View line = LayoutInflater.from(getContext()).inflate(R.layout.item_grid_filter, null);
            ((TextView) line.findViewById(R.id.filterName)).setText(filter.name);
            TvRecyclerView gridView = line.findViewById(R.id.mFilterKv);
            gridView.setHasFixedSize(true);
            gridView.setLayoutManager(new V7LinearLayoutManager(getContext(), 0, false));
            GridFilterKVAdapter filterKVAdapter = new GridFilterKVAdapter();
            gridView.setAdapter(filterKVAdapter);
            String key = filter.key;
            ArrayList<String> values = new ArrayList<>(filter.values.keySet());
            ArrayList<String> keys = new ArrayList<>(filter.values.values());
            filterKVAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
                View pre = null;

                @Override
                public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                    selectChange = true;
                    String filterSelect = sortData.filterSelect.get(key);
                    if (filterSelect == null || !filterSelect.equals(keys.get(position))) {
                        sortData.filterSelect.put(key, keys.get(position));
                        if (pre != null) {
                            TextView val = pre.findViewById(R.id.filterValue);
                            val.getPaint().setFakeBoldText(false);
                            val.setTextColor(ContextCompat.getColor(getContext(), R.color.color_FFFFFF));
                        }
                        TextView val = view.findViewById(R.id.filterValue);
                        val.getPaint().setFakeBoldText(true);
                        TypedArray a = getContext().obtainStyledAttributes(R.styleable.themeColor);
                        int themeColor = a.getColor(R.styleable.themeColor_color_theme, 0);
                        val.setTextColor(themeColor);
                        pre = view;
                    } else {
                        sortData.filterSelect.remove(key);
                        TextView val = pre.findViewById(R.id.filterValue);
                        val.getPaint().setFakeBoldText(false);
                        val.setTextColor(ContextCompat.getColor(getContext(), R.color.color_FFFFFF));
                        pre = null;
                    }
                }
            });
            filterKVAdapter.setNewData(values);
            filterRoot.addView(line);
        }
    }

    private boolean selectChange = false;

    public void show() {
        selectChange = false;
        super.show();
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.gravity = Gravity.BOTTOM;
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        getWindow().getDecorView().setPadding(0, 0, 0, 0);
        getWindow().setAttributes(layoutParams);
        if (filterRoot != null) {
            filterRoot.post(this::requestFirstFilterFocus);
        }
    }

    /**
     * 将焦点落到第一个筛选行的第一个选项上
     */
    private void requestFirstFilterFocus() {
        if (filterRoot == null || filterRoot.getChildCount() == 0) {
            return;
        }
        // 如果已经有子 View 获取焦点，则不再强制重置焦点
        if (hasFocusedChild(filterRoot)) {
            return;
        }
        View firstLine = filterRoot.getChildAt(0);
        if (firstLine == null) {
            return;
        }
        TvRecyclerView firstRow = firstLine.findViewById(R.id.mFilterKv);
        if (firstRow == null || firstRow.getLayoutManager() == null) {
            return;
        }
        firstRow.post(new Runnable() {
            int retryCount = 0;

            @Override
            public void run() {
                if (firstRow.getLayoutManager() == null) {
                    return;
                }
                View item = firstRow.getLayoutManager().findViewByPosition(0);
                if (item != null) {
                    item.requestFocus();
                    return;
                }
                // 子 View 尚未布局完成，先滚动到目标位置再等待下一帧重试
                firstRow.setSelectedPosition(0);
                if (++retryCount < 5) {
                    firstRow.post(this);
                }
            }
        });
    }

    /**
     * 判断弹窗内容中是否已有 View 获取焦点
     */
    private boolean hasFocusedChild(View view) {
        return view != null && view.hasFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 部分 ROM 窗口焦点恢复滞后，窗口重新获得焦点时再次尝试落入焦点
        if (hasFocus && filterRoot != null) {
            filterRoot.post(this::requestFirstFilterFocus);
        }
    }
}
