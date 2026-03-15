package com.github.tvbox.osc.ui.fragment;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.media.MediaPlayer;

import com.github.tvbox.osc.util.ToastHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.recyclerview.widget.DiffUtil;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.ParseBean;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.bean.SubtitleBean;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cast.model.CastData;
import com.github.tvbox.osc.cast.model.CastDevice;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.player.EXOmPlayer;
import com.github.tvbox.osc.player.IjkmPlayer;
import com.github.tvbox.osc.player.MyVideoView;
import com.github.tvbox.osc.player.TrackInfo;
import com.github.tvbox.osc.player.TrackInfoBean;
import com.github.tvbox.osc.player.controller.VodController;
import com.github.tvbox.osc.player.danmu.Parser;
import com.github.tvbox.osc.player.thirdparty.Kodi;
import com.github.tvbox.osc.player.thirdparty.MXPlayer;
import com.github.tvbox.osc.player.thirdparty.ReexPlayer;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.server.RemoteServer;
import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.CastDialog;
import com.github.tvbox.osc.ui.dialog.DanmuSettingDialog;
import com.github.tvbox.osc.ui.dialog.SearchSubtitleDialog;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.SubtitleDialog;
import com.github.tvbox.osc.util.AdBlocker;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FileUtils;
import com.hjq.permissions.XXPermissions;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HawkUtils;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.M3U8;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.parser.SuperParse;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.StringUtils;
import com.github.tvbox.osc.util.SubtitleHelper;
import com.github.tvbox.osc.util.UpdateCheckManager;
import com.github.tvbox.osc.util.VideoParseRuler;
import com.github.tvbox.osc.util.thunder.Jianpian;
import com.github.tvbox.osc.util.thunder.Thunder;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.HttpHeaders;
import com.lzy.okgo.model.Response;
import com.obsez.android.lib.filechooser.ChooserDialog;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URLEncoder;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import master.flame.danmaku.danmaku.model.BaseDanmaku;
import master.flame.danmaku.danmaku.model.IDisplayer;
import master.flame.danmaku.danmaku.model.android.DanmakuContext;
import master.flame.danmaku.ui.widget.DanmakuView;
import me.jessyan.autosize.AutoSize;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkTimedText;
import xyz.doikki.videoplayer.player.AbstractPlayer;
import xyz.doikki.videoplayer.player.AndroidMediaPlayer;
import xyz.doikki.videoplayer.player.BaseVideoView;
import xyz.doikki.videoplayer.player.ProgressManager;

public class PlayFragment extends BaseLazyFragment {
    public MyVideoView mVideoView;
    private TextView mPlayLoadTip;
    private ImageView mPlayLoadErr;
    private ProgressBar mPlayLoading;
    private VodController mController;
    private SourceViewModel sourceViewModel;
    private SafeHandler mHandler;

    private final Runnable mTrackSwitchRunnable = new Runnable() {
        @Override
        public void run() {
            if (mVideoView != null) {
                mVideoView.seekTo(mTrackSwitchProgress);
                mVideoView.start();
                if (mController != null) {
                    mController.startProgress();
                }
            }
        }
    };
    private long mTrackSwitchProgress = 0;

    ExecutorService executorService;
    private volatile boolean isDanmuLoadingCancelled = false; // 弹幕加载取消标志
    private DanmakuView mDanmuView;
    private DanmakuContext mDanmakuContext;
    private String danmuText;

    // 提供给投屏功能的 getter/setter 方法
    public DanmakuView getDanmuView() {
        return mDanmuView;
    }

    public void setDanmuView(DanmakuView danmuView) {
        this.mDanmuView = danmuView;
    }

    public String getDanmuText() {
        return danmuText;
    }

    public void setDanmuText(String danmuText) {
        this.danmuText = danmuText;
    }

