package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.BounceInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.ui.activity.CollectActivity;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.DriveActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.HistoryActivity;
import com.github.tvbox.osc.ui.activity.LivePlayActivity;
import com.github.tvbox.osc.ui.activity.PushActivity;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.adapter.HomeHotVodAdapter;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.ToastHelper;
import com.github.tvbox.osc.util.UA;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.UpdateCheckManager;
import com.github.tvbox.osc.event.RefreshEvent;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * @author pj567
 * @date :2021/3/9
 * @description:
 */
public class UserFragment extends BaseLazyFragment implements View.OnClickListener {
    private LinearLayout tvDrive;
    private LinearLayout tvLive;
    private LinearLayout tvSearch;
    private LinearLayout tvSetting;
    private LinearLayout tvHistory;
    private LinearLayout tvCollect;
    private LinearLayout tvPush;
    private TextView tvHistoryBadge;
    private ImageView ivHistoryIcon;
    private Animation rotateAnimation;
    private static WeakReference<LinearLayout> tvUserHomeRef;
    private static WeakReference<HomeHotVodAdapter> homeHotVodAdapterRef;
    private List<Movie.Video> homeSourceRec;
    private static WeakReference<TvRecyclerView> tvHotListForGridRef;
    private static WeakReference<TvRecyclerView> tvHotListForLineRef;
    private static boolean isUserHomeVisible = true;
    private UpdateCheckManager.UpdateCheckListener updateCheckListener;

    public static UserFragment newInstance() {
        return new UserFragment();
    }

    public static UserFragment newInstance(List<Movie.Video> recVod) {
        return new UserFragment().setArguments(recVod);
    }

    public UserFragment setArguments(List<Movie.Video> recVod) {
        this.homeSourceRec = recVod;
        return this;
    }

