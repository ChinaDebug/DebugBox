package com.github.tvbox.osc.ui.fragment;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.util.ToastHelper;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.player.thirdparty.Kodi;
import com.github.tvbox.osc.player.thirdparty.MXPlayer;
import com.github.tvbox.osc.player.thirdparty.ReexPlayer;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.activity.SplashActivity;
import com.github.tvbox.osc.ui.adapter.ApiHistoryDialogAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.AboutDialog;
import com.github.tvbox.osc.ui.dialog.ApiDialog;
import com.github.tvbox.osc.ui.dialog.ApiHistoryDialog;
import com.github.tvbox.osc.ui.dialog.BackupDialog;
import com.github.tvbox.osc.ui.dialog.HomeIconDialog;
import com.github.tvbox.osc.ui.dialog.MediaSettingDialog;
import com.github.tvbox.osc.ui.dialog.CleanResetDialog;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HawkListHelper;
import com.github.tvbox.osc.util.HistoryHelper;
import com.github.tvbox.osc.util.LocaleHelper;
import com.github.tvbox.osc.util.OkGoHelper;
import com.github.tvbox.osc.util.PlayerHelper;
import com.github.tvbox.osc.util.UpdateCheckManager;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.FileCallback;
import com.lzy.okgo.model.Progress;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import com.github.tvbox.osc.event.RefreshEvent;
import org.greenrobot.eventbus.EventBus;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;
import okhttp3.HttpUrl;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class ModelSettingFragment extends BaseLazyFragment {
    private TextView tvDebugOpen;
    private TextView tvApi;
    // Home Section
    private TextView tvHomeApi;
    private TextView tvHomeDefaultShow;
    private TextView tvHomeShow;
    private TextView tvHomeIcon;
    private TextView tvHomeRec;
    private TextView tvHomeNum;

    // Player Section
    private TextView tvShowPreviewText;
    private TextView tvScale;
    private TextView tvVideoPurifyText;

    // System Section
    private TextView tvLocale;
    private TextView tvTheme;
    private TextView tvSearchView;
    private TextView tvDns;
    private TextView tvFastSearchText;
    private TextView tvUpdateCheckEnable;
    private TextView tvUpdateCheckStartup;
    private TextView tvUpdateCheckInterval;
    private TextView tvUpdateCheckWifiOnly;
    private LinearLayout llUpdateCheckStartup;
    private LinearLayout llUpdateCheckInterval;
    private LinearLayout llUpdateCheckWifiOnly;

    public static ModelSettingFragment newInstance() {
        return new ModelSettingFragment().setArguments();
    }

    public ModelSettingFragment setArguments() {
        return this;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_model;
    }

    @Override
    protected void init() {
        tvFastSearchText = findViewById(R.id.showFastSearchText);
        tvFastSearchText.setText(Hawk.get(HawkConfig.FAST_SEARCH_MODE, false) ? "已开启" : "已关闭");
        tvDebugOpen = findViewById(R.id.tvDebugOpen);
        tvDebugOpen.setText(Hawk.get(HawkConfig.DEBUG_OPEN, false) ? "开启" : "关闭");
        tvApi = findViewById(R.id.tvApi);
        tvApi.setText(Hawk.get(HawkConfig.API_URL, ""));
        // Home Section
        tvHomeApi = findViewById(R.id.tvHomeApi);
        tvHomeApi.setText(ApiConfig.get().getHomeSourceBean().getName());
        tvHomeShow = findViewById(R.id.tvHomeShow);
        tvHomeShow.setText(Hawk.get(HawkConfig.HOME_SHOW_SOURCE, false) ? "开启" : "关闭");
        tvHomeRec = findViewById(R.id.tvHomeRec);
        tvHomeRec.setText(getHomeRecName(Hawk.get(HawkConfig.HOME_REC, 0)));
        tvHomeNum = findViewById(R.id.tvHomeNum);
        tvHomeNum.setText(HistoryHelper.getHomeRecName(Hawk.get(HawkConfig.HOME_NUM, 0)));
        // Player Section
        tvShowPreviewText = findViewById(R.id.showPreviewText);
        tvShowPreviewText.setText(getDetailPageModeName(Hawk.get(HawkConfig.SHOW_PREVIEW, true)));
        tvScale = findViewById(R.id.tvScaleType);
        tvScale.setText(PlayerHelper.getScaleName(Hawk.get(HawkConfig.PLAY_SCALE, 0)));
        tvVideoPurifyText = findViewById(R.id.tvVideoPurifyText);
        tvVideoPurifyText.setText(Hawk.get(HawkConfig.VIDEO_PURIFY, true) ? "开启" : "关闭");
        // System Section
        tvLocale = findViewById(R.id.tvLocale);
        tvLocale.setText(getLocaleView(Hawk.get(HawkConfig.HOME_LOCALE, 0)));
        tvTheme = findViewById(R.id.tvTheme);
        tvTheme.setText(getThemeView(Hawk.get(HawkConfig.THEME_SELECT, 0)));
        tvSearchView = findViewById(R.id.tvSearchView);
        tvSearchView.setText(getSearchView(Hawk.get(HawkConfig.SEARCH_VIEW, 0)));
        tvDns = findViewById(R.id.tvDns);
        tvDns.setText(OkGoHelper.dnsHttpsList.get(Hawk.get(HawkConfig.DOH_URL, 0)));
        tvHomeDefaultShow = findViewById(R.id.tvHomeDefaultShow);
        tvHomeDefaultShow.setText(Hawk.get(HawkConfig.HOME_DEFAULT_SHOW, false) ? "开启" : "关闭");

        tvUpdateCheckEnable = findViewById(R.id.tvUpdateCheckEnable);
        tvUpdateCheckEnable.setText(UpdateCheckManager.get().isEnable() ? "开启" : "关闭");
        tvUpdateCheckStartup = findViewById(R.id.tvUpdateCheckStartup);
        tvUpdateCheckStartup.setText(UpdateCheckManager.get().isStartupCheck() ? "开启" : "关闭");
        tvUpdateCheckInterval = findViewById(R.id.tvUpdateCheckInterval);
        tvUpdateCheckInterval.setText(UpdateCheckManager.get().getIntervalDisplay());
        tvUpdateCheckWifiOnly = findViewById(R.id.tvUpdateCheckWifiOnly);
        tvUpdateCheckWifiOnly.setText(UpdateCheckManager.get().isWifiOnly() ? "开启" : "关闭");
        
        llUpdateCheckStartup = findViewById(R.id.llUpdateCheckStartup);
        llUpdateCheckInterval = findViewById(R.id.llUpdateCheckInterval);
        llUpdateCheckWifiOnly = findViewById(R.id.llUpdateCheckWifiOnly);
        updateEnabledState();

        //takagen99 : Set HomeApi as default
        findViewById(R.id.llHomeApi).requestFocus();

        findViewById(R.id.llDebug).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                Hawk.put(HawkConfig.DEBUG_OPEN, !Hawk.get(HawkConfig.DEBUG_OPEN, false));
                tvDebugOpen.setText(Hawk.get(HawkConfig.DEBUG_OPEN, false) ? "开启" : "关闭");
            }
        });
        // Input Source URL ------------------------------------------------------------------------
        findViewById(R.id.llApi).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                // 检查Activity是否有效
                if (mActivity == null || mActivity.isFinishing() || mActivity.isDestroyed()) {
                    return;
                }
                // 确保服务已启动
                if (ControlManager.mContext != null) {
                    ControlManager.get().startServer();
                } else {
                    ToastHelper.showToast(mActivity, "服务初始化中，请稍后再试");
                    return;
                }
                ApiDialog dialog = new ApiDialog(mActivity);
                try {
                    EventBus.getDefault().register(dialog);
                } catch (Exception e) {
                    // EventBus注册失败，不显示对话框
                    return;
                }
                dialog.setOnListener(new ApiDialog.OnListener() {
                    @Override
                    public void onchange(String api, boolean changed) {
                        if (changed) {
                            Hawk.put(HawkConfig.API_URL, api);
                            UpdateCheckManager.get().clearCache();
                            tvApi.setText(api);
                        }
                    }
                });
                dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        // 检查Activity是否仍然有效
                        if (mActivity != null && !mActivity.isFinishing() && !mActivity.isDestroyed()) {
                            ((BaseActivity) mActivity).hideSystemUI(true);
                        }
                        try {
                            EventBus.getDefault().unregister(dialog);
                        } catch (Exception e) {
                            // 忽略注销失败
                        }
                    }
                });
                dialog.show();
            }
        });
        findViewById(R.id.llApiHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                // 使用Hawk读取API历史记录
                ArrayList<String> history = HawkListHelper.getList(HawkConfig.API_HISTORY);
                if (history.isEmpty()) {
                    ToastHelper.showToast(mActivity, "暂无历史配置地址");
                    return;
                }
                String current = Hawk.get(HawkConfig.API_URL, "");
                int idx = 0;
                // 如果当前地址在历史记录中，临时将其移到顶部（仅用于显示）
                if (history.contains(current)) {
                    history.remove(current);
                    history.add(0, current);
                    idx = 0;
                }
                ApiHistoryDialog dialog = new ApiHistoryDialog(mActivity);
                dialog.setTip(getString(R.string.dia_history_list));
                dialog.setAdapter(new ApiHistoryDialogAdapter.SelectDialogInterface() {
                    @Override
                    public void click(String api) {
                        String oldApi = Hawk.get(HawkConfig.API_URL, "");
                        boolean apiChanged = !oldApi.equals(api);

                        if (apiChanged) {
                            Hawk.put(HawkConfig.API_URL, api);
                            ToastHelper.showToast(mActivity, "正在清理缓存并切换配置");
                            UpdateCheckManager.get().clearCache();
                            ApiConfig.get().clearAllCache();
                            UpdateCheckManager.get().resetCheckState();
                            ToastHelper.showToast(mActivity, "缓存清理完成");
                            tvApi.setText(api);
                        }

                        dialog.dismiss();
                    }

                    @Override
                    public void del(String value, ArrayList<String> data) {
                        // 使用Hawk删除历史记录
                        HawkListHelper.putList(HawkConfig.API_HISTORY, data);
                    }
                }, history, idx);
                dialog.show();
            }
        });
        // 1. HOME Configuration ---------------------------------------------------------------- //
        // Select Home Source ------------------------------------
        findViewById(R.id.llHomeApi).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                List<SourceBean> sites = new ArrayList<>();
                for (SourceBean sb : ApiConfig.get().getSourceBeanList()) {
                    if (sb.getHide() == 0) sites.add(sb);
                }
                if (sites.size() > 0) {
                    SelectDialog<SourceBean> dialog = new SelectDialog<>(mActivity);

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
                            tvHomeApi.setText(ApiConfig.get().getHomeSourceBean().getName());
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
                    dialog.show();
                }
            }
        });
        // Switch to show / hide source header --------------------------
        findViewById(R.id.llHomeShow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                Hawk.put(HawkConfig.HOME_SHOW_SOURCE, !Hawk.get(HawkConfig.HOME_SHOW_SOURCE, false));
                tvHomeShow.setText(Hawk.get(HawkConfig.HOME_SHOW_SOURCE, true) ? "开启" : "关闭");
            }
        });
        findViewById(R.id.llHomeIcon).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                HomeIconDialog dialog = new HomeIconDialog(mActivity);
                dialog.show();
            }
        });
        // Select Home Display Type : Douban / Recommended / History -----
        findViewById(R.id.llHomeRec).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                final int oldHomeRec = Hawk.get(HawkConfig.HOME_REC, 0);
                int defaultPos = oldHomeRec;
                ArrayList<Integer> types = new ArrayList<>();
                types.add(0);
                types.add(1);
                types.add(2);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip(getString(R.string.dia_hm_type));
                dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        Hawk.put(HawkConfig.HOME_REC, value);
                        tvHomeRec.setText(getHomeRecName(value));
                        dialog.dismiss();
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return getHomeRecName(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        int newHomeRec = Hawk.get(HawkConfig.HOME_REC, 0);
                        if (oldHomeRec != newHomeRec) {
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HOME_REC_CHANGE));
                        }
                    }
                });
                dialog.show();
            }
        });
        // History to Keep ------------------------------------------
        findViewById(R.id.llHomeNum).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = Hawk.get(HawkConfig.HOME_NUM, 0);
                ArrayList<Integer> types = new ArrayList<>();
                types.add(0);
                types.add(1);
                types.add(2);
                types.add(3);
                types.add(4);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip(getString(R.string.dia_history));
                dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        Hawk.put(HawkConfig.HOME_NUM, value);
                        tvHomeNum.setText(HistoryHelper.getHomeRecName(value));
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return HistoryHelper.getHomeRecName(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.show();
            }
        });
        // 2. PLAYER Configuration -------------------------------------------------------------- //
        // Select Detail Page Mode -------------------------------
        findViewById(R.id.showPreview).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                boolean currentMode = Hawk.get(HawkConfig.SHOW_PREVIEW, true);
                int defaultPos = currentMode ? 0 : 1;
                ArrayList<String> modes = new ArrayList<>();
                modes.add(getString(R.string.detail_page_preview));
                modes.add(getString(R.string.detail_page_poster));
                SelectDialog<String> dialog = new SelectDialog<>(mActivity);
                dialog.setTip(getString(R.string.dia_detail_page));
                dialog.setCanceledOnTouchOutside(true);
                dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<String>() {
                    @Override
                    public void click(String value, int pos) {
                        boolean isPreviewMode = (pos == 0);
                        Hawk.put(HawkConfig.SHOW_PREVIEW, isPreviewMode);
                        tvShowPreviewText.setText(getDetailPageModeName(isPreviewMode));
                    }

                    @Override
                    public String getDisplay(String val) {
                        return val;
                    }
                }, new DiffUtil.ItemCallback<String>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
                        return oldItem.equals(newItem);
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
                        return oldItem.equals(newItem);
                    }
                }, modes, defaultPos);
                dialog.show();
            }
        });
        // Select Screen Ratio -------------------------------------
        findViewById(R.id.llScale).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = Hawk.get(HawkConfig.PLAY_SCALE, 0);
                ArrayList<Integer> players = new ArrayList<>();
                players.add(0);
                players.add(1);
                players.add(2);
                players.add(3);
                players.add(4);
                players.add(5);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip(getString(R.string.dia_ratio));
                dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        Hawk.put(HawkConfig.PLAY_SCALE, value);
                        tvScale.setText(PlayerHelper.getScaleName(value));
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return PlayerHelper.getScaleName(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, players, defaultPos);
                dialog.show();
            }
        });
        //后台播放
        View backgroundPlay = findViewById(R.id.llBackgroundPlay);
        TextView tvBgPlayType = findViewById(R.id.tvBackgroundPlayType);
        Integer defaultBgPlayTypePos = Hawk.get(HawkConfig.BACKGROUND_PLAY_TYPE, 0);
        ArrayList<String> bgPlayTypes = new ArrayList<>();
        bgPlayTypes.add("关闭");
        bgPlayTypes.add("开启");
        bgPlayTypes.add("画中画");
        tvBgPlayType.setText(bgPlayTypes.get(defaultBgPlayTypePos));
        backgroundPlay.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            int bgPlayTypePos = Hawk.get(HawkConfig.BACKGROUND_PLAY_TYPE, 0);
            SelectDialog<String> dialog = new SelectDialog<>(mActivity);
            dialog.setTip("请选择");
            dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<String>() {
                @Override
                public void click(String value, int pos) {
                    tvBgPlayType.setText(value);
                    Hawk.put(HawkConfig.BACKGROUND_PLAY_TYPE, pos);
                }
                @Override
                public String getDisplay(String val) {
                    return val;
                }
            }, new DiffUtil.ItemCallback<String>() {
                @Override
                public boolean areItemsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
                    return oldItem.equals(newItem);
                }
                @Override
                public boolean areContentsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
                    return oldItem.equals(newItem);
                }
            }, bgPlayTypes,bgPlayTypePos);
            dialog.show();
        });


        // Select DECODER Type --------------------------------------------
        //更改选择是否用硬解码还是软解码 改成播放器设置
        findViewById(R.id.llMediaSetting).setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            MediaSettingDialog mediaSettingDialog = new MediaSettingDialog(view.getContext());
            mediaSettingDialog.show();
        });

        // toggle purify video -------------------------------------
        findViewById(R.id.llVideoPurify).setOnClickListener(v -> {
            FastClickCheckUtil.check(v);
            Hawk.put(HawkConfig.VIDEO_PURIFY, !Hawk.get(HawkConfig.VIDEO_PURIFY, true));
            tvVideoPurifyText.setText(Hawk.get(HawkConfig.VIDEO_PURIFY, true) ? "开启" : "关闭");
        });

        // Select DNS ---------------------------------------------
        findViewById(R.id.llDns).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int dohUrl = Hawk.get(HawkConfig.DOH_URL, 0);

                SelectDialog<String> dialog = new SelectDialog<>(mActivity);
                dialog.setTip(getString(R.string.dia_dns));
                dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<String>() {
                    @Override
                    public void click(String value, int pos) {
                        tvDns.setText(OkGoHelper.dnsHttpsList.get(pos));
                        Hawk.put(HawkConfig.DOH_URL, pos);
                        String url = OkGoHelper.getDohUrl(pos);
                        OkGoHelper.dnsOverHttps.setUrl(url.isEmpty() ? null : HttpUrl.get(url));
                        IjkMediaPlayer.toggleDotPort(pos > 0);
                    }

                    @Override
                    public String getDisplay(String val) {
                        return val;
                    }
                }, new DiffUtil.ItemCallback<String>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
                        return oldItem.equals(newItem);
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull String oldItem, @NonNull @NotNull String newItem) {
                        return oldItem.equals(newItem);
                    }
                }, OkGoHelper.dnsHttpsList, dohUrl);
                dialog.show();
            }
        });
        // Select Backup / Restore -------------------------------------
        findViewById(R.id.llBackup).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                BackupDialog dialog = new BackupDialog(mActivity);
                dialog.show();
            }
        });
        // resetApp
        findViewById(R.id.llReset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                CleanResetDialog dialog = new CleanResetDialog(mActivity);
                dialog.show();
            }
        });
        // Load Wallpaper from URL -------------------------------------
        findViewById(R.id.llWp).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                if (!ApiConfig.get().wallpaper.isEmpty())
                    ToastHelper.showToast(mContext, getString(R.string.mn_wall_load));
                OkGo.<File>get(ApiConfig.get().wallpaper).execute(new FileCallback(requireActivity().getFilesDir().getAbsolutePath(), "wp") {
                    @Override
                    public void onSuccess(Response<File> response) {
                        ((BaseActivity) requireActivity()).changeWallpaper(true);
                    }

                    @Override
                    public void onError(Response<File> response) {
                        super.onError(response);
                    }

                    @Override
                    public void downloadProgress(Progress progress) {
                        super.downloadProgress(progress);
                    }
                });
            }
        });
        // Restore Default Wallpaper from system -------------------------
        findViewById(R.id.llWpRecovery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                File wp = new File(requireActivity().getFilesDir().getAbsolutePath() + "/wp");
                if (wp.exists())
                    wp.delete();
                ((BaseActivity) requireActivity()).changeWallpaper(true);
            }
        });
        // Select Search Display Results ( Text or Picture ) -------------
        findViewById(R.id.llSearchView).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = Hawk.get(HawkConfig.SEARCH_VIEW, 0);
                ArrayList<Integer> types = new ArrayList<>();
                types.add(0);
                types.add(1);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip(getString(R.string.dia_search));
                dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        Hawk.put(HawkConfig.SEARCH_VIEW, value);
                        tvSearchView.setText(getSearchView(value));
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return getSearchView(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.show();
            }
        });
        findViewById(R.id.showFastSearch).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                Hawk.put(HawkConfig.FAST_SEARCH_MODE, !Hawk.get(HawkConfig.FAST_SEARCH_MODE, false));
                tvFastSearchText.setText(Hawk.get(HawkConfig.FAST_SEARCH_MODE, false) ? "已开启" : "已关闭");
            }
        });
        // Select App Language ( English / Chinese ) -----------------
        findViewById(R.id.llLocale).setOnClickListener(new View.OnClickListener() {
            private final int chkLang = Hawk.get(HawkConfig.HOME_LOCALE, 0);

            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = Hawk.get(HawkConfig.HOME_LOCALE, 0);
                ArrayList<Integer> types = new ArrayList<>();
                types.add(0);
                types.add(1);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip(getString(R.string.dia_locale));
                dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        Hawk.put(HawkConfig.HOME_LOCALE, value);
                        tvLocale.setText(getLocaleView(value));
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return getLocaleView(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        if (chkLang != Hawk.get(HawkConfig.HOME_LOCALE, 0)) {
                            reloadActivity();
                        }
                    }
                });
                dialog.show();
            }
        });
        // Select App Theme Color -------------------------------------
        findViewById(R.id.llTheme).setOnClickListener(new View.OnClickListener() {
            private final int chkTheme = Hawk.get(HawkConfig.THEME_SELECT, 0);

            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = Hawk.get(HawkConfig.THEME_SELECT, 0);
                ArrayList<Integer> types = new ArrayList<>();
                types.add(0);
                types.add(1);
                types.add(2);
                types.add(3);
                types.add(4);
                types.add(5);
                types.add(6);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip(getString(R.string.dia_theme));
                dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        Hawk.put(HawkConfig.THEME_SELECT, value);
                        tvTheme.setText(getThemeView(value));
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return getThemeView(val);
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        if (chkTheme != Hawk.get(HawkConfig.THEME_SELECT, 0)) {
                            reloadActivity();
                        }
                    }
                });
                dialog.show();
            }
        });
        // About App -----------------------------------------------
        findViewById(R.id.llAbout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                AboutDialog dialog = new AboutDialog(mActivity);
                dialog.show();
            }
        });

        findViewById(R.id.llHomeLive).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                Hawk.put(HawkConfig.HOME_DEFAULT_SHOW, !Hawk.get(HawkConfig.HOME_DEFAULT_SHOW, false));
                tvHomeDefaultShow.setText(Hawk.get(HawkConfig.HOME_DEFAULT_SHOW, true) ? "开启" : "关闭");
            }
        });

        findViewById(R.id.llUpdateCheckEnable).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = UpdateCheckManager.get().isEnable() ? 0 : 1;
                ArrayList<Integer> types = new ArrayList<>();
                types.add(1);
                types.add(0);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip(getString(R.string.update_check_enable));
                dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        UpdateCheckManager.get().setEnable(value == 1);
                        tvUpdateCheckEnable.setText(value == 1 ? "开启" : "关闭");
                        updateEnabledState();
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return val == 1 ? "开启" : "关闭";
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.show();
            }
        });

        findViewById(R.id.llUpdateCheckStartup).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = UpdateCheckManager.get().isStartupCheck() ? 0 : 1;
                ArrayList<Integer> types = new ArrayList<>();
                types.add(1);
                types.add(0);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip(getString(R.string.update_check_startup));
                dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        UpdateCheckManager.get().setStartupCheck(value == 1);
                        tvUpdateCheckStartup.setText(value == 1 ? "开启" : "关闭");
                        checkAndDisableFeature();
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return val == 1 ? "开启" : "关闭";
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.show();
            }
        });

        findViewById(R.id.llUpdateCheckInterval).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int currentInterval = UpdateCheckManager.get().getCheckInterval();
                int defaultPos = 0;
                if (currentInterval == UpdateCheckManager.INTERVAL_OFF) {
                    defaultPos = 0;
                } else if (currentInterval == UpdateCheckManager.INTERVAL_30MIN) {
                    defaultPos = 1;
                } else if (currentInterval == UpdateCheckManager.INTERVAL_1HOUR) {
                    defaultPos = 2;
                } else if (currentInterval == UpdateCheckManager.INTERVAL_2HOURS) {
                    defaultPos = 3;
                }
                ArrayList<Integer> types = new ArrayList<>();
                types.add(UpdateCheckManager.INTERVAL_OFF);
                types.add(UpdateCheckManager.INTERVAL_30MIN);
                types.add(UpdateCheckManager.INTERVAL_1HOUR);
                types.add(UpdateCheckManager.INTERVAL_2HOURS);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip(getString(R.string.update_check_interval));
                dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        UpdateCheckManager.get().setCheckInterval(value);
                        tvUpdateCheckInterval.setText(UpdateCheckManager.get().getIntervalDisplay());
                        checkAndDisableFeature();
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        if (val == UpdateCheckManager.INTERVAL_OFF) {
                            return "关闭";
                        } else if (val == UpdateCheckManager.INTERVAL_30MIN) {
                            return "30分钟";
                        } else if (val == UpdateCheckManager.INTERVAL_1HOUR) {
                            return "1小时";
                        } else if (val == UpdateCheckManager.INTERVAL_2HOURS) {
                            return "2小时";
                        }
                        return "关闭";
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.show();
            }
        });

        findViewById(R.id.llUpdateCheckWifiOnly).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                int defaultPos = UpdateCheckManager.get().isWifiOnly() ? 0 : 1;
                ArrayList<Integer> types = new ArrayList<>();
                types.add(1);
                types.add(0);
                SelectDialog<Integer> dialog = new SelectDialog<>(mActivity);
                dialog.setTip(getString(R.string.update_check_wifi_only));
                dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
                    @Override
                    public void click(Integer value, int pos) {
                        UpdateCheckManager.get().setWifiOnly(value == 1);
                        tvUpdateCheckWifiOnly.setText(value == 1 ? "开启" : "关闭");
                    }

                    @Override
                    public String getDisplay(Integer val) {
                        return val == 1 ? "开启" : "关闭";
                    }
                }, new DiffUtil.ItemCallback<Integer>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                        return oldItem.intValue() == newItem.intValue();
                    }
                }, types, defaultPos);
                dialog.show();
            }
        });

        SettingActivity.callback = new SettingActivity.DevModeCallback() {
            @Override
            public void onChange() {
                findViewById(R.id.llDebug).setVisibility(View.VISIBLE);
            }
        };

    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        SettingActivity.callback = null;
    }

    String getHomeRecName(int type) {
        if (type == 1) {
            return "站点推荐";
        } else if (type == 2) {
            return "观看历史";
        } else {
            return "豆瓣热播";
        }
    }

    String getDetailPageModeName(boolean isPreviewMode) {
        if (isPreviewMode) {
            return getString(R.string.detail_page_preview);
        } else {
            return getString(R.string.detail_page_poster);
        }
    }

    String getSearchView(int type) {
        if (type == 0) {
            return "文字列表";
        } else {
            return "缩略图";
        }
    }

    String getLocaleView(int type) {
        if (type == 0) {
            return "中文";
        } else {
            return "英文";
        }
    }

    String getThemeView(int type) {
        if (type == 0) {
            return "奈飞";
        } else if (type == 1) {
            return "哆啦";
        } else if (type == 2) {
            return "百事";
        } else if (type == 3) {
            return "鸣人";
        } else if (type == 4) {
            return "小黄";
        } else if (type == 5) {
            return "八神";
        } else {
            return "樱花";
        }
    }

    void updateEnabledState() {
        boolean enabled = UpdateCheckManager.get().isEnable();
        float alpha = enabled ? 1.0f : 0.5f;
        llUpdateCheckWifiOnly.setAlpha(alpha);
        llUpdateCheckStartup.setAlpha(alpha);
        llUpdateCheckInterval.setAlpha(alpha);
        llUpdateCheckWifiOnly.setEnabled(enabled);
        llUpdateCheckStartup.setEnabled(enabled);
        llUpdateCheckInterval.setEnabled(enabled);
    }

    void checkAndDisableFeature() {
        boolean startupCheck = UpdateCheckManager.get().isStartupCheck();
        int interval = UpdateCheckManager.get().getCheckInterval();
        boolean intervalOff = interval == UpdateCheckManager.INTERVAL_OFF;

        if (!startupCheck && intervalOff) {
            UpdateCheckManager.get().setEnable(false);
            tvUpdateCheckEnable.setText("关闭");
            updateEnabledState();
        }
    }

    void reloadActivity() {
        if (getActivity() == null || getActivity().isFinishing()) {
            return;
        }
        if (Hawk.get(HawkConfig.HOME_LOCALE, 0) == 0) {
            LocaleHelper.setLocale(getActivity().getApplicationContext(), "zh");
        } else {
            LocaleHelper.setLocale(getActivity().getApplicationContext(), "");
        }
        Intent intent = new Intent(getActivity().getApplicationContext(), SplashActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle bundle = new Bundle();
        bundle.putBoolean("useCache", true);
        intent.putExtras(bundle);
        getActivity().getApplicationContext().startActivity(intent);
        getActivity().overridePendingTransition(0, 0);
    }

}
