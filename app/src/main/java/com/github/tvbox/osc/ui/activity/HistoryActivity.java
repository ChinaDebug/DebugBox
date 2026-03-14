package com.github.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.BounceInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.adapter.HistoryAdapter;
import com.github.tvbox.osc.ui.dialog.ConfirmClearDialog;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.ToastHelper;
import com.github.tvbox.osc.util.UpdateCheckManager;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 */
public class HistoryActivity extends BaseActivity {
    private TextView tvDelTip;
    private ImageView tvDelete;
    private ImageView tvClear;
    private LinearLayout tvCheckUpdate;
    private ImageView ivRefreshIcon;
    private TextView tvCheckUpdateText;
    private Animation rotateAnimation;
    private TvRecyclerView mGridView;
    private HistoryAdapter historyAdapter;
    private boolean delMode = false;
    private boolean isActivityResumed = false;
    private UpdateCheckManager.UpdateCheckListener updateCheckListener;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_history;
    }

    @Override
    protected void init() {
        initView();
        initData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityResumed = true;
        initData();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityResumed = false;
    }

    private void toggleDelMode() {
        HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
        historyAdapter.notifyDataSetChanged();

        delMode = !delMode;
        tvDelTip.setVisibility(delMode ? View.VISIBLE : View.GONE);
    }

    private void setCheckingState(boolean checking) {
        tvCheckUpdate.setClickable(!checking);
        if (checking) {
            ivRefreshIcon.startAnimation(rotateAnimation);
            tvCheckUpdateText.setText("正在检测更新");
        } else {
            ivRefreshIcon.clearAnimation();
            tvCheckUpdateText.setText(R.string.update_check_btn);
        }
    }

    private void initView() {
        EventBus.getDefault().register(this);
        tvDelTip = findViewById(R.id.tvDelTip);
        tvDelete = findViewById(R.id.tvDelete);
        tvClear = findViewById(R.id.tvClear);
        tvCheckUpdate = findViewById(R.id.tvCheckUpdate);
        ivRefreshIcon = findViewById(R.id.ivRefreshIcon);
        tvCheckUpdateText = findViewById(R.id.tvCheckUpdateText);
        rotateAnimation = AnimationUtils.loadAnimation(this, R.anim.rotate_refresh);
        mGridView = findViewById(R.id.mGridView);
        mGridView.setHasFixedSize(true);
        mGridView.setLayoutManager(new V7GridLayoutManager(this.mContext, isBaseOnWidth() ? 5 : 6));
        historyAdapter = new HistoryAdapter();
        mGridView.setAdapter(historyAdapter);
        tvDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (historyAdapter.getData().isEmpty()) {
                    ToastHelper.showToast(mContext, "暂无历史记录");
                    return;
                }
                toggleDelMode();
            }
        });
        tvClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (historyAdapter.getData().isEmpty()) {
                    ToastHelper.showToast(mContext, "暂无历史记录");
                    return;
                }
                ConfirmClearDialog dialog = new ConfirmClearDialog(mContext, "History");
                dialog.show();
            }
        });
        tvCheckUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (historyAdapter.getData().isEmpty()) {
                    ToastHelper.showToast(mContext, "暂无历史记录");
                    return;
                }
                if (UpdateCheckManager.get().startManualCheck(mContext)) {
                    setCheckingState(true);
                }
            }
        });
        updateCheckListener = new UpdateCheckManager.UpdateCheckListener() {
            @Override
            public void onCheckComplete(boolean hasUpdate, Map<String, Boolean> updates) {
                setCheckingState(false);
                if (!isActivityResumed) {
                    return;
                }
                if (hasUpdate) {
                    int count = 0;
                    for (Boolean value : updates.values()) {
                        if (value) count++;
                    }
                    ToastHelper.showToast(mContext, String.format(getString(R.string.update_found), count));
                } else {
                    ToastHelper.showToast(mContext, getString(R.string.update_no_found));
                }
                sortAndRefreshData();
            }

            @Override
            public void onCheckProgress(int current, int total) {
            }

            @Override
            public void onCheckError(String errorMessage) {
                setCheckingState(false);
                if (!isActivityResumed) {
                    return;
                }
                ToastHelper.showToast(mContext, errorMessage);
            }
        };
        UpdateCheckManager.get().addListener(updateCheckListener);
        if (UpdateCheckManager.get().isChecking()) {
            setCheckingState(true);
        }
        mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction == View.FOCUS_UP) {
                    tvDelete.setFocusable(true);
                    tvClear.setFocusable(true);
                    tvDelete.requestFocus();
                }
                return false;
            }
        });
        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.2f).scaleY(1.2f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        historyAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                VodInfo vodInfo = historyAdapter.getData().get(position);

                if (vodInfo != null) {
                    if (delMode) {
                        historyAdapter.remove(position);
                        UpdateCheckManager.get().clearVideoUpdate(vodInfo.sourceKey, vodInfo.id);
                        RoomDataManger.deleteVodRecord(vodInfo.sourceKey, vodInfo);
                        CacheManager.deleteVodCache(vodInfo.sourceKey, vodInfo);
                        // 如果删除后没有历史记录了，自动退出删除模式
                        if (historyAdapter.getData().isEmpty() && delMode) {
                            toggleDelMode();
                        }
                    } else {
                        Bundle bundle = new Bundle();
                        bundle.putString("id", vodInfo.id);
                        bundle.putString("sourceKey", vodInfo.sourceKey);
                        jumpActivity(DetailActivity.class, bundle);
                    }
                }

            }
        });
        historyAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                tvDelete.setFocusable(true);
                toggleDelMode();
                return true;
            }
        });
    }

    private void initData() {
        List<VodInfo> allVodRecord = RoomDataManger.getAllVodRecord(100);
        if (allVodRecord == null) {
            allVodRecord = new ArrayList<>();
        }
        List<VodInfo> vodInfoList = new ArrayList<>();
        for (VodInfo vodInfo : allVodRecord) {
            if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty()) {
                vodInfo.note = "看到" + vodInfo.playNote;
            }
            vodInfoList.add(vodInfo);
        }
        sortVodListByUpdate(vodInfoList);
        historyAdapter.setNewData(vodInfoList);
    }

    private void sortAndRefreshData() {
        if (historyAdapter != null && historyAdapter.getData() != null) {
            List<VodInfo> currentList = new ArrayList<>(historyAdapter.getData());
            sortVodListByUpdate(currentList);
            historyAdapter.setNewData(currentList);
        }
    }

    private void sortVodListByUpdate(List<VodInfo> vodInfoList) {
        Collections.sort(vodInfoList, new Comparator<VodInfo>() {
            @Override
            public int compare(VodInfo o1, VodInfo o2) {
                boolean hasUpdate1 = UpdateCheckManager.get().hasVideoUpdate(o1.sourceKey, o1.id);
                boolean hasUpdate2 = UpdateCheckManager.get().hasVideoUpdate(o2.sourceKey, o2.id);
                if (hasUpdate1 && !hasUpdate2) {
                    return -1;
                } else if (!hasUpdate1 && hasUpdate2) {
                    return 1;
                }
                return 0;
            }
        });
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        // 检查Activity是否正在销毁或已销毁,避免在销毁后处理事件
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (event.type == RefreshEvent.TYPE_HISTORY_REFRESH) {
            initData();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        if (ivRefreshIcon != null) {
            ivRefreshIcon.clearAnimation();
        }
        if (updateCheckListener != null) {
            UpdateCheckManager.get().removeListener(updateCheckListener);
            updateCheckListener = null;
        }
        historyAdapter = null;
    }

    @Override
    public void onBackPressed() {
        if (delMode) {
            toggleDelMode();
            return;
        }
        super.onBackPressed();
    }
}