    @Override
    public void onFragmentResume() {

        // takagen99: Initialize Icon Placement
        if (!Hawk.get(HawkConfig.HOME_SEARCH_POSITION, true)) {
            tvSearch.setVisibility(View.VISIBLE);
        } else {
            tvSearch.setVisibility(View.GONE);
        }
        if (!Hawk.get(HawkConfig.HOME_MENU_POSITION, true)) {
            tvSetting.setVisibility(View.VISIBLE);
        } else {
            tvSetting.setVisibility(View.GONE);
        }

        super.onFragmentResume();
        
        if (UpdateCheckManager.get().isChecking()) {
            setCheckingState(true);
        }
        
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) {
        refreshHomeHotVod();
    }
    if (tvHistoryBadge != null) {
        tvHistoryBadge.setVisibility(UpdateCheckManager.get().hasUpdate() ? View.VISIBLE : View.GONE);
    }
}

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }
    private ImgUtil.Style style;
    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        tvDrive = findViewById(R.id.tvDrive);
        tvLive = findViewById(R.id.tvLive);
        tvSearch = findViewById(R.id.tvSearch);
        tvSetting = findViewById(R.id.tvSetting);
        tvCollect = findViewById(R.id.tvFavorite);
        tvHistory = findViewById(R.id.tvHistory);
        tvPush = findViewById(R.id.tvPush);
        tvUserHomeRef = new WeakReference<>(findViewById(R.id.tvUserHome));
        tvHistoryBadge = findViewById(R.id.tvHistoryBadge);
        tvDrive.setOnClickListener(this);
        tvLive.setOnClickListener(this);
        tvSearch.setOnClickListener(this);
        tvSetting.setOnClickListener(this);
        tvHistory.setOnClickListener(this);
        tvPush.setOnClickListener(this);
        tvCollect.setOnClickListener(this);
        tvDrive.setOnFocusChangeListener(focusChangeListener);
        tvLive.setOnFocusChangeListener(focusChangeListener);
        tvSearch.setOnFocusChangeListener(focusChangeListener);
        tvSetting.setOnFocusChangeListener(focusChangeListener);
        tvHistory.setOnFocusChangeListener(focusChangeListener);
        tvPush.setOnFocusChangeListener(focusChangeListener);
        tvCollect.setOnFocusChangeListener(focusChangeListener);
        initUpdateBadge();
        TvRecyclerView tvHotListForLine = findViewById(R.id.tvHotListForLine);
        tvHotListForLineRef = new WeakReference<>(tvHotListForLine);
        TvRecyclerView tvHotListForGrid = findViewById(R.id.tvHotListForGrid);
        tvHotListForGridRef = new WeakReference<>(tvHotListForGrid);
        tvHotListForGrid.setHasFixedSize(true);
        int spanCount = 5;
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 1 && homeSourceRec!=null) {
            style=ImgUtil.initStyle();
        }
        if(style!=null && Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
            spanCount=ImgUtil.spanCountByStyle(style,spanCount);
        }
        TvRecyclerView gridRef = tvHotListForGridRef.get();
        if (gridRef != null) {
            gridRef.setLayoutManager(new V7GridLayoutManager(this.mContext, spanCount));
        }
        String tvRate="";
        if(Hawk.get(HawkConfig.HOME_REC, 0) == 0){
            tvRate="豆瓣热播";
        }else if(Hawk.get(HawkConfig.HOME_REC, 0) == 1){
          tvRate= homeSourceRec != null ? "站点推荐" : "豆瓣热播";
        }
        HomeHotVodAdapter homeHotVodAdapter = new HomeHotVodAdapter(style,tvRate);
        homeHotVodAdapterRef = new WeakReference<>(homeHotVodAdapter);
        final HomeHotVodAdapter finalAdapter = homeHotVodAdapter;
        homeHotVodAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty())
                    return;
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));

                // takagen99: CHeck if in Delete Mode
                if ((vod.id != null && !vod.id.isEmpty()) && (Hawk.get(HawkConfig.HOME_REC, 0) == 2) && HawkConfig.hotVodDelete) {
                    finalAdapter.remove(position);
                    VodInfo vodInfo = RoomDataManger.getVodInfo(vod.sourceKey, vod.id);
                    UpdateCheckManager.get().clearVideoUpdate(vod.sourceKey, vod.id);
                    RoomDataManger.deleteVodRecord(vod.sourceKey, vodInfo);
                    CacheManager.deleteByKeyPrefix(vod.sourceKey + vod.id);
                    ToastHelper.showToast(mContext, getString(R.string.hm_hist_del));
                } else if (vod.id != null && !vod.id.isEmpty()) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", vod.id);
                    bundle.putString("sourceKey", vod.sourceKey);
                    if (vod.id.startsWith("msearch:")) {
                        bundle.putString("title", vod.name);
                        jumpActivity(FastSearchActivity.class, bundle);
                    } else {
                        jumpActivity(DetailActivity.class, bundle);
                    }
                } else {
                    Intent newIntent;
                    if(Hawk.get(HawkConfig.FAST_SEARCH_MODE, false)){
                        newIntent = new Intent(mContext, FastSearchActivity.class);
                    }else {
                        newIntent = new Intent(mContext, SearchActivity.class);
                    }
                    newIntent.putExtra("title", vod.name);
                    newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    mActivity.startActivity(newIntent);
                }
            }
        });
        // takagen99 : Long press to trigger Delete Mode for VOD History on Home Page
        homeHotVodAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty())
                    return false;
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));
                // Additional Check if : Home Rec 0=豆瓣, 1=推荐, 2=历史
                if ((vod.id != null && !vod.id.isEmpty()) && (Hawk.get(HawkConfig.HOME_REC, 0) == 2)) {
                    HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
                    finalAdapter.notifyDataSetChanged();
                } else {
                    Intent newIntent = new Intent(mContext, FastSearchActivity.class);
                    newIntent.putExtra("title", vod.name);
                    newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    mActivity.startActivity(newIntent);
                }
                return true;
            }
        });
        
        // Grid View
        if (gridRef != null) {
            gridRef.setOnItemListener(new TvRecyclerView.OnItemListener() {
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
            gridRef.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    
                    TvRecyclerView gridRefInner = tvHotListForGridRef.get();
                    LinearLayout userHomeInner = tvUserHomeRef.get();
                    boolean canScrollUp = gridRefInner != null && !gridRefInner.canScrollVertically(-1);
                    
                    if (dy > 0) {
                        if (isUserHomeVisible && userHomeInner != null) {
                            userHomeInner.setVisibility(View.GONE);
                            isUserHomeVisible = false;
                        }
                    } else if (dy < 0 && !canScrollUp) {
                        if (isUserHomeVisible && userHomeInner != null) {
                            userHomeInner.setVisibility(View.GONE);
                            isUserHomeVisible = false;
                        }
                    }
                }
                
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);
                    
                    TvRecyclerView gridRefInner = tvHotListForGridRef.get();
                    LinearLayout userHomeInner = tvUserHomeRef.get();
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        boolean canScrollUp = gridRefInner != null && !gridRefInner.canScrollVertically(-1);
                        if (canScrollUp && !isUserHomeVisible && userHomeInner != null) {
                            userHomeInner.setVisibility(View.VISIBLE);
                            isUserHomeVisible = true;
                        }
                    }
                }
            });
            gridRef.setAdapter(homeHotVodAdapter);
        }
        // Line View
        TvRecyclerView lineRef = tvHotListForLineRef.get();
        if (lineRef != null) {
            lineRef.setOnItemListener(new TvRecyclerView.OnItemListener() {
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
            lineRef.setAdapter(homeHotVodAdapter);
        }

        initHomeHotVod(homeHotVodAdapter);

        // Swifly: Home Style
        if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) {
            if (gridRef != null) gridRef.setVisibility(View.VISIBLE);
            if (lineRef != null) lineRef.setVisibility(View.GONE);
        } else {
            if (gridRef != null) gridRef.setVisibility(View.GONE);
            if (lineRef != null) lineRef.setVisibility(View.VISIBLE);
        }
    }

    private void initHomeHotVod(HomeHotVodAdapter adapter) {
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
            if (homeSourceRec != null) {
                adapter.setNewData(homeSourceRec);
            }
            return;
        } else if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) {
            return;
        }
        try {
            Calendar cal = Calendar.getInstance();
            int year = cal.get(Calendar.YEAR);
            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DATE);
            String today = String.format("%d%d%d", year, month, day);
            String requestDay = Hawk.get("home_hot_day", "");
            if (requestDay.equals(today)) {
                String json = Hawk.get("home_hot", "");
                if (!json.isEmpty()) {
                    adapter.setNewData(loadHots(json));
                    return;
                }
            }
            String doubanHotURL = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range=" + year + "," + year;
            String userAgent = UA.random();
            OkGo.<String>get(doubanHotURL).headers("User-Agent", userAgent).execute(new AbsCallback<String>() {
                @Override
                public void onSuccess(Response<String> response) {
                    String netJson = response.body();
                    Hawk.put("home_hot_day", today);
                    Hawk.put("home_hot", netJson);
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            adapter.setNewData(loadHots(netJson));
                        }
                    });
                }

                @Override
                public String convertResponse(okhttp3.Response response) throws Throwable {
                    return response.body().string();
                }
            });
        } catch (Throwable th) {
            LOG.e(th);
        }
    }

    private ArrayList<Movie.Video> loadHots(String json) {
        ArrayList<Movie.Video> result = new ArrayList<>();
        try {
            JsonObject infoJson = new Gson().fromJson(json, JsonObject.class);
            JsonArray array = infoJson.getAsJsonArray("data");
            for (JsonElement ele : array) {
                JsonObject obj = (JsonObject) ele;
                Movie.Video vod = new Movie.Video();
                vod.name = obj.get("title").getAsString();
                vod.note = obj.get("rate").getAsString();
                vod.pic = obj.get("cover").getAsString() + "@User-Agent=" + UA.random() + "@Referer=https://www.douban.com/";                
                result.add(vod);
            }
        } catch (Throwable th) {

        }
        return result;
    }

    private void initUpdateBadge() {
        if (tvHistoryBadge != null) {
            tvHistoryBadge.setVisibility(UpdateCheckManager.get().hasUpdate() ? View.VISIBLE : View.GONE);
        }
        ivHistoryIcon = findViewById(R.id.hm_history);
        rotateAnimation = AnimationUtils.loadAnimation(mContext, R.anim.rotate_refresh_reverse);
        updateCheckListener = new UpdateCheckManager.UpdateCheckListener() {
            @Override
            public void onCheckComplete(boolean hasUpdate, Map<String, Boolean> updates) {
                setCheckingState(false);
                if (tvHistoryBadge != null) {
                    tvHistoryBadge.setVisibility(hasUpdate ? View.VISIBLE : View.GONE);
                }
                if (Hawk.get(HawkConfig.HOME_REC, 0) == 2 && homeHotVodAdapterRef.get() != null) {
                    refreshHomeHotVod();
                }
            }

            @Override
            public void onCheckProgress(int current, int total) {
            }

            @Override
            public void onCheckError(String errorMessage) {
                setCheckingState(false);
            }
        };
        UpdateCheckManager.get().addListener(updateCheckListener);
        if (UpdateCheckManager.get().isChecking()) {
            setCheckingState(true);
        }
    }

    private void setCheckingState(boolean checking) {
        if (ivHistoryIcon != null) {
            if (checking) {
                ivHistoryIcon.startAnimation(rotateAnimation);
            } else {
                ivHistoryIcon.clearAnimation();
            }
        }
    }

    private final View.OnFocusChangeListener focusChangeListener = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus)
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            else
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
        }
    };

    @Override
    public void onClick(View v) {
        // 检查Fragment是否已添加,避免在已销毁时处理点击事件
        if (!isAdded() || isDetached()) {
            return;
        }

        // takagen99: Remove Delete Mode
        HawkConfig.hotVodDelete = false;

        FastClickCheckUtil.check(v);
        if (v.getId() == R.id.tvLive) {
            jumpActivity(LivePlayActivity.class);
        } else if (v.getId() == R.id.tvSearch) {
            jumpActivity(SearchActivity.class);
        } else if (v.getId() == R.id.tvSetting) {
            jumpActivity(SettingActivity.class);
        } else if (v.getId() == R.id.tvHistory) {
            jumpActivity(HistoryActivity.class);
        } else if (v.getId() == R.id.tvPush) {
            jumpActivity(PushActivity.class);
        } else if (v.getId() == R.id.tvFavorite) {
            jumpActivity(CollectActivity.class);
        } else if (v.getId() == R.id.tvDrive) {
            jumpActivity(DriveActivity.class);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void server(ServerEvent event) {
        if (event.type == ServerEvent.SERVER_CONNECTION) {
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        // 检查Fragment是否已添加,避免在已销毁时处理事件
        if (!isAdded() || isDetached()) {
            return;
        }
        if (event.type == RefreshEvent.TYPE_HISTORY_REFRESH || event.type == RefreshEvent.TYPE_APP_REFRESH) {
            if (tvHistoryBadge != null) {
                tvHistoryBadge.setVisibility(UpdateCheckManager.get().hasUpdate() ? View.VISIBLE : View.GONE);
            }
            if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) {
                refreshHomeHotVod();
            }
        } else if (event.type == RefreshEvent.TYPE_HOME_REC_CHANGE) {
            refreshHomeRecType();
        }
    }

    private void refreshHomeRecType() {
        HomeHotVodAdapter adapter = homeHotVodAdapterRef.get();
        if (adapter == null) return;
        
        int homeRec = Hawk.get(HawkConfig.HOME_REC, 0);
        String tvRate = "";
        if (homeRec == 0) {
            tvRate = "豆瓣热播";
        } else if (homeRec == 1) {
            tvRate = homeSourceRec != null ? "站点推荐" : "豆瓣热播";
        } else if (homeRec == 2) {
            tvRate = "历史记录";
        }
        adapter.setTitle(tvRate);
        
        if (homeRec == 2) {
            refreshHomeHotVod();
        } else if (homeRec == 1 && homeSourceRec != null) {
            adapter.setNewData(homeSourceRec);
        } else if (homeRec == 0) {
            initHomeHotVod(adapter);
        } else {
            adapter.setNewData(new ArrayList<>());
        }
    }

    private void refreshHomeHotVod() {
        try {
            List<VodInfo> allVodRecord = RoomDataManger.getAllVodRecord(20);
            if (allVodRecord == null || allVodRecord.isEmpty()) {
                HomeHotVodAdapter adapter = homeHotVodAdapterRef.get();
                if (adapter != null) {
                    adapter.setNewData(new ArrayList<>());
                }
                return;
            }
            
            List<VodInfo> validRecords = new ArrayList<>();
            for (VodInfo vodInfo : allVodRecord) {
                if (vodInfo != null && vodInfo.id != null && vodInfo.sourceKey != null) {
                    validRecords.add(vodInfo);
                }
            }
            
            Collections.sort(validRecords, new Comparator<VodInfo>() {
                @Override
                public int compare(VodInfo o1, VodInfo o2) {
                    if (o1 == null || o2 == null) return 0;
                    try {
                        boolean hasUpdate1 = UpdateCheckManager.get().hasVideoUpdate(o1.sourceKey, o1.id);
                        boolean hasUpdate2 = UpdateCheckManager.get().hasVideoUpdate(o2.sourceKey, o2.id);
                        if (hasUpdate1 && !hasUpdate2) {
                            return -1;
                        } else if (!hasUpdate1 && hasUpdate2) {
                            return 1;
                        }
                    } catch (Exception e) {
                    }
                    return 0;
                }
            });
            List<Movie.Video> vodList = new ArrayList<>();
            for (VodInfo vodInfo : validRecords) {
                try {
                    Movie.Video vod = new Movie.Video();
                    vod.id = vodInfo.id;
                    vod.sourceKey = vodInfo.sourceKey;
                    vod.name = vodInfo.name != null ? vodInfo.name : "";
                    vod.pic = vodInfo.pic;
                    if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty())
                        vod.note = "上次看到" + vodInfo.playNote;
                    vodList.add(vod);
                } catch (Exception e) {
                }
            }
            HomeHotVodAdapter adapter = homeHotVodAdapterRef.get();
            if (adapter != null) {
                adapter.setNewData(vodList);
            }
        } catch (Exception e) {
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        if (ivHistoryIcon != null) {
            ivHistoryIcon.clearAnimation();
        }
        if (updateCheckListener != null) {
            UpdateCheckManager.get().removeListener(updateCheckListener);
            updateCheckListener = null;
        }
        homeHotVodAdapterRef.clear();
        tvHotListForGridRef.clear();
        tvHotListForLineRef.clear();
        tvUserHomeRef.clear();
    }

    public static void setUserHomeVisibility(int visibility) {
        LinearLayout userHome = tvUserHomeRef.get();
        if (userHome != null) {
            userHome.setVisibility(visibility);
        }
    }

    public static void updateUserHomeVisibility() {
        LinearLayout userHome = tvUserHomeRef.get();
        TvRecyclerView grid = tvHotListForGridRef.get();
        if (userHome != null && grid != null) {
            boolean canScrollUp = !grid.canScrollVertically(-1);
            if (canScrollUp) {
                userHome.setVisibility(View.VISIBLE);
                isUserHomeVisible = true;
            } else {
                userHome.setVisibility(View.GONE);
                isUserHomeVisible = false;
            }
        }
    }

    public static void notifyHomeAdapterChanged() {
        HomeHotVodAdapter adapter = homeHotVodAdapterRef.get();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    public static boolean canScrollUp() {
        TvRecyclerView grid = tvHotListForGridRef.get();
        return grid != null && grid.canScrollVertically(-1);
    }

    public static void scrollToTop() {
        TvRecyclerView grid = tvHotListForGridRef.get();
        if (grid != null) {
            grid.scrollToPosition(0);
        }
    }

    public static void showGridView() {
        TvRecyclerView grid = tvHotListForGridRef.get();
        TvRecyclerView line = tvHotListForLineRef.get();
        if (grid != null) grid.setVisibility(View.VISIBLE);
        if (line != null) line.setVisibility(View.GONE);
    }

    public static void showListView() {
        TvRecyclerView grid = tvHotListForGridRef.get();
        TvRecyclerView line = tvHotListForLineRef.get();
        if (grid != null) grid.setVisibility(View.GONE);
        if (line != null) line.setVisibility(View.VISIBLE);
    }
}
