package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.github.tvbox.osc.util.ToastHelper;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.adapter.CheckboxSearchAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.SearchHelper;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class SearchCheckboxDialog extends BaseDialog {

    private TvRecyclerView mGridView;
    private CheckboxSearchAdapter checkboxSearchAdapter;
    private final List<SourceBean> mSourceList;
    LinearLayout checkAll;
    LinearLayout selectHome;
    LinearLayout clearAll;
    LinearLayout checkModeNormal;
    LinearLayout checkModeFast;
    ImageView ivModeNormal;
    ImageView ivModeFast;
    ImageView ivCheckAll;
    ImageView ivSelectHome;

    public HashMap<String, String> mCheckSourcees;

    public SearchCheckboxDialog(@NonNull @NotNull Context context, List<SourceBean> sourceList, HashMap<String, String> checkedSources) {
        super(context);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        setCanceledOnTouchOutside(true);
        setCancelable(true);
        mSourceList = sourceList;
        mCheckSourcees = checkedSources;
        setContentView(R.layout.dialog_checkbox_search);
        initView(context);
    }

    protected void initView(Context context) {
        mGridView = findViewById(R.id.mGridView);
        checkAll = findViewById(R.id.checkAll);
        selectHome = findViewById(R.id.selectHome);
        clearAll = findViewById(R.id.clearAll);
        checkModeNormal = findViewById(R.id.checkModeNormal);
        checkModeFast = findViewById(R.id.checkModeFast);
        ivModeNormal = findViewById(R.id.ivModeNormal);
        ivModeFast = findViewById(R.id.ivModeFast);
        ivCheckAll = findViewById(R.id.ivCheckAll);
        ivSelectHome = findViewById(R.id.ivSelectHome);

        updateModeDisplay();
        updateButtonStates();

        checkboxSearchAdapter = new CheckboxSearchAdapter(new DiffUtil.ItemCallback<SourceBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull SourceBean oldItem, @NonNull SourceBean newItem) {
                return oldItem.getKey().equals(newItem.getKey());
            }

            @Override
            public boolean areContentsTheSame(@NonNull SourceBean oldItem, @NonNull SourceBean newItem) {
                return oldItem.getName().equals(newItem.getName());
            }
        });
        mGridView.setHasFixedSize(true);

        int size = mSourceList.size();
        int spanCount = (int) Math.floor(size / 10);
        if (spanCount <= 1) spanCount = 2;
        if (spanCount >= 3) spanCount = 3;
        mGridView.setLayoutManager(new V7GridLayoutManager(getContext(), spanCount));
        View root = findViewById(R.id.root);
        ViewGroup.LayoutParams clp = root.getLayoutParams();
        clp.width = AutoSizeUtils.mm2px(getContext(), 280 + 200 * (spanCount - 1));

        mGridView.setAdapter(checkboxSearchAdapter);
        checkboxSearchAdapter.setData(mSourceList, mCheckSourcees, false);
        // 在适配器创建并设置数据后再更新启用状态
        updateGridViewEnabledState();
        int pos = 0;
        if (mSourceList != null && mCheckSourcees != null) {
            for (int i = 0; i < mSourceList.size(); i++) {
                String key = mSourceList.get(i).getKey();
                if (mCheckSourcees.containsKey(key)) {
                    pos = i;
                    break;
                }
            }
        }
        final int scrollPosition = pos;
        mGridView.post(new Runnable() {
            @Override
            public void run() {
                mGridView.smoothScrollToPosition(scrollPosition);
            }
        });

        checkModeNormal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                Hawk.put(HawkConfig.FAST_SEARCH_MODE, false);
                updateModeDisplay();
                updateGridViewEnabledState();
            }
        });

        checkModeFast.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                Hawk.put(HawkConfig.FAST_SEARCH_MODE, true);
                updateModeDisplay();
                updateGridViewEnabledState();
            }
        });

        checkAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                for (SourceBean sourceBean : mSourceList) {
                    if (!mCheckSourcees.containsKey(sourceBean.getKey())) {
                        mCheckSourcees.put(sourceBean.getKey(), "1");
                    }
                }
                checkboxSearchAdapter.setData(mSourceList, mCheckSourcees, false);
                updateButtonStates();
                SearchHelper.putCheckedSources(mCheckSourcees);
            }
        });

        selectHome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                SourceBean homeSource = ApiConfig.get().getHomeSourceBean();
                if (homeSource == null || !homeSource.isSearchable()) {
                    ToastHelper.showToast(getContext(), getContext().getString(R.string.search_home_not_searchable));
                    return;
                }
                mCheckSourcees.clear();
                mCheckSourcees.put(homeSource.getKey(), "1");
                checkboxSearchAdapter.setData(mSourceList, mCheckSourcees, false);
                updateButtonStates();
                SearchHelper.putCheckedSources(mCheckSourcees);
                ToastHelper.showToast(getContext(), String.format(getContext().getString(R.string.search_selected_home), homeSource.getName()));
            }
        });

        clearAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                if (mCheckSourcees.size() <= 0) {
                    return;
                }
                mCheckSourcees.clear();
                checkboxSearchAdapter.setData(mSourceList, mCheckSourcees, false);
                updateButtonStates();
                SearchHelper.putCheckedSources(mCheckSourcees);
            }
        });

        checkboxSearchAdapter.setOnCheckedChangedListener(new CheckboxSearchAdapter.OnCheckedChangedListener() {
            @Override
            public void onCheckedChanged() {
                updateButtonStates();
            }
        });
    }

    private void updateModeDisplay() {
        boolean isFastMode = Hawk.get(HawkConfig.FAST_SEARCH_MODE, false);
        ivModeNormal.setImageResource(isFastMode ? R.drawable.shape_search_clearall : R.drawable.shape_search_checkall);
        ivModeFast.setImageResource(isFastMode ? R.drawable.shape_search_checkall : R.drawable.shape_search_clearall);
    }

    private void updateGridViewEnabledState() {
        boolean isFastMode = Hawk.get(HawkConfig.FAST_SEARCH_MODE, false);
        // 聚合搜索模式下禁用下方的勾选框和操作按钮，因为聚合搜索是所有数据源都参与搜索
        if (checkboxSearchAdapter != null) {
            checkboxSearchAdapter.setEnabled(!isFastMode);
        }
        // 禁用/启用操作按钮
        checkAll.setEnabled(!isFastMode);
        checkAll.setAlpha(isFastMode ? 0.5f : 1.0f);
        selectHome.setEnabled(!isFastMode);
        selectHome.setAlpha(isFastMode ? 0.5f : 1.0f);
        clearAll.setEnabled(!isFastMode);
        clearAll.setAlpha(isFastMode ? 0.5f : 1.0f);
    }

    private void updateButtonStates() {
        if (mSourceList == null || mSourceList.isEmpty()) {
            ivCheckAll.setImageResource(R.drawable.shape_search_clearall);
            ivSelectHome.setImageResource(R.drawable.shape_search_clearall);
            return;
        }

        boolean allChecked = true;
        int checkedCount = 0;
        for (SourceBean sourceBean : mSourceList) {
            if (mCheckSourcees.containsKey(sourceBean.getKey())) {
                checkedCount++;
            } else {
                allChecked = false;
            }
        }
        ivCheckAll.setImageResource(allChecked ? R.drawable.shape_search_checkall : R.drawable.shape_search_clearall);

        SourceBean homeSource = ApiConfig.get().getHomeSourceBean();
        boolean onlyHomeSelected = false;
        if (homeSource != null && homeSource.isSearchable() && checkedCount == 1 && mCheckSourcees.containsKey(homeSource.getKey())) {
            onlyHomeSelected = true;
        }
        ivSelectHome.setImageResource(onlyHomeSelected ? R.drawable.shape_search_checkall : R.drawable.shape_search_clearall);
    }
}
