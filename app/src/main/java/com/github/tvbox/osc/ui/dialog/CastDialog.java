package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.cast.CastBridge;
import com.github.tvbox.osc.cast.model.CastData;
import com.github.tvbox.osc.cast.model.CastDevice;
import com.github.tvbox.osc.util.ToastHelper;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 投屏设备选择对话框
 */
public class CastDialog extends BaseDialog {

    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private TextView tvScanning;
    private DeviceAdapter adapter;
    private List<CastDevice> deviceList = new ArrayList<>();
    private CastBridge castBridge;
    private CastData castData;
    private OnCastListener listener;

    public CastDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_cast);
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
            CastDevice device = deviceList.get(position);
            startCast(device);
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
     * 设置投屏数据
     */
    public void setCastData(CastData data) {
        this.castData = data;
    }
    
    /**
     * 设置投屏监听器
     */
    public void setOnCastListener(OnCastListener listener) {
        this.listener = listener;
    }
    
    /**
     * 开始搜索设备
     */
    private void startDiscovery() {
        deviceList.clear();
        adapter.notifyDataSetChanged();
        
        progressBar.setVisibility(View.VISIBLE);
        tvScanning.setVisibility(View.VISIBLE);
        tvScanning.setText(R.string.cast_scanning);
        tvEmpty.setVisibility(View.GONE);
        
        castBridge.startDeviceDiscovery(new CastBridge.DeviceDiscoveryCallback() {
            @Override
            public void onDeviceFound(CastDevice device) {
                // 过滤本机设备
                if (!deviceList.contains(device)) {
                    deviceList.add(device);
                    adapter.notifyDataSetChanged();
                    
                    tvEmpty.setVisibility(deviceList.isEmpty() ? View.VISIBLE : View.GONE);
                }
            }
            
            @Override
            public void onDiscoveryComplete() {
                progressBar.setVisibility(View.GONE);
                tvScanning.setVisibility(View.GONE);
                
                if (deviceList.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText(R.string.cast_no_devices);
                }
            }
            
            @Override
            public void onDiscoveryError(String error) {
                progressBar.setVisibility(View.GONE);
                tvScanning.setVisibility(View.GONE);
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText(R.string.cast_scan_error);
            }
        });
    }
    
    /**
     * 开始投屏
     */
    private void startCast(CastDevice device) {
        if (castData == null) {
            ToastHelper.showToast(getContext(), "投屏数据为空");
            return;
        }
        
        ToastHelper.showToast(getContext(), "正在投屏到: " + device.getName());
        
        castBridge.castToDevice(device, castData, success -> {
            if (success) {
                ToastHelper.showToast(getContext(), "投屏成功");
                if (listener != null) {
                    listener.onCastSuccess(device);
                }
                dismiss();
            } else {
                ToastHelper.showToast(getContext(), "投屏失败，请重试");
                if (listener != null) {
                    listener.onCastFailed(device, "连接失败");
                }
            }
        });
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
            super(R.layout.item_cast_device, data);
        }
        
        @Override
        protected void convert(BaseViewHolder helper, CastDevice item) {
            helper.setText(R.id.tvDeviceName, item.getName());
            helper.setText(R.id.tvDeviceIp, item.getHostAddress());
            
            // 根据设备类型设置图标
            int iconRes = R.drawable.ic_cast_device;
            switch (item.getDeviceType()) {
                case TVBOX:
                    iconRes = R.drawable.ic_cast_tvbox;
                    break;
                case DLNA:
                    iconRes = R.drawable.ic_cast_dlna;
                    break;
                case AIRPLAY:
                    iconRes = R.drawable.ic_cast_airplay;
                    break;
                case CHROMECAST:
                    iconRes = R.drawable.ic_cast_chromecast;
                    break;
            }
            helper.setImageResource(R.id.ivDeviceIcon, iconRes);
        }
    }
    
    /**
     * 投屏监听器
     */
    public interface OnCastListener {
        void onCastSuccess(CastDevice device);
        void onCastFailed(CastDevice device, String error);
    }
}
