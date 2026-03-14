package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.ViewPager;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.adapter.SettingMenuAdapter;
import com.github.tvbox.osc.ui.adapter.SettingPageAdapter;
import com.github.tvbox.osc.ui.fragment.ModelSettingFragment;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.UpdateCheckManager;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class SettingActivity extends BaseActivity {
    private TvRecyclerView mGridView;
    private ViewPager mViewPager;
    private SettingMenuAdapter sortAdapter;
    private SettingPageAdapter pageAdapter;
    private List<BaseLazyFragment> fragments = new ArrayList<>();
    private boolean sortChange = false;
    private int defaultSelected = 0;
    private int sortFocused = 0;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private String homeSourceKey;
    private String currentApi;
    private String currentLive;
    private String currentEpg;
    private int dnsOpt;
    private boolean cacheCleared = false;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_setting;
    }

    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        initView();
        initData();
    }

    private void initView() {
        mGridView = findViewById(R.id.mGridView);
        mViewPager = findViewById(R.id.mViewPager);
        sortAdapter = new SettingMenuAdapter();
        mGridView.setAdapter(sortAdapter);
        mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        sortAdapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, View view, int position) {
                if (view.getId() == R.id.tvName) {
                    if (view.getParent() != null) {
                        ((ViewGroup) view.getParent()).requestFocus();
                        sortFocused = position;
                        if (sortFocused != defaultSelected) {
                            defaultSelected = sortFocused;
                            mViewPager.setCurrentItem(sortFocused, false);
                        }
                    }
                }
            }
        });
        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    TextView tvName = itemView.findViewById(R.id.tvName);
                    tvName.setTextColor(ContextCompat.getColor(SettingActivity.this, R.color.color_FFFFFF_70));
                }
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) {
                    sortChange = true;
                    sortFocused = position;
                    TextView tvName = itemView.findViewById(R.id.tvName);
                    tvName.setTextColor(Color.WHITE);
                }
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
    }

    private void initData() {
        currentApi = Hawk.get(HawkConfig.API_URL, "");
        currentLive = Hawk.get(HawkConfig.LIVE_URL, "");
        currentEpg = Hawk.get(HawkConfig.EPG_URL, "");
        homeSourceKey = ApiConfig.get().getHomeSourceBean().getKey();
        dnsOpt = Hawk.get(HawkConfig.DOH_URL, 0);
        List<String> sortList = new ArrayList<>();
        sortList.add("设置其他");
        sortAdapter.setNewData(sortList);
        initViewPager();
    }

    private void initViewPager() {
        fragments.add(ModelSettingFragment.newInstance());
        pageAdapter = new SettingPageAdapter(getSupportFragmentManager(), fragments);
        mViewPager.setAdapter(pageAdapter);
        mViewPager.setCurrentItem(0);
    }

    private final Runnable mDataRunnable = new Runnable() {
        @Override
        public void run() {
            if (sortChange) {
                sortChange = false;
                if (sortFocused != defaultSelected) {
                    defaultSelected = sortFocused;
                    mViewPager.setCurrentItem(sortFocused, false);
                }
            }
        }
    };

    private final Runnable mDevModeRun = new Runnable() {
        @Override
        public void run() {
            devMode = "";
        }
    };


    public interface DevModeCallback {
        void onChange();
    }

    public static DevModeCallback callback = null;

    String devMode = "";

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            mHandler.removeCallbacks(mDataRunnable);
            int keyCode = event.getKeyCode();
            switch (keyCode) {
                case KeyEvent.KEYCODE_0:
                    mHandler.removeCallbacks(mDevModeRun);
                    devMode += "0";
                    mHandler.postDelayed(mDevModeRun, 200);
                    if (devMode.length() >= 4) {
                        if (callback != null) {
                            callback.onChange();
                        }
                    }
                    break;
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            mHandler.postDelayed(mDataRunnable, 200);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (isFinishing()) {
            return;
        }
        if (cacheCleared) {
            Intent intent = new Intent(mContext, SplashActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            overridePendingTransition(0, 0);
            finish();
            return;
        }
        if ((homeSourceKey != null && !homeSourceKey.equals(Hawk.get(HawkConfig.HOME_API, ""))) ||
                !currentApi.equals(Hawk.get(HawkConfig.API_URL, "")) || !currentLive.equals(Hawk.get(HawkConfig.LIVE_URL, "")) ||
                !currentEpg.equals(Hawk.get(HawkConfig.EPG_URL, "")) || dnsOpt != Hawk.get(HawkConfig.DOH_URL, 0)) {
            Intent intent = new Intent(mContext, SplashActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            // 判断是否需要使用缓存：只有API、直播、EPG地址都没变时才使用缓存
            boolean useCache = currentApi.equals(Hawk.get(HawkConfig.API_URL, ""))
                    && currentLive.equals(Hawk.get(HawkConfig.LIVE_URL, ""))
                    && currentEpg.equals(Hawk.get(HawkConfig.EPG_URL, ""));
            if (useCache) {
                intent.putExtra("useCache", true);
            } else {
                // 只有不使用缓存时才清理，避免清理后又要使用缓存的矛盾
                ApiConfig.get().clearAllCache();
                UpdateCheckManager.get().clearCache();
                // 重置检测状态，允许返回首页后立即检测历史记录更新
                UpdateCheckManager.get().resetCheckState();
            }
            startActivity(intent);
            overridePendingTransition(0, 0);
            finish();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        mHandler.removeCallbacksAndMessages(null);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_APP_REFRESH) {
            cacheCleared = true;
        }
    }
}