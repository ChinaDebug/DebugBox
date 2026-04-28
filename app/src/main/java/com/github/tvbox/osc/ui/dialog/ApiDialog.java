package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.ui.adapter.ApiHistoryDialogAdapter;
import com.github.tvbox.osc.ui.tv.QRCodeGen;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HawkListHelper;
import com.github.tvbox.osc.util.ToastHelper;
import com.github.tvbox.osc.util.UpdateCheckManager;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import android.content.SharedPreferences;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 描述
 *
 * @author pj567
 * @since 2020/12/27
 */
public class ApiDialog extends BaseDialog {
    private final ImageView ivQRCode;
    private final TextView tvAddress;
    private final EditText inputApi;
    private final EditText inputLive;
    private final EditText inputEPG;
    private final EditText inputProxy;

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_API_URL_CHANGE) {
            inputApi.setText((String) event.obj);
        }
        if (event.type == RefreshEvent.TYPE_LIVE_URL_CHANGE) {
            inputLive.setText((String) event.obj);
        }
        if (event.type == RefreshEvent.TYPE_EPG_URL_CHANGE) {
            inputEPG.setText((String) event.obj);
        }
        if (event.type == RefreshEvent.TYPE_PROXYS_CHANGE) {
            inputProxy.setText((String) event.obj);
        }
    }

    public ApiDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_api);
        setCanceledOnTouchOutside(true);
        ivQRCode = findViewById(R.id.ivQRCode);
        tvAddress = findViewById(R.id.tvAddress);
        inputApi = findViewById(R.id.input);
        inputApi.setText(Hawk.get(HawkConfig.API_URL, ""));

        // takagen99: Add Live & EPG Address
        inputLive = findViewById(R.id.input_live);
        inputLive.setText(Hawk.get(HawkConfig.LIVE_URL, ""));
        inputEPG = findViewById(R.id.input_epg);
        inputEPG.setText(Hawk.get(HawkConfig.EPG_URL, ""));
        inputProxy = findViewById(R.id.input_proxy);
        inputProxy.setText(Hawk.get(HawkConfig.PROXY_SERVER, ""));

        findViewById(R.id.inputSubmit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String newApi = inputApi.getText().toString().trim();
                String newLive = inputLive.getText().toString().trim();
                String newEPG = inputEPG.getText().toString().trim();
                String newProxyServer = inputProxy.getText().toString().trim();
                // takagen99: Convert all to clan://localhost format
                if (newApi.startsWith("file://")) {
                    newApi = newApi.replace("file://", "clan://localhost/");
                } else if (newApi.startsWith("./")) {
                    newApi = newApi.replace("./", "clan://localhost/");
                }
                if (!newApi.isEmpty()) {
                    String oldApi = Hawk.get(HawkConfig.API_URL, "");
                    boolean apiChanged = !oldApi.equals(newApi);
                    
                    // 使用Hawk存储API历史记录
                    HawkListHelper.addToList(HawkConfig.API_HISTORY, newApi, 20);
                    
                    if (apiChanged) {
                        Hawk.put(HawkConfig.API_URL, newApi);
                        ToastHelper.showToast(getContext(), "正在清理缓存并切换配置");
                        UpdateCheckManager.get().clearCache();
                        ApiConfig.get().clearAllCache();
                        UpdateCheckManager.get().resetCheckState();
                        ToastHelper.showToast(getContext(), "缓存清理完成");
                    }

                    if (listener != null) {
                        listener.onchange(newApi, apiChanged);
                    }
                }
                // Capture Live input into Settings & Live History (max 20)
                Hawk.put(HawkConfig.LIVE_URL, newLive);
                if (!newLive.isEmpty()) {
                    // 使用Hawk存储Live历史记录
                    HawkListHelper.addToList(HawkConfig.LIVE_HISTORY, newLive, 20);
                }
                // Capture EPG input into Settings
                Hawk.put(HawkConfig.EPG_URL, newEPG);
                if (!newEPG.isEmpty()) {
                    // 使用Hawk存储EPG历史记录
                    HawkListHelper.addToList(HawkConfig.EPG_HISTORY, newEPG, 20);
                }
                // Capture oroxy server input into Settings
                Hawk.put(HawkConfig.PROXY_SERVER, newProxyServer);
                dismiss();
            }
        });
        findViewById(R.id.apiHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 使用Hawk读取API历史记录
                ArrayList<String> history = HawkListHelper.getList(HawkConfig.API_HISTORY);
                if (history.isEmpty()) {
                    ToastHelper.showToast(getContext(), "暂无历史配置地址");
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
                ApiHistoryDialog dialog = new ApiHistoryDialog(getOwnerActivity() != null ? getOwnerActivity() : getContext());
                dialog.setTip(HomeActivity.getRes().getString(R.string.dia_history_list));
                dialog.setAdapter(new ApiHistoryDialogAdapter.SelectDialogInterface() {
                    @Override
                    public void click(String value) {
                        String oldApi = Hawk.get(HawkConfig.API_URL, "");
                        boolean apiChanged = !oldApi.equals(value);

                        inputApi.setText(value);

                        if (apiChanged) {
                            Hawk.put(HawkConfig.API_URL, value);
                            ToastHelper.showToast(getContext(), "正在清理缓存并切换配置");
                            UpdateCheckManager.get().clearCache();
                            ApiConfig.get().clearAllCache();
                            UpdateCheckManager.get().resetCheckState();
                            ToastHelper.showToast(getContext(), "缓存清理完成");
                        }

                        if (listener != null) {
                            listener.onchange(value, apiChanged);
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
        findViewById(R.id.apiClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                inputApi.setText("");
            }
        });
        findViewById(R.id.liveHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 使用Hawk读取Live历史记录
                ArrayList<String> liveHistory = HawkListHelper.getList(HawkConfig.LIVE_HISTORY);
                if (liveHistory.isEmpty()) {
                    ToastHelper.showToast(getContext(), "暂无直播历史配置");
                    return;
                }
                String current = Hawk.get(HawkConfig.LIVE_URL, "");
                int idx = 0;
                // 如果当前地址在历史记录中，临时将其移到顶部（仅用于显示）
                if (liveHistory.contains(current)) {
                    liveHistory.remove(current);
                    liveHistory.add(0, current);
                    idx = 0;
                }
                ApiHistoryDialog dialog = new ApiHistoryDialog(getOwnerActivity() != null ? getOwnerActivity() : getContext());
                dialog.setTip(HomeActivity.getRes().getString(R.string.dia_history_live));
                dialog.setAdapter(new ApiHistoryDialogAdapter.SelectDialogInterface() {
                    @Override
                    public void click(String liveURL) {
                        inputLive.setText(liveURL);
                        Hawk.put(HawkConfig.LIVE_URL, liveURL);
                        dialog.dismiss();
                    }

                    @Override
                    public void del(String value, ArrayList<String> data) {
                        // 使用Hawk删除Live历史记录
                        HawkListHelper.putList(HawkConfig.LIVE_HISTORY, data);
                    }
                }, liveHistory, idx);
                dialog.show();
            }
        });
        findViewById(R.id.liveClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                inputLive.setText("");
            }
        });
        findViewById(R.id.EPGHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 使用Hawk读取EPG历史记录
                ArrayList<String> epgHistory = HawkListHelper.getList(HawkConfig.EPG_HISTORY);
                if (epgHistory.isEmpty()) {
                    ToastHelper.showToast(getContext(), "暂无EPG历史配置");
                    return;
                }
                String current = Hawk.get(HawkConfig.EPG_URL, "");
                int idx = 0;
                // 如果当前地址在历史记录中，临时将其移到顶部（仅用于显示）
                if (epgHistory.contains(current)) {
                    epgHistory.remove(current);
                    epgHistory.add(0, current);
                    idx = 0;
                }
                ApiHistoryDialog dialog = new ApiHistoryDialog(getOwnerActivity() != null ? getOwnerActivity() : getContext());
                dialog.setTip(HomeActivity.getRes().getString(R.string.dia_history_epg));
                dialog.setAdapter(new ApiHistoryDialogAdapter.SelectDialogInterface() {
                    @Override
                    public void click(String epgURL) {
                        inputEPG.setText(epgURL);
                        Hawk.put(HawkConfig.EPG_URL, epgURL);
                        dialog.dismiss();
                    }

                    @Override
                    public void del(String value, ArrayList<String> data) {
                        // 使用Hawk删除EPG历史记录
                        HawkListHelper.putList(HawkConfig.EPG_HISTORY, data);
                    }
                }, epgHistory, idx);
                dialog.show();
            }
        });
        findViewById(R.id.epgClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                inputEPG.setText("");
            }
        });
        findViewById(R.id.proxyClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                inputProxy.setText("");
            }
        });
        refreshQRCode();
    }

    private void refreshQRCode() {
        String address = ControlManager.get().getAddress(false);
        tvAddress.setText(String.format("手机/电脑扫描上方二维码或者直接浏览器访问地址\n%s", address));
        ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(address, AutoSizeUtils.mm2px(getContext(), 300), AutoSizeUtils.mm2px(getContext(), 300)));
    }

    public void setOnListener(OnListener listener) {
        this.listener = listener;
    }

    OnListener listener = null;

    public interface OnListener {
        void onchange(String api, boolean changed);
    }
}
