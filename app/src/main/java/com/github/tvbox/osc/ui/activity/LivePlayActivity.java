package com.github.tvbox.osc.ui.activity;

import static xyz.doikki.videoplayer.util.PlayerUtils.stringForTimeVod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;

import com.github.tvbox.osc.util.ToastHelper;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.Epginfo;
import com.github.tvbox.osc.bean.LiveChannelGroup;
import com.github.tvbox.osc.bean.LiveChannelItem;
import com.github.tvbox.osc.bean.LiveEpgDate;
import com.github.tvbox.osc.bean.LivePlayerManager;
import com.github.tvbox.osc.bean.LiveSettingGroup;
import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.controller.LiveController;
import com.github.tvbox.osc.ui.adapter.ApiHistoryDialogAdapter;
import com.github.tvbox.osc.ui.adapter.LiveChannelGroupAdapter;
import com.github.tvbox.osc.ui.adapter.LiveChannelItemAdapter;
import com.github.tvbox.osc.ui.adapter.LiveEpgAdapter;
import com.github.tvbox.osc.ui.adapter.LiveEpgDateAdapter;
import com.github.tvbox.osc.ui.adapter.LiveSettingGroupAdapter;
import com.github.tvbox.osc.ui.adapter.LiveSettingItemAdapter;
import com.github.tvbox.osc.ui.dialog.ApiHistoryDialog;
import com.github.tvbox.osc.ui.dialog.LivePasswordDialog;
import com.github.tvbox.osc.util.EpgUtil;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HawkListHelper;
import com.github.tvbox.osc.util.HawkUtils;
import com.github.tvbox.osc.util.JavaUtil;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.live.TxtSubscribe;
import com.google.gson.JsonArray;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.apache.commons.lang3.StringUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;
import java.lang.ref.WeakReference;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import kotlin.Pair;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

public class LivePlayActivity extends BaseActivity {

    private Thread logoPreloadThread;
    private Thread epgLoadThread;

    // Main View
    private VideoView mVideoView;
    private LiveController controller;

    // Left Channel View
    private LinearLayout tvLeftChannelListLayout;
    private TvRecyclerView mGroupGridView;
    private LinearLayout mDivLeft;
    private TvRecyclerView mChannelGridView;
    private LinearLayout mDivRight;
    private LinearLayout mGroupEPG;
    private TvRecyclerView mEpgDateGridView;
    private TvRecyclerView mEpgInfoGridView;
    // Left Channel View - Variables
    private LiveChannelGroupAdapter liveChannelGroupAdapter;
    private LiveChannelItemAdapter liveChannelItemAdapter;
    private final List<LiveChannelGroup> liveChannelGroupList = new ArrayList<>();
    private final List<LiveSettingGroup> liveSettingGroupList = new ArrayList<>();
    public static int currentChannelGroupIndex = 0;
    private int currentLiveChannelIndex = -1;
    private LiveChannelItem currentLiveChannelItem = null;

    // 遥控器数字键输入的要切换的频道号码
    private int selectedChannelNumber = 0;
    private TextView tvSelectedChannel;

    // Right Channel View
    private LinearLayout tvRightSettingLayout;
    private TvRecyclerView mSettingGroupView;
    private TvRecyclerView mSettingItemView;
    // Right Channel View - Variables
    private LiveSettingGroupAdapter liveSettingGroupAdapter;
    private LiveSettingItemAdapter liveSettingItemAdapter;
    private final LivePlayerManager livePlayerManager = new LivePlayerManager();
    private final ArrayList<Integer> channelGroupPasswordConfirmed = new ArrayList<>();
    private int currentLiveChangeSourceTimes = 0;

    // Bottom Channel View
    private LinearLayout tvBottomLayout;
    private ImageView tv_logo;
    private TextView tv_sys_time;
    private TextView tv_week;
    private TextView tv_date;
    private TextView tv_size;
    private TextView tv_source;
    // Bottom Channel View - Line 1 / 2 / 3
    private TextView tv_channelname;
    private TextView tv_channelnum;
    private TextView tv_curr_name;
    private TextView tv_curr_time;
    private TextView tv_next_name;
    private TextView tv_next_time;
    // Bottom Channel View - Variables
    private LiveEpgDateAdapter epgDateAdapter;
    private LiveEpgAdapter epgListAdapter;
    private String epgDateInitDay = "";

    // Misc Variables
    public String epgStringAddress = "";
    SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private static LiveChannelItem channel_Name = null;
    
    private static String xmltvSourceUrl = "";
    private static long xmltvCacheTime = 0;
    private static final long XMLTV_CACHE_EXPIRE_MS = 60 * 60 * 1000;
    private static HashMap<String, String> xmltvChannelMap = null;
    private static HashMap<String, ArrayList<HashMap<String, String>>> xmltvProgrammesMap = null;
    private static final String XMLTV_CACHE_FILE = "xmltv_cache.json";
    private static boolean xmltvCacheLoaded = false;
    
    private static final HashMap<String, android.graphics.drawable.Drawable> channelLogoCache = new HashMap<>();
    private static final HashMap<String, Long> channelLogoCacheTime = new HashMap<>();
    private static final long LOGO_CACHE_EXPIRE_MS = 24 * 60 * 60 * 1000;
    private static final HashSet<String> logoFailedCache = new HashSet<>();
    private TextView tvTime;
    private TextView tvNetSpeed;

    // Seek Bar
    boolean mIsDragging;
    LinearLayout llSeekBar;
    TextView mCurrentTime;
    SeekBar mSeekBar;
    TextView mTotalTime;
    boolean isVOD = false;

    // center BACK button
    LinearLayout mBack;

    private boolean isSHIYI = false;
    private static String shiyi_time;//时移时间

