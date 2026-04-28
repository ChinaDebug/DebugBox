package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.cast.CastBridge;
import com.github.tvbox.osc.cast.model.CastDevice;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.ToastHelper;

import org.greenrobot.eventbus.EventBus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 推送设备选择对话框
 * 采用类似投屏的设备发现方式，自动搜索局域网内的TVBox设备
 */
public class PushDialog extends BaseDialog {

    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private TextView tvScanning;
    private DeviceAdapter adapter;
    private List<CastDevice> deviceList = new ArrayList<>();
    private CastBridge castBridge;
    private String vodId;
    private String sourceKey;
    private boolean isPushing = false;

    public PushDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_push);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        recyclerView = findViewById(R.id.recyclerView);
        progressBar = findViewById(R.id.progressBar);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvScanning = findViewById(R.id.tvScanning);

        // 设置RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new DeviceAdapter(deviceList);
        recyclerView.setAdapter(adapter);

        // 设备点击事件
        adapter.setOnItemClickListener((adapter1, view, position) -> {
            // 防止重复点击
            if (isPushing) return;
            isPushing = true;
            CastDevice device = deviceList.get(position);
            pushToDevice(device);
        });

        // 刷新按钮
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            startDiscovery();
        });

        // 取消按钮
        findViewById(R.id.btnCancel).setOnClickListener(v -> {
            dismiss();
        });

        castBridge = CastBridge.getInstance(getContext());

        // 开始搜索设备
        startDiscovery();
    }

    /**
     * 设置推送数据
     */
    public void setPushData(String vodId, String sourceKey) {
        this.vodId = vodId;
        this.sourceKey = sourceKey;
    }

    /**
     * 开始搜索设备
     */
    private void startDiscovery() {
        deviceList.clear();
        adapter.notifyDataSetChanged();

        progressBar.setVisibility(View.VISIBLE);
        tvScanning.setVisibility(View.VISIBLE);
        tvScanning.setText(R.string.push_scanning);
        tvEmpty.setVisibility(View.GONE);

        castBridge.startDeviceDiscovery(new CastBridge.DeviceDiscoveryCallback() {
            @Override
            public void onDeviceFound(CastDevice device) {
                // 只添加TVBox类型的设备（用于推送）
                if (device.getDeviceType() == CastDevice.DeviceType.TVBOX) {
                    // 过滤重复设备
                    boolean exists = false;
                    for (CastDevice d : deviceList) {
                        if (d.getHostAddress().equals(device.getHostAddress()) && d.getPort() == device.getPort()) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        deviceList.add(device);
                        adapter.notifyDataSetChanged();
                    }
                }

                tvEmpty.setVisibility(deviceList.isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onDiscoveryComplete() {
                progressBar.setVisibility(View.GONE);
                tvScanning.setVisibility(View.GONE);

                if (deviceList.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText(R.string.push_no_devices);
                }
            }

            @Override
            public void onDiscoveryError(String error) {
                progressBar.setVisibility(View.GONE);
                tvScanning.setVisibility(View.GONE);
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText(R.string.push_scan_error);
            }
        });
    }

    /**
     * 推送到指定设备
     */
    private void pushToDevice(CastDevice device) {
        if (vodId == null || sourceKey == null) {
            ToastHelper.showLong(getContext(), "推送数据为空");
            return;
        }

        ToastHelper.showToast(getContext(), "正在推送到: " + device.getName());

        List<String> list = new ArrayList<>();
        list.add(device.getHostAddress());
        // 推送使用 RemoteServer 的端口 9978，而不是投屏端口 9979
        list.add(String.valueOf(9978));
        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_PUSH_VOD, list));

        dismiss();
    }

    @Override
    public void dismiss() {
        castBridge.stopDeviceDiscovery();
        super.dismiss();
    }

    /**
     * 设备列表适配器
     */
    private static class DeviceAdapter extends BaseQuickAdapter<CastDevice, BaseViewHolder> {

        public DeviceAdapter(List<CastDevice> data) {
            super(R.layout.item_push_device, data);
        }

        @Override
        protected void convert(BaseViewHolder helper, CastDevice item) {
            helper.setText(R.id.tvDeviceName, item.getName());
            // 显示推送端口 9978
            helper.setText(R.id.tvDeviceIp, item.getHostAddress() + ":9978");
        }
    }
}