    private String videoURL;
    private HashMap<String, String> videoHeaders;
    private long videoDuration = -1;
    private List<String> videoSegmentationURL = new ArrayList<>();
    private boolean historySaved = false;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_play;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (!isAdded()) return;
        if (getActivity() == null || getActivity().isFinishing() || getActivity().isDestroyed()) return;
        if (mController == null) return;
        if (event.type == RefreshEvent.TYPE_SUBTITLE_SIZE_CHANGE) {
            if (mController.mSubtitleView != null) {
                mController.mSubtitleView.setTextSize((int) event.obj);
            }
        }
        if (event.type == RefreshEvent.TYPE_SET_DANMU_SETTINGS) {
            boolean needReload = (Boolean) event.obj;
            if (HawkUtils.getDanmuOpen() && mDanmuView != null && danmuText != null && !danmuText.isEmpty()) {
                mDanmuView.setVisibility(View.VISIBLE);
                if (danmuLoaded) {
                    // 弹幕已加载，根据needReload决定是否重新加载
                    if (needReload) {
                        setDanmuViewSettings(true);
                    } else {
                        setDanmuViewSettings(false);
                        mDanmuView.show();
                        if (mVideoView != null) {
                            mDanmuView.seekTo(mVideoView.getCurrentPosition());
                        }
                    }
                } else {
                    // 弹幕还未加载（换集后），立即加载
                    loadDanmuWhenPlaying();
                }
            } else if (!HawkUtils.getDanmuOpen() && mDanmuView != null) {
                // 弹幕关闭时隐藏视图并重置加载状态，下次开启时重新加载
                mDanmuView.hide();
                mDanmuView.setVisibility(View.GONE);
                danmuLoaded = false;
            }
        }
    }

    @Override
    protected void init() {
        initView();
        initViewModel();
        initData();
        initDanmuView();
    }
    private void initDanmuView() {
        mDanmuView  = findViewById(R.id.danmaku);
        mDanmakuContext = DanmakuContext.create();
        mVideoView.setDanmuView(mDanmuView);
    }

    private void setDanmuViewSettings(boolean reload) {
        float speed = HawkUtils.getDanmuSpeed();
        float alpha = HawkUtils.getDanmuAlpha();
        float sizeScale = HawkUtils.getDanmuSizeScale();
        int maxLine = HawkUtils.getDanmuMaxLine();
        HashMap<Integer, Integer> maxLines = new HashMap<>();
        maxLines.put(BaseDanmaku.TYPE_FIX_TOP, maxLine);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_RL, maxLine);
        maxLines.put(BaseDanmaku.TYPE_SCROLL_LR, maxLine);
        maxLines.put(BaseDanmaku.TYPE_FIX_BOTTOM, maxLine);
        maxLines.put(BaseDanmaku.TYPE_SPECIAL, maxLine);
        mDanmakuContext.setMaximumLines(maxLines).setScrollSpeedFactor(speed).setDanmakuTransparency(alpha).setScaleTextSize(sizeScale);
        mDanmakuContext.setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3).setDanmakuMargin(8);
        if (reload){
            // 取消之前的弹幕加载任务
            isDanmuLoadingCancelled = true;
            if (executorService != null){
                executorService.shutdownNow();
                executorService = null;
            }
            executorService = Executors.newSingleThreadExecutor();
            // 没有弹幕数据，不显示加载提示也不执行加载
            if (danmuText == null || danmuText.isEmpty()) {
                return;
            }
            // 显示加载提示
            App.post(() -> {
                if (mActivity != null && !mActivity.isFinishing()) {
                    Toast.makeText(mActivity, "弹幕加载需要点时间，请稍等", Toast.LENGTH_SHORT).show();
                }
            });
            executorService.execute(() -> {
                // 新任务开始时重置取消标志
                isDanmuLoadingCancelled = false;
                if (mDanmuView == null || mDanmakuContext == null) {
                    return;
                }
                mDanmuView.release();

                // 如果 danmuText 是 URL，先下载 XML 内容并保存
                String xmlContent = danmuText;
                if (danmuText != null && danmuText.startsWith("http")) {
                    // 检查弹幕 URL 是否包含无效端口 (-1)
                    if (danmuText.contains(":-1/")) {
                        App.post(() -> {
                            if (mActivity != null && !mActivity.isFinishing()) {
                                Toast.makeText(mActivity, "弹幕服务初始化失败，请尝试重启应用", Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }
                    xmlContent = Parser.getXmlContent(danmuText);
                    // 保存 XML 内容到 danmuText，供投屏使用
                    if (xmlContent != null && !xmlContent.isEmpty() && !xmlContent.startsWith("http")) {
                        danmuText = xmlContent;
                    }
                }
                
                // 检查内容是否为空
                if (isDanmuLoadingCancelled || xmlContent == null || xmlContent.isEmpty()) {
                    if (!isDanmuLoadingCancelled) {
                        App.post(() -> {
                            if (mActivity != null && !mActivity.isFinishing()) {
                                Toast.makeText(mActivity, "弹幕加载失败，请检查弹幕源", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    return;
                }

                final String finalXmlContent = xmlContent;
                Parser parser = new Parser(finalXmlContent);
                mDanmuView.prepare(parser, mDanmakuContext);
                // 弹幕加载完成后，在主线程显示弹幕
                App.post(() -> {
                    if (mActivity != null && !mActivity.isFinishing() && mDanmuView != null && HawkUtils.getDanmuOpen()) {
                        mDanmuView.setVisibility(View.VISIBLE);
                        mDanmuView.show();
                        // 弹幕准备完成后，开始渲染并同步到当前播放位置
                        startDanmuWhenPrepared();
                    }
                });
            });
        }
    }

    public VodController getVodController() {
        return mController;
    }

    private void initView() {
        EventBus.getDefault().register(this);
        mHandler = new SafeHandler(this);
        mVideoView = findViewById(R.id.mVideoView);
        mPlayLoadTip = findViewById(R.id.play_load_tip);
        mPlayLoading = findViewById(R.id.play_loading);
        mPlayLoadErr = findViewById(R.id.play_load_error);
        mController = new VodController(requireContext());
        mController.setCanChangePosition(true);
        mController.setEnableInNormal(true);
        mController.setGestureEnabled(true);
        ProgressManager progressManager = new ProgressManager() {
            @Override
            public void saveProgress(String url, long progress) {
                if (videoDuration == 0) return;
                if (mActivity instanceof DetailActivity) {
                    ((DetailActivity) mActivity).setPlaySuccess(true);
                }
                CacheManager.save(MD5.string2MD5(url), progress);
                if (!historySaved && mVodInfo != null && sourceKey != null) {
                    historySaved = true;
                    if (getActivity() != null && !getActivity().isFinishing() && !getActivity().isDestroyed()) {
                        mVodInfo.saveCurrentEpisodeInfo();
                        List<VodInfo.VodSeries> seriesList = mVodInfo.seriesMap.get(mVodInfo.playFlag);
                        if (seriesList != null && mVodInfo.playEpisodeIndex >= 0 && mVodInfo.playEpisodeIndex < seriesList.size()) {
                            VodInfo.VodSeries currentSeries = seriesList.get(mVodInfo.playEpisodeIndex);
                            if (currentSeries != null && currentSeries.name != null) {
                                mVodInfo.playNote = currentSeries.name;
                            } else {
                                mVodInfo.playNote = "";
                            }
                        } else {
                            mVodInfo.playNote = "";
                        }
                        int currentSourceEpisodes = getCurrentSourceEpisodes(mVodInfo);
                        int playIndex = mVodInfo.playEpisodeIndex;
                        boolean isLatestEpisode;
                        if (mVodInfo.reverseSort) {
                            isLatestEpisode = (playIndex == 0);
                        } else {
                            isLatestEpisode = (currentSourceEpisodes > 0 && playIndex + 1 >= currentSourceEpisodes);
                        }
                        RoomDataManger.insertVodRecord(sourceKey, mVodInfo);
                        UpdateCheckManager.get().setVideoUpdate(sourceKey, mVodInfo.id, !isLatestEpisode);
                        // 检查Activity是否正在销毁,避免在销毁后发送事件
                        if (!getActivity().isFinishing() && !getActivity().isDestroyed()) {
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
                        }
                    }
                }
            }

            @Override
            public long getSavedProgress(String url) {
                int st = 0;
                try {
                    st = mVodPlayerCfg.getInt("st");
                } catch (JSONException e) {
                    LOG.e(e);
                }
                long skip = st * 1000L;
                if (CacheManager.getCache(MD5.string2MD5(url)) == null) {
                    return skip;
                }
                long rec = (long) CacheManager.getCache(MD5.string2MD5(url));
                if (rec < skip)
                    return skip;
                return rec;
            }
        };
        mVideoView.setProgressManager(progressManager);
        mController.setListener(new VodController.VodControlListener() {

            @Override
            public void showDanmuSetting() {
                if (mActivity instanceof DetailActivity) {
                    final DetailActivity activity = (DetailActivity) mActivity;
                    // 检查DetailActivity是否正在销毁
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        DanmuSettingDialog dialog = new DanmuSettingDialog(activity, mDanmuView);
                        dialog.show();
                    }
                }
            }

            @Override
            public void playNext(boolean rmProgress) {
                if (videoSegmentationURL.size() > 0) {
                    for (int i=0; i<videoSegmentationURL.size()-1; i++) {
                        if (videoSegmentationURL.get(i).equals(videoURL)) {
                            mVideoView.setPlayFromZeroPositionOnce(true);
                            startPlayUrl(videoSegmentationURL.get(i+1), new HashMap<>());//todo header
                            return;
                        }
                    }
                }
                String preProgressKey = progressKey;
                PlayFragment.this.playNext(rmProgress);
                if (rmProgress && preProgressKey != null)
                    CacheManager.delete(MD5.string2MD5(preProgressKey), 0);
            }

            @Override
            public void playPre() {
                if (videoSegmentationURL.size() > 0) {
                    for (int i=1; i<videoSegmentationURL.size(); i++) {
                        if (videoSegmentationURL.get(i).equals(videoURL)) {
                            mVideoView.setPlayFromZeroPositionOnce(true);
                            startPlayUrl(videoSegmentationURL.get(i-1), new HashMap<>());//todo header
                            return;
                        }
                    }
                }
                PlayFragment.this.playPrevious();
            }

            @Override
            public void changeParse(ParseBean pb) {
                doParse(pb);
            }

            @Override
            public void updatePlayerCfg() {
                mVodInfo.playerCfg = mVodPlayerCfg.toString();
                // 确保在 Fragment 有效状态下发送事件
                if (isAdded()) {
                    EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodPlayerCfg));
                }
            }

            @Override
            public void replay(boolean replay) {
                play(replay);
            }

            @Override
            public void errReplay() {
                errorWithRetry("视频播放出错", false, true);
            }

            @Override
            public void onPlayerSelected(int playerType) {
                userSelectedPlayerType = playerType;
                // 标记为用户手动选择
                try {
                    if (mVodPlayerCfg != null) {
                        mVodPlayerCfg.put("ps", "user");
                        if (mVodInfo != null) {
                            mVodInfo.playerCfg = mVodPlayerCfg.toString();
                        }
                    }
                } catch (Exception e) {
                    LOG.e(e);
                }
            }

            @Override
            public void selectSubtitle() {
                selectMySubtitle();
            }

            @Override
            public void selectAudioTrack() {
                selectMyAudioTrack();
            }

            @Override
            public void openVideo() {
                openMyVideo();
            }

            @Override
            public void prepared() {
                initSubtitleView();
            }

            @Override
            public void playing() {
                // 清除加载提示
                setTip("", false, false);
                // 取消播放超时检测
                cancelPlayTimeoutCheck();
                // 视频成功播放后加载弹幕
                loadDanmuWhenPlaying();
            }

            @Override
            public void clickCast() {
                // 点击投屏图标时先暂停本地播放
                if (mVideoView != null && mVideoView.isPlaying()) {
                    mVideoView.pause();
                }
                showCastDialog();
            }

        });
        mVideoView.setVideoController(mController);
        mVideoView.setmHandler(mHandler);
    }

    //设置字幕
    void setSubtitle(String path) {
        if (path != null && path.length() > 0) {
            // 设置字幕
            mController.mSubtitleView.setVisibility(View.GONE);
            mController.mSubtitleView.setSubtitlePath(path);
            mController.mSubtitleView.setVisibility(View.VISIBLE);
        }
    }

    void selectMySubtitle() {
        SubtitleDialog subtitleDialog = new SubtitleDialog(getActivity());

        subtitleDialog.setSubtitleViewListener(new SubtitleDialog.SubtitleViewListener() {
        	@Override
            public void setTextSize(int size) {
                mController.mSubtitleView.setTextSize(size);
            }@Override
            public void setSubtitleDelay(int milliseconds) {
                mController.mSubtitleView.setSubtitleDelay(milliseconds);
            }@Override
            public void selectInternalSubtitle() {
                selectMyInternalSubtitle();
            }@Override
            public void updateSubtitleStyle() {
                setSubtitleViewTextStyle();
            }
        });
        subtitleDialog.setSearchSubtitleListener(new SubtitleDialog.SearchSubtitleListener() {
        	@Override
            public void openSearchSubtitleDialog() {
                SearchSubtitleDialog searchSubtitleDialog = new SearchSubtitleDialog(getActivity());
                searchSubtitleDialog.setSubtitleLoader(new SearchSubtitleDialog.SubtitleLoader() {
                	@Override
                    public void loadSubtitle(SubtitleBean subtitle) {
                    	if (!isAdded()) return;
                        requireActivity().runOnUiThread(new Runnable() {
                        	@Override
                            public void run() {
                                String zimuUrl = subtitle.getUrl();
                                LOG.i("Remote SubtitleBean Url: " + zimuUrl);
                                setSubtitle(zimuUrl); //设置字幕
                                if (searchSubtitleDialog != null) {
                                    searchSubtitleDialog.dismiss();
                                }
                            }
                        });
                    }
                });
              /*  EventBus.getDefault().register(searchSubtitleDialog);
                searchSubtitleDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                	@Override
                    public void onDismiss(DialogInterface dialog) {
                        EventBus.getDefault().unregister(dialog);
                    }
                });*/
                if (mVodInfo.playFlag.contains("Ali") || mVodInfo.playFlag.contains("parse")) {
                    String searchWord = (mVodInfo.playNote != null && !mVodInfo.playNote.isEmpty()) ? mVodInfo.playNote : mVodInfo.name;
                    searchSubtitleDialog.setSearchWord(searchWord);
                } else {
                    searchSubtitleDialog.setSearchWord(mVodInfo.name);
                }
                searchSubtitleDialog.show();
            }
        });
        subtitleDialog.setLocalFileChooserListener(new SubtitleDialog.LocalFileChooserListener() {
            @Override
            public void openLocalFileChooserDialog() {
                // Android 11+ (API 30+) 检查 MANAGE_EXTERNAL_STORAGE 特殊权限
                // 注意：只有当应用的 targetSdkVersion >= 30 时才需要检查此权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getActivity().getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        openSubtitleFileChooser();
                    } else {
                        new AlertDialog.Builder(getActivity())
                                .setTitle("权限提醒")
                                .setMessage("需要所有文件访问权限才能选择本地字幕。是否前往设置开启权限？")
                                .setPositiveButton("去设置", (dialog, which) -> {
                                    XXPermissions.startPermissionActivity(getActivity(), DefaultConfig.StoragePermissionGroup());
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                    return;
                }

                // Android 10 及以下，或者 targetSdkVersion < 30 的应用使用传统存储权限
                if (XXPermissions.isGranted(getContext(), DefaultConfig.StoragePermissionGroup())) {
                    openSubtitleFileChooser();
                } else {
                    XXPermissions.with(getActivity())
                        .permission(DefaultConfig.StoragePermissionGroup())
                        .request((permissions, allGranted) -> {
                            if (allGranted) {
                                openSubtitleFileChooser();
                            } else {
                                ToastHelper.showToast(mContext, getString(R.string.vod_sub_no_permission));
                            }
                        });
                }
            }

            private void openSubtitleFileChooser() {
                new ChooserDialog(getActivity())
                    .withFilter(false, false, "srt", "ass", "scc", "stl", "ttml")
                    .withStartFile("/storage/emulated/0/Download")
                    .withChosenListener(new ChooserDialog.Result() {
                        @Override
                        public void onChoosePath(String path, File pathFile) {
                            LOG.i("Local SubtitleBean Path: " + path);
                            setSubtitle(path); //设置字幕
                        }
                    })
                    .build()
                    .show();
            }
        });
        subtitleDialog.setCloseSubtitleListener(new SubtitleDialog.CloseSubtitleListener() {
            @Override
            public void closeSubtitle() {
                // 关闭字幕：清除字幕内容、停止字幕引擎、隐藏字幕视图
                if (mController.mSubtitleView != null) {
                    mController.mSubtitleView.destroy();
                    mController.mSubtitleView.clearSubtitleCache();
                    mController.mSubtitleView.setText("");
                    mController.mSubtitleView.setVisibility(View.GONE);
                }
                ToastHelper.showToast(mContext, getString(R.string.vod_sub_off));
            }
        });
        subtitleDialog.show();
    }

    void setSubtitleViewTextStyle() {
        SubtitleHelper.applyStyle(mController.mSubtitleView);
    }

    void selectMyInternalSubtitle() {
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = null;

        if (mediaPlayer instanceof EXOmPlayer) {
            trackInfo = ((EXOmPlayer) mediaPlayer).getTrackInfo();
        }
        if (mediaPlayer instanceof IjkmPlayer) {
            trackInfo = ((IjkmPlayer) mediaPlayer).getTrackInfo();
        }
        if (mediaPlayer instanceof AndroidMediaPlayer) {
            trackInfo = ((AndroidMediaPlayer) mediaPlayer).getTrackInfo();
        }

        if (trackInfo == null) {
            ToastHelper.showToast(mContext, getString(R.string.vod_sub_na));
            return;
        }

        List<TrackInfoBean> bean = trackInfo.getSubtitle();
        if (bean.size() < 1) {
            ToastHelper.showToast(mContext, getString(R.string.vod_sub_na));
            return;
        }
        SelectDialog<TrackInfoBean> dialog = new SelectDialog<>(getActivity());
        dialog.setTip(getString(R.string.vod_sub_sel));
        dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<TrackInfoBean>() {
            @Override
            public void click(TrackInfoBean value, int pos) {
                mController.mSubtitleView.setVisibility(View.VISIBLE);
                for (TrackInfoBean subtitle : bean) {
                    subtitle.selected = subtitle.trackId == value.trackId;
                }
                mediaPlayer.pause();
                long progress = mediaPlayer.getCurrentPosition();
                if (mediaPlayer instanceof IjkmPlayer) {
                    mController.mSubtitleView.destroy();
                    mController.mSubtitleView.clearSubtitleCache();
                    mController.mSubtitleView.isInternal = true;
                    ((IjkmPlayer) mediaPlayer).setTrack(value.trackId);
                    mTrackSwitchProgress = progress;
                    if (mHandler != null) {
                        mHandler.removeCallbacks(mTrackSwitchRunnable);
                        mHandler.postDelayed(mTrackSwitchRunnable, 800);
                    }
                }
                if (mediaPlayer instanceof EXOmPlayer) {
                    mController.mSubtitleView.destroy();
                    mController.mSubtitleView.clearSubtitleCache();
                    mController.mSubtitleView.isInternal = true;
                    ((EXOmPlayer) mediaPlayer).selectExoTrack(value);
                    mTrackSwitchProgress = progress;
                    if (mHandler != null) {
                        mHandler.removeCallbacks(mTrackSwitchRunnable);
                        mHandler.postDelayed(mTrackSwitchRunnable, 800);
                    }
                }
                if (mediaPlayer instanceof AndroidMediaPlayer) {
                    mController.mSubtitleView.destroy();
                    mController.mSubtitleView.clearSubtitleCache();
                    mController.mSubtitleView.isInternal = true;
                    ((AndroidMediaPlayer) mediaPlayer).setTrack(value.trackId);
                    mTrackSwitchProgress = progress;
                    mHandler.removeCallbacks(mTrackSwitchRunnable);
                    mHandler.postDelayed(mTrackSwitchRunnable, 800);
                }
                dialog.dismiss();
            }

            @Override
            public String getDisplay(TrackInfoBean val) {
                return val.name + (StringUtils.isEmpty(val.language) ? "" : " " + val.language);
            }
        }, new DiffUtil.ItemCallback<TrackInfoBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }
        }, bean, trackInfo.getSubtitleSelected(false));
        dialog.show();
    }

    void selectMyAudioTrack() {
        AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();

        TrackInfo trackInfo = null;
        if (mediaPlayer instanceof IjkmPlayer) {
            trackInfo = ((IjkmPlayer) mediaPlayer).getTrackInfo();
        }
        if (mediaPlayer instanceof EXOmPlayer) {
            trackInfo = ((EXOmPlayer) mediaPlayer).getTrackInfo();
        }
        if (mediaPlayer instanceof AndroidMediaPlayer) {
            trackInfo = ((AndroidMediaPlayer) mediaPlayer).getTrackInfo();
        }

        if (trackInfo == null) {
            ToastHelper.showToast(mContext, getString(R.string.vod_no_audio));
            return;
        }

        List<TrackInfoBean> bean = trackInfo.getAudio();
        if (bean.size() < 1) return;
        SelectDialog<TrackInfoBean> dialog = new SelectDialog<>(getActivity());
        dialog.setTip(getString(R.string.vod_audio));
        dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<TrackInfoBean>() {
            @Override
            public void click(TrackInfoBean value, int pos) {
                for (TrackInfoBean audio : bean) {
                    audio.selected = audio.trackId == value.trackId;
                }
                mediaPlayer.pause();
                long progress = mediaPlayer.getCurrentPosition();
                if (mediaPlayer instanceof IjkmPlayer) {
                    ((IjkmPlayer) mediaPlayer).setTrack(value.trackId);
                }
                if (mediaPlayer instanceof EXOmPlayer) {
                    ((EXOmPlayer) mediaPlayer).selectExoTrack(value);
                }
                if (mediaPlayer instanceof AndroidMediaPlayer) {
                    ((AndroidMediaPlayer) mediaPlayer).setTrack(value.trackId);
                }
                mTrackSwitchProgress = progress;
                if (mHandler != null) {
                    mHandler.removeCallbacks(mTrackSwitchRunnable);
                    mHandler.postDelayed(mTrackSwitchRunnable, 800);
                }
                dialog.dismiss();
            }

            @Override
            public String getDisplay(TrackInfoBean val) {
                String name = val.name.replace("AUDIO,", "");
                name = name.replace("N/A,", "");
                name = name.replace(" ", "");
                return name + (StringUtils.isEmpty(val.language) ? "" : " " + val.language);
            }
        }, new DiffUtil.ItemCallback<TrackInfoBean>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull TrackInfoBean oldItem, @NonNull @NotNull TrackInfoBean newItem) {
                return oldItem.trackId == newItem.trackId;
            }
        }, bean, trackInfo.getAudioSelected(false));
        dialog.show();
    }

    void openMyVideo() {
        Intent i = new Intent();
        i.addCategory(Intent.CATEGORY_DEFAULT);
        i.setAction(Intent.ACTION_VIEW);
        if (videoURL == null) return;
        i.setDataAndType(Uri.parse(videoURL), "video/*");
        startActivity(Intent.createChooser(i, "Open Video with ..."));
    }

    void showCastDialog() {
        if (videoURL == null || videoURL.isEmpty()) {
            Toast.makeText(requireContext(), "暂无播放内容", Toast.LENGTH_SHORT).show();
            return;
        }
        CastDialog dialog = new CastDialog(requireActivity());
        String episodeName = "";
        if (mVodInfo != null && mVodInfo.seriesMap != null && mVodInfo.playFlag != null) {
            List<VodInfo.VodSeries> seriesList = mVodInfo.seriesMap.get(mVodInfo.playFlag);
            if (seriesList != null && mVodInfo.playIndex >= 0 && mVodInfo.playIndex < seriesList.size()) {
                episodeName = seriesList.get(mVodInfo.playIndex).name;
            }
        }
        // 获取当前播放器类型
        int currentPlayerType = 2; // 默认Exo
        try {
            if (mVodPlayerCfg != null && mVodPlayerCfg.has("pl")) {
                currentPlayerType = mVodPlayerCfg.getInt("pl");
            }
        } catch (Exception e) {
            LOG.e(e);
        }

        // 获取弹幕数据（如果已加载）
        String danmakuData = null;
        boolean hasDanmaku = false;
        if (danmuText != null && !danmuText.isEmpty()) {
            // 获取弹幕 XML 内容（如果 danmuText 是 URL，则下载内容）
            danmakuData = Parser.getXmlContent(danmuText);
            hasDanmaku = danmakuData != null && !danmakuData.isEmpty() && !danmakuData.startsWith("http");
        }

        CastData castData = new CastData(
            videoURL,
            mVodInfo != null ? mVodInfo.name : "",
            mVideoView != null ? mVideoView.getCurrentPosition() : 0,
            mVideoView != null ? mVideoView.getDuration() : 0,
            mVideoView != null ? mVideoView.isPlaying() : false,
            null,
            mVodInfo != null ? mVodInfo.playIndex : 0,
            episodeName,
            currentPlayerType,
            danmakuData,
            hasDanmaku
        );
        dialog.setCastData(castData);
        // 设置投屏监听器，成功后暂停播放
        dialog.setOnCastListener(new CastDialog.OnCastListener() {
            @Override
            public void onCastSuccess(CastDevice device) {
                // 投屏成功，暂停本地播放
                if (mVideoView != null && mVideoView.isPlaying()) {
                    mVideoView.pause();
                }
            }

            @Override
            public void onCastFailed(CastDevice device, String error) {
                // 投屏失败，继续本地播放
            }
        });
        dialog.show();
    }

    void setTip(String msg, boolean loading, boolean err) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(new Runnable() { //影魔
            @Override
            public void run() {
                mPlayLoadTip.setText(msg);
                mPlayLoadTip.setVisibility(View.VISIBLE);
                mPlayLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
                mPlayLoadErr.setVisibility(err ? View.VISIBLE : View.GONE);
            }
        });
    }

    void hideTip() {
        mPlayLoadTip.setVisibility(View.GONE);
        mPlayLoading.setVisibility(View.GONE);
        mPlayLoadErr.setVisibility(View.GONE);
    }

    public boolean isReadyToPlay() {
        return mPlayLoadTip.getVisibility() == View.GONE && 
               mPlayLoading.getVisibility() == View.GONE && 
               mPlayLoadErr.getVisibility() == View.GONE &&
               mVideoView != null && 
               mVideoView.isPlaying();
    }

    // 允许显示的错误提示白名单
    private static final String[] ALLOWED_ERRORS = {
        "视频播放出错",
        "获取播放信息错误",
        "服务器已响应但解析错误",
        "解析错误：服务器未响应",
        "嗅探超时",
        "所有播放器均无法播放",
        "播放地址失效"
        // 注意："未知播放错误"是播放器内部错误，应该自动切换播放器，不在白名单中
    };

    void errorWithRetry(String err, boolean finish, boolean allowRetry) {
        // 检查错误是否在白名单中
        boolean isAllowed = false;
        for (String allowed : ALLOWED_ERRORS) {
            if (err.contains(allowed)) {
                isAllowed = true;
                break;
            }
        }

        if (isAllowed) {
            // 白名单错误：直接显示，不自动切换播放器
            if (!isAdded()) return;
            requireActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (finish) {
                        ToastHelper.showToast(mContext, err);
                    } else {
                        setTip(err, false, true);
                    }
                }
            });
        } else {
            // 非白名单错误：尝试自动切换播放器重试
            if (allowRetry && autoRetry()) {
                return;
            }
            // 自动切换未开启或所有播放器都切换失败后显示提示
            if (!isAdded()) return;
            requireActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!Hawk.get(HawkConfig.AUTO_SWITCH_PLAYER, false)) {
                        // 自动切换未开启，显示原始错误
                        if (finish) {
                            ToastHelper.showToast(mContext, err);
                        } else {
                            setTip(err, false, true);
                        }
                    } else {
                        // 所有播放器都切换失败
                        setTip("所有播放器均无法播放", false, true);
                    }
                }
            });
        }
    }

    void playUrl(String url, HashMap<String, String> headers) {
        if (!Hawk.get(HawkConfig.VIDEO_PURIFY, true)) {
            startPlayUrl(url, headers);
            return;
        }
        if (!url.contains("://127.0.0.1/") && !url.contains(".m3u8")) {
            startPlayUrl(url, headers);
            return;
        }
        OkGo.getInstance().cancelTag("m3u8-1");
        OkGo.getInstance().cancelTag("m3u8-2");
        //remove ads in m3u8
        HttpHeaders hheaders = new HttpHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> s : headers.entrySet()) {
                hheaders.put(s.getKey(), s.getValue());
            }
        }


        OkGo.<String>get(url)
                .tag("m3u8-1")
                .headers(hheaders)
                .execute(new AbsCallback<String>() {
                    @Override
                    public void onSuccess(Response<String> response) {
                        String content = response.body();
                        if (!content.startsWith("#EXTM3U")) {
                            startPlayUrl(url, headers);
                            return;
                        }

                        String[] lines = null;
                        if (content.contains("\r\n"))
                            lines = content.split("\r\n", 10);
                        else
                            lines = content.split("\n", 10);
                        String forwardurl = "";
                        boolean dealedFirst = false;
                        for (String line : lines) {
                            if (!"".equals(line) && line.charAt(0) != '#') {
                                if (dealedFirst) {
                                    //跳转行后还有内容，说明不需要跳转
                                    forwardurl = "";
                                    break;
                                }
                                if (line.endsWith(".m3u8") || line.contains(".m3u8?")) {
                                    if (line.startsWith("http://") || line.startsWith("https://")) {
                                        forwardurl = line;
                                    } else if (line.charAt(0) == '/') {
                                        int ifirst = url.indexOf('/', 9);//skip https://, http://
                                        forwardurl = url.substring(0, ifirst) + line;
                                    } else {
                                        int ilast = url.lastIndexOf('/');
                                        forwardurl = url.substring(0, ilast + 1) + line;
                                    }
                                }
                                dealedFirst = true;
                            }
                        }
                        if ("".equals(forwardurl)) {
                            int ilast = url.lastIndexOf('/');

                            RemoteServer.m3u8Content = M3U8.purify(url.substring(0, ilast + 1), content);
                            if (RemoteServer.m3u8Content == null)
                                startPlayUrl(url, headers);
                            else {
                                startPlayUrl("http://127.0.0.1:" + RemoteServer.serverPort + "/m3u8", headers);
                                //Toast.makeText(getContext(), "已移除视频广告", Toast.LENGTH_SHORT).show();
                            }
                            return;
                        }
                        final String finalforwardurl = forwardurl;
                        OkGo.<String>get(forwardurl)
                                .tag("m3u8-2")
                                .headers(hheaders)
                                .execute(new AbsCallback<String>() {
                                    @Override
                                    public void onSuccess(Response<String> response) {
                                        String content = response.body();
                                        int ilast = finalforwardurl.lastIndexOf('/');
                                        RemoteServer.m3u8Content = M3U8.purify(finalforwardurl.substring(0, ilast + 1), content);

                                        if (RemoteServer.m3u8Content == null)
                                            startPlayUrl(finalforwardurl, headers);
                                        else {
                                            startPlayUrl("http://127.0.0.1:" + RemoteServer.serverPort + "/m3u8", headers);
                                            //Toast.makeText(getContext(), "已移除视频广告", Toast.LENGTH_SHORT).show();
                                        }
                                    }

                                    @Override
                                    public String convertResponse(okhttp3.Response response) throws Throwable {
                                        return response.body().string();
                                    }

                                    @Override
                                    public void onError(Response<String> response) {
                                        super.onError(response);
                                        startPlayUrl(url, headers);
                                    }
                                });
                    }

                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        return response.body().string();
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        startPlayUrl(url, headers);
                    }
                });
    }

    void startPlayUrl(String url, HashMap<String, String> headers) {
        if (!isAdded()) return;
        final String finalUrl = url;
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stopParse();
                if (mVideoView != null) {
                    mVideoView.release();
                }
                if (finalUrl != null) {
                    String url = finalUrl;
                    videoURL = url;
                    videoHeaders = headers;
                    try {
                        int playerType = mVodPlayerCfg.getInt("pl");
                        // takagen99: Check for External Player
                        extPlay = false;
                        if (playerType >= 10) {
                            VodInfo.VodSeries vs = mVodInfo.seriesMap.get(mVodInfo.playFlag).get(mVodInfo.playIndex);
                            String playTitle = mVodInfo.name + " : " + vs.name;
                            setTip("调用外部播放器" + PlayerHelper.getPlayerName(playerType) + "进行播放", true, false);
                            boolean callResult = false;
                            switch (playerType) {
                                case 10: {
                                    extPlay = true;
                                    callResult = MXPlayer.run(requireActivity(), url, playTitle, playSubtitle, headers);
                                    break;
                                }
                                case 11: {
                                    extPlay = true;
                                    callResult = ReexPlayer.run(requireActivity(), url, playTitle, playSubtitle, headers);
                                    break;
                                }
                                case 12: {
                                    extPlay = true;
                                    callResult = Kodi.run(requireActivity(), url, playTitle, playSubtitle, headers);
                                    break;
                                }
                            }
                            setTip("调用外部播放器" + PlayerHelper.getPlayerName(playerType) + (callResult ? "成功" : "失败"), callResult, !callResult);
                            return;
                        }
                    } catch (JSONException e) {
                        LOG.e(e);
                    }
                    hideTip();
                    if (url.startsWith("data:application/dash+xml;base64,")) {
                        PlayerHelper.updateCfg(mVideoView, mVodPlayerCfg, 2);
                        App.getInstance().setDashData(url.split("base64,")[1]);
                        url = ControlManager.get().getAddress(true) + "dash/proxy.mpd";
                    } else if (url.contains(".mpd") || url.contains("type=mpd")) {
                        PlayerHelper.updateCfg(mVideoView, mVodPlayerCfg, 2);
                    } else {
                        PlayerHelper.updateCfg(mVideoView, mVodPlayerCfg);
                    }
                    mVideoView.setProgressKey(progressKey);
                    if (headers != null) {
                        mVideoView.setUrl(url, headers);
                    } else {
                        mVideoView.setUrl(url);
                    }
                    mVideoView.start();
                    mController.resetSpeed();
                    // 启动播放超时检测
                    startPlayTimeoutCheck();
                }
            }
        });
    }

    private void initSubtitleView() {
    	AbstractPlayer mediaPlayer = mVideoView.getMediaPlayer();
        TrackInfo trackInfo = null;
        if (mVideoView.getMediaPlayer() instanceof IjkmPlayer) {
            trackInfo = ((IjkmPlayer) (mVideoView.getMediaPlayer())).getTrackInfo();
            if (trackInfo != null && trackInfo.getSubtitle().size() > 0) {
                mController.mSubtitleView.hasInternal = true;
            }
            ((IjkmPlayer) (mVideoView.getMediaPlayer())).setOnTimedTextListener(new IMediaPlayer.OnTimedTextListener() {
                @Override
                public void onTimedText(IMediaPlayer mp, IjkTimedText text) {
                    if (text != null && mController.mSubtitleView.isInternal) {
                        Subtitle subtitle = new Subtitle();
                        subtitle.content = text.getText();
                        mController.mSubtitleView.onSubtitleChanged(subtitle);
                    }
                }
            });
        }

        if (mVideoView.getMediaPlayer() instanceof EXOmPlayer) {
            trackInfo = ((EXOmPlayer) (mVideoView.getMediaPlayer())).getTrackInfo();
            if (trackInfo != null && trackInfo.getSubtitle().size() > 0) {
                mController.mSubtitleView.hasInternal = true;
            }
            ((EXOmPlayer) (mVideoView.getMediaPlayer())).setOnTimedTextListener(new Player.Listener() {
                @Override
                public void onCues(@NonNull List<Cue> cues) {
                    if (cues.size() > 0) {
                        CharSequence ss = cues.get(0).text;
                        if (ss != null && mController.mSubtitleView.isInternal) {
                            Subtitle subtitle = new Subtitle();
                            subtitle.content = ss.toString();
                            mController.mSubtitleView.onSubtitleChanged(subtitle);
                        }
                    }else{
                        Subtitle subtitle = new Subtitle();
                        subtitle.content = "";
                        mController.mSubtitleView.onSubtitleChanged(subtitle);
                    }
                }
            });
        }

        if (mVideoView.getMediaPlayer() instanceof AndroidMediaPlayer) {
            trackInfo = ((AndroidMediaPlayer) (mVideoView.getMediaPlayer())).getTrackInfo();
            if (trackInfo != null && trackInfo.getSubtitle().size() > 0) {
                mController.mSubtitleView.hasInternal = true;
            }
            ((AndroidMediaPlayer) (mVideoView.getMediaPlayer())).setOnTimedTextListener(new MediaPlayer.OnTimedTextListener() {
                @Override
                public void onTimedText(MediaPlayer mp, android.media.TimedText text) {
                    if (text != null && mController.mSubtitleView.isInternal) {
                        Subtitle subtitle = new Subtitle();
                        subtitle.content = text.getText();
                        mController.mSubtitleView.onSubtitleChanged(subtitle);
                    }
                }
            });
        }

        mController.mSubtitleView.bindToMediaPlayer(mVideoView.getMediaPlayer());
        mController.mSubtitleView.setPlaySubtitleCacheKey(subtitleCacheKey);
        String subtitlePathCache = (String) CacheManager.getCache(MD5.string2MD5(subtitleCacheKey));
        if (subtitlePathCache != null && !subtitlePathCache.isEmpty()) {
            mController.mSubtitleView.setSubtitlePath(subtitlePathCache);
        } else {
            if (playSubtitle != null && playSubtitle.length() > 0) {
                mController.mSubtitleView.setSubtitlePath(playSubtitle);
            } else {
                if (mController.mSubtitleView.hasInternal) {
                    mController.mSubtitleView.isInternal = true;
                    if (mediaPlayer instanceof IjkmPlayer) {
                        if (trackInfo != null && trackInfo.getSubtitle()
                            .size() > 0) {
                            List < TrackInfoBean > subtitleTrackList = trackInfo.getSubtitle();
                            int selectedIndex = trackInfo.getSubtitleSelected(true);
                            boolean hasCh = false;
                            for (TrackInfoBean subtitleTrackInfoBean: subtitleTrackList) {
                                String lowerLang = subtitleTrackInfoBean.language.toLowerCase();
                                if (lowerLang.contains("zh") || lowerLang.contains("ch")) {
                                    hasCh = true;
                                    if (selectedIndex != subtitleTrackInfoBean.trackId) {
                                        ((IjkmPlayer)(mVideoView.getMediaPlayer()))
                                            .setTrack(subtitleTrackInfoBean.trackId);
                                        break;
                                    }
                                }
                            }
                            if (!hasCh)((IjkmPlayer)(mVideoView.getMediaPlayer()))
                                .setTrack(subtitleTrackList.get(0)
                                .trackId);
                        }
                    }
                }
            }
        }
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.playResult.observeForever(mObserverPlayResult);
    }

    private final Observer<JSONObject> mObserverPlayResult= new Observer<JSONObject>() {
        @Override
        public void onChanged(JSONObject info) {
            if (!isAdded() || isDetached()) return;
            if (info != null) {
                try {
                    progressKey = info.optString("proKey", null);
                    boolean parse = info.optString("parse", "1").equals("1");
                    boolean jx = info.optString("jx", "0").equals("1");
                    playSubtitle = info.optString("subt", "");
                    if (playSubtitle.isEmpty() && info.has("subs")) {
                        try {
                            JSONObject obj = info.getJSONArray("subs").optJSONObject(0);
                            String url = obj.optString("url", "");
                            if (!TextUtils.isEmpty(url) && !FileUtils.hasExtension(url)) {
                                String format = obj.optString("format", "");
                                String name = obj.optString("name", "字幕");
                                String ext = ".srt";
                                switch (format) {
                                    case "text/x-ssa":
                                        ext = ".ass";
                                        break;
                                    case "text/vtt":
                                        ext = ".vtt";
                                        break;
                                    case "application/x-subrip":
                                        ext = ".srt";
                                        break;
                                    case "text/lrc":
                                        ext = ".lrc";
                                        break;
                                }
                                String filename = name + (name.toLowerCase().endsWith(ext) ? "" : ext);
                                url += "#" + URLEncoder.encode(filename);
                            }
                            playSubtitle = url;
                        } catch (Throwable th) {}
                    }
                    subtitleCacheKey = info.optString("subtKey", null);
                    String playUrl = info.optString("playUrl", "");
                    String msg = info.optString("msg", "");
                    if (!msg.isEmpty()) {
                        ToastHelper.showToast(mContext, msg);
                    }
                    String flag = info.optString("flag");
                    String url = info.getString("url");
                    String danmaku = info.optString("danmaku");
                    // 修复Jar爬虫首次安装时弹幕URL端口为-1的问题
                    if (danmaku != null && danmaku.contains("127.0.0.1:-1")) {
                        danmaku = danmaku.replace("127.0.0.1:-1", "127.0.0.1:" + RemoteServer.serverPort);
                    }
                    HashMap<String, String> headers = null;
                    webUserAgent = null;
                    webHeaderMap = null;
                    if (info.has("header")) {
                        try {
                            JSONObject hds = new JSONObject(info.getString("header"));
                            Iterator<String> keys = hds.keys();
                            while (keys.hasNext()) {
                                String key = keys.next();
                                if (headers == null) {
                                    headers = new HashMap<>();
                                }
                                headers.put(key, hds.getString(key));
                                if (key.equalsIgnoreCase("user-agent")) {
                                    webUserAgent = hds.getString(key).trim();
                                } else if (key.equalsIgnoreCase("cookie")) {
                                    for (String split : hds.getString(key).split(";"))
                                        CookieManager.getInstance().setCookie(url, split.trim());
                                }
                            }
                            webHeaderMap = headers;
                        } catch (Throwable th) {

                        }
                    }
                    if (parse || jx) {
                        boolean userJxList = (playUrl.isEmpty() && ApiConfig.get().getVipParseFlags().contains(flag)) || jx;
                        initParse(flag, userJxList, playUrl, url);
                    } else {
                        mController.showParse(false);
                        playUrl(playUrl + url, headers);
                    }
                    checkDanmu(danmaku);
                } catch (Throwable th) {
                    errorWithRetry("获取播放信息错误", true, false);
                }
            } else {
                errorWithRetry("获取播放信息错误", true, false);
            }
        }        
    };

    private boolean danmuLoaded = false;

    private void checkDanmu(String danmu) {
        danmuText = danmu;
        danmuLoaded = false;
        // 根据弹幕开关设置弹幕视图可见性（默认关闭）
        mDanmuView.setVisibility(TextUtils.isEmpty(danmuText) || !HawkUtils.getDanmuOpen() ? View.GONE : View.VISIBLE);
        // 只要有弹幕数据就显示按钮，无论是否开启
        if (TextUtils.isEmpty(danmuText)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && mActivity != null && mActivity.isInPictureInPictureMode())) {
            return;
        }
        if (!danmuText.isEmpty()) {
            mController.setHasDanmu(true);
            // 如果弹幕开关已打开且视频正在播放，立即加载弹幕
            if (HawkUtils.getDanmuOpen() && mVideoView != null && mVideoView.isPlaying()) {
                loadDanmuWhenPlaying();
            }
        }
    }

    private void loadDanmuWhenPlaying() {
        // 视频成功播放后加载弹幕
        if (!danmuLoaded && HawkUtils.getDanmuOpen() && mDanmuView != null && danmuText != null && !danmuText.isEmpty()) {
            danmuLoaded = true;
            setDanmuViewSettings(true);
        }
    }

    private static final int DANMU_PREPARE_RETRY_DELAY = 100;
    private static final int DANMU_PREPARE_MAX_RETRY = 50; // 最多重试5秒
    private int danmuPrepareRetryCount = 0;

    private void startDanmuWhenPrepared() {
        if (mDanmuView == null || mVideoView == null) {
            danmuPrepareRetryCount = 0;
            return;
        }
        if (mDanmuView.isPrepared()) {
            mDanmuView.start(mVideoView.getCurrentPosition());
            danmuPrepareRetryCount = 0;
        } else if (danmuPrepareRetryCount < DANMU_PREPARE_MAX_RETRY) {
            // 弹幕还未准备好，延迟重试
            danmuPrepareRetryCount++;
            mHandler.postDelayed(() -> {
                if (mActivity != null && !mActivity.isFinishing() && mDanmuView != null && HawkUtils.getDanmuOpen()) {
                    startDanmuWhenPrepared();
                } else {
                    danmuPrepareRetryCount = 0;
                }
            }, DANMU_PREPARE_RETRY_DELAY);
        } else {
            // 超过最大重试次数，重置计数器
            danmuPrepareRetryCount = 0;
        }
    }

    public void setData(Bundle bundle) {
        if (bundle == null) return;
        mVodInfo = (VodInfo) bundle.getSerializable("VodInfo");
        sourceKey = bundle.getString("sourceKey");
        if (mVodInfo == null) return;
        sourceBean = ApiConfig.get().getSource(sourceKey);
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null); // 取消所有延迟任务，避免之前的自动重试任务干扰
        }
        initPlayerCfg();
        play(false);
    }
    
    public void updateVodInfo(VodInfo vodInfo) {
        if (mVodInfo != null && vodInfo != null) {
            mVodInfo.playEpisodeIndex = vodInfo.playEpisodeIndex;
            mVodInfo.playIndex = vodInfo.playIndex;
            mVodInfo.playGroup = vodInfo.playGroup;
            mVodInfo.playGroupCount = vodInfo.playGroupCount;
            mVodInfo.reverseSort = vodInfo.reverseSort;
            if (vodInfo.seriesMap != null) {
                mVodInfo.seriesMap = new LinkedHashMap<>();
                for (Map.Entry<String, List<VodInfo.VodSeries>> entry : vodInfo.seriesMap.entrySet()) {
                    List<VodInfo.VodSeries> seriesList = new ArrayList<>();
                    for (VodInfo.VodSeries series : entry.getValue()) {
                        VodInfo.VodSeries newSeries = new VodInfo.VodSeries();
                        newSeries.name = series.name;
                        newSeries.url = series.url;
                        newSeries.episodeId = series.episodeId;
                        newSeries.selected = series.selected;
                        seriesList.add(newSeries);
                    }
                    mVodInfo.seriesMap.put(entry.getKey(), seriesList);
                }
            }
        }
    }

    public VodInfo getVodInfo() {
        return mVodInfo;
    }

    private void initData() {
    }

    void initPlayerCfg() {
        try {
            mVodPlayerCfg = new JSONObject(mVodInfo.playerCfg);
        } catch (Throwable th) {
            mVodPlayerCfg = new JSONObject();
        }
        try {
            int playType;
            boolean configurationFile = HawkUtils.getVodPlayerPreferredConfigurationFile();
            int playerType = sourceBean != null ? sourceBean.getPlayerType() : -1;
            if (configurationFile && playerType != -1) {
                // 检查资源站配置的播放器类型是否合法（0=系统, 1=IJK, 2=Exo）
                // 如果不合法，默认使用 Exo(2)
                if (playerType >= 0 && playerType <= 2) {
                    playType = playerType;
                } else {
                    playType = 2;  // 默认 Exo
                }
            } else {
                int vodPlayerPreferred = HawkUtils.getVodPlayerPreferred();
                if (vodPlayerPreferred == 0) {
                    playType = 2;
                } else {
                    // 映射关系：1=系统(0), 2=IJK(1), 3=Exo(2), 4=MX(10), 5=Reex(11), 6=Kodi(12)
                    playType = vodPlayerPreferred - 1;
                    if (playType >= 3) {
                        playType += 7;  // 3→10, 4→11, 5→12
                    }
                }
            }
            mVodPlayerCfg.put("pl", playType);
            defaultPlayerType = playType;  // 设置默认播放器类型
            
            // 检查是否已有来源标记（从保存的配置中读取）
            String savedSource = mVodPlayerCfg.optString("ps", "");
            boolean isUserSelected = "user".equals(savedSource);
            
            if (isUserSelected) {
                // 用户之前手动选择过，保持用户指定
                configPlayerType = playType;
                userSelectedPlayerType = playType;
                playerSource = "用户指定";
                mController.setPlayerSource("用户指定");
            } else if (configurationFile && playerType != -1) {
                // 配置地址指定且资源站配置了播放器类型
                configPlayerType = playType;
                userSelectedPlayerType = -1;
                playerSource = "资源指定";
                mController.setPlayerSource("资源指定");
                mVodPlayerCfg.put("ps", "resource");
            } else if (playerType == -1) {
                // 资源站没有配置播放器类型，使用自动选择
                configPlayerType = playType;
                userSelectedPlayerType = -1;
                playerSource = "自动";
                mController.setPlayerSource("自动");
                mVodPlayerCfg.put("ps", "auto");
            } else {
                // 用户通过设置偏好指定的播放器
                configPlayerType = playType;
                userSelectedPlayerType = playType;
                playerSource = "用户指定";
                mController.setPlayerSource("用户指定");
                mVodPlayerCfg.put("ps", "user");
            }
            if (!mVodPlayerCfg.has("pr")) {
                mVodPlayerCfg.put("pr", Hawk.get(HawkConfig.PLAY_RENDER, 0));
            }
            if (!mVodPlayerCfg.has("ijk")) {
                mVodPlayerCfg.put("ijk", Hawk.get(HawkConfig.IJK_CODEC, ""));
            }
            if (!mVodPlayerCfg.has("sc")) {
                mVodPlayerCfg.put("sc", Hawk.get(HawkConfig.PLAY_SCALE, 0));
            }
            if (!mVodPlayerCfg.has("sp")) {
                mVodPlayerCfg.put("sp", 1.0f);
            }
            if (!mVodPlayerCfg.has("st")) {
                mVodPlayerCfg.put("st", 0);
            }
            if (!mVodPlayerCfg.has("et")) {
                mVodPlayerCfg.put("et", 0);
            }
        } catch (Throwable th) {

        }
        mController.setPlayerConfig(mVodPlayerCfg);
    }

    void initPlayerDrive() {
        try {
            if (!mVodPlayerCfg.has("pl")) {
                mVodPlayerCfg.put("pl", Hawk.get(HawkConfig.PLAY_TYPE, 1));
            }
            defaultPlayerType = mVodPlayerCfg.getInt("pl");
            if (!mVodPlayerCfg.has("pr")) {
                mVodPlayerCfg.put("pr", Hawk.get(HawkConfig.PLAY_RENDER, 0));
            }
            if (!mVodPlayerCfg.has("ijk")) {
                mVodPlayerCfg.put("ijk", Hawk.get(HawkConfig.IJK_CODEC, ""));
            }
            if (!mVodPlayerCfg.has("sc")) {
                mVodPlayerCfg.put("sc", Hawk.get(HawkConfig.PLAY_SCALE, 0));
            }
            if (!mVodPlayerCfg.has("sp")) {
                mVodPlayerCfg.put("sp", 1.0f);
            }
            if (!mVodPlayerCfg.has("st")) {
                mVodPlayerCfg.put("st", 0);
            }
            if (!mVodPlayerCfg.has("et")) {
                mVodPlayerCfg.put("et", 0);
            }
        } catch (Throwable th) {

        }
        mController.setPlayerConfig(mVodPlayerCfg);
    }

    public boolean onBackPressed() {
        if (mController != null) {
            return mController.onBackPressed();
        }
        return false;
    }

    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && mController != null) {
            return mController.onKeyEvent(event);
        }
        return false;
    }

    // takagen99 : Picture-in-Picture support
    public boolean extPlay;
    
    @Override
    public void onPause() {
        super.onPause();
        if (mVideoView != null) {
            VodController controller = getVodController();
            if (controller != null && controller.mProgressTop != null) {
                controller.mProgressTop.setAlpha(0);
            }
            mVideoView.pause();
        }
        if (mHandler != null) {
            mHandler.removeCallbacks(mTrackSwitchRunnable);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mVideoView != null) {
            VodController controller = getVodController();
            if (controller != null && controller.mProgressTop != null) {
                controller.mProgressTop.setAlpha(1);
            }
            mVideoView.resume();
        }
    }

    private int getCurrentSourceEpisodes(VodInfo vodInfo) {
        if (vodInfo == null || vodInfo.seriesMap == null || vodInfo.playFlag == null) {
            return 0;
        }
        List<VodInfo.VodSeries> seriesList = vodInfo.seriesMap.get(vodInfo.playFlag);
        return seriesList != null ? seriesList.size() : 0;
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) {
            if (mVideoView != null) {
                mVideoView.pause();
            }
        } else {
            if (mVideoView != null) {
                mVideoView.resume();
            }
        }
        super.onHiddenChanged(hidden);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 先清理Handler消息，防止在清理过程中继续处理
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }
        // 注销EventBus,防止在清理过程中继续接收事件
        try {
            EventBus.getDefault().unregister(this);
        } catch (Throwable th) {
        }
        if(executorService!=null){
            executorService.shutdownNow();
            executorService = null;
        }
        if (parseThreadPool != null) {
            parseThreadPool.shutdownNow();
            parseThreadPool = null;
        }
        if (mDanmuView != null) {
            try {
                mDanmuView.release();
            } catch (Throwable th) {
            }
            mDanmuView = null;
        }
        if (mVideoView != null) {
            mVideoView.setProgressManager(null);
            try {
                mVideoView.release();
            } catch (Throwable th) {
            }
            mVideoView = null;
        }
        stopLoadWebView(true);
        stopParse();
        Thunder.stop(true);
        Jianpian.finish();
        if (sourceViewModel != null && mObserverPlayResult != null) {
            sourceViewModel.playResult.removeObserver(mObserverPlayResult);
        }
    }
    
    public MyVideoView getPlayer() {
        return mVideoView;
    }

    /**
     * 清理 Handler 的所有消息，防止在 Fragment 销毁后继续处理
     */
    public void clearHandlerMessages() {
        if (mHandler != null) {
            try {
                mHandler.removeCallbacksAndMessages(null);
            } catch (Exception e) {
            }
        }
    }

    private VodInfo mVodInfo;
    private JSONObject mVodPlayerCfg;
    private String sourceKey;
    private SourceBean sourceBean;

    public void playNext(boolean inProgress) {
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null); // 取消所有延迟任务，避免之前的自动重试任务干扰
        }
        if (mVodInfo == null || mVodInfo.seriesMap == null || mVodInfo.playFlag == null) {
            return;
        }
        List<VodInfo.VodSeries> seriesList = mVodInfo.seriesMap.get(mVodInfo.playFlag);
        if (seriesList == null) {
            return;
        }
        boolean hasNext = mVodInfo.playEpisodeIndex + 1 < seriesList.size();
        if (!hasNext) {
            if (mVodInfo.reverseSort) {
                ToastHelper.showToast("已经是倒序的第一集了");
            } else {
                ToastHelper.showToast("已经是正序的最新一集了");
            }
            // takagen99: To auto go back to Detail Page after last episode
            if (inProgress && mActivity instanceof DetailActivity) {
                DetailActivity detailActivity = (DetailActivity) mActivity;
                // 检查DetailActivity是否正在销毁
                if (!detailActivity.isFinishing() && !detailActivity.isDestroyed()) {
                    if (detailActivity.fullWindows) {
                        detailActivity.toggleFullPreview();
                    }
                }
            }
            return;
        }
        mVodInfo.playEpisodeIndex++;
        int groupCount = Math.max(mVodInfo.playGroupCount, 1);
        mVodInfo.playGroup = mVodInfo.playEpisodeIndex / groupCount;
        mVodInfo.playIndex = mVodInfo.playEpisodeIndex % groupCount;
        play(false);
    }

    public void playPrevious() {
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null); // 取消所有延迟任务，避免之前的自动重试任务干扰
        }
        if (mVodInfo == null || mVodInfo.seriesMap == null || mVodInfo.playFlag == null) {
            return;
        }
        List<VodInfo.VodSeries> seriesList = mVodInfo.seriesMap.get(mVodInfo.playFlag);
        if (seriesList == null) {
            return;
        }
        boolean hasPre = mVodInfo.playEpisodeIndex - 1 >= 0;
        if (!hasPre) {
            if (mVodInfo.reverseSort) {
                ToastHelper.showToast("已经是倒序的最新一集了");
            } else {
                ToastHelper.showToast("已经是正序的第一集了");
            }
            return;
        }
        mVodInfo.playEpisodeIndex--;
        int groupCount = Math.max(mVodInfo.playGroupCount, 1);
        mVodInfo.playGroup = mVodInfo.playEpisodeIndex / groupCount;
        mVodInfo.playIndex = mVodInfo.playEpisodeIndex % groupCount;
        play(false);
    }

    private int autoRetryCount = 0;
    private int defaultPlayerType = 1;
    private int configPlayerType = 1;
    private int userSelectedPlayerType = -1;
    private String playerSource = "配置";

    // 播放超时检测
    private Runnable playTimeoutRunnable = null;

    // 根据缓冲模式获取超时时间
    private int getPlayTimeoutMs() {
        // 获取当前播放器类型：0=系统, 1=IJK, 2=Exo
        int playerType = mVodPlayerCfg.optInt("pl", 2);

        if (playerType == 1) {
            // IJK播放器 - 5种模式：0默认, 1API, 2流畅, 3均衡, 4原画
            int bufferMode = Hawk.get(HawkConfig.BUFFER_MODE, 3);
            switch (bufferMode) {
                case 4: // 原画模式
                    return 45000; // 45秒
                case 3: // 均衡模式
                    return 30000; // 30秒
                case 2: // 流畅模式
                    return 20000; // 20秒
                case 1: // API配置
                case 0: // 默认内置
                default:
                    return 25000; // 25秒
            }
        } else if (playerType == 2) {
            // Exo播放器 - 4种模式：0默认, 1流畅, 2均衡, 3原画
            int bufferMode = Hawk.get(HawkConfig.BUFFER_MODE_EXO, 2);
            switch (bufferMode) {
                case 3: // 原画模式
                    return 45000; // 45秒
                case 2: // 均衡模式
                    return 30000; // 30秒
                case 1: // 流畅模式
                    return 20000; // 20秒
                case 0: // 默认内置
                default:
                    return 25000; // 25秒
            }
        } else {
            // 系统播放器或其他
            return 25000; // 25秒
        }
    }

    boolean autoRetry() {
        // 自动切换播放器未开启，直接返回false
        if (!Hawk.get(HawkConfig.AUTO_SWITCH_PLAYER, false)) {
            return false;
        }
        // 三个播放器循环切换：0→1→2→0，共2次切换尝试
        int maxRetry = 2;
        // 先检查是否还有备用视频URL可尝试
        if (loadFoundVideoUrls != null && loadFoundVideoUrls.size() > 0) {
            // 不重置autoRetryCount，继续尝试备用URL
            autoRetryFromLoadFoundVideoUrls();
            return true;
        }
        if (autoRetryCount < maxRetry) {
            // 先释放当前播放器，确保切换前状态干净
            if (mVideoView != null) {
                try {
                    mVideoView.release();
                } catch (Throwable th) {
                }
            }
            switchPlayer();
            autoRetryCount++;
            // 取消之前的超时检测
            cancelPlayTimeoutCheck();
            // 获取当前切换到的播放器名称
            String playerName = PlayerHelper.getPlayerName(mVodPlayerCfg.optInt("pl", 1));
            int timeoutSec = getPlayTimeoutMs() / 1000;
            setTip("      自动切换 " + playerName + " 播放器重试\n(如果卡加载黑屏" + timeoutSec + "秒会自动超时)", true, false);
            // 延迟3秒后切换，给播放器更多初始化时间
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (isAdded()) {
                        // 使用已保存的播放地址直接播放，避免重新解析
                        if (videoURL != null && !videoURL.isEmpty()) {
                            startPlayUrl(videoURL, videoHeaders);
                        } else {
                            // 如果没有保存的地址，说明解析异常，直接报错
                            errorWithRetry("播放地址失效", false, true);
                        }
                    }
                }
            }, 3000);
            return true;
        } else {
            autoRetryCount = maxRetry;
            try {
                // 所有播放器都失败后，恢复到默认的配置指定或用户指定播放器
                mVodPlayerCfg.put("pl", defaultPlayerType);
                // 恢复来源标记
                String savedSource = mVodPlayerCfg.optString("ps", "");
                mController.setPlayerConfig(mVodPlayerCfg);
                if ("user".equals(savedSource)) {
                    mController.setPlayerSource("用户指定");
                } else if ("resource".equals(savedSource)) {
                    mController.setPlayerSource("资源指定");
                } else {
                    mController.setPlayerSource("自动");
                }
                mVodInfo.playerCfg = mVodPlayerCfg.toString();
                // 确保在 Fragment 有效状态下发送事件
                if (isAdded()) {
                    EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodPlayerCfg));
                }
            } catch (Exception e) {
            }
            return false;
        }
    }

    // 启动播放超时检测
    void startPlayTimeoutCheck() {
        // 自动切换播放器未开启，不启动超时检测
        if (!Hawk.get(HawkConfig.AUTO_SWITCH_PLAYER, false)) {
            return;
        }
        cancelPlayTimeoutCheck();
        playTimeoutRunnable = new Runnable() {
            @Override
            public void run() {
                // 播放超时，切换到下一个播放器
                if (isAdded()) {
                    autoRetry();
                }
            }
        };
        int timeoutMs = getPlayTimeoutMs();
        mHandler.postDelayed(playTimeoutRunnable, timeoutMs);
    }

    // 取消播放超时检测
    void cancelPlayTimeoutCheck() {
        if (playTimeoutRunnable != null) {
            mHandler.removeCallbacks(playTimeoutRunnable);
            playTimeoutRunnable = null;
        }
    }

    void switchPlayer() {
        try {
            int currentPlayerType = mVodPlayerCfg.getInt("pl");
            int nextPlayerType;

            // 三个播放器循环切换：0(系统)→1(IJK)→2(Exo)→0(系统)
            if (currentPlayerType == 0) {
                nextPlayerType = 1;  // 系统→IJK
            } else if (currentPlayerType == 1) {
                nextPlayerType = 2;  // IJK→Exo
            } else {
                nextPlayerType = 0;  // Exo→系统
            }

            mVodPlayerCfg.put("pl", nextPlayerType);
            mVodPlayerCfg.put("ps", "auto");
            mController.setPlayerConfig(mVodPlayerCfg);
            playerSource = "自动";
            mController.setPlayerSource("自动");
            mVodInfo.playerCfg = mVodPlayerCfg.toString();
            // 确保在 Fragment 有效状态下发送事件
            if (isAdded()) {
                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodPlayerCfg));
            }
        } catch (Exception e) {
        }
    }

    void autoRetryFromLoadFoundVideoUrls() {
        String videoUrl = loadFoundVideoUrls.poll();
        if (videoUrl != null) {
            HashMap<String, String> header = loadFoundVideoUrlsHeader.get(videoUrl);
            playUrl(videoUrl, header);
        }
    }

    void initParseLoadFound() {
        loadFoundCount.set(0);
        loadFoundVideoUrls = new LinkedList<String>();
        loadFoundVideoUrlsHeader = new HashMap<String, HashMap<String, String>>();
    }
    public void setPlayTitle(boolean show)
    {
        if(show){
            String playTitleInfo= "";
            if(mVodInfo!=null && mVodInfo.seriesMap!=null && mVodInfo.playFlag!=null){
                List<VodInfo.VodSeries> seriesList = mVodInfo.seriesMap.get(mVodInfo.playFlag);
                if(seriesList!=null && mVodInfo.playIndex>=0 && mVodInfo.playIndex<seriesList.size()){
                    VodInfo.VodSeries series = seriesList.get(mVodInfo.playIndex);
                    if(series!=null){
                        playTitleInfo = mVodInfo.name + " " + series.name;
                    }
                }
            }
            mController.setTitle(playTitleInfo);
        }else {
            mController.setTitle("");
        }
    }
    public void play(boolean reset) {
        // 防止在 Fragment 销毁后继续操作
        if (mVodInfo == null || !isAdded() || isDetached()) return;
        // 取消之前的超时检测，避免换集时触发错误的切换
        cancelPlayTimeoutCheck();
        // 重置自动重试计数，开始新的播放流程
        autoRetryCount = 0;
        historySaved = false;
        if (mActivity instanceof DetailActivity) {
            ((DetailActivity) mActivity).setPlaySuccess(false);
        }
        // 重置已保存的播放地址，避免换集或切换路线时使用旧地址
        videoURL = null;
        videoHeaders = null;
        videoSegmentationURL.clear();
        // 重置弹幕加载状态（注意：不重置danmuText，因为它是动态加载的）
        danmuLoaded = false;
        // 清除字幕显示，避免上一集字幕残留
        if (mController != null && mController.mSubtitleView != null) {
            mController.mSubtitleView.setText("");
        }
        if (mVodInfo.seriesMap == null || mVodInfo.playFlag == null || !mVodInfo.seriesMap.containsKey(mVodInfo.playFlag)) {
            return;
        }
        List<VodInfo.VodSeries> seriesList = mVodInfo.seriesMap.get(mVodInfo.playFlag);
        if (seriesList == null || mVodInfo.playEpisodeIndex < 0 || mVodInfo.playEpisodeIndex >= seriesList.size()) {
            return;
        }
        VodInfo.VodSeries vs = seriesList.get(mVodInfo.playEpisodeIndex);
        if (vs == null) return;
        // 确保在 Fragment 有效状态下发送事件
        if (getActivity() != null && !getActivity().isFinishing() && !getActivity().isDestroyed()) {
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH, mVodInfo.playEpisodeIndex));
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_REFRESH_NOTIFY, mVodInfo.name + "&&" + vs.name));
        }
        String playTitleInfo = mVodInfo.name + " : " + vs.name;
        setTip("正在获取播放信息", true, false);
        if (mController != null) {
            mController.setTitle(playTitleInfo);
        }
        RemoteServer.vodName = mVodInfo.name;
        RemoteServer.artist = vs.name;

        stopParse();
        initParseLoadFound();
        if (mVideoView != null) {
            try {
                mVideoView.release();
            } catch (Throwable th) {
            }
        }
        subtitleCacheKey = mVodInfo.sourceKey + "-" + mVodInfo.id + "-" + mVodInfo.playFlag + "-" + vs.getEpisodeId() + "-" + vs.name + "-subt";
        progressKey = mVodInfo.sourceKey + mVodInfo.id + mVodInfo.playFlag + vs.getEpisodeId();
        //重新播放清除现有进度
        if (reset) {
            CacheManager.delete(MD5.string2MD5(progressKey), 0);
            CacheManager.delete(MD5.string2MD5(subtitleCacheKey), "");
        }
        if (vs.url.startsWith("tvbox-drive://")) {
            progressKey = vs.url.replace("tvbox-drive://", "");
            initPlayerDrive();
            mController.showParse(false);
            HashMap<String, String> headers = null;
            if (mVodInfo.playerCfg != null && mVodInfo.playerCfg.length() > 0) {
                try {
                    JsonObject playerConfig = JsonParser.parseString(mVodInfo.playerCfg).getAsJsonObject();
                    if (playerConfig.has("headers")) {
                        headers = new HashMap<>();
                        for (JsonElement headerEl : playerConfig.getAsJsonArray("headers")) {
                            JsonObject headerJson = headerEl.getAsJsonObject();
                            headers.put(headerJson.get("name").getAsString(), headerJson.get("value").getAsString());
                        }
                    }
                } catch (Throwable th) {
                    LOG.e(th);
                }
            }
            playUrl(vs.url.replace("tvbox-drive://", ""), headers);
            return;
        }
        if(Jianpian.isJpUrl(vs.url)){//荐片地址特殊判断
            String jp_url= vs.url;
            mController.showParse(false);
            if(vs.url.startsWith("tvbox-xg:")){
                playUrl(Jianpian.JPUrlDec(jp_url.substring(9)), null);
            }else {
                playUrl(Jianpian.JPUrlDec(jp_url), null);
            }
            return;
        }
        if (Thunder.play(vs.url, new Thunder.ThunderCallback() {
            @Override
            public void status(int code, String info) {
                if (code < 0) {
                    setTip(info, false, true);
                } else {
                    setTip(info, true, false);
                }
            }

            @Override
            public void list(Map<Integer, String> urlMap) {
            }

            @Override
            public void play(String url) {
                playUrl(url, null);
            }
        })) {
            mController.showParse(false);
            return;
        }
        
        String trimmedUrl = vs.url == null ? "" : vs.url.trim();
        if (trimmedUrl.isEmpty()) {
            stopParse();
            setTip("无视频源地址", false, true);
            return;
        }
        SourceBean sourceBean = ApiConfig.get().getSource(sourceKey);
        int sourceType = sourceBean.getType();
        if (sourceType == 0 || sourceType == 1) {
            if (!trimmedUrl.startsWith("http")) {
                stopParse();
                setTip("视频源地址错误", false, true);
                return;
            }
        }
        sourceViewModel.getPlay(sourceKey, mVodInfo.playFlag, progressKey, vs.url, subtitleCacheKey);
    }

    private String playSubtitle;
    private String subtitleCacheKey;
    private String progressKey;
    private String parseFlag;
    private String webUrl;
    private String webUserAgent;
    private Map<String, String> webHeaderMap;

    private void initParse(String flag, boolean useParse, String playUrl, final String url) {
        parseFlag = flag;
        webUrl = url;
        ParseBean parseBean = null;
        mController.showParse(useParse);
        if (useParse) {
            parseBean = ApiConfig.get().getDefaultParse();
        } else {
            if (playUrl.startsWith("json:")) {
                parseBean = new ParseBean();
                parseBean.setType(1);
                parseBean.setUrl(playUrl.substring(5));
            } else if (playUrl.startsWith("parse:")) {
                String parseRedirect = playUrl.substring(6);
                for (ParseBean pb : ApiConfig.get().getParseBeanList()) {
                    if (pb.getName().equals(parseRedirect)) {
                        parseBean = pb;
                        break;
                    }
                }
            }
            if (parseBean == null) {
                parseBean = new ParseBean();
                parseBean.setType(0);
                parseBean.setUrl(playUrl);
            }
        }
        doParse(parseBean);
    }

    JSONObject jsonParse(String input, String json) throws JSONException {
        JSONObject jsonPlayData = new JSONObject(json);
        //小窗版解析方法改到这了  之前那个位置data解析无效
        String url;
        if (jsonPlayData.has("data")) {
            url = jsonPlayData.getJSONObject("data").getString("url");
        } else {
            url = jsonPlayData.getString("url");
        }
        if (url.startsWith("//")) {
            url = "http:" + url;
        }
        if (!url.startsWith("http")) {
            return null;
        }
        JSONObject headers = new JSONObject();
        String ua = jsonPlayData.optString("user-agent", "");
        if (ua.trim().length() > 0) {
            headers.put("User-Agent", " " + ua);
        }
        String referer = jsonPlayData.optString("referer", "");
        if (referer.trim().length() > 0) {
            headers.put("Referer", " " + referer);
        }
        JSONObject taskResult = new JSONObject();
        taskResult.put("header", headers);
        taskResult.put("url", url);
        return taskResult;
    }

    void stopParse() {
        if (mHandler != null) {
            mHandler.removeMessages(100);
        }
        stopLoadWebView(false);
        OkGo.getInstance().cancelTag("json_jx");
        if (parseThreadPool != null) {
            try {
                parseThreadPool.shutdown();
                parseThreadPool = null;
            } catch (Throwable th) {
                LOG.e(th);
            }
        }
        if (mVideoView != null) {
            try {
                mVideoView.release();
            } catch (Throwable th) {
            }
        }
    }

    ExecutorService parseThreadPool;

    private void doParse(ParseBean pb) {
        stopParse();
        initParseLoadFound();
        if (pb.getType() == 4) {
            parseMix(pb,true);
        }else if (pb.getType() == 0) {
            setTip("正在嗅探播放地址", true, false);
            if (mHandler != null) {
                mHandler.removeMessages(100);
                mHandler.sendEmptyMessageDelayed(100, 20 * 1000);
            }
            if(pb.getExt()!=null){
                // 解析ext
                try {
                    HashMap<String, String> reqHeaders = new HashMap<>();
                    JSONObject jsonObject = new JSONObject(pb.getExt());
                    if (jsonObject.has("header")) {
                        JSONObject headerJson = jsonObject.optJSONObject("header");
                        Iterator<String> keys = headerJson.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            if (key.equalsIgnoreCase("user-agent")) {
                                webUserAgent = headerJson.getString(key).trim();
                            } else {
                                reqHeaders.put(key, headerJson.optString(key, ""));
                            }
                        }
                        if (reqHeaders.size() > 0) webHeaderMap = reqHeaders;
                    }
                } catch (Throwable e) {
                    LOG.e(e);
                }
            }
            loadWebView(pb.getUrl() + encodeUrl(webUrl));

        } else if (pb.getType() == 1) { // json 解析
            setTip("正在解析播放地址", true, false);
            // 解析ext
            HttpHeaders reqHeaders = new HttpHeaders();
            try {
                JSONObject jsonObject = new JSONObject(pb.getExt());
                if (jsonObject.has("header")) {
                    JSONObject headerJson = jsonObject.optJSONObject("header");
                    Iterator<String> keys = headerJson.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        reqHeaders.put(key, headerJson.optString(key, ""));
                    }
                }
            } catch (Throwable e) {
                LOG.e(e);
            }
            OkGo.<String>get(pb.getUrl() + encodeUrl(webUrl))
                    .tag("json_jx")
                    .headers(reqHeaders)
                    .execute(new AbsCallback<String>() {
                        @Override
                        public String convertResponse(okhttp3.Response response) throws Throwable {
                            if (response.body() != null) {
                                return response.body().string();
                            } else {
                                throw new IllegalStateException("网络请求错误");
                            }
                        }

                        @Override
                        public void onSuccess(Response<String> response) {
                            String json = response.body();
                            try {
                                JSONObject rs = jsonParse(webUrl, json);
                                HashMap<String, String> headers = null;
                                if (rs.has("header")) {
                                    try {
                                        JSONObject hds = rs.getJSONObject("header");
                                        Iterator<String> keys = hds.keys();
                                        while (keys.hasNext()) {
                                            String key = keys.next();
                                            if (headers == null) {
                                                headers = new HashMap<>();
                                            }
                                            headers.put(key, hds.getString(key));
                                        }
                                    } catch (Throwable th) {

                                    }
                                }
                                playUrl(rs.getString("url"), headers);
                            } catch (Throwable e) {
                                LOG.e(e);
                                errorWithRetry("服务器已响应但解析错误", false, false);
                            }
                        }

                        @Override
                        public void onError(Response<String> response) {
                            super.onError(response);
                            errorWithRetry("解析错误：服务器未响应" + response.getException().getMessage(), false, false);
                        }
                    });
        } else if (pb.getType() == 2) { // json 扩展
            setTip("正在解析播放地址", true, false);
            parseThreadPool = Executors.newSingleThreadExecutor();
            LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
            for (ParseBean p : ApiConfig.get().getParseBeanList()) {
                if (p.getType() == 1) {
                    jxs.put(p.getName(), p.mixUrl());
                }
            }
            parseThreadPool.execute(new JsonExtParseRunnable(this, pb.getUrl(), jxs, webUrl));
        } else if (pb.getType() == 3) { // json 聚合
             parseMix(pb,false);
        }
    }

    private static class JsonExtParseRunnable implements Runnable {
        private final WeakReference<PlayFragment> fragmentRef;
        private final String parseUrl;
        private final LinkedHashMap<String, String> jxs;
        private final String webUrl;

        JsonExtParseRunnable(PlayFragment fragment, String parseUrl, LinkedHashMap<String, String> jxs, String webUrl) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.parseUrl = parseUrl;
            this.jxs = jxs;
            this.webUrl = webUrl;
        }

        @Override
        public void run() {
            if (Thread.currentThread().isInterrupted()) return;
            PlayFragment fragment = fragmentRef.get();
            if (fragment == null || !fragment.isAdded()) return;
            
            JSONObject rs = ApiConfig.get().jsonExt(parseUrl, jxs, webUrl);
            if (Thread.currentThread().isInterrupted()) return;
            if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
                fragment.setTip("解析错误（扩展）", false, true);
            } else {
                HashMap<String, String> headers = null;
                if (rs.has("header")) {
                    try {
                        JSONObject hds = rs.getJSONObject("header");
                        Iterator<String> keys = hds.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            if (headers == null) {
                                headers = new HashMap<>();
                            }
                            headers.put(key, hds.getString(key));
                        }
                    } catch (Throwable th) {
                    }
                }
                if (rs.has("jxFrom")) {
                    final String jxFrom = rs.optString("jxFrom");
                    fragment.requireActivity().runOnUiThread(() -> ToastHelper.showToast("解析来自:" + jxFrom));
                }
                boolean parseWV = rs.optInt("parse", 0) == 1;
                if (parseWV) {
                    String wvUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                    fragment.loadUrl(wvUrl);
                } else {
                    fragment.playUrl(rs.optString("url", ""), headers);
                }
            }
        }
    }

    private void parseMix(ParseBean pb,boolean isSuper){
        setTip("正在解析播放地址", true, false);
        parseThreadPool = Executors.newSingleThreadExecutor();
        LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
        LinkedHashMap<String, String> json_jxs = new LinkedHashMap<>();
        String extendName = "";
        for (ParseBean p : ApiConfig.get().getParseBeanList()) {
            HashMap<String, String> data = new HashMap<String, String>();
            data.put("url", p.getUrl());
            if (p.getUrl().equals(pb.getUrl())) {
                extendName = p.getName();
            }
            data.put("type", p.getType() + "");
            data.put("ext", p.getExt());
            jxs.put(p.getName(), data);

            if (p.getType() == 1) {
                json_jxs.put(p.getName(), p.mixUrl());
            }
        }
        parseThreadPool.execute(new ParseMixRunnable(this, pb.getUrl(), jxs, parseFlag, webUrl, webUserAgent, isSuper));
    }

    private static class ParseMixRunnable implements Runnable {
        private final WeakReference<PlayFragment> fragmentRef;
        private final String parseUrl;
        private final LinkedHashMap<String, HashMap<String, String>> jxs;
        private final String parseFlag;
        private final String webUrl;
        private String webUserAgent;
        private final boolean isSuper;

        ParseMixRunnable(PlayFragment fragment, String parseUrl, LinkedHashMap<String, HashMap<String, String>> jxs, 
                        String parseFlag, String webUrl, String webUserAgent, boolean isSuper) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.parseUrl = parseUrl;
            this.jxs = jxs;
            this.parseFlag = parseFlag;
            this.webUrl = webUrl;
            this.webUserAgent = webUserAgent;
            this.isSuper = isSuper;
        }

        @Override
        public void run() {
            if (Thread.currentThread().isInterrupted()) return;
            PlayFragment fragment = fragmentRef.get();
            if (fragment == null || !fragment.isAdded()) return;
            
            if(isSuper){
                JSONObject rs = SuperParse.parse(jxs, parseFlag+"123", webUrl);
                if (Thread.currentThread().isInterrupted()) return;
                if (!rs.has("url") || rs.optString("url").isEmpty()) {
                    fragment.setTip("解析错误（超级）", false, true);
                } else {
                    if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                        if (rs.has("ua")) {
                            webUserAgent = rs.optString("ua").trim();
                            fragment.webUserAgent = webUserAgent;
                        }
                        fragment.setTip("超级解析中", true, false);
                        final String mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                        fragment.requireActivity().runOnUiThread(() -> {
                            fragment.stopParse();
                            if (fragment.mHandler != null) {
                                fragment.mHandler.removeMessages(100);
                                fragment.mHandler.sendEmptyMessageDelayed(100, 20 * 1000);
                            }
                            fragment.loadWebView(mixParseUrl);
                        });
                        fragment.parseThreadPool.execute(new SuperJsonJxRunnable(fragment, webUrl));
                    } else {
                        fragment.rsJsonJX(rs, false);
                    }
                }
            } else {
                JSONObject rs = ApiConfig.get().jsonExtMix(parseFlag + "111", parseUrl, jxs.keySet().iterator().next(), jxs, webUrl);
                if (Thread.currentThread().isInterrupted()) return;
                if (rs == null || !rs.has("url") || rs.optString("url").isEmpty()) {
                    fragment.setTip("解析错误（常规）", false, true);
                } else {
                    if (rs.has("parse") && rs.optInt("parse", 0) == 1) {
                        if (rs.has("ua")) {
                            webUserAgent = rs.optString("ua").trim();
                            fragment.webUserAgent = webUserAgent;
                        }
                        final String mixParseUrl = DefaultConfig.checkReplaceProxy(rs.optString("url", ""));
                        fragment.requireActivity().runOnUiThread(() -> {
                            fragment.stopParse();
                            fragment.setTip("正在嗅探播放地址", true, false);
                            if (fragment.mHandler != null) {
                                fragment.mHandler.removeMessages(100);
                                fragment.mHandler.sendEmptyMessageDelayed(100, 20 * 1000);
                            }
                            fragment.loadWebView(mixParseUrl);
                        });
                    } else {
                        fragment.rsJsonJX(rs, false);
                    }
                }
            }
        }
    }

    private static class SuperJsonJxRunnable implements Runnable {
        private final WeakReference<PlayFragment> fragmentRef;
        private final String webUrl;

        SuperJsonJxRunnable(PlayFragment fragment, String webUrl) {
            this.fragmentRef = new WeakReference<>(fragment);
            this.webUrl = webUrl;
        }

        @Override
        public void run() {
            if (Thread.currentThread().isInterrupted()) return;
            PlayFragment fragment = fragmentRef.get();
            if (fragment == null) return;
            JSONObject res = SuperParse.doJsonJx(webUrl);
            if (Thread.currentThread().isInterrupted()) return;
            fragment.rsJsonJX(res, true);
        }
    }

    private void rsJsonJX(JSONObject rs,boolean isSuper){
        if(isSuper){
            if(rs==null || !rs.has("url"))return;
            stopLoadWebView(false);
        }
        HashMap<String, String> headers = null;
        if (rs.has("header")) {
            try {
                JSONObject hds = rs.getJSONObject("header");
                Iterator<String> keys = hds.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (headers == null) {
                        headers = new HashMap<>();
                    }
                    headers.put(key, hds.getString(key));
                }
            } catch (Throwable th) {
                LOG.e(th);
            }
        }
        if (rs.has("jxFrom")) {
            if(!isAdded())return;
            requireActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    ToastHelper.showToast("解析来自:" + rs.optString("jxFrom"));
                }
            });
        }
        playUrl(rs.optString("url", ""), headers);
    }


    private String encodeUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        try {
            if (url.contains("%") && url.matches(".*%[0-9A-Fa-f]{2}.*")) {
                return encodeUrlPartial(url);
            }
            int schemeEnd = url.indexOf("://");
            if (schemeEnd > 0) {
                String scheme = url.substring(0, schemeEnd + 3);
                String rest = url.substring(schemeEnd + 3);
                int pathStart = rest.indexOf('/');
                if (pathStart > 0) {
                    String domain = rest.substring(0, pathStart);
                    String path = rest.substring(pathStart);
                    String encodedPath = encodePath(path);
                    return scheme + domain + encodedPath;
                } else {
                    return url;
                }
            }
            return URLEncoder.encode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }

    private String encodeUrlPartial(String url) {
        try {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < url.length(); i++) {
                char c = url.charAt(i);
                if (c == '%' && i + 2 < url.length()) {
                    result.append(c);
                    result.append(url.charAt(i + 1));
                    result.append(url.charAt(i + 2));
                    i += 2;
                } else if (needEncode(c)) {
                    result.append(URLEncoder.encode(String.valueOf(c), "UTF-8"));
                } else {
                    result.append(c);
                }
            }
            return result.toString();
        } catch (Exception e) {
            return url;
        }
    }

    private String encodePath(String path) {
        try {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < path.length(); i++) {
                char c = path.charAt(i);
                if (needEncode(c)) {
                    result.append(URLEncoder.encode(String.valueOf(c), "UTF-8"));
                } else {
                    result.append(c);
                }
            }
            return result.toString();
        } catch (Exception e) {
            return path;
        }
    }

    private boolean needEncode(char c) {
        return !((c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '-' || c == '_' || c == '.' || c == '~'
              || c == '/' || c == '?' || c == '&' || c == '='
              || c == '#' || c == ':' || c == '@');
    }

    // webview
    private WebView mSysWebView;
    private SysWebClient mSysWebClient;
    private final Map<String, Boolean> loadedUrls = new HashMap<>();
    private LinkedList<String> loadFoundVideoUrls = new LinkedList<>();
    private HashMap<String, HashMap<String, String>> loadFoundVideoUrlsHeader = new HashMap<>();
    private final AtomicInteger loadFoundCount = new AtomicInteger(0);

    void loadWebView(String url) {
        if (mSysWebView == null) {
            initWebView();
            loadUrl(url);
        } else {
            loadUrl(url);
        }
    }

    void initWebView() {
        mSysWebView = new MyWebView(mContext);
        configWebViewSys(mSysWebView);
    }

    void loadUrl(String url) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mSysWebView != null) {
                    mSysWebView.stopLoading();
                    if (webUserAgent != null) {
                        mSysWebView.getSettings().setUserAgentString(webUserAgent);
                    }
                    //mSysWebView.clearCache(true);
                    if (webHeaderMap != null) {
                        mSysWebView.loadUrl(url, webHeaderMap);
                    } else {
                        mSysWebView.loadUrl(url);
                    }
                }
            }
        });
    }

    void stopLoadWebView(boolean destroy) {
        if (mActivity == null) return;
        if (!isAdded() || isDetached()) return;
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mSysWebView != null) {
                    try {
                        mSysWebView.stopLoading();
                        mSysWebView.loadUrl("about:blank");
                        mSysWebView.clearHistory();
                        if (destroy) {
                            mSysWebView.setWebChromeClient(null);
                            mSysWebView.setWebViewClient(null);
                            mSysWebView.removeAllViews();
                            mSysWebView.destroy();
                            mSysWebView = null;
                        }
                    } catch (Exception e) {
                    }
                }
            }
        });
    }

    boolean checkVideoFormat(String url) {
        try {
            if (url.contains("url=http") || url.contains(".html")) {
                return false;
            }
            if (sourceBean.getType() == 3) {
                Spider sp = ApiConfig.get().getCSP(sourceBean);
                if (sp != null && sp.manualVideoCheck()) {
                    return sp.isVideoFormat(url);
                }
            }
            return VideoParseRuler.checkIsVideoForParse(webUrl, url);
        } catch (Exception e) {
            return false;
        }
    }

    class MyWebView extends WebView {
        public MyWebView(@NonNull Context context) {
            super(context);
        }

        @Override
        public void setOverScrollMode(int mode) {
            super.setOverScrollMode(mode);
            if (mContext instanceof Activity)
                AutoSize.autoConvertDensityOfCustomAdapt((Activity) mContext, PlayFragment.this);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return false;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configWebViewSys(WebView webView) {
        if (webView == null) {
            return;
        }
        ViewGroup.LayoutParams layoutParams = Hawk.get(HawkConfig.DEBUG_OPEN, false)
                ? new ViewGroup.LayoutParams(800, 400) :
                new ViewGroup.LayoutParams(1, 1);
        webView.setFocusable(false);
        webView.setFocusableInTouchMode(false);
        webView.clearFocus();
        webView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        if (!isAdded()) return;
        requireActivity().addContentView(webView, layoutParams);
        /* 添加webView配置 */
        final WebSettings settings = webView.getSettings();
        settings.setNeedInitialFocus(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setDatabaseEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptEnabled(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }
        settings.setBlockNetworkImage(!Hawk.get(HawkConfig.DEBUG_OPEN, false));
        settings.setUseWideViewPort(true);
        settings.setDomStorageEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setSupportZoom(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
//        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        /* 添加webView配置 */
        //设置编码
        settings.setDefaultTextEncodingName("utf-8");
        settings.setUserAgentString(webView.getSettings().getUserAgentString());
//         settings.setUserAgentString(ANDROID_UA);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return false;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
                return true;
            }
        });
        mSysWebClient = new SysWebClient();
        webView.setWebViewClient(mSysWebClient);
        webView.setBackgroundColor(Color.BLACK);
    }

    private class SysWebClient extends WebViewClient {

        @Override
        public void onReceivedSslError(WebView webView, SslErrorHandler sslErrorHandler, SslError sslError) {
            sslErrorHandler.proceed();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (!isAdded() || isDetached()) return;
            LOG.i("echo-onPageFinished url:" + url);
            if(!url.equals("about:blank")){
                mController.evaluateScript(sourceBean,url,view);
            }
            if (mHandler != null) {
                mHandler.sendEmptyMessage(200);
            }
        }

        WebResourceResponse checkIsVideo(String url, HashMap<String, String> headers) {
            if (!isAdded() || isDetached()) return null;
            if (url.endsWith("/favicon.ico")) {
                if (url.startsWith("http://127.0.0.1")) {
                    return new WebResourceResponse("image/x-icon", "UTF-8", null);
                }
                return null;
            }

            boolean isFilter = VideoParseRuler.isFilter(webUrl, url);
            if (isFilter) {
                LOG.i("shouldInterceptLoadRequest filter:" + url);
                return null;
            }

            boolean ad;
            if (!loadedUrls.containsKey(url)) {
                ad = AdBlocker.isAd(url);
                loadedUrls.put(url, ad);
            } else {
                ad = loadedUrls.get(url);
            }

            if (!ad) {
                if (checkVideoFormat(url)) {
                    loadFoundVideoUrls.add(url);
                    loadFoundVideoUrlsHeader.put(url, headers);
                    LOG.i("loadFoundVideoUrl:" + url);
                    if (loadFoundCount.incrementAndGet() == 1) {
                        url = loadFoundVideoUrls.poll();
                        if (mHandler != null) {
                            mHandler.removeMessages(100);
                        }
                        String cookie = CookieManager.getInstance().getCookie(url);
                        if (!TextUtils.isEmpty(cookie))
                            headers.put("Cookie", " " + cookie);//携带cookie
                        playUrl(url, headers);
                        stopLoadWebView(false);
                        SuperParse.stopJsonJx();
                    }
                }
            }
            return ad || loadFoundCount.get() > 0 ?
                    AdBlocker.createEmptyResource() :
                    null;
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
//            WebResourceResponse response = checkIsVideo(url, new HashMap<>());
            return null;
        }

        @Nullable
        @Override
        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            LOG.i("shouldInterceptRequest url:" + url);
            HashMap<String, String> webHeaders = new HashMap<>();
            Map<String, String> hds = request.getRequestHeaders();
            if (hds != null && hds.keySet().size() > 0) {
                for (String k : hds.keySet()) {
                    if (k.equalsIgnoreCase("user-agent")
                            || k.equalsIgnoreCase("referer")
                            || k.equalsIgnoreCase("origin")) {
                        webHeaders.put(k," " + hds.get(k));
                    }
                }
            }
            return checkIsVideo(url, webHeaders);
        }

        @Override
        public void onLoadResource(WebView webView, String url) {
            super.onLoadResource(webView, url);
        }
    }

    private static class SafeHandler extends Handler {
        private final WeakReference<PlayFragment> fragmentRef;

        SafeHandler(PlayFragment fragment) {
            super(Looper.getMainLooper());
            fragmentRef = new WeakReference<>(fragment);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            PlayFragment fragment = fragmentRef.get();
            if (fragment == null) return;
            
            if (fragment.isDetached() || !fragment.isAdded()) {
                return;
            }
            
            if (fragment.getActivity() == null || fragment.getActivity().isFinishing() || fragment.getActivity().isDestroyed()) {
                return;
            }
            
            if (msg.what == 100) {
                try {
                    fragment.stopParse();
                    fragment.errorWithRetry("嗅探超时", false, false);
                } catch (Exception e) {
                }
            } else if (msg.what == 200) {
                if (hasMessages(100)) {
                    try {
                        fragment.setTip("加载完成，嗅探视频中", true, false);
                    } catch (Exception e) {
                    }
                }
            } else if (msg.what == 300) {
                try {
                    // 底层错误也经过白名单过滤
                    String errorMsg = (String) msg.obj;
                    fragment.errorWithRetry(errorMsg, false, true);
                } catch (Exception e) {
                }
            }
        }
    }
}