    private HashMap<String, String> setPlayHeaders(String url) {
        HashMap<String, String> header = new HashMap<>();
        try {
            boolean matchTo = false;
            JSONArray livePlayHeaders = new JSONArray(ApiConfig.get().getLivePlayHeaders().toString());
            for (int i = 0; i < livePlayHeaders.length(); i++) {
                JSONObject headerObj = livePlayHeaders.getJSONObject(i);
                JSONArray flags = headerObj.getJSONArray("flag");
                JSONObject headerData = headerObj.getJSONObject("header");
                for (int j = 0; j < flags.length(); j++) {
                    String flag = flags.getString(j);
                    if (url.contains(flag)) {
                        matchTo = true;
                        break;
                    }
                }
                if (matchTo) {
                    Iterator<String> keys = headerData.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String value = headerData.getString(key);
                        header.put(key, value);
                    }
                    break;
                }
            }
            if (!matchTo) {
                header.put("User-Agent", "Lavf/59.27.100");
            }
        } catch (Exception e) {
            header.put("User-Agent", "Lavf/59.27.100");
        }
        return header;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_live_play;
    }

    @Override
    protected void init() {

        // takagen99 : Hide only when video playing
        hideSystemUI(false);

        // Getting EPG Address
        epgStringAddress = Hawk.get(HawkConfig.EPG_URL, "");


        EventBus.getDefault().register(this);
        setLoadSir(findViewById(R.id.live_root));
        mVideoView = findViewById(R.id.mVideoView);

        tvSelectedChannel = findViewById(R.id.tv_selected_channel);
        tv_size = findViewById(R.id.tv_size);                 // Resolution
        tv_source = findViewById(R.id.tv_source);             // Source/Total Source
        tv_sys_time = findViewById(R.id.tv_sys_time);         // System Time
        tv_week = findViewById(R.id.tv_week);
        tv_date = findViewById(R.id.tv_date);

        // VOD SeekBar
        llSeekBar = findViewById(R.id.ll_seekbar);
        mCurrentTime = findViewById(R.id.curr_time);
        mSeekBar = findViewById(R.id.seekBar);
        mTotalTime = findViewById(R.id.total_time);

        // Center Back Button
        mBack = findViewById(R.id.tvBackButton);
        mBack.setVisibility(View.INVISIBLE);

        // Bottom Info
        tvBottomLayout = findViewById(R.id.tvBottomLayout);
        tvBottomLayout.setVisibility(View.INVISIBLE);
        tv_channelname = findViewById(R.id.tv_channel_name);  //底部名称
        tv_channelnum = findViewById(R.id.tv_channel_number); //底部数字
        tv_logo = findViewById(R.id.tv_logo);
        tv_curr_time = findViewById(R.id.tv_current_program_time);
        tv_curr_name = findViewById(R.id.tv_current_program_name);
        tv_next_time = findViewById(R.id.tv_next_program_time);
        tv_next_name = findViewById(R.id.tv_next_program_name);

        // EPG Info
        mGroupEPG = findViewById(R.id.mGroupEPG);
        mDivRight = findViewById(R.id.mDivRight);
        mDivLeft = findViewById(R.id.mDivLeft);
        mEpgDateGridView = findViewById(R.id.mEpgDateGridView);
        mEpgInfoGridView = findViewById(R.id.mEpgInfoGridView);

        // Left Layout
        tvLeftChannelListLayout = findViewById(R.id.tvLeftChannelListLayout);
        mGroupGridView = findViewById(R.id.mGroupGridView);
        mChannelGridView = findViewById(R.id.mChannelGridView);

        // Right Layout
        tvRightSettingLayout = findViewById(R.id.tvRightSettingLayout);
        mSettingGroupView = findViewById(R.id.mSettingGroupView);
        mSettingItemView = findViewById(R.id.mSettingItemView);

        // Not in Used
        tvTime = findViewById(R.id.tvTime);
        tvNetSpeed = findViewById(R.id.tvNetSpeed);

        // Initialization
        initEpgDateView();
        initEpgListView();
        initVideoView();
        initChannelGroupView();
        initLiveChannelView();
        initSettingGroupView();
        initSettingItemView();
        initLiveChannelList();
        initLiveSettingGroupList();

        // takagen99 : Add SeekBar for VOD
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                mHandler.removeCallbacks(mHideChannelInfoRun);
                mHandler.postDelayed(mHideChannelInfoRun, 6000);

                long duration = mVideoView.getDuration();
                long newPosition = (duration * progress) / seekBar.getMax();
                if (mCurrentTime != null)
                    mCurrentTime.setText(stringForTimeVod((int) newPosition));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mIsDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mIsDragging = false;
                long duration = mVideoView.getDuration();
                long newPosition = (duration * seekBar.getProgress()) / seekBar.getMax();
                mVideoView.seekTo((int) newPosition);
            }
        });
        mSeekBar.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View arg0, int keycode, KeyEvent event) {

                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keycode == KeyEvent.KEYCODE_DPAD_LEFT || keycode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        mIsDragging = true;
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    mIsDragging = false;
                    long duration = mVideoView.getDuration();
                    long newPosition = (duration * mSeekBar.getProgress()) / mSeekBar.getMax();
                    mVideoView.seekTo((int) newPosition);
                }
                return false;
            }
        });
        // Button: BACK click to go back to previous page -------------------
        mBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
    }

    boolean PiPON = Hawk.get(HawkConfig.BACKGROUND_PLAY_TYPE, 0) == 2;

    // takagen99 : Enter PIP if supported
    @Override
    public void onUserLeaveHint() {
        if (supportsPiPMode() && PiPON) {
            // Hide controls when entering PIP
            mHandler.post(mHideChannelListRun);
            mHandler.post(mHideChannelInfoRun);
            mHandler.post(mHideSettingLayoutRun);
            enterPictureInPictureMode();
        }
    }

    @Override
    public void onBackPressed() {
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
        } else if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
        } else if (tvBottomLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelInfoRun);
            mHandler.post(mHideChannelInfoRun);
        } else {
            mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
            mHandler.removeCallbacks(mUpdateNetSpeedRun);
            mHandler.removeCallbacks(mUpdateTimeRun);
            mHandler.removeCallbacks(tv_sys_timeRunnable);
            exit();
        }
    }

    private long mExitTime = 0;

    private void exit() {
        if (System.currentTimeMillis() - mExitTime < 2000) {
            super.onBackPressed();
        } else {
            mExitTime = System.currentTimeMillis();
            ToastHelper.showToast(mContext, getString(R.string.hm_exit_live));
        }
    }

    private final Runnable mPlaySelectedChannel = new Runnable() {
        @Override
        public void run() {
            tvSelectedChannel.setVisibility(View.GONE);
            tvSelectedChannel.setText("");

            int grpIndx = 0;
            int chaIndx = 0;
            int getMin = 1;
            int getMax;
            for (int j = 0; j < 20; j++) {
                getMax = getMin + getLiveChannels(j).size() - 1;

                if (selectedChannelNumber >= getMin && selectedChannelNumber <= getMax) {
                    grpIndx = j;
                    chaIndx = selectedChannelNumber - getMin + 1;
                    break;
                } else {
                    getMin = getMax + 1;
                }
            }

            if (selectedChannelNumber > 0) {
                playChannel(grpIndx, chaIndx - 1, false);
            }
            selectedChannelNumber = 0;
        }
    };

    private void numericKeyDown(int digit) {
        selectedChannelNumber = selectedChannelNumber * 10 + digit;

        tvSelectedChannel.setText(Integer.toString(selectedChannelNumber));
        tvSelectedChannel.setVisibility(View.VISIBLE);

        mHandler.removeCallbacks(mPlaySelectedChannel);
        mHandler.postDelayed(mPlaySelectedChannel, 2000);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                showSettingGroup();
            } else if (!isListOrSettingLayoutVisible()) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
                            playNext();
                        else
                            playPrevious();
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false))
                            playPrevious();
                        else
                            playNext();
                        break;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        // takagen99 : To cater for newer Android w no Menu button
                        // playPreSource();
                        if (!isVOD) {
                            showSettingGroup();
                        } else {
                            showChannelInfo();
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (!isVOD) {
                            playNextSource();
                        } else {
                            showChannelInfo();
                        }
                        break;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        showChannelList();
                        break;
                    default:
                        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                            keyCode -= KeyEvent.KEYCODE_0;
                        } else if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
                            keyCode -= KeyEvent.KEYCODE_NUMPAD_0;
                        } else {
                            break;
                        }
                        numericKeyDown(keyCode);
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
        }
        return super.dispatchKeyEvent(event);
    }

    // takagen99 : Use onStopCalled to track close activity
    private boolean onStopCalled;

    @Override
    protected void onResume() {
        super.onResume();
        if (mVideoView != null) {
            mVideoView.resume();
        }
        checkAndUpdateEpgDate();
    }

    private void checkAndUpdateEpgDate() {
        String todayStr = timeFormat.format(new Date());
        if (!todayStr.equals(epgDateInitDay) && epgDateAdapter != null) {
            epgDateAdapter.getData().clear();
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(new Date());
            epgDateInitDay = todayStr;

            for (int i = 0; i < 2; i++) {
                Date dateIns = calendar.getTime();
                LiveEpgDate epgDate = new LiveEpgDate();
                epgDate.setIndex(i);

                if (i == 0) {
                    epgDate.setDatePresented("今天");
                } else {
                    epgDate.setDatePresented("明天");
                }

                epgDate.setDateParamVal(dateIns);
                epgDateAdapter.addData(epgDate);
                calendar.add(Calendar.DAY_OF_MONTH, 1);
            }
            epgDateAdapter.setSelectedIndex(0);
            epgDateAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        onStopCalled = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mVideoView != null) {
            if (supportsPiPMode()) {
                if (isInPictureInPictureMode()) {
                    // Continue playback
                    mVideoView.resume();
                } else {
                    // Pause playback
                    mVideoView.pause();
                }
            } else {
                mVideoView.pause();
            }
        }
    }

    // takagen99 : PIP fix to close video when close window
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        if (supportsPiPMode()) {
            if (!isInPictureInPictureMode()) {
                // Closed playback
                if (onStopCalled) {
                    mVideoView.release();
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        mHandler.removeCallbacksAndMessages(null);
        if (logoPreloadThread != null && logoPreloadThread.isAlive()) {
            logoPreloadThread.interrupt();
        }
        if (epgLoadThread != null && epgLoadThread.isAlive()) {
            epgLoadThread.interrupt();
        }
        if (mVideoView != null) {
            mVideoView.release();
            mVideoView = null;
        }
    }

    private void showChannelList() {
        mBack.setVisibility(View.INVISIBLE);
        if (tvBottomLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelInfoRun);
            mHandler.post(mHideChannelInfoRun);
        } else if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
        } else if (tvLeftChannelListLayout.getVisibility() == View.INVISIBLE & tvRightSettingLayout.getVisibility() == View.INVISIBLE) {
            //重新载入上一次状态
            liveChannelItemAdapter.setNewData(getLiveChannels(currentChannelGroupIndex));
            if (currentLiveChannelIndex > -1)
                mChannelGridView.scrollToPosition(currentLiveChannelIndex);
            mChannelGridView.setSelection(currentLiveChannelIndex);
            mGroupGridView.scrollToPosition(currentChannelGroupIndex);
            mGroupGridView.setSelection(currentChannelGroupIndex);
            mHandler.postDelayed(mFocusCurrentChannelAndShowChannelList, 200);
            mHandler.post(tv_sys_timeRunnable);
        } else {
            mBack.setVisibility(View.INVISIBLE);
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
            mHandler.removeCallbacks(tv_sys_timeRunnable);
        }
    }

    //频道列表
    public void divLoadEpgR(View view) {
        mGroupGridView.setVisibility(View.GONE);
        mEpgInfoGridView.setVisibility(View.VISIBLE);
        mGroupEPG.setVisibility(View.VISIBLE);
        mDivLeft.setVisibility(View.VISIBLE);
        mDivRight.setVisibility(View.GONE);
        tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
        showChannelList();
    }

    public void divLoadEpgL(View view) {
        mGroupGridView.setVisibility(View.VISIBLE);
        mEpgInfoGridView.setVisibility(View.GONE);
        mGroupEPG.setVisibility(View.GONE);
        mDivLeft.setVisibility(View.GONE);
        mDivRight.setVisibility(View.VISIBLE);
        tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
        showChannelList();
    }

    private final Runnable mFocusCurrentChannelAndShowChannelList = new Runnable() {
        @Override
        public void run() {
            if (mGroupGridView.isScrolling() || mChannelGridView.isScrolling() || mGroupGridView.isComputingLayout() || mChannelGridView.isComputingLayout()) {
                mHandler.postDelayed(this, 100);
            } else {
                liveChannelGroupAdapter.setSelectedGroupIndex(currentChannelGroupIndex);
                liveChannelItemAdapter.setSelectedChannelIndex(currentLiveChannelIndex);
                RecyclerView.ViewHolder holder = mChannelGridView.findViewHolderForAdapterPosition(currentLiveChannelIndex);
                if (holder != null)
                    holder.itemView.requestFocus();
                tvLeftChannelListLayout.setVisibility(View.VISIBLE);
                tvLeftChannelListLayout.setAlpha(0.0f);
                tvLeftChannelListLayout.setTranslationX(-tvLeftChannelListLayout.getWidth() / 2);
                tvLeftChannelListLayout.animate()
                        .translationX(0)
                        .alpha(1.0f)
                        .setDuration(250)
                        .setInterpolator(new DecelerateInterpolator())
                        .setListener(null);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
                mHandler.postDelayed(mUpdateLayout, 255);   // Workaround Fix : SurfaceView
            }
        }
    };

    private final Runnable mUpdateLayout = new Runnable() {
        @Override
        public void run() {
            tvLeftChannelListLayout.requestLayout();
            tvRightSettingLayout.requestLayout();
        }
    };

    private final Runnable mHideChannelListRun = new Runnable() {
        @Override
        public void run() {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) tvLeftChannelListLayout.getLayoutParams();
            if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
                tvLeftChannelListLayout.animate()
                        .translationX(-tvLeftChannelListLayout.getWidth() / 2)
                        .alpha(0.0f)
                        .setDuration(250)
                        .setInterpolator(new DecelerateInterpolator())
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                super.onAnimationEnd(animation);
                                tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
                                tvLeftChannelListLayout.clearAnimation();
                            }
                        });
            }
        }
    };

    private void showChannelInfo() {
        // takagen99: Check if Touch Screen, show back button
        if (supportsTouch()) {
            mBack.setVisibility(View.VISIBLE);
        }

        if (tvBottomLayout.getVisibility() == View.GONE || tvBottomLayout.getVisibility() == View.INVISIBLE) {
            tvBottomLayout.setVisibility(View.VISIBLE);
            tvBottomLayout.setTranslationY(tvBottomLayout.getHeight() / 2);
            tvBottomLayout.setAlpha(0.0f);
            tvBottomLayout.animate()
                    .alpha(1.0f)
                    .setDuration(250)
                    .setInterpolator(new DecelerateInterpolator())
                    .translationY(0)
                    .setListener(null);
        }
        mHandler.removeCallbacks(mHideChannelInfoRun);
        mHandler.postDelayed(mHideChannelInfoRun, 6000);
        mHandler.postDelayed(mUpdateLayout, 255);   // Workaround Fix : SurfaceView
    }

    private final Runnable mHideChannelInfoRun = new Runnable() {
        @Override
        public void run() {
            mBack.setVisibility(View.INVISIBLE);
            if (tvBottomLayout.getVisibility() == View.VISIBLE) {
                tvBottomLayout.animate()
                        .alpha(0.0f)
                        .setDuration(250)
                        .setInterpolator(new DecelerateInterpolator())
                        .translationY(tvBottomLayout.getHeight() / 2)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                super.onAnimationEnd(animation);
                                tvBottomLayout.setVisibility(View.INVISIBLE);
                                tvBottomLayout.clearAnimation();
                            }
                        });
            }
        }
    };

    private void toggleChannelInfo() {
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
        } else if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
        } else if (tvBottomLayout.getVisibility() == View.INVISIBLE) {
            showChannelInfo();
        } else {
            mBack.setVisibility(View.INVISIBLE);
            mHandler.removeCallbacks(mHideChannelInfoRun);
            mHandler.post(mHideChannelInfoRun);
            mHandler.post(mUpdateLayout);   // Workaround Fix : SurfaceView
        }
    }

    //显示侧边EPG
    private void showEpg(Date date, ArrayList<Epginfo> arrayList) {
        if (arrayList != null && arrayList.size() > 0) {
            epgdata = arrayList;
            epgListAdapter.CanBack(currentLiveChannelItem.getinclude_back());
            epgListAdapter.setNewData(epgdata);

            Date now = new Date();
            boolean isToday = isSameDay(date, now);
            
            int highlightIndex = -1;
            
            if (isToday) {
                int i = -1;
                int size = epgdata.size() - 1;
                while (size >= 0) {
                    if (now.compareTo(epgdata.get(size).startdateTime) >= 0) {
                        break;
                    }
                    size--;
                }
                i = size;
                
                if (i >= 0 && now.compareTo(epgdata.get(i).enddateTime) <= 0) {
                    highlightIndex = i;
                } else if (i < 0 && epgdata.size() > 0) {
                    highlightIndex = 0;
                }
            } else {
                Calendar nowCal = Calendar.getInstance();
                int nowHour = nowCal.get(Calendar.HOUR_OF_DAY);
                int nowMinute = nowCal.get(Calendar.MINUTE);
                int nowTotalMinutes = nowHour * 60 + nowMinute;
                
                int closestIndex = 0;
                int minDiff = Integer.MAX_VALUE;
                
                for (int idx = 0; idx < epgdata.size(); idx++) {
                    Epginfo epg = epgdata.get(idx);
                    Calendar startCal = Calendar.getInstance();
                    startCal.setTime(epg.startdateTime);
                    int startMinutes = startCal.get(Calendar.HOUR_OF_DAY) * 60 + startCal.get(Calendar.MINUTE);
                    
                    int diff = Math.abs(startMinutes - nowTotalMinutes);
                    if (diff < minDiff) {
                        minDiff = diff;
                        closestIndex = idx;
                    }
                }
                highlightIndex = closestIndex;
            }
            
            if (highlightIndex >= 0) {
                mEpgInfoGridView.setSelectedPosition(highlightIndex);
                mEpgInfoGridView.setSelection(highlightIndex);
                epgListAdapter.setSelectedEpgIndex(highlightIndex);
                int finalI = highlightIndex;
                mEpgInfoGridView.post(new Runnable() {
                    @Override
                    public void run() {
                        mEpgInfoGridView.smoothScrollToPosition(finalI);
                    }
                });
            }
        } else {

            Epginfo epgbcinfo = new Epginfo(date, "暂无节目信息", date, "00:00", "23:59", 0);
            arrayList.add(epgbcinfo);
            epgdata = arrayList;
            epgListAdapter.setNewData(epgdata);

        }
    }
    
    private boolean isSameDay(Date date1, Date date2) {
        Calendar cal1 = Calendar.getInstance();
        Calendar cal2 = Calendar.getInstance();
        cal1.setTime(date1);
        cal2.setTime(date2);
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    private final Runnable tv_sys_timeRunnable = new Runnable() {
        @Override
        public void run() {
            Date date = new Date();
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.ENGLISH);
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA);
            SimpleDateFormat weekFormat = new SimpleDateFormat("EEEE", Locale.CHINA);
            
            tv_sys_time.setText(timeFormat.format(date));
            tv_week.setText(weekFormat.format(date));
            tv_date.setText(dateFormat.format(date));
            
            mHandler.postDelayed(this, 1000);

            // takagen99 : Update SeekBar
            if (mVideoView != null & !mIsDragging) {
                int currentPosition = (int) mVideoView.getCurrentPosition();
                mCurrentTime.setText(stringForTimeVod(currentPosition));
                mSeekBar.setProgress(currentPosition);
            }
        }
    };

    //显示底部EPG
    private void showBottomEpg() {
        if (isSHIYI)
            return;
        if (channel_Name.getChannelName() != null) {
            showChannelInfo();
            int selectedIndex = epgDateAdapter.getSelectedIndex();
            Date selectedDate = selectedIndex < 0 ? new Date() : epgDateAdapter.getData().get(selectedIndex).getDateParamVal();
            
            String[] epgInfo = EpgUtil.getEpgInfo(channel_Name.getChannelName());
            String epgid = (epgInfo != null && epgInfo[1] != null && !epgInfo[1].isEmpty()) ? epgInfo[1] : channel_Name.getChannelName();
            
            String logoUrl = null;
            String fallbackLogoUrl = null;
            String logoTemplate = Hawk.get(HawkConfig.LOGO_URL, "");
            if (!logoTemplate.isEmpty()) {
                fallbackLogoUrl = logoTemplate.replace("{name}", channel_Name.getChannelName()).replace("{epgid}", epgid);
            }
            
            if (epgInfo != null && epgInfo[0] != null && !epgInfo[0].isEmpty()) {
                logoUrl = epgInfo[0];
            } else if (fallbackLogoUrl != null) {
                logoUrl = fallbackLogoUrl;
                fallbackLogoUrl = null;
            }
            getTvLogo(epgid, logoUrl, fallbackLogoUrl);
            
            getEpg(selectedDate);
        }
    }

    private void updateBottomEpgInfo(ArrayList<Epginfo> arrayList) {
        updateBottomEpgInfo(arrayList, null);
    }

    private void updateBottomEpgInfo(ArrayList<Epginfo> arrayList, String channelName) {
        if (arrayList != null && arrayList.size() > 0) {
            Date date = new Date();
            int size = arrayList.size() - 1;
            int currentIndex = -1;
            while (size >= 0) {
                Epginfo epgInfo = arrayList.get(size);
                if (date.after(epgInfo.startdateTime) & date.before(epgInfo.enddateTime)) {
                    currentIndex = size;
                    break;
                } else {
                    size--;
                }
            }
            
            if (currentIndex >= 0) {
                Epginfo epgInfo = arrayList.get(currentIndex);
                tv_curr_time.setText(epgInfo.start + " - " + epgInfo.end);
                tv_curr_name.setText(epgInfo.title);
                if (currentIndex != arrayList.size() - 1) {
                    Epginfo nextEpgInfo = arrayList.get(currentIndex + 1);
                    tv_next_time.setText(nextEpgInfo.start + " - " + nextEpgInfo.end);
                    tv_next_name.setText(nextEpgInfo.title);
                } else {
                    boolean foundNext = findNextEpgFromTomorrow(channelName);
                }
            } else {
                Epginfo lastEpgFromYesterday = getLastEpgFromYesterday(channelName);
                if (lastEpgFromYesterday != null) {
                    tv_curr_time.setText(lastEpgFromYesterday.start + " - " + lastEpgFromYesterday.end);
                    tv_curr_name.setText(lastEpgFromYesterday.title);
                } else {
                    tv_curr_time.setText("--:-- - --:--");
                    tv_curr_name.setText("当前无节目");
                }
                if (arrayList.size() > 0) {
                    Epginfo nextEpgInfo = arrayList.get(0);
                    tv_next_time.setText(nextEpgInfo.start + " - " + nextEpgInfo.end);
                    tv_next_name.setText(nextEpgInfo.title);
                } else {
                    tv_next_time.setText("00:00 - 23:59");
                    tv_next_name.setText("暂无节目信息");
                }
            }
        }
    }

    private boolean findNextEpgFromTomorrow(String channelName) {
        if (channelName != null) {
            String[] epgInfo = EpgUtil.getEpgInfo(channelName);
            String epgid = (epgInfo != null && epgInfo[1] != null && !epgInfo[1].isEmpty()) ? epgInfo[1] : channelName;
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, 1);
            String tomorrowStr = timeFormat.format(cal.getTime());
            ArrayList<Epginfo> tomorrowEpg = getEpgFromXmltvCache(epgid, channelName, tomorrowStr, cal.getTime());
            if (tomorrowEpg != null && tomorrowEpg.size() > 0) {
                Epginfo nextEpgInfo = tomorrowEpg.get(0);
                tv_next_time.setText(nextEpgInfo.start + " - " + nextEpgInfo.end);
                tv_next_name.setText(nextEpgInfo.title);
                return true;
            }
        }
        tv_next_time.setText("00:00 - 23:59");
        tv_next_name.setText("暂无节目信息");
        return false;
    }

    private Epginfo getLastEpgFromYesterday(String channelName) {
        if (channelName != null) {
            String[] epgInfo = EpgUtil.getEpgInfo(channelName);
            String epgid = (epgInfo != null && epgInfo[1] != null && !epgInfo[1].isEmpty()) ? epgInfo[1] : channelName;
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -1);
            String yesterdayStr = timeFormat.format(cal.getTime());
            ArrayList<Epginfo> yesterdayEpg = getEpgFromXmltvCache(epgid, channelName, yesterdayStr, cal.getTime());
            if (yesterdayEpg != null && yesterdayEpg.size() > 0) {
                return yesterdayEpg.get(yesterdayEpg.size() - 1);
            }
        }
        return null;
    }

    // 获取EPG并存储 
    private List<Epginfo> epgdata = new ArrayList<>();

    // Get Channel Logo
    private java.io.File getLogoCacheDir() {
        java.io.File cacheDir = new java.io.File(App.getInstance().getFilesDir(), "logo_cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        return cacheDir;
    }
    
    private java.io.File getLogoCacheFile(String epgid) {
        String safeName = epgid.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_");
        return new java.io.File(getLogoCacheDir(), safeName + ".png");
    }
    
    private void getTvLogo(String epgid, String logoUrl, String fallbackLogoUrl) {
        android.graphics.drawable.Drawable cachedDrawable = channelLogoCache.get(epgid);
        Long cacheTime = channelLogoCacheTime.get(epgid);
        boolean cacheValid = cacheTime != null && (System.currentTimeMillis() - cacheTime) < LOGO_CACHE_EXPIRE_MS;
        
        if (cachedDrawable != null && cacheValid) {
            tv_logo.setImageDrawable(cachedDrawable);
            return;
        }
        
        java.io.File cacheFile = getLogoCacheFile(epgid);
        if (cacheFile.exists()) {
            long fileTime = cacheFile.lastModified();
            if (System.currentTimeMillis() - fileTime < LOGO_CACHE_EXPIRE_MS) {
                RequestOptions options = new RequestOptions();
                options.diskCacheStrategy(DiskCacheStrategy.ALL)
                        .skipMemoryCache(false)
                        .placeholder(R.drawable.img_logo_placeholder)
                        .error(R.drawable.img_logo_placeholder);
                Glide.with(App.getInstance())
                        .load(cacheFile)
                        .apply(options)
                        .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                            @Override
                            public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                                cacheFile.delete();
                                return false;
                            }
                            
                            @Override
                            public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                channelLogoCache.put(epgid, resource);
                                channelLogoCacheTime.put(epgid, cacheFile.lastModified());
                                return false;
                            }
                        })
                        .into(tv_logo);
                return;
            }
        }
        
        if (isLogoFailed(epgid)) {
            tv_logo.setImageResource(R.drawable.img_logo_placeholder);
            return;
        }
        
        String primaryUrl = (logoUrl != null && !logoUrl.isEmpty()) ? logoUrl : null;
        String secondaryUrl = (fallbackLogoUrl != null && !fallbackLogoUrl.isEmpty()) ? fallbackLogoUrl : null;
        
        String finalUrl;
        String finalFallbackUrl;
        
        if (primaryUrl != null) {
            finalUrl = primaryUrl;
            finalFallbackUrl = secondaryUrl;
        } else if (secondaryUrl != null) {
            finalUrl = secondaryUrl;
            finalFallbackUrl = null;
        } else {
            tv_logo.setImageResource(R.drawable.img_logo_placeholder);
            return;
        }
        
        final String urlToLoad = finalUrl;
        final String fallbackToLoad = finalFallbackUrl;
        final String finalEpgid = epgid;
        
        RequestOptions options = new RequestOptions();
        options.diskCacheStrategy(DiskCacheStrategy.ALL)
                .skipMemoryCache(false)
                .placeholder(R.drawable.img_logo_placeholder)
                .error(R.drawable.img_logo_placeholder);
        Glide.with(App.getInstance())
                .load(urlToLoad)
                .apply(options)
                .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                    @Override
                    public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                        if (fallbackToLoad != null) {
                            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                            handler.post(() -> {
                                RequestOptions fallbackOptions = new RequestOptions();
                                fallbackOptions.diskCacheStrategy(DiskCacheStrategy.ALL)
                                        .skipMemoryCache(false)
                                        .placeholder(R.drawable.img_logo_placeholder)
                                        .error(R.drawable.img_logo_placeholder);
                                Glide.with(App.getInstance())
                                        .load(fallbackToLoad)
                                        .apply(fallbackOptions)
                                        .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                                            @Override
                                            public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e2, Object model2, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target2, boolean isFirstResource2) {
                                                markLogoFailed(finalEpgid);
                                                return false;
                                            }
                                            
                                            @Override
                                            public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                                                saveLogoToFile(finalEpgid, resource);
                                                return false;
                                            }
                                        })
                                        .into(tv_logo);
                            });
                        } else {
                            markLogoFailed(finalEpgid);
                        }
                        return false;
                    }
                    
                    @Override
                    public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                        saveLogoToFile(finalEpgid, resource);
                        return false;
                    }
                })
                .into(tv_logo);
    }
    
    private void saveLogoToFile(String epgid, android.graphics.drawable.Drawable drawable) {
        channelLogoCache.put(epgid, drawable);
        channelLogoCacheTime.put(epgid, System.currentTimeMillis());
        
        try {
            java.io.File cacheFile = getLogoCacheFile(epgid);
            android.graphics.Bitmap bitmap;
            if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                bitmap = ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
            } else {
                bitmap = android.graphics.Bitmap.createBitmap(
                        drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 100,
                        drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 100,
                        android.graphics.Bitmap.Config.ARGB_8888
                );
                android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(cacheFile);
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();
        } catch (Exception e) {
            // ignore
        }
    }
    
    private boolean isLogoFailed(String epgid) {
        return logoFailedCache.contains(epgid);
    }
    
    private void markLogoFailed(String epgid) {
        logoFailedCache.add(epgid);
    }
    
    private static class LogoPreloadRunnable implements Runnable {
        private final WeakReference<LivePlayActivity> activityRef;
        private final List<LiveChannelGroup> channelGroups;
        
        LogoPreloadRunnable(LivePlayActivity activity, List<LiveChannelGroup> groups) {
            this.activityRef = new WeakReference<>(activity);
            this.channelGroups = new ArrayList<>(groups);
        }
        
        @Override
        public void run() {
            LivePlayActivity activity = activityRef.get();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            
            String logoTemplate = Hawk.get(HawkConfig.LOGO_URL, "");
            for (LiveChannelGroup group : channelGroups) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                for (LiveChannelItem item : group.getLiveChannels()) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    
                    LivePlayActivity act = activityRef.get();
                    if (act == null || act.isFinishing()) {
                        return;
                    }
                    
                    String channelName = item.getChannelName();
                    
                    String[] epgInfo = EpgUtil.getEpgInfo(channelName);
                    String epgid = (epgInfo != null && epgInfo[1] != null && !epgInfo[1].isEmpty()) ? epgInfo[1] : channelName;
                    
                    if (act.isLogoFailed(epgid)) {
                        continue;
                    }
                    
                    java.io.File cacheFile = act.getLogoCacheFile(epgid);
                    if (cacheFile.exists()) {
                        long fileTime = cacheFile.lastModified();
                        if (System.currentTimeMillis() - fileTime < LOGO_CACHE_EXPIRE_MS) {
                            continue;
                        }
                    }
                    
                    String logoUrl = null;
                    String fallbackLogoUrl = null;
                    if (!logoTemplate.isEmpty()) {
                        fallbackLogoUrl = logoTemplate.replace("{name}", channelName).replace("{epgid}", epgid);
                    }
                    
                    if (epgInfo != null && epgInfo[0] != null && !epgInfo[0].isEmpty()) {
                        logoUrl = epgInfo[0];
                    } else if (fallbackLogoUrl != null) {
                        logoUrl = fallbackLogoUrl;
                        fallbackLogoUrl = null;
                    }
                    
                    if (logoUrl == null || logoUrl.isEmpty()) {
                        continue;
                    }
                    
                    final String finalLogoUrl = logoUrl;
                    final String finalFallbackUrl = fallbackLogoUrl;
                    final String finalEpgid = epgid;
                    
                    boolean success = false;
                    try {
                        android.graphics.drawable.Drawable drawable = Glide.with(App.getInstance())
                                .load(finalLogoUrl)
                                .submit()
                                .get();
                        
                        if (drawable != null) {
                            act.saveLogoToFile(finalEpgid, drawable);
                            success = true;
                        }
                    } catch (Exception e) {
                        if (finalFallbackUrl != null) {
                            try {
                                android.graphics.drawable.Drawable drawable = Glide.with(App.getInstance())
                                        .load(finalFallbackUrl)
                                        .submit()
                                        .get();
                                if (drawable != null) {
                                    act.saveLogoToFile(finalEpgid, drawable);
                                    success = true;
                                }
                            } catch (Exception e2) {
                            }
                        }
                    }
                    
                    if (!success) {
                        act.markLogoFailed(finalEpgid);
                    }
                    
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
    }
    
    private void preloadAllChannelLogos() {
        if (logoPreloadThread != null && logoPreloadThread.isAlive()) {
            logoPreloadThread.interrupt();
        }
        logoPreloadThread = new Thread(new LogoPreloadRunnable(this, liveChannelGroupList));
        logoPreloadThread.start();
    }

    public void getEpg(Date date) {
        getEpg(date, true);
    }

    public void getEpg(Date date, final boolean updateBottomEpg) {
        String channelName = channel_Name.getChannelName();
        String dateStr = timeFormat.format(date);
        
        String[] epgInfo = EpgUtil.getEpgInfo(channelName);
        String epgTagName = channelName;
        String tvid = EpgUtil.getTvid(channelName);
        String epgid = (epgInfo != null && epgInfo[1] != null && !epgInfo[1].isEmpty()) ? epgInfo[1] : channelName;
        
        String logoUrl = null;
        String fallbackLogoUrl = null;
        String logoTemplate = Hawk.get(HawkConfig.LOGO_URL, "");
        if (!logoTemplate.isEmpty()) {
            fallbackLogoUrl = logoTemplate.replace("{name}", channelName).replace("{epgid}", epgid);
        }
        
        if (epgInfo != null && epgInfo[0] != null && !epgInfo[0].isEmpty()) {
            logoUrl = epgInfo[0];
        } else if (fallbackLogoUrl != null) {
            logoUrl = fallbackLogoUrl;
            fallbackLogoUrl = null;
        }
        getTvLogo(epgid, logoUrl, fallbackLogoUrl);
        
        if (epgInfo != null && !epgInfo[1].isEmpty()) {
            epgTagName = epgInfo[1];
        }
        epgListAdapter.CanBack(currentLiveChannelItem.getinclude_back());

        String epgUrl;
        String cleanedEpgAddress = epgStringAddress.replaceAll("^`|`$", "");
        epgUrl = cleanedEpgAddress;
        final String finalEpgTagName = epgTagName;
        final String finalChannelName = channelName;
        final String finalEpgid = epgid;
        final String requestDateStr = dateStr;
        
        String lowerEpgUrl = epgUrl.toLowerCase();
        boolean isFullEpgFile = lowerEpgUrl.endsWith(".gz") || lowerEpgUrl.endsWith(".xml") || lowerEpgUrl.endsWith(".json");
        
        if (isFullEpgFile && !xmltvCacheLoaded) {
            loadXmltvCacheFromFile();
            xmltvCacheLoaded = true;
        }
        
        if (isFullEpgFile && !epgUrl.equals(xmltvSourceUrl)) {
            xmltvSourceUrl = "";
            xmltvCacheTime = 0;
            xmltvChannelMap = null;
            xmltvProgrammesMap = null;
        }
        
        if (isFullEpgFile && xmltvChannelMap != null && xmltvProgrammesMap != null) {
            boolean cacheValid = (System.currentTimeMillis() - xmltvCacheTime) < XMLTV_CACHE_EXPIRE_MS;
            
            if (!cacheValid) {
                Calendar cal = Calendar.getInstance();
                int hour = cal.get(Calendar.HOUR_OF_DAY);
                if (hour >= 23 || hour < 5) {
                    String yesterdayStr = timeFormat.format(new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000));
                    boolean hasYesterdayData = false;
                    if (xmltvProgrammesMap != null) {
                        for (ArrayList<HashMap<String, String>> programmes : xmltvProgrammesMap.values()) {
                            for (HashMap<String, String> prog : programmes) {
                                String start = prog.get("start");
                                if (start != null && start.length() >= 8) {
                                    String progDate = start.substring(0, 4) + "-" + start.substring(4, 6) + "-" + start.substring(6, 8);
                                    if (progDate.equals(yesterdayStr)) {
                                        hasYesterdayData = true;
                                        break;
                                    }
                                }
                            }
                            if (hasYesterdayData) break;
                        }
                    }
                    if (hasYesterdayData) {
                        cacheValid = true;
                    }
                }
            }
            
            if (cacheValid) {
                ArrayList<Epginfo> result = getEpgFromXmltvCache(finalEpgTagName, finalChannelName, requestDateStr, date);
                showEpg(date, result);
                if (updateBottomEpg) {
                    updateBottomEpgInfo(result, channelName);
                }
                return;
            }
        }
        
        if (isFullEpgFile) {
            final String finalEpgUrl = epgUrl;
            if (epgLoadThread != null && epgLoadThread.isAlive()) {
                epgLoadThread.interrupt();
            }
            epgLoadThread = new Thread(new EpgLoadRunnable(this, finalEpgUrl, finalEpgTagName, finalChannelName, date, updateBottomEpg));
            epgLoadThread.start();
        }
    }
    
    private static class EpgLoadRunnable implements Runnable {
        private final WeakReference<LivePlayActivity> activityRef;
        private final String epgUrl;
        private final String epgTagName;
        private final String channelName;
        private final Date date;
        private final boolean updateBottomEpg;
        
        EpgLoadRunnable(LivePlayActivity activity, String url, String tag, String name, Date d, boolean update) {
            this.activityRef = new WeakReference<>(activity);
            this.epgUrl = url;
            this.epgTagName = tag;
            this.channelName = name;
            this.date = d;
            this.updateBottomEpg = update;
        }
        
        @Override
        public void run() {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            
            LivePlayActivity activity = activityRef.get();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            
            try {
                String paramString;
                if (epgUrl.toLowerCase().endsWith(".gz")) {
                    java.net.URL url = new java.net.URL(epgUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    conn.connect();
                    
                    java.io.InputStream is = conn.getInputStream();
                    GZIPInputStream gzis = new GZIPInputStream(is);
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(gzis, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    br.close();
                    gzis.close();
                    is.close();
                    conn.disconnect();
                    paramString = sb.toString();
                } else {
                    java.net.URL url = new java.net.URL(epgUrl);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    br.close();
                    conn.disconnect();
                    paramString = sb.toString();
                }
                
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                
                LivePlayActivity act = activityRef.get();
                if (act == null || act.isFinishing()) {
                    return;
                }
                
                act.parseAndCacheEpgData(paramString, epgUrl);
                ArrayList<Epginfo> result = act.getEpgFromXmltvCache(epgTagName, channelName, 
                    new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date), date);
                
                final ArrayList<Epginfo> finalResult = result;
                final Date finalDate = date;
                final boolean finalUpdate = updateBottomEpg;
                final String finalName = channelName;
                
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        LivePlayActivity a = activityRef.get();
                        if (a != null && !a.isFinishing()) {
                            a.showEpg(finalDate, finalResult);
                            if (finalUpdate) {
                                a.updateBottomEpgInfo(finalResult, finalName);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                LOG.e(e);
                LivePlayActivity act = activityRef.get();
                if (act != null && !act.isFinishing()) {
                    final Date finalDate = date;
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            LivePlayActivity a = activityRef.get();
                            if (a != null && !a.isFinishing()) {
                                a.showEpg(finalDate, new ArrayList<>());
                            }
                        }
                    });
                }
            }
        }
    }

    private void parseEpgData(final String paramString, final Date date, final String epgTagName, 
                              final String channelName, final String epgid, final String requestDateStr,
                              final boolean updateBottomEpg) {
        ArrayList<Epginfo> arrayList = new ArrayList<>();
        
        try {
            String trimmedParam = paramString.trim();
            if (trimmedParam.startsWith("{") || trimmedParam.startsWith("[")) {
                JSONArray jSONArray = null;
                
                if (trimmedParam.startsWith("[")) {
                    jSONArray = new JSONArray(trimmedParam);
                } else {
                    JSONObject jsonObj = new JSONObject(trimmedParam);
                    String[] arrayFields = {"epg_data", "data", "programs", "list", "items", "epg"};
                    for (String field : arrayFields) {
                        if (jsonObj.has(field)) {
                            jSONArray = jsonObj.optJSONArray(field);
                            break;
                        }
                    }
                    if (jSONArray == null) {
                        for (Iterator<String> keys = jsonObj.keys(); keys.hasNext(); ) {
                            String key = keys.next();
                            Object val = jsonObj.get(key);
                            if (val instanceof JSONArray) {
                                jSONArray = (JSONArray) val;
                                break;
                            }
                        }
                    }
                }
                
                if (jSONArray != null) {
                    for (int b = 0; b < jSONArray.length(); b++) {
                        JSONObject jSONObject = jSONArray.getJSONObject(b);
                        String title = jSONObject.optString("title", jSONObject.optString("name", ""));
                        String start = jSONObject.optString("start", jSONObject.optString("startTime", ""));
                        String end = jSONObject.optString("end", jSONObject.optString("stop", jSONObject.optString("endTime", "")));
                        if (!title.isEmpty() && !start.isEmpty()) {
                            Epginfo epgbcinfo = new Epginfo(date, title, date, start, end, b);
                            arrayList.add(epgbcinfo);
                        }
                    }
                }
            } else if (trimmedParam.contains("<tv") || trimmedParam.contains("<programme>")) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                InputSource is = new InputSource(new StringReader(paramString));
                Document doc = builder.parse(is);
                
                HashMap<String, String> channelIdMap = new HashMap<>();
                NodeList channels = doc.getElementsByTagName("channel");
                for (int j = 0; j < channels.getLength(); j++) {
                    Element channel = (Element) channels.item(j);
                    String id = channel.getAttribute("id");
                    NodeList displayNames = channel.getElementsByTagName("display-name");
                    if (displayNames.getLength() > 0) {
                        String displayName = displayNames.item(0).getTextContent();
                        channelIdMap.put(id, displayName);
                    }
                }
                
                String matchedChannelId = null;
                for (Map.Entry<String, String> entry : channelIdMap.entrySet()) {
                    String displayName = entry.getValue();
                    String normalizedDisplayName = displayName.replaceAll("[-_\\s]", "");
                    String normalizedChannelName = channelName.replaceAll("[-_\\s]", "");
                    String normalizedEpgTagName = epgTagName.replaceAll("[-_\\s]", "");
                    if (normalizedDisplayName.equals(normalizedChannelName) || 
                        normalizedDisplayName.equals(normalizedEpgTagName)) {
                        matchedChannelId = entry.getKey();
                        break;
                    }
                }
                
                NodeList programmes = doc.getElementsByTagName("programme");
                for (int i = 0; i < programmes.getLength(); i++) {
                    Element programme = (Element) programmes.item(i);
                    String channelId = programme.getAttribute("channel");
                    
                    boolean shouldInclude = false;
                    if (matchedChannelId != null) {
                        shouldInclude = channelId.equals(matchedChannelId);
                    } else {
                        shouldInclude = channelId.equals(epgTagName);
                    }
                    
                    if (shouldInclude) {
                        String start = programme.getAttribute("start");
                        String stop = programme.getAttribute("stop");
                        String programmeStartDateStr = "";
                        String programmeEndDateStr = "";
                        if (start.length() >= 8) {
                            programmeStartDateStr = start.substring(0, 4) + "-" + start.substring(4, 6) + "-" + start.substring(6, 8);
                        }
                        if (stop.length() >= 8) {
                            programmeEndDateStr = stop.substring(0, 4) + "-" + stop.substring(4, 6) + "-" + stop.substring(6, 8);
                        }
                        if (!programmeStartDateStr.equals(requestDateStr) && !programmeEndDateStr.equals(requestDateStr)) {
                            continue;
                        }
                        
                        NodeList titles = programme.getElementsByTagName("title");
                        String title = "";
                        if (titles.getLength() > 0) {
                            title = titles.item(0).getTextContent();
                        }
                        String startTime = "";
                        String endTime = "";
                        if (start.length() >= 14) {
                            startTime = start.substring(8, 10) + ":" + start.substring(10, 12);
                        }
                        if (stop.length() >= 14) {
                            endTime = stop.substring(8, 10) + ":" + stop.substring(10, 12);
                        }
                        Date programmeDate = date;
                        if (!programmeStartDateStr.equals(requestDateStr) && programmeEndDateStr.equals(requestDateStr)) {
                            try {
                                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                                programmeDate = sdf.parse(programmeStartDateStr);
                            } catch (Exception e) {
                                programmeDate = date;
                            }
                        }
                        Epginfo epgbcinfo = new Epginfo(date, title, programmeDate, startTime, endTime, i);
                        arrayList.add(epgbcinfo);
                    }
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
        
        final ArrayList<Epginfo> finalArrayList = arrayList;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showEpg(date, finalArrayList);
                
                if (updateBottomEpg) {
                    updateBottomEpgInfo(finalArrayList, channelName);
                }
            }
        });
    }

    private void parseAndCacheEpgData(String paramString, String sourceUrl) {
        try {
            String trimmedParam = paramString.trim();
            
            HashMap<String, String> channelIdMap = new HashMap<>();
            HashMap<String, ArrayList<HashMap<String, String>>> programmesMap = new HashMap<>();
            
            if (trimmedParam.startsWith("{") || trimmedParam.startsWith("[")) {
                JSONArray jSONArray = null;
                
                if (trimmedParam.startsWith("[")) {
                    jSONArray = new JSONArray(trimmedParam);
                } else {
                    JSONObject jsonObj = new JSONObject(trimmedParam);
                    String[] arrayFields = {"epg_data", "data", "programs", "list", "items", "epg"};
                    for (String field : arrayFields) {
                        if (jsonObj.has(field)) {
                            jSONArray = jsonObj.optJSONArray(field);
                            break;
                        }
                    }
                    if (jSONArray == null) {
                        for (Iterator<String> keys = jsonObj.keys(); keys.hasNext(); ) {
                            String key = keys.next();
                            Object val = jsonObj.get(key);
                            if (val instanceof JSONArray) {
                                jSONArray = (JSONArray) val;
                                break;
                            }
                        }
                    }
                }
                
                if (jSONArray != null) {
                    for (int b = 0; b < jSONArray.length(); b++) {
                        JSONObject jSONObject = jSONArray.getJSONObject(b);
                        String title = jSONObject.optString("title", jSONObject.optString("name", ""));
                        String start = jSONObject.optString("start", jSONObject.optString("startTime", ""));
                        String stop = jSONObject.optString("end", jSONObject.optString("stop", jSONObject.optString("endTime", "")));
                        String channel = jSONObject.optString("channel", jSONObject.optString("ch", jSONObject.optString("id", "")));
                        
                        if (!channel.isEmpty()) {
                            if (!channelIdMap.containsKey(channel)) {
                                channelIdMap.put(channel, channel);
                            }
                            
                            HashMap<String, String> progInfo = new HashMap<>();
                            progInfo.put("start", start);
                            progInfo.put("stop", stop);
                            progInfo.put("title", title);
                            
                            if (!programmesMap.containsKey(channel)) {
                                programmesMap.put(channel, new ArrayList<HashMap<String, String>>());
                            }
                            programmesMap.get(channel).add(progInfo);
                        }
                    }
                }
            } else if (trimmedParam.contains("<tv") || trimmedParam.contains("<programme>")) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                DocumentBuilder builder = factory.newDocumentBuilder();
                InputSource is = new InputSource(new StringReader(paramString));
                Document doc = builder.parse(is);
                
                NodeList channels = doc.getElementsByTagName("channel");
                for (int j = 0; j < channels.getLength(); j++) {
                    Element channel = (Element) channels.item(j);
                    String id = channel.getAttribute("id");
                    NodeList displayNames = channel.getElementsByTagName("display-name");
                    if (displayNames.getLength() > 0) {
                        String displayName = displayNames.item(0).getTextContent();
                        channelIdMap.put(id, displayName);
                    }
                }
                
                NodeList programmes = doc.getElementsByTagName("programme");
                for (int i = 0; i < programmes.getLength(); i++) {
                    Element programme = (Element) programmes.item(i);
                    String channelId = programme.getAttribute("channel");
                    String start = programme.getAttribute("start");
                    String stop = programme.getAttribute("stop");
                    
                    NodeList titles = programme.getElementsByTagName("title");
                    String title = "";
                    if (titles.getLength() > 0) {
                        title = titles.item(0).getTextContent();
                    }
                    
                    HashMap<String, String> progInfo = new HashMap<>();
                    progInfo.put("start", start);
                    progInfo.put("stop", stop);
                    progInfo.put("title", title);
                    
                    if (!programmesMap.containsKey(channelId)) {
                        programmesMap.put(channelId, new ArrayList<HashMap<String, String>>());
                    }
                    programmesMap.get(channelId).add(progInfo);
                }
            }
            
            if (channelIdMap.size() > 0 && programmesMap.size() > 0) {
                xmltvSourceUrl = sourceUrl;
                xmltvCacheTime = System.currentTimeMillis();
                xmltvChannelMap = channelIdMap;
                xmltvProgrammesMap = programmesMap;
                saveXmltvCacheToFile();
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    private void saveXmltvCacheToFile() {
        try {
            JSONObject cacheObj = new JSONObject();
            cacheObj.put("sourceUrl", xmltvSourceUrl);
            cacheObj.put("cacheTime", xmltvCacheTime);
            
            JSONObject channelsObj = new JSONObject();
            for (Map.Entry<String, String> entry : xmltvChannelMap.entrySet()) {
                channelsObj.put(entry.getKey(), entry.getValue());
            }
            cacheObj.put("channels", channelsObj);
            
            JSONObject programmesObj = new JSONObject();
            for (Map.Entry<String, ArrayList<HashMap<String, String>>> entry : xmltvProgrammesMap.entrySet()) {
                JSONArray progArray = new JSONArray();
                for (HashMap<String, String> prog : entry.getValue()) {
                    JSONObject progObj = new JSONObject();
                    progObj.put("start", prog.get("start"));
                    progObj.put("stop", prog.get("stop"));
                    progObj.put("title", prog.get("title"));
                    progArray.put(progObj);
                }
                programmesObj.put(entry.getKey(), progArray);
            }
            cacheObj.put("programmes", programmesObj);
            
            java.io.File cacheFile = new java.io.File(getFilesDir(), XMLTV_CACHE_FILE);
            java.io.FileWriter writer = new java.io.FileWriter(cacheFile);
            writer.write(cacheObj.toString());
            writer.close();
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    private void loadXmltvCacheFromFile() {
        try {
            java.io.File cacheFile = new java.io.File(getFilesDir(), XMLTV_CACHE_FILE);
            if (!cacheFile.exists()) {
                return;
            }
            
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(cacheFile));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            
            JSONObject cacheObj = new JSONObject(sb.toString());
            xmltvSourceUrl = cacheObj.optString("sourceUrl", "");
            xmltvCacheTime = cacheObj.optLong("cacheTime", 0);
            
            JSONObject channelsObj = cacheObj.optJSONObject("channels");
            if (channelsObj != null) {
                xmltvChannelMap = new HashMap<>();
                Iterator<String> keys = channelsObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    xmltvChannelMap.put(key, channelsObj.getString(key));
                }
            }
            
            JSONObject programmesObj = cacheObj.optJSONObject("programmes");
            if (programmesObj != null) {
                xmltvProgrammesMap = new HashMap<>();
                Iterator<String> progKeys = programmesObj.keys();
                while (progKeys.hasNext()) {
                    String channelId = progKeys.next();
                    JSONArray progArray = programmesObj.getJSONArray(channelId);
                    ArrayList<HashMap<String, String>> progList = new ArrayList<>();
                    for (int i = 0; i < progArray.length(); i++) {
                        JSONObject progObj = progArray.getJSONObject(i);
                        HashMap<String, String> prog = new HashMap<>();
                        prog.put("start", progObj.optString("start", ""));
                        prog.put("stop", progObj.optString("stop", ""));
                        prog.put("title", progObj.optString("title", ""));
                        progList.add(prog);
                    }
                    xmltvProgrammesMap.put(channelId, progList);
                }
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    private ArrayList<Epginfo> getEpgFromXmltvCache(String epgTagName, String channelName, String requestDateStr, Date date) {
        ArrayList<Epginfo> arrayList = new ArrayList<>();
        
        if (xmltvChannelMap == null || xmltvProgrammesMap == null) {
            return arrayList;
        }
        
        String matchedChannelId = null;
        for (Map.Entry<String, String> entry : xmltvChannelMap.entrySet()) {
            String displayName = entry.getValue();
            String normalizedDisplayName = displayName.replaceAll("[-_\\s]", "");
            String normalizedChannelName = channelName.replaceAll("[-_\\s]", "");
            String normalizedEpgTagName = epgTagName.replaceAll("[-_\\s]", "");
            if (normalizedDisplayName.equals(normalizedChannelName) || 
                normalizedDisplayName.equals(normalizedEpgTagName)) {
                matchedChannelId = entry.getKey();
                break;
            }
        }
        
        if (matchedChannelId == null) {
            return arrayList;
        }
        
        ArrayList<HashMap<String, String>> programmes = xmltvProgrammesMap.get(matchedChannelId);
        if (programmes == null) {
            return arrayList;
        }
        
        int index = 0;
        for (HashMap<String, String> progInfo : programmes) {
            String start = progInfo.get("start");
            String stop = progInfo.get("stop");
            String title = progInfo.get("title");
            
            String programmeStartDateStr = "";
            String programmeEndDateStr = "";
            if (start.length() >= 8) {
                programmeStartDateStr = start.substring(0, 4) + "-" + start.substring(4, 6) + "-" + start.substring(6, 8);
            }
            if (stop.length() >= 8) {
                programmeEndDateStr = stop.substring(0, 4) + "-" + stop.substring(4, 6) + "-" + stop.substring(6, 8);
            }
            
            if (!programmeStartDateStr.equals(requestDateStr) && !programmeEndDateStr.equals(requestDateStr)) {
                continue;
            }
            
            String startTime = "";
            String endTime = "";
            if (start.length() >= 14) {
                startTime = start.substring(8, 10) + ":" + start.substring(10, 12);
            }
            if (stop.length() >= 14) {
                endTime = stop.substring(8, 10) + ":" + stop.substring(10, 12);
            }
            
            Date programmeDate = date;
            if (!programmeStartDateStr.equals(requestDateStr) && programmeEndDateStr.equals(requestDateStr)) {
                try {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                    programmeDate = sdf.parse(programmeStartDateStr);
                } catch (Exception e) {
                    programmeDate = date;
                }
            }
            
            Epginfo epgbcinfo = new Epginfo(date, title, programmeDate, startTime, endTime, index);
            arrayList.add(epgbcinfo);
            index++;
        }
        
        return arrayList;
    }

    private boolean replayChannel() {
        if (mVideoView == null) return true;
        mVideoView.release();
        currentLiveChannelItem = getLiveChannels(currentChannelGroupIndex).get(currentLiveChannelIndex);
        Hawk.put(HawkConfig.LIVE_CHANNEL, currentLiveChannelItem.getChannelName());
        HawkUtils.setLastLiveChannelGroup(liveChannelGroupList.get(currentChannelGroupIndex).getGroupName());
        livePlayerManager.getLiveChannelPlayer(mVideoView, currentLiveChannelItem.getChannelName());
        channel_Name = currentLiveChannelItem;
        currentLiveChannelItem.setinclude_back(currentLiveChannelItem.getUrl().indexOf("PLTV/8888") != -1);
        mHandler.post(tv_sys_timeRunnable);
        tv_channelname.setText(channel_Name.getChannelName());
        tv_channelnum.setText("" + channel_Name.getChannelNum());
        if (channel_Name == null || channel_Name.getSourceNum() <= 0) {
            tv_source.setText("1/1");
        } else {
            tv_source.setText("线路 " + (channel_Name.getSourceIndex() + 1) + "/" + channel_Name.getSourceNum());
        }

        getEpg(new Date());
        mVideoView.setUrl(currentLiveChannelItem.getUrl(), setPlayHeaders(currentLiveChannelItem.getUrl()));
        showChannelInfo();
        mVideoView.start();
        return true;
    }

    //节目播放
    private boolean playChannel(int channelGroupIndex, int liveChannelIndex, boolean changeSource) {
        if ((channelGroupIndex == currentChannelGroupIndex && liveChannelIndex == currentLiveChannelIndex && !changeSource)
                || (changeSource && currentLiveChannelItem.getSourceNum() == 1)) {
            showChannelInfo();
            return true;
        }
        if (mVideoView == null) return true;
        mVideoView.release();
        if (!changeSource) {
            currentChannelGroupIndex = channelGroupIndex;
            currentLiveChannelIndex = liveChannelIndex;
            currentLiveChannelItem = getLiveChannels(currentChannelGroupIndex).get(currentLiveChannelIndex);
            Hawk.put(HawkConfig.LIVE_CHANNEL, currentLiveChannelItem.getChannelName());
            HawkUtils.setLastLiveChannelGroup(liveChannelGroupList.get(currentChannelGroupIndex).getGroupName());
            livePlayerManager.getLiveChannelPlayer(mVideoView, currentLiveChannelItem.getChannelName());
        }
        channel_Name = currentLiveChannelItem;
        currentLiveChannelItem.setinclude_back(currentLiveChannelItem.getUrl().indexOf("PLTV/8888") != -1);

        // takagen99 : Moved update of Channel Info here before getting EPG (no dependency on EPG)
        mHandler.post(tv_sys_timeRunnable);

        // Channel Name & No. + Source No.
        tv_channelname.setText(channel_Name.getChannelName());
        tv_channelnum.setText("" + channel_Name.getChannelNum());
        if (channel_Name == null || channel_Name.getSourceNum() <= 0) {
            tv_source.setText("1/1");
        } else {
            tv_source.setText("线路 " + (channel_Name.getSourceIndex() + 1) + "/" + channel_Name.getSourceNum());
        }

        getEpg(new Date());
        mVideoView.setUrl(currentLiveChannelItem.getUrl(), setPlayHeaders(currentLiveChannelItem.getUrl()));
        showChannelInfo();
        mVideoView.start();
        return true;
    }

    private void playNext() {
        if (!isCurrentLiveChannelValid()) return;
        Integer[] groupChannelIndex = getNextChannel(1);
        playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
    }

    private void playPrevious() {
        if (!isCurrentLiveChannelValid()) return;
        Integer[] groupChannelIndex = getNextChannel(-1);
        playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
    }

    public void playPreSource() {
        if (!isCurrentLiveChannelValid()) return;
        currentLiveChannelItem.preSource();
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
    }

    public void playNextSource() {
        if (mVideoView == null) {
            return;
        }
        if (!isCurrentLiveChannelValid()) return;
        currentLiveChannelItem.nextSource();
        playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
    }

    //显示设置列表
    private void showSettingGroup() {
        mBack.setVisibility(View.INVISIBLE);
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
        } else if (tvBottomLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelInfoRun);
            mHandler.post(mHideChannelInfoRun);
        } else if (tvRightSettingLayout.getVisibility() == View.INVISIBLE) {
            if (!isCurrentLiveChannelValid()) return;
            //重新载入默认状态
            loadCurrentSourceList();
            liveSettingGroupAdapter.setNewData(liveSettingGroupList);
            selectSettingGroup(0, false);
            mSettingGroupView.scrollToPosition(0);
            mSettingItemView.scrollToPosition(currentLiveChannelItem.getSourceIndex());
            mHandler.postDelayed(mFocusAndShowSettingGroup, 200);
        } else {
            mBack.setVisibility(View.INVISIBLE);
            mHandler.removeCallbacks(mHideSettingLayoutRun);
            mHandler.post(mHideSettingLayoutRun);
        }
    }

    private final Runnable mFocusAndShowSettingGroup = new Runnable() {
        @Override
        public void run() {
            if (mSettingGroupView.isScrolling() || mSettingItemView.isScrolling() || mSettingGroupView.isComputingLayout() || mSettingItemView.isComputingLayout()) {
                mHandler.postDelayed(this, 100);
            } else {
                RecyclerView.ViewHolder holder = mSettingGroupView.findViewHolderForAdapterPosition(0);
                if (holder != null)
                    holder.itemView.requestFocus();
                tvRightSettingLayout.setVisibility(View.VISIBLE);
                tvRightSettingLayout.setAlpha(0.0f);
                tvRightSettingLayout.setTranslationX(tvRightSettingLayout.getWidth() / 2);
                tvRightSettingLayout.animate()
                        .translationX(0)
                        .alpha(1.0f)
                        .setDuration(250)
                        .setInterpolator(new DecelerateInterpolator())
                        .setListener(null);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, 6000);
                mHandler.postDelayed(mUpdateLayout, 255);   // Workaround Fix : SurfaceView
            }
        }
    };

    private final Runnable mHideSettingLayoutRun = new Runnable() {
        @Override
        public void run() {
            if (tvRightSettingLayout.getVisibility() == View.VISIBLE) {
                tvRightSettingLayout.animate()
                        .translationX(tvRightSettingLayout.getWidth() / 2)
                        .alpha(0.0f)
                        .setDuration(250)
                        .setInterpolator(new DecelerateInterpolator())
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                super.onAnimationEnd(animation);
                                tvRightSettingLayout.setVisibility(View.INVISIBLE);
                                tvRightSettingLayout.clearAnimation();
                                liveSettingGroupAdapter.setSelectedGroupIndex(-1);
                            }
                        });
            }
        }
    };

    private void initVideoView() {
        controller = new LiveController(this);
        controller.setListener(new LiveController.LiveControlListener() {
            @Override
            public boolean singleTap(MotionEvent e) {
                int fiveScreen = PlayerUtils.getScreenWidth(mContext, true) / 5;

                if (e.getX() > 0 && e.getX() < (fiveScreen * 2)) {
                    // left side <<<<<
                    showChannelList();
                } else if ((e.getX() > (fiveScreen * 2)) && (e.getX() < (fiveScreen * 3))) {
                    // middle screen
                    toggleChannelInfo();
                } else if (e.getX() > (fiveScreen * 3)) {
                    // right side >>>>>
                    showSettingGroup();
                }
                return true;
            }

            @Override
            public void longPress() {
                showSettingGroup();
            }

            @Override
            public void playStateChanged(int playState) {
                switch (playState) {
                    case VideoView.STATE_IDLE:
                    case VideoView.STATE_PAUSED:
                        break;
                    case VideoView.STATE_PREPARED:
                        // takagen99 : Retrieve Video Resolution & Retrieve Video Duration
                        if (mVideoView.getVideoSize().length >= 2) {
                            tv_size.setText(mVideoView.getVideoSize()[0] + " x " + mVideoView.getVideoSize()[1]);
                        }
                        // Show SeekBar if it's a VOD (with duration) and not a live stream
                        int duration = (int) mVideoView.getDuration();
                        boolean isLiveStream = mVideoView.isLive();
                        if (duration > 0 && !isLiveStream) {
                            isVOD = true;
                            llSeekBar.setVisibility(View.VISIBLE);
                            mSeekBar.setProgress(10);
                            mSeekBar.setMax(duration);
                            mSeekBar.setProgress(0);
                            mTotalTime.setText(stringForTimeVod(duration));
                        } else {
                            isVOD = false;
                            llSeekBar.setVisibility(View.GONE);
                        }
                        break;
                    case VideoView.STATE_BUFFERED:
                    case VideoView.STATE_PLAYING:
                        currentLiveChangeSourceTimes = 0;
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        mHandler.removeCallbacks(mConnectTimeoutReplayRun);
                        break;
                    case VideoView.STATE_ERROR:
                    case VideoView.STATE_PLAYBACK_COMPLETED:
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        mHandler.removeCallbacks(mConnectTimeoutReplayRun);
                        if (Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 2) == 0) {
                            //缓冲30s重新播放
                            mHandler.postDelayed(mConnectTimeoutReplayRun, 30 * 1000L);
                        } else {
                            mHandler.post(mConnectTimeoutChangeSourceRun);
                        }
                        break;
                    case VideoView.STATE_PREPARING:
                    case VideoView.STATE_BUFFERING:
                        mHandler.removeCallbacks(mConnectTimeoutChangeSourceRun);
                        mHandler.removeCallbacks(mConnectTimeoutReplayRun);
                        if (Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 2) == 0) {
                            //缓冲30s重新播放
                            mHandler.postDelayed(mConnectTimeoutReplayRun, 30 * 1000L);
                        } else {
                            mHandler.postDelayed(mConnectTimeoutChangeSourceRun, (Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 2)) * 5000L);
                        }
                        break;
                }
            }

            @Override
            public void changeSource(int direction) {
                if (direction > 0)
                    playNextSource();
                else
                    playPreSource();
            }
        });
        controller.setCanChangePosition(false);
        controller.setEnableInNormal(true);
        controller.setGestureEnabled(true);
        controller.setDoubleTapTogglePlayEnabled(false);
        mVideoView.setVideoController(controller);
        mVideoView.setProgressManager(null);
    }

    private final Runnable mConnectTimeoutChangeSourceRun = new Runnable() {
        @Override
        public void run() {
            currentLiveChangeSourceTimes++;
            if (currentLiveChannelItem.getSourceNum() == currentLiveChangeSourceTimes) {
                currentLiveChangeSourceTimes = 0;
                Integer[] groupChannelIndex = getNextChannel(Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false) ? -1 : 1);
                playChannel(groupChannelIndex[0], groupChannelIndex[1], false);
            } else {
                playNextSource();
            }
        }
    };

    private final Runnable mConnectTimeoutReplayRun = new Runnable() {
        @Override
        public void run() {
            replayChannel();
        }
    };

    private void initEpgListView() {
        mEpgInfoGridView.setHasFixedSize(true);
        mEpgInfoGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        epgListAdapter = new LiveEpgAdapter();
        mEpgInfoGridView.setAdapter(epgListAdapter);

        mEpgInfoGridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
            }
        });
        //电视
        mEpgInfoGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                epgListAdapter.setFocusedEpgIndex(-1);
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
                epgListAdapter.setFocusedEpgIndex(position);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (!currentLiveChannelItem.getinclude_back()) {
                    return;
                }
                Date date = epgDateAdapter.getSelectedIndex() < 0 ? new Date() :
                        epgDateAdapter.getData().get(epgDateAdapter.getSelectedIndex()).getDateParamVal();
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
                dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
                Epginfo selectedData = epgListAdapter.getItem(position);
                String targetDate = dateFormat.format(date);
                String shiyiStartdate = targetDate + selectedData.originStart.replace(":", "") + "30";
                String shiyiEnddate = targetDate + selectedData.originEnd.replace(":", "") + "30";
                Date now = new Date();
                if (now.compareTo(selectedData.startdateTime) < 0) {
                    return;
                }
                epgListAdapter.setSelectedEpgIndex(position);
                if (now.compareTo(selectedData.startdateTime) >= 0 && now.compareTo(selectedData.enddateTime) <= 0) {
                    mVideoView.release();
                    isSHIYI = false;
                    mVideoView.setUrl(currentLiveChannelItem.getUrl(), setPlayHeaders(currentLiveChannelItem.getUrl()));
                    mVideoView.start();
                    epgListAdapter.setShiyiSelection(-1, false, timeFormat.format(date));
                }
                if (now.compareTo(selectedData.startdateTime) < 0) {

                } else {
                    mVideoView.release();
                    shiyi_time = shiyiStartdate + "-" + shiyiEnddate;
                    isSHIYI = true;
                    mVideoView.setUrl(currentLiveChannelItem.getUrl() + "?playseek=" + shiyi_time, setPlayHeaders(currentLiveChannelItem.getUrl()));
                    mVideoView.start();
                    epgListAdapter.setShiyiSelection(position, true, timeFormat.format(date));
                    epgListAdapter.notifyDataSetChanged();
                    mEpgInfoGridView.setSelectedPosition(position);
                }
            }
        });

        //手机/模拟器
        epgListAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                if (!currentLiveChannelItem.getinclude_back()) {
                    return;
                }
                Date date = epgDateAdapter.getSelectedIndex() < 0 ? new Date() :
                        epgDateAdapter.getData().get(epgDateAdapter.getSelectedIndex()).getDateParamVal();
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
                dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
                Epginfo selectedData = epgListAdapter.getItem(position);
                String targetDate = dateFormat.format(date);
                String shiyiStartdate = targetDate + selectedData.originStart.replace(":", "") + "30";
                String shiyiEnddate = targetDate + selectedData.originEnd.replace(":", "") + "30";
                Date now = new Date();
                if (now.compareTo(selectedData.startdateTime) < 0) {
                    return;
                }
                epgListAdapter.setSelectedEpgIndex(position);
                if (now.compareTo(selectedData.startdateTime) >= 0 && now.compareTo(selectedData.enddateTime) <= 0) {
                    mVideoView.release();
                    isSHIYI = false;
                    mVideoView.setUrl(currentLiveChannelItem.getUrl(), setPlayHeaders(currentLiveChannelItem.getUrl()));
                    mVideoView.start();
                    epgListAdapter.setShiyiSelection(-1, false, timeFormat.format(date));
                }
                if (now.compareTo(selectedData.startdateTime) < 0) {

                } else {
                    mVideoView.release();
                    shiyi_time = shiyiStartdate + "-" + shiyiEnddate;
                    isSHIYI = true;
                    mVideoView.setUrl(currentLiveChannelItem.getUrl() + "?playseek=" + shiyi_time, setPlayHeaders(currentLiveChannelItem.getUrl()));
                    mVideoView.start();
                    epgListAdapter.setShiyiSelection(position, true, timeFormat.format(date));
                    epgListAdapter.notifyDataSetChanged();
                    mEpgInfoGridView.setSelectedPosition(position);
                }
            }
        });
    }

    private void initEpgDateView() {
        mEpgDateGridView.setHasFixedSize(true);
        mEpgDateGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        epgDateAdapter = new LiveEpgDateAdapter();
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(new Date());
        epgDateInitDay = timeFormat.format(calendar.getTime());

        for (int i = 0; i < 2; i++) {
            Date dateIns = calendar.getTime();
            LiveEpgDate epgDate = new LiveEpgDate();
            epgDate.setIndex(i);

            if (i == 0) {
                epgDate.setDatePresented("今天");
            } else {
                epgDate.setDatePresented("明天");
            }

            epgDate.setDateParamVal(dateIns);
            epgDateAdapter.addData(epgDate);
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
        mEpgDateGridView.setAdapter(epgDateAdapter);
        mEpgDateGridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
            }
        });

        //电视
        mEpgDateGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                epgDateAdapter.setFocusedIndex(-1);
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
                epgDateAdapter.setFocusedIndex(position);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
                epgDateAdapter.setSelectedIndex(position);
                getEpg(epgDateAdapter.getData().get(position).getDateParamVal(), false);
            }
        });

        //手机/模拟器
        epgDateAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
                epgDateAdapter.setSelectedIndex(position);
                getEpg(epgDateAdapter.getData().get(position).getDateParamVal(), false);
            }
        });
        epgDateAdapter.setSelectedIndex(0);
    }

    private void initChannelGroupView() {
        mGroupGridView.setHasFixedSize(true);
        mGroupGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveChannelGroupAdapter = new LiveChannelGroupAdapter();
        mGroupGridView.setAdapter(liveChannelGroupAdapter);
        mGroupGridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
            }
        });

        //电视
        mGroupGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                selectChannelGroup(position, true, -1);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (isNeedInputPassword(position)) {
                    showPasswordDialog(position, -1);
                }
            }
        });

        //手机/模拟器
        liveChannelGroupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectChannelGroup(position, false, -1);
            }
        });
    }

    private void selectChannelGroup(int groupIndex, boolean focus, int liveChannelIndex) {
        if (focus) {
            liveChannelGroupAdapter.setFocusedGroupIndex(groupIndex);
            liveChannelItemAdapter.setFocusedChannelIndex(-1);
        }
        if ((groupIndex > -1 && groupIndex != liveChannelGroupAdapter.getSelectedGroupIndex()) || isNeedInputPassword(groupIndex)) {
            liveChannelGroupAdapter.setSelectedGroupIndex(groupIndex);
            if (isNeedInputPassword(groupIndex)) {
                showPasswordDialog(groupIndex, liveChannelIndex);
                return;
            }
            loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex);
        }
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.postDelayed(mHideChannelListRun, 6000);
        }
    }

    private void initLiveChannelView() {
        mChannelGridView.setHasFixedSize(true);
        mChannelGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveChannelItemAdapter = new LiveChannelItemAdapter();
        mChannelGridView.setAdapter(liveChannelItemAdapter);
        mChannelGridView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
            }
        });

        //电视
        mChannelGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (position < 0) return;
                liveChannelGroupAdapter.setFocusedGroupIndex(-1);
                liveChannelItemAdapter.setFocusedChannelIndex(position);
                mHandler.removeCallbacks(mHideChannelListRun);
                mHandler.postDelayed(mHideChannelListRun, 6000);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                clickLiveChannel(position);
            }
        });

        //手机/模拟器
        liveChannelItemAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                clickLiveChannel(position);
            }
        });
    }

    private void clickLiveChannel(int position) {
        liveChannelItemAdapter.setSelectedChannelIndex(position);

        // Set default as Today
        epgDateAdapter.setSelectedIndex(0);

        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
            mHandler.removeCallbacks(mHideChannelListRun);
            mHandler.post(mHideChannelListRun);
//            mHandler.postDelayed(mHideChannelListRun, 500);
        }
        playChannel(liveChannelGroupAdapter.getSelectedGroupIndex(), position, false);
    }

    private void initSettingGroupView() {
        mSettingGroupView.setHasFixedSize(true);
        mSettingGroupView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveSettingGroupAdapter = new LiveSettingGroupAdapter();
        mSettingGroupView.setAdapter(liveSettingGroupAdapter);
        mSettingGroupView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, 5000);
            }
        });

        //电视
        mSettingGroupView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                selectSettingGroup(position, true);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });

        //手机/模拟器
        liveSettingGroupAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                selectSettingGroup(position, false);
            }
        });
    }

    private void selectSettingGroup(int position, boolean focus) {
        if (!isCurrentLiveChannelValid()) return;
        if (focus) {
            liveSettingGroupAdapter.setFocusedGroupIndex(position);
            liveSettingItemAdapter.setFocusedItemIndex(-1);
        }
        if (position == liveSettingGroupAdapter.getSelectedGroupIndex() || position < -1)
            return;

        liveSettingGroupAdapter.setSelectedGroupIndex(position);
        liveSettingItemAdapter.setNewData(liveSettingGroupList.get(position).getLiveSettingItems());

        switch (position) {
            case 0:
                liveSettingItemAdapter.selectItem(currentLiveChannelItem.getSourceIndex(), true, false);
                break;
            case 1:
                liveSettingItemAdapter.selectItem(livePlayerManager.getLivePlayerScale(), true, true);
                break;
            case 2:
                liveSettingItemAdapter.selectItem(livePlayerManager.getLivePlayerType(), true, true);
                break;
        }
        int scrollToPosition = liveSettingItemAdapter.getSelectedItemIndex();
        if (scrollToPosition < 0) scrollToPosition = 0;
        mSettingItemView.scrollToPosition(scrollToPosition);
        mHandler.removeCallbacks(mHideSettingLayoutRun);
        mHandler.postDelayed(mHideSettingLayoutRun, 5000);
    }

    private void initSettingItemView() {
        mSettingItemView.setHasFixedSize(true);
        mSettingItemView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));

        liveSettingItemAdapter = new LiveSettingItemAdapter();
        mSettingItemView.setAdapter(liveSettingItemAdapter);
        mSettingItemView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, 5000);
            }
        });

        //电视
        mSettingItemView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (position < 0) return;
                liveSettingGroupAdapter.setFocusedGroupIndex(-1);
                liveSettingItemAdapter.setFocusedItemIndex(position);
                mHandler.removeCallbacks(mHideSettingLayoutRun);
                mHandler.postDelayed(mHideSettingLayoutRun, 5000);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                clickSettingItem(position);
            }
        });

        //手机/模拟器
        liveSettingItemAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                clickSettingItem(position);
            }
        });
    }

    private void clickSettingItem(int position) {
        int settingGroupIndex = liveSettingGroupAdapter.getSelectedGroupIndex();
        if (settingGroupIndex < 4) {
            if (position == liveSettingItemAdapter.getSelectedItemIndex())
                return;
            liveSettingItemAdapter.selectItem(position, true, true);
        }
        switch (settingGroupIndex) {
            case 0://线路切换
                currentLiveChannelItem.setSourceIndex(position);
                playChannel(currentChannelGroupIndex, currentLiveChannelIndex, true);
                break;
            case 1://画面比例
                livePlayerManager.changeLivePlayerScale(mVideoView, position, currentLiveChannelItem.getChannelName());
                break;
            case 2://播放解码
                mVideoView.release();
                livePlayerManager.changeLivePlayerType(mVideoView, position, currentLiveChannelItem.getChannelName());
                mVideoView.setUrl(currentLiveChannelItem.getUrl(), setPlayHeaders(currentLiveChannelItem.getUrl()));
                mVideoView.start();
                break;
            case 3://超时换源
                Hawk.put(HawkConfig.LIVE_CONNECT_TIMEOUT, position);
                break;
            case 4://偏好设置
                boolean select = false;
                switch (position) {
                    case 0:
                        select = !Hawk.get(HawkConfig.LIVE_SHOW_TIME, false);
                        Hawk.put(HawkConfig.LIVE_SHOW_TIME, select);
                        showTime();
                        break;
                    case 1:
                        select = !Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false);
                        Hawk.put(HawkConfig.LIVE_SHOW_NET_SPEED, select);
                        showNetSpeed();
                        break;
                    case 2:
                        select = !Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false);
                        Hawk.put(HawkConfig.LIVE_CHANNEL_REVERSE, select);
                        break;
                    case 3:
                        select = !Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false);
                        Hawk.put(HawkConfig.LIVE_CROSS_GROUP, select);
                        break;
                    case 4:
                        // takagen99 : Added Skip Password Option
                        select = !Hawk.get(HawkConfig.LIVE_SKIP_PASSWORD, false);
                        Hawk.put(HawkConfig.LIVE_SKIP_PASSWORD, select);
                        break;
                }
                liveSettingItemAdapter.selectItem(position, select, false);
                break;
            case 5:// 直播历史 takagen99 : Live History
                switch (position) {
                    case 0:
                        // takagen99 : Added Live History list selection - 直播列表
                        ArrayList<String> liveHistory = HawkListHelper.getList(HawkConfig.LIVE_HISTORY);
                        if (liveHistory.isEmpty())
                            return;
                        String current = Hawk.get(HawkConfig.LIVE_URL, "");
                        int idx = 0;
                        // 如果当前地址在历史记录中，临时将其移到顶部（仅用于显示）
                        if (liveHistory.contains(current)) {
                            liveHistory.remove(current);
                            liveHistory.add(0, current);
                            idx = 0;
                        }
                        ApiHistoryDialog dialog = new ApiHistoryDialog(LivePlayActivity.this);
                        dialog.setTip(getString(R.string.dia_history_live));
                        dialog.setAdapter(new ApiHistoryDialogAdapter.SelectDialogInterface() {
                            @Override
                            public void click(String liveURL) {
                                Hawk.put(HawkConfig.LIVE_URL, liveURL);
                                liveChannelGroupList.clear();
                                try {
                                    liveURL = Base64.encodeToString(liveURL.getBytes("UTF-8"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP);
                                    liveURL = "http://127.0.0.1:9978/proxy?do=live&type=txt&ext=" + liveURL;
                                    loadProxyLives(liveURL);
                                } catch (Throwable th) {
                                    LOG.e(th);
                                }
                                dialog.dismiss();
                            }

                            @Override
                            public void del(String value, ArrayList<String> data) {
                                HawkListHelper.putList(HawkConfig.LIVE_HISTORY, data);
                            }
                        }, liveHistory, idx);
                        dialog.show();
                        break;
                }
                break;
            case 6:// 退出直播 takagen99 : Added Exit Option
                switch (position) {
                    case 0:
                        finish();
                        break;
                }
                break;
        }
        mHandler.removeCallbacks(mHideSettingLayoutRun);
        mHandler.postDelayed(mHideSettingLayoutRun, 5000);
    }

    private void initLiveChannelList() {
        List<LiveChannelGroup> list = ApiConfig.get().getChannelGroupList();
        if (list.isEmpty()) {
            Toast.makeText(App.getInstance(), getString(R.string.act_live_play_empty_channel), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (list.size() == 1 && list.get(0).getGroupName().startsWith("http://127.0.0.1")) {
            loadProxyLives(list.get(0).getGroupName());
        } else {
            liveChannelGroupList.clear();
            liveChannelGroupList.addAll(list);
            showSuccess();
            initLiveState();
        }
    }

    //加载列表
    public void loadProxyLives(String url) {
        try {
            Uri parsedUrl = Uri.parse(url);
            url = new String(Base64.decode(parsedUrl.getQueryParameter("ext"), Base64.DEFAULT | Base64.URL_SAFE | Base64.NO_WRAP), "UTF-8");
            if (url.equals("")) {
                Toast.makeText(App.getInstance(), getString(R.string.act_live_play_empty_live_url), Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        } catch (Throwable th) {
            Toast.makeText(App.getInstance(), getString(R.string.act_live_play_empty_channel), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        showLoading();
        OkGo.<String>get(url).execute(new AbsCallback<String>() {

            @Override
            public String convertResponse(okhttp3.Response response) throws Throwable {
                return response.body().string();
            }

            @Override
            public void onSuccess(Response<String> response) {
                JsonArray livesArray;
                LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>> linkedHashMap = new LinkedHashMap<>();
                TxtSubscribe.parse(linkedHashMap, response.body());
                livesArray = TxtSubscribe.live2JsonArray(linkedHashMap);

                ApiConfig.get().loadLives(livesArray);
                List<LiveChannelGroup> list = ApiConfig.get().getChannelGroupList();
                if (list.isEmpty()) {
                    Toast.makeText(App.getInstance(), getString(R.string.act_live_play_empty_channel), Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                liveChannelGroupList.clear();
                liveChannelGroupList.addAll(list);

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        LivePlayActivity.this.showSuccess();
                        initLiveState();
                    }
                });
            }

            @Override
            public void onError(Response<String> response) {
                super.onError(response);
                Toast.makeText(App.getInstance(), getString(R.string.act_live_play_network_error), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_LIVEPLAY_UPDATE) {
            Bundle bundle = (Bundle) event.obj;
            int channelGroupIndex = bundle.getInt("groupIndex", 0);
            int liveChannelIndex = bundle.getInt("channelIndex", 0);
            if (channelGroupIndex != liveChannelGroupAdapter.getSelectedGroupIndex())
                selectChannelGroup(channelGroupIndex, true, liveChannelIndex);
            else {
                clickLiveChannel(liveChannelIndex);
                mGroupGridView.scrollToPosition(channelGroupIndex);
                mChannelGridView.scrollToPosition(liveChannelIndex);
                playChannel(channelGroupIndex, liveChannelIndex, false);
            }
        }
    }

    private void initLiveState() {
        int lastChannelGroupIndex = -1;
        int lastLiveChannelIndex = -1;
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            lastChannelGroupIndex = bundle.getInt("groupIndex", 0);
            lastLiveChannelIndex = bundle.getInt("channelIndex", 0);
        } else {
            Pair<Integer, Integer> lastChannel = JavaUtil.findLiveLastChannel(liveChannelGroupList);
            lastChannelGroupIndex = lastChannel.getFirst();
            lastLiveChannelIndex = lastChannel.getSecond();
        }

        livePlayerManager.init(mVideoView);
        showTime();
        showNetSpeed();
        tvLeftChannelListLayout.setVisibility(View.INVISIBLE);
        tvRightSettingLayout.setVisibility(View.INVISIBLE);

        liveChannelGroupAdapter.setNewData(liveChannelGroupList);
        selectChannelGroup(lastChannelGroupIndex, false, lastLiveChannelIndex);
        preloadAllChannelLogos();
    }

    private boolean isListOrSettingLayoutVisible() {
        return tvLeftChannelListLayout.getVisibility() == View.VISIBLE || tvRightSettingLayout.getVisibility() == View.VISIBLE;
    }

    private void initLiveSettingGroupList() {
        ArrayList<String> groupNames = new ArrayList<>(Arrays.asList("线路选择", "画面比例", "播放解码", "超时换源", "偏好设置", "直播地址", "退出直播"));
        ArrayList<ArrayList<String>> itemsArrayList = new ArrayList<>();
        ArrayList<String> sourceItems = new ArrayList<>();
        ArrayList<String> scaleItems = new ArrayList<>(Arrays.asList("默认", "16:9", "4:3", "填充", "原始", "裁剪"));
        ArrayList<String> playerDecoderItems = new ArrayList<>(Arrays.asList("系统", "ijk硬解", "ijk软解", "exo"));
        ArrayList<String> timeoutItems = new ArrayList<>(Arrays.asList("关", "5s", "10s", "15s", "20s", "25s", "30s"));
        ArrayList<String> personalSettingItems = new ArrayList<>(Arrays.asList("显示时间", "显示网速", "换台反转", "跨选分类", "关闭密码"));
        ArrayList<String> liveAdd = new ArrayList<>(Arrays.asList("列表历史"));
        ArrayList<String> exitConfirm = new ArrayList<>(Arrays.asList("确定"));
        itemsArrayList.add(sourceItems);
        itemsArrayList.add(scaleItems);
        itemsArrayList.add(playerDecoderItems);
        itemsArrayList.add(timeoutItems);
        itemsArrayList.add(personalSettingItems);
        itemsArrayList.add(liveAdd);
        itemsArrayList.add(exitConfirm);

        liveSettingGroupList.clear();
        for (int i = 0; i < groupNames.size(); i++) {
            LiveSettingGroup liveSettingGroup = new LiveSettingGroup();
            ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
            liveSettingGroup.setGroupIndex(i);
            liveSettingGroup.setGroupName(groupNames.get(i));
            for (int j = 0; j < itemsArrayList.get(i).size(); j++) {
                LiveSettingItem liveSettingItem = new LiveSettingItem();
                liveSettingItem.setItemIndex(j);
                liveSettingItem.setItemName(itemsArrayList.get(i).get(j));
                liveSettingItemList.add(liveSettingItem);
            }
            liveSettingGroup.setLiveSettingItems(liveSettingItemList);
            liveSettingGroupList.add(liveSettingGroup);
        }
        liveSettingGroupList.get(3).getLiveSettingItems().get(Hawk.get(HawkConfig.LIVE_CONNECT_TIMEOUT, 2)).setItemSelected(true);
        liveSettingGroupList.get(4).getLiveSettingItems().get(0).setItemSelected(Hawk.get(HawkConfig.LIVE_SHOW_TIME, false));
        liveSettingGroupList.get(4).getLiveSettingItems().get(1).setItemSelected(Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false));
        liveSettingGroupList.get(4).getLiveSettingItems().get(2).setItemSelected(Hawk.get(HawkConfig.LIVE_CHANNEL_REVERSE, false));
        liveSettingGroupList.get(4).getLiveSettingItems().get(3).setItemSelected(Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false));
        liveSettingGroupList.get(4).getLiveSettingItems().get(4).setItemSelected(Hawk.get(HawkConfig.LIVE_SKIP_PASSWORD, false));
    }

    private void loadCurrentSourceList() {
        ArrayList<String> currentSourceNames = currentLiveChannelItem.getChannelSourceNames();
        ArrayList<LiveSettingItem> liveSettingItemList = new ArrayList<>();
        for (int j = 0; j < currentSourceNames.size(); j++) {
            LiveSettingItem liveSettingItem = new LiveSettingItem();
            liveSettingItem.setItemIndex(j);
            liveSettingItem.setItemName(currentSourceNames.get(j));
            liveSettingItemList.add(liveSettingItem);
        }
        liveSettingGroupList.get(0).setLiveSettingItems(liveSettingItemList);
    }

    void showTime() {
        if (Hawk.get(HawkConfig.LIVE_SHOW_TIME, false)) {
            mHandler.post(mUpdateTimeRun);
            tvTime.setVisibility(View.VISIBLE);
        } else {
            mHandler.removeCallbacks(mUpdateTimeRun);
            tvTime.setVisibility(View.GONE);
        }
    }

    private final Runnable mUpdateTimeRun = new Runnable() {
        @Override
        public void run() {
            Date day = new Date();
            SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            tvTime.setText(df.format(day));
            mHandler.postDelayed(this, 1000);
        }
    };

    private void showNetSpeed() {
        if (Hawk.get(HawkConfig.LIVE_SHOW_NET_SPEED, false)) {
            mHandler.post(mUpdateNetSpeedRun);
            tvNetSpeed.setVisibility(View.VISIBLE);
        } else {
            mHandler.removeCallbacks(mUpdateNetSpeedRun);
            tvNetSpeed.setVisibility(View.GONE);
        }
    }

    private final Runnable mUpdateNetSpeedRun = new Runnable() {
        @Override
        public void run() {
            if (mVideoView == null) return;
            tvNetSpeed.setText(String.format("%.2fMB/s", (float) mVideoView.getTcpSpeed() / 1024.0 / 1024.0));
            mHandler.postDelayed(this, 1000);
        }
    };

    private void showPasswordDialog(int groupIndex, int liveChannelIndex) {
        if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE)
            mHandler.removeCallbacks(mHideChannelListRun);

        LivePasswordDialog dialog = new LivePasswordDialog(this);
        dialog.setOnListener(new LivePasswordDialog.OnListener() {
            @Override
            public void onChange(String password) {
                if (password.equals(liveChannelGroupList.get(groupIndex).getGroupPassword())) {
                    channelGroupPasswordConfirmed.add(groupIndex);
                    loadChannelGroupDataAndPlay(groupIndex, liveChannelIndex);
                } else {
                    Toast.makeText(App.getInstance(), "密码错误", Toast.LENGTH_SHORT).show();
                }

                if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE)
                    mHandler.postDelayed(mHideChannelListRun, 6000);
            }

            @Override
            public void onCancel() {
                if (tvLeftChannelListLayout.getVisibility() == View.VISIBLE) {
                    int groupIndex = liveChannelGroupAdapter.getSelectedGroupIndex();
                    liveChannelItemAdapter.setNewData(getLiveChannels(groupIndex));
                }
            }
        });
        dialog.show();
    }

    private void loadChannelGroupDataAndPlay(int groupIndex, int liveChannelIndex) {
        liveChannelItemAdapter.setNewData(getLiveChannels(groupIndex));
        if (groupIndex == currentChannelGroupIndex) {
            if (currentLiveChannelIndex > -1)
                mChannelGridView.scrollToPosition(currentLiveChannelIndex);
            liveChannelItemAdapter.setSelectedChannelIndex(currentLiveChannelIndex);
        } else {
            mChannelGridView.scrollToPosition(0);
            liveChannelItemAdapter.setSelectedChannelIndex(-1);
        }

        if (liveChannelIndex > -1) {
            clickLiveChannel(liveChannelIndex);
            mGroupGridView.scrollToPosition(groupIndex);
            mChannelGridView.scrollToPosition(liveChannelIndex);
            playChannel(groupIndex, liveChannelIndex, false);
        }
    }

    private boolean isNeedInputPassword(int groupIndex) {
        return !liveChannelGroupList.get(groupIndex).getGroupPassword().isEmpty()
                && !isPasswordConfirmed(groupIndex);
    }

    private boolean isPasswordConfirmed(int groupIndex) {
        if (Hawk.get(HawkConfig.LIVE_SKIP_PASSWORD, false)) {
            return true;
        } else {
            for (Integer confirmedNum : channelGroupPasswordConfirmed) {
                if (confirmedNum == groupIndex)
                    return true;
            }
            return false;
        }
    }

    private ArrayList<LiveChannelItem> getLiveChannels(int groupIndex) {
        if (!isNeedInputPassword(groupIndex)) {
            return liveChannelGroupList.get(groupIndex).getLiveChannels();
        } else {
            return new ArrayList<>();
        }
    }

    private Integer[] getNextChannel(int direction) {
        int channelGroupIndex = currentChannelGroupIndex;
        int liveChannelIndex = currentLiveChannelIndex;

        //跨选分组模式下跳过加密频道分组（遥控器上下键换台/超时换源）
        if (direction > 0) {
            liveChannelIndex++;
            if (liveChannelIndex >= getLiveChannels(channelGroupIndex).size()) {
                liveChannelIndex = 0;
                if (Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false)) {
                    do {
                        channelGroupIndex++;
                        if (channelGroupIndex >= liveChannelGroupList.size())
                            channelGroupIndex = 0;
                    } while (!liveChannelGroupList.get(channelGroupIndex).getGroupPassword().isEmpty() || channelGroupIndex == currentChannelGroupIndex);
                }
            }
        } else {
            liveChannelIndex--;
            if (liveChannelIndex < 0) {
                if (Hawk.get(HawkConfig.LIVE_CROSS_GROUP, false)) {
                    do {
                        channelGroupIndex--;
                        if (channelGroupIndex < 0)
                            channelGroupIndex = liveChannelGroupList.size() - 1;
                    } while (!liveChannelGroupList.get(channelGroupIndex).getGroupPassword().isEmpty() || channelGroupIndex == currentChannelGroupIndex);
                }
                liveChannelIndex = getLiveChannels(channelGroupIndex).size() - 1;
            }
        }

        Integer[] groupChannelIndex = new Integer[2];
        groupChannelIndex[0] = channelGroupIndex;
        groupChannelIndex[1] = liveChannelIndex;

        return groupChannelIndex;
    }

    private int getFirstNoPasswordChannelGroup() {
        for (LiveChannelGroup liveChannelGroup : liveChannelGroupList) {
            if (liveChannelGroup.getGroupPassword().isEmpty())
                return liveChannelGroup.getGroupIndex();
        }
        return -1;
    }

    private boolean isCurrentLiveChannelValid() {
        if (currentLiveChannelItem == null) {
            Toast.makeText(App.getInstance(), "请先选择频道", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }
}
