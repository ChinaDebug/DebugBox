package com.github.tvbox.osc.ui.activity;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.IntEvaluator;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.BounceInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.tvbox.osc.util.ToastHelper;
import com.github.tvbox.osc.util.UpdateCheckManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.subtitle.runtime.AppTaskExecutor;
import com.github.tvbox.osc.ui.adapter.HomePageAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.adapter.SortAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.github.tvbox.osc.ui.fragment.GridFragment;
import com.github.tvbox.osc.ui.fragment.UserFragment;
import com.github.tvbox.osc.ui.tv.widget.DefaultTransformer;
import com.github.tvbox.osc.ui.tv.widget.FixedSpeedScroller;
import com.github.tvbox.osc.ui.tv.widget.NoScrollViewPager;
import com.github.tvbox.osc.ui.tv.widget.ViewObj;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.XXPermissions;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class HomeActivity extends BaseActivity {

    private static int lastHomeRec = -1;
    private static Resources res;

    private View currentView;
    private LinearLayout topLayout;
    private LinearLayout contentLayout;
    private TextView tvName;
    private ImageView tvWifi;
    private ImageView tvFind;
    private ImageView tvStyle;
    private ImageView tvDraw;
    private ImageView tvMenu;
    private TextView tvDate;
    private TvRecyclerView mGridView;
    private NoScrollViewPager mViewPager;
    private SourceViewModel sourceViewModel;
    private SortAdapter sortAdapter;
    private HomePageAdapter pageAdapter;
    private final List<BaseLazyFragment> fragments = new ArrayList<>();
    private boolean isDownOrUp = false;
    private boolean sortChange = false;
    private int currentSelected = 0;
    private int sortFocused = 0;
    public View sortFocusView = null;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mExitTime = 0;
    private long lastLogoClickTime = 0;
    private boolean isClearingCache = false;
    private static final long LOGO_CLICK_INTERVAL = 3500L;
    private final Runnable mRunnable = new Runnable() {
        @SuppressLint({"DefaultLocale", "SetTextI18n"})
        @Override
        public void run() {
            Date date = new Date();
            @SuppressLint("SimpleDateFormat")
			//修改时间分隔符
            SimpleDateFormat timeFormat = new SimpleDateFormat(getString(R.string.hm_date1) + " | " + getString(R.string.hm_date2));
            tvDate.setText(timeFormat.format(date));
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        setTheme(R.style.AppTheme_NoActionBar);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_home;
    }

    boolean useCacheConfig = false;

    @Override
    protected void init() {
        res = getResources();

        try {
            EventBus.getDefault().register(this);
        } catch (Exception e) {
        }
 
        if (ControlManager.mContext != null) {
            ControlManager.get().startServer();
        }
        
        initView();
        initViewModel();
        useCacheConfig = false;
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            useCacheConfig = bundle.getBoolean("useCache", false);
        }
        mHandler.postDelayed(() -> initData(), 100);
    }

    // takagen99: Added to allow read string
    public static Resources getRes() {
        if (res == null) {
            return App.getInstance().getResources();
        }
        return res;
    }

    private void initView() {
        this.topLayout = findViewById(R.id.topLayout);
        this.tvName = findViewById(R.id.tvName);
        this.tvWifi = findViewById(R.id.tvWifi);
        this.tvFind = findViewById(R.id.tvFind);
        this.tvStyle = findViewById(R.id.tvStyle);
        this.tvDraw = findViewById(R.id.tvDrawer);
        this.tvMenu = findViewById(R.id.tvMenu);
        this.tvDate = findViewById(R.id.tvDate);
        this.contentLayout = findViewById(R.id.contentLayout);
        this.mGridView = findViewById(R.id.mGridViewCategory);
        this.mViewPager = findViewById(R.id.mViewPager);
        this.sortAdapter = new SortAdapter();
        this.mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        this.mGridView.setSpacingWithMargins(0, AutoSizeUtils.dp2px(this.mContext, 10.0f));
        this.mGridView.setAdapter(this.sortAdapter);
        sortAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                mGridView.post(() -> {
                    View firstChild = Objects.requireNonNull(mGridView.getLayoutManager()).findViewByPosition(0);
                    if (firstChild != null) {
                        mGridView.setSelectedPosition(0);
                        firstChild.requestFocus();
                    }
                });
            }
        });
        this.mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            public void onItemPreSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null && !HomeActivity.this.isDownOrUp) {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(250).start();
                    TextView textView = view.findViewById(R.id.tvTitle);
                    textView.getPaint().setFakeBoldText(false);
                    textView.setTextColor(ContextCompat.getColor(HomeActivity.this, R.color.color_FFFFFF_70));
                    textView.invalidate();
                    view.findViewById(R.id.tvFilter).setVisibility(View.GONE);
                }
            }

            public void onItemSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null) {
                    HomeActivity.this.currentView = view;
                    HomeActivity.this.isDownOrUp = false;
                    HomeActivity.this.sortChange = true;
                    view.animate().scaleX(1.1f).scaleY(1.1f).setInterpolator(new BounceInterpolator()).setDuration(250).start();
                    TextView textView = view.findViewById(R.id.tvTitle);
                    textView.getPaint().setFakeBoldText(true);
                    textView.setTextColor(ContextCompat.getColor(HomeActivity.this, R.color.color_FFFFFF));
                    textView.invalidate();
//                    if (!sortAdapter.getItem(position).filters.isEmpty())
//                        view.findViewById(R.id.tvFilter).setVisibility(View.VISIBLE);
                    if (position == -1) {
                        position = 0;
                        HomeActivity.this.mGridView.setSelection(0);
                    }
                    MovieSort.SortData sortData = sortAdapter.getItem(position);
                    if (null != sortData && !sortData.filters.isEmpty()) {
                        showFilterIcon(sortData.filterSelectCount());
                    }
                    HomeActivity.this.sortFocusView = view;
                    HomeActivity.this.sortFocused = position;
                    mHandler.removeCallbacks(mDataRunnable);
                    mHandler.postDelayed(mDataRunnable, 200);
                }
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null && currentSelected == position) {
                    BaseLazyFragment baseLazyFragment = fragments.get(currentSelected);
                    if ((baseLazyFragment instanceof GridFragment) && !sortAdapter.getItem(position).filters.isEmpty()) {// 弹出筛选
                        ((GridFragment) baseLazyFragment).showFilter();
                    } else if (baseLazyFragment instanceof UserFragment) {
                        showSiteSwitch();
                    }
                }
            }
        });
        this.mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            public boolean onInBorderKeyEvent(int direction, View view) {
                if (direction == View.FOCUS_UP) {
                    BaseLazyFragment baseLazyFragment = fragments.get(sortFocused);
                    if ((baseLazyFragment instanceof GridFragment)) {// 弹出筛选
                        ((GridFragment) baseLazyFragment).forceRefresh();
                    }
                }
                if (direction != View.FOCUS_DOWN) {
                    return false;
                }
                BaseLazyFragment baseLazyFragment = fragments.get(sortFocused);
                if (!(baseLazyFragment instanceof GridFragment)) {
                    return false;
                }
                return !((GridFragment) baseLazyFragment).isLoad();
            }
        });
        // Button : TVBOX >> Delete Cache / Longclick to Refresh Source --
        tvName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastLogoClickTime < LOGO_CLICK_INTERVAL || isClearingCache) {
                    return;
                }
                lastLogoClickTime = currentTime;

                // 检查存储权限
                if (!hasStoragePermission()) {
                    showStoragePermissionDialog(() -> clearCacheAndReload());
                    return;
                }

                clearCacheAndReload();
            }
        });
        tvName.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                reloadHome();
                return true;
            }
        });
        // Button : Wifi >> Go into Android Wifi Settings -------------
        tvWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                }catch (Exception ignored){
                }
            }
        });
        // Button : Search --------------------------------------------
        tvFind.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                jumpActivity(SearchActivity.class);
            }
        });
        // Button : Style --------------------------------------------
        tvStyle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    Hawk.put(HawkConfig.HOME_REC_STYLE, !Hawk.get(HawkConfig.HOME_REC_STYLE, false));
                    if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) {
                        UserFragment.showGridView();
                        UserFragment.updateUserHomeVisibility();
                        ToastHelper.showToast(getString(R.string.hm_style_grid));
                        tvStyle.setImageResource(R.drawable.hm_up_down);
                    } else {
                        UserFragment.showListView();
                        UserFragment.setUserHomeVisibility(View.VISIBLE);
                        ToastHelper.showToast(getString(R.string.hm_style_line));
                        tvStyle.setImageResource(R.drawable.hm_left_right);
                    }
                } catch (Exception ex) {
                }
            }
        });
        // Button : Drawer >> To go into App Drawer -------------------
        tvDraw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                jumpActivity(AppsActivity.class);
            }
        });
        // Button : Settings >> To go into Settings --------------------
        tvMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                jumpActivity(SettingActivity.class);
            }
        });
        // Button : Settings >> To go into App Settings ----------------
        tvMenu.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
                return true;
            }
        });
        // Button : Date >> Go into Android Date Settings --------------
        tvDate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(Settings.ACTION_DATE_SETTINGS));
            }
        });
        setLoadSir(this.contentLayout);
        //mHandler.postDelayed(mFindFocus, 250);
    }

    private boolean skipNextUpdate = false;	
    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.sortResult.observe(this, new Observer<AbsSortXml>() {
            @Override
            public void onChanged(AbsSortXml absXml) {
                if (skipNextUpdate) {
                    skipNextUpdate = false;
                    return;
                }
                showSuccess();
                if (absXml != null && absXml.classes != null && absXml.classes.sortList != null) {
                    sortAdapter.setNewData(DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), absXml.classes.sortList, true));
                } else {
                    sortAdapter.setNewData(DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), new ArrayList<>(), true));
                }
                initViewPager(absXml);
                // takagen99 : Switch to show / hide source title
                SourceBean home = ApiConfig.get().getHomeSourceBean();
                if (HomeShow) {
                    if (home != null && home.getName() != null && !home.getName().isEmpty()) tvName.setText(home.getName());
                        tvName.clearAnimation();
                }
            }
        });
    }

    private boolean dataInitOk = false;
    private boolean jarInitOk = false;
    private TipDialog mErrorDialog = null;
    private boolean mFromSettings = false;

    // takagen99 : Switch to show / hide source title
    boolean HomeShow = Hawk.get(HawkConfig.HOME_SHOW_SOURCE, false);

    // takagen99 : Check if network is available
    boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } else {
            android.net.NetworkInfo activeNetworkInfo = cm.getActiveNetworkInfo();
            return activeNetworkInfo != null && activeNetworkInfo.isConnectedOrConnecting();
        }
    }

    private void initData() {
        if (isNetworkAvailable()) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                if (capabilities != null) {
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        tvWifi.setImageDrawable(ContextCompat.getDrawable(HomeActivity.this, R.drawable.hm_wifi));
                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        tvWifi.setImageDrawable(ContextCompat.getDrawable(HomeActivity.this, R.drawable.hm_mobile));
                    } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        tvWifi.setImageDrawable(ContextCompat.getDrawable(HomeActivity.this, R.drawable.hm_lan));
                    }
                }
            } else {
                android.net.NetworkInfo activeNetworkInfo = cm.getActiveNetworkInfo();
                if (activeNetworkInfo != null) {
                    if (activeNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
                        tvWifi.setImageDrawable(ContextCompat.getDrawable(HomeActivity.this, R.drawable.hm_wifi));
                    } else if (activeNetworkInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                        tvWifi.setImageDrawable(ContextCompat.getDrawable(HomeActivity.this, R.drawable.hm_mobile));
                    } else if (activeNetworkInfo.getType() == ConnectivityManager.TYPE_ETHERNET) {
                        tvWifi.setImageDrawable(ContextCompat.getDrawable(HomeActivity.this, R.drawable.hm_lan));
                    }
                }
            }
        }

        // takagen99: Set Style either Grid or Line
        if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) {
            tvStyle.setImageResource(R.drawable.hm_up_down);
        } else {
            tvStyle.setImageResource(R.drawable.hm_left_right);
        }

        mGridView.requestFocus();

        if (dataInitOk && jarInitOk) {
            sourceViewModel.getSort(ApiConfig.get().getHomeSourceBean().getKey());
            if (Hawk.get(HawkConfig.HOME_DEFAULT_SHOW, false)) {
                jumpActivity(LivePlayActivity.class);
            }
            if (!useCacheConfig) {
                if (UpdateCheckManager.get().isStartupCheck() && UpdateCheckManager.get().canCheck(this)) {
                    UpdateCheckManager.get().startCheck(this, true);
                }
                UpdateCheckManager.get().startScheduledCheck(this);
            }
            return;
        }
        tvNameAnimation();
        showLoading();
        if (dataInitOk && !jarInitOk) {
            if (!ApiConfig.get().getSpider().isEmpty()) {
                ApiConfig.get().loadJar(useCacheConfig, ApiConfig.get().getSpider(), new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        jarInitOk = true;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!useCacheConfig) {
                                    ToastHelper.showToast(getString(R.string.hm_ok));
                                }
                                initData();
                            }
                        }, 50);
                    }

                    @Override
                    public void retry() {

                    }

                    @Override
                    public void error(String msg) {
                        jarInitOk = true;
                        dataInitOk = true;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                  if ("".equals(msg))
                                      ToastHelper.showToast(getString(R.string.hm_notok));
                                  else
                                      ToastHelper.showToast(msg);
                                initData();
                            }
                        },50);
                    }
                });
            }
            return;
        }
        ApiConfig.get().loadConfig(useCacheConfig, new ApiConfig.LoadConfigCallback() {
            @Override
            public void retry() {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        initData();
                    }
                });
            }

            @Override
            public void success() {
                dataInitOk = true;
                if (ApiConfig.get().getSpider().isEmpty()) {
                    jarInitOk = true;
                }
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        initData();
                    }
                }, 50);
            }

            @Override
            public void error(String msg) {
                if (msg.equalsIgnoreCase("-1")) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            dataInitOk = true;
                            jarInitOk = true;
                            initData();
                        }
                    });
                    return;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mErrorDialog != null && mErrorDialog.isShowing()) {
                            mErrorDialog.dismiss();
                        }
                        mErrorDialog = new TipDialog(HomeActivity.this, msg, getString(R.string.hm_setting), getString(R.string.hm_cancel), new TipDialog.OnListener() {
                            @Override
                            public void left() {
                                mHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (mErrorDialog != null && mErrorDialog.isShowing()) {
                                            mErrorDialog.dismiss();
                                        }
                                        mFromSettings = true;
                                        jumpActivity(SettingActivity.class);
                                    }
                                });
                            }

                            @Override
                            public void right() {
                                mHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (mErrorDialog != null && mErrorDialog.isShowing()) {
                                            mErrorDialog.dismiss();
                                        }
                                        refreshEmpty();
                                    }
                                });
                            }

                            @Override
                            public void cancel() {
                                mHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (mErrorDialog != null && mErrorDialog.isShowing()) {
                                            mErrorDialog.dismiss();
                                        }
                                        refreshEmpty();
                                    }
                                });
                            }
                        });
                        mErrorDialog.show();
                    }
                });
            }
        }, this);
    }

    private void initViewPager(AbsSortXml absXml) {
        if (sortAdapter.getData().size() > 0) {
            for (MovieSort.SortData data : sortAdapter.getData()) {
                if (data.id.equals("my0")) {
                    if (Hawk.get(HawkConfig.HOME_REC, 0) == 1 && absXml != null && absXml.videoList != null && absXml.videoList.size() > 0) {
                        fragments.add(UserFragment.newInstance(absXml.videoList));
                    } else {
                        fragments.add(UserFragment.newInstance(null));
                    }
                } else {
                    fragments.add(GridFragment.newInstance(data));
                }
            }
            pageAdapter = new HomePageAdapter(getSupportFragmentManager(), fragments);
            try {
                Field field = ViewPager.class.getDeclaredField("mScroller");
                field.setAccessible(true);
                FixedSpeedScroller scroller = new FixedSpeedScroller(mContext, new AccelerateInterpolator());
                field.set(mViewPager, scroller);
                scroller.setmDuration(300);
            } catch (Exception e) {
            }
            mViewPager.setPageTransformer(true, new DefaultTransformer());
            mViewPager.setAdapter(pageAdapter);
            mViewPager.setCurrentItem(currentSelected, false);
        }
    }

    @Override
    public void onBackPressed() {
        // 加载API期间禁止返回
        if(isLoading()){
            return;
        }
        // 如果处于 VOD 删除模式，则退出该模式并刷新界面
        if (HawkConfig.hotVodDelete) {
            HawkConfig.hotVodDelete = false;
            UserFragment.notifyHomeAdapterChanged();
            return;
        }

        // 检查 fragments 状态
        if (this.fragments.size() <= 0 || this.sortFocused >= this.fragments.size() || this.sortFocused < 0) {
            doExit();
            return;
        }

        BaseLazyFragment baseLazyFragment = this.fragments.get(this.sortFocused);
        if (baseLazyFragment instanceof GridFragment) {
            GridFragment grid = (GridFragment) baseLazyFragment;
            // 如果当前 Fragment 能恢复之前保存的 UI 状态，则直接返回
            if (grid.restoreView()) {
                return;
            }
            // 如果 sortFocusView 存在且没有获取焦点，则请求焦点
            if (this.sortFocusView != null && !this.sortFocusView.isFocused()) {
                this.sortFocusView.requestFocus();
            }
            // 如果当前不是第一个界面，则将列表设置到第一项
            else if (this.sortFocused != 0) {
                this.mGridView.setSelection(0);
            } else {
                doExit();
            }
        } else if (baseLazyFragment instanceof UserFragment && UserFragment.canScrollUp()) {
            // 如果 UserFragment 列表可以向上滚动，则滚动到顶部
            UserFragment.scrollToTop();
            this.mGridView.setSelection(0);
        } else {
            doExit();
        }
    }

    private void doExit() {
        // 如果两次返回间隔小于 2000 毫秒，则退出应用
        if (System.currentTimeMillis() - mExitTime < 2000) {
            try {
                EventBus.getDefault().unregister(this);
            } catch (Exception e) {
            }
            try {
                ControlManager.get().stopServer();
            } catch (Exception e) {
            }
            try {
                UpdateCheckManager.get().shutdown();
            } catch (Exception e) {
            }
            // 关键修复：使用appExit直接退出，确保应用真正退出
            AppManager.getInstance().appExit(0);
        } else {
            // 否则仅提示用户，再按一次退出应用
            mExitTime = System.currentTimeMillis();
            ToastHelper.showToast(mContext, getString(R.string.hm_exit));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mFromSettings) {
            mFromSettings = false;
            String apiUrl = Hawk.get(HawkConfig.API_URL, "");
            if (apiUrl.isEmpty()) {
                initData();
            }
        }

        // takagen99 : Switch to show / hide source title
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        if (Hawk.get(HawkConfig.HOME_SHOW_SOURCE, false)) {
            if (home != null && home.getName() != null && !home.getName().isEmpty()) {
                tvName.setText(home.getName());
                tvName.clearAnimation();
            }
        } else {
            tvName.setText(R.string.app_name);
        }

        // takagen99: Icon Placement
        if (Hawk.get(HawkConfig.HOME_SEARCH_POSITION, true)) {
            tvFind.setVisibility(View.VISIBLE);
        } else {
            tvFind.setVisibility(View.GONE);
        }
        if (Hawk.get(HawkConfig.HOME_MENU_POSITION, true)) {
            tvMenu.setVisibility(View.VISIBLE);
        } else {
            tvMenu.setVisibility(View.GONE);
        }
        mHandler.post(mRunnable);
        
        int currentHomeRec = Hawk.get(HawkConfig.HOME_REC, 0);
        if (lastHomeRec != -1 && lastHomeRec != currentHomeRec) {
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HOME_REC_CHANGE));
        }
        lastHomeRec = currentHomeRec;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacksAndMessages(null);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (event.type == RefreshEvent.TYPE_PUSH_URL) {
            if (ApiConfig.get().getSource("push_agent") != null) {
                Intent newIntent = new Intent(mContext, DetailActivity.class);
                newIntent.putExtra("id", (String) event.obj);
                newIntent.putExtra("sourceKey", "push_agent");
                newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                HomeActivity.this.startActivity(newIntent);
            }
        } else if (event.type == RefreshEvent.TYPE_FILTER_CHANGE) {
            if (currentView != null) {
//                showFilterIcon((int) event.obj);
            }
        } else if (event.type == RefreshEvent.TYPE_STORAGE_PERMISSION_DENIED) {
            // Python爬虫等功能需要存储权限
            new AlertDialog.Builder(this)
                    .setTitle("权限提醒")
                    .setMessage("部分功能需要存储权限才能正常使用，是否前往设置开启权限？")
                    .setPositiveButton("去设置", (dialog, which) -> {
                        XXPermissions.startPermissionActivity(this, DefaultConfig.StoragePermissionGroup());
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    private void showFilterIcon(int count) {
        boolean activated = count > 0;
        currentView.findViewById(R.id.tvFilter).setVisibility(View.VISIBLE);
        ImageView imgView = currentView.findViewById(R.id.tvFilter);
        imgView.setColorFilter(activated ? this.getThemeColor() : Color.WHITE);
    }

    private final Runnable mDataRunnable = new Runnable() {
        @Override
        public void run() {
            if (sortChange) {
                sortChange = false;
                if (sortFocused != currentSelected) {
                    currentSelected = sortFocused;
                    mViewPager.setCurrentItem(sortFocused, false);
                    changeTop(sortFocused != 0);
                }
            }
        }
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (topHide < 0)
            return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                showSiteSwitch();
            }
//            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
//                if () {
//
//                }
//            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {

        }
        return super.dispatchKeyEvent(event);
    }

    byte topHide = 0;

    private void changeTop(boolean hide) {
        ViewObj viewObj = new ViewObj(topLayout, (ViewGroup.MarginLayoutParams) topLayout.getLayoutParams());
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                topHide = (byte) (hide ? 1 : 0);
            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        });
        // Hide Top =======================================================
        if (hide && topHide == 0) {
            animatorSet.playTogether(ObjectAnimator.ofObject(viewObj, "marginTop", new IntEvaluator(),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 20.0f)),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 0.0f))),
                    ObjectAnimator.ofObject(viewObj, "height", new IntEvaluator(),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 50.0f)),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 1.0f))),
                    ObjectAnimator.ofFloat(this.topLayout, "alpha", 1.0f, 0.0f));
            animatorSet.setDuration(250);
            animatorSet.start();
            tvName.setFocusable(false);
            tvWifi.setFocusable(false);
            tvFind.setFocusable(false);
            tvStyle.setFocusable(false);
            tvDraw.setFocusable(false);
            tvMenu.setFocusable(false);
            return;
        }
        // Show Top =======================================================
        if (!hide && topHide == 1) {
            animatorSet.playTogether(ObjectAnimator.ofObject(viewObj, "marginTop", new IntEvaluator(),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 0.0f)),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 20.0f))),
                    ObjectAnimator.ofObject(viewObj, "height", new IntEvaluator(),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 1.0f)),
                            Integer.valueOf(AutoSizeUtils.mm2px(this.mContext, 50.0f))),
                    ObjectAnimator.ofFloat(this.topLayout, "alpha", 0.0f, 1.0f));
            animatorSet.setDuration(250);
            animatorSet.start();
            tvName.setFocusable(true);
            tvWifi.setFocusable(true);
            tvFind.setFocusable(true);
            tvStyle.setFocusable(true);
            tvDraw.setFocusable(true);
            tvMenu.setFocusable(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 先注销EventBus,防止内存泄露
        try {
            EventBus.getDefault().unregister(this);
        } catch (Throwable th) {
        }
        mHandler.removeCallbacksAndMessages(null);
        try {
            ControlManager.get().stopServer();
        } catch (Exception e) {
        }
        // 取消Toast，防止内存泄漏
        ToastHelper.cancelToast();
        res = null;
    }

    // Site Switch on Home Button
    void showSiteSwitch() {
        List<SourceBean> sites = new ArrayList<>();
        for (SourceBean sb : ApiConfig.get().getSourceBeanList()) {
            if (sb.getHide() == 0) sites.add(sb);
        }
        if (sites.size() > 0) {
            SelectDialog<SourceBean> dialog = new SelectDialog<>(HomeActivity.this);

            // Multi Column Selection
            int spanCount = (int) Math.floor(sites.size() / 10);
            if (spanCount <= 1) spanCount = 1;
            if (spanCount >= 3) spanCount = 3;

            TvRecyclerView tvRecyclerView = dialog.findViewById(R.id.list);
            tvRecyclerView.setLayoutManager(new V7GridLayoutManager(dialog.getContext(), spanCount));
            ConstraintLayout cl_root = dialog.findViewById(R.id.cl_root);
            ViewGroup.LayoutParams clp = cl_root.getLayoutParams();
            if (spanCount != 1) {
                clp.width = AutoSizeUtils.mm2px(dialog.getContext(), 400 + 260 * (spanCount - 1));
            }

            dialog.setTip(getString(R.string.dia_source));
            dialog.setAdapter(tvRecyclerView, new SelectDialogAdapter.SelectDialogInterface<SourceBean>() {
                @Override
                public void click(SourceBean value, int pos) {
                    ApiConfig.get().setSourceBean(value);
                    reloadHome();
                }

                @Override
                public String getDisplay(SourceBean val) {
                    return val.getName();
                }
            }, new DiffUtil.ItemCallback<SourceBean>() {
                @Override
                public boolean areItemsTheSame(@NonNull @NotNull SourceBean oldItem, @NonNull @NotNull SourceBean newItem) {
                    return oldItem == newItem;
                }

                @Override
                public boolean areContentsTheSame(@NonNull @NotNull SourceBean oldItem, @NonNull @NotNull SourceBean newItem) {
                    return oldItem.getKey().equals(newItem.getKey());
                }
            }, sites, sites.indexOf(ApiConfig.get().getHomeSourceBean()));
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
//                    if (homeSourceKey != null && !homeSourceKey.equals(Hawk.get(HawkConfig.HOME_API, ""))) {
//                        Intent intent = new Intent(getApplicationContext(), HomeActivity.class);
//                        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
//                        Bundle bundle = new Bundle();
//                        bundle.putBoolean("useCache", true);
//                        intent.putExtras(bundle);
//                        HomeActivity.this.startActivity(intent);
//                    }
                }
            });
            dialog.show();
        }
    }

    void reloadHome() {
        Intent intent = new Intent(getApplicationContext(), HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle bundle = new Bundle();
        bundle.putBoolean("useCache", true);
        intent.putExtras(bundle);
        startActivity(intent);
        finish();
    }

    private void refreshEmpty() {
        skipNextUpdate=true;
        showSuccess();
        ApiConfig.get().cancelCurrentRequest();
        sortAdapter.setNewData(DefaultConfig.adjustSort(ApiConfig.get().getHomeSourceBean().getKey(), new ArrayList<>(), true));
        initViewPager(null);
        tvName.clearAnimation();
    }

    private void tvNameAnimation()
    {
        AlphaAnimation blinkAnimation = new AlphaAnimation(0.0f, 1.0f);
        blinkAnimation.setDuration(500);
        blinkAnimation.setStartOffset(20);
        blinkAnimation.setRepeatMode(Animation.REVERSE);
        blinkAnimation.setRepeatCount(Animation.INFINITE);
        tvName.startAnimation(blinkAnimation);
    }

    private boolean hasStoragePermission() {
        // Android 11+ (API 30+) 检查 MANAGE_EXTERNAL_STORAGE 特殊权限
        // 注意：只有当应用的 targetSdkVersion >= 30 时才需要检查此权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        // Android 10 及以下，或者 targetSdkVersion < 30 的应用使用传统存储权限
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void showStoragePermissionDialog(Runnable onGranted) {
        // Android 11+ (API 30+) 检查 MANAGE_EXTERNAL_STORAGE 特殊权限
        // 注意：只有当应用的 targetSdkVersion >= 30 时才需要检查此权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R) {
            new AlertDialog.Builder(HomeActivity.this)
                    .setTitle("权限提醒")
                    .setMessage("需要所有文件访问权限才能清理缓存。是否前往设置开启权限？")
                    .setPositiveButton("去设置", (dialog, which) -> {
                        XXPermissions.startPermissionActivity(HomeActivity.this, DefaultConfig.StoragePermissionGroup());
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        // Android 10 及以下申请传统存储权限
        XXPermissions.with(this)
                .permission(DefaultConfig.StoragePermissionGroup())
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean all) {
                        onGranted.run();
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {
                        new AlertDialog.Builder(HomeActivity.this)
                                .setTitle("权限提醒")
                                .setMessage("存储权限被拒绝，无法清理缓存。是否前往设置开启权限？")
                                .setPositiveButton("去设置", (dialog, which) -> {
                                    XXPermissions.startPermissionActivity(HomeActivity.this, permissions);
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
    }

    private void clearCacheAndReload() {
        isClearingCache = true;

        File dir = getCacheDir();
        FileUtils.recursiveDelete(dir);
        dir = getExternalCacheDir();
        FileUtils.recursiveDelete(dir);
        ToastHelper.showToast(getString(R.string.hm_cache_del));
        if(dataInitOk && jarInitOk){
            SourceBean homeSource = ApiConfig.get().getHomeSourceBean();
            if (homeSource == null) {
                isClearingCache = false;
                return;
            }
            String cspCachePath = FileUtils.getFilePath()+"/csp/";
            String jar = homeSource.getJar();
            String jarUrl = (jar != null && !jar.isEmpty()) ? jar : ApiConfig.get().getSpider();
            if (jarUrl == null || jarUrl.isEmpty()) {
                isClearingCache = false;
                return;
            }
            File cspCacheDir = new File(cspCachePath + MD5.string2MD5(jarUrl)+".jar");
            if (!cspCacheDir.exists()){
                reloadHome();
                return;
            }
            AppTaskExecutor.getInstance().executeOnDeskIO(() -> {
                try {
                    FileUtils.deleteFile(cspCacheDir);
                    ApiConfig.get().clearJarLoader();
                    mHandler.post(() -> {
                        reloadHome();
                    });
                } catch (Exception e) {
                    mHandler.post(() -> {
                        isClearingCache = false;
                    });
                    LOG.e(e);
                }
            });
        } else {
            isClearingCache = false;
        }
    }
}
