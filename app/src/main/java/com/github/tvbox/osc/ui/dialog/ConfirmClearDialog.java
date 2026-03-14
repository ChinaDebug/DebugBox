package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.cache.VodCollect;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.ToastHelper;
import com.github.tvbox.osc.util.UpdateCheckManager;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.XXPermissions;

import org.greenrobot.eventbus.EventBus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class ConfirmClearDialog extends BaseDialog {
    private final TextView tvYes;
    private final TextView tvNo;
    private final TextView tvTitle;
    private final String mType;

    public ConfirmClearDialog(@NonNull @NotNull Context context, String type) {
        super(context);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        mType = type;
        setContentView(R.layout.dialog_confirm);
        setCanceledOnTouchOutside(true);
        tvYes = findViewById(R.id.btnConfirm);
        tvNo = findViewById(R.id.btnCancel);
        tvTitle = findViewById(R.id.confirmation);

        if ("Cache".equals(type)) {
            tvTitle.setText("确定清除所有数据吗？");
            tvTitle.setTextColor(0xFFB30000);
        } else if ("History".equals(type)) {
            tvTitle.setText("确定清除所有历史记录吗？");
            tvTitle.setTextColor(0xFFFFFFFF);
        } else if ("Collect".equals(type)) {
            tvTitle.setText("确定清除所有收藏吗？");
            tvTitle.setTextColor(0xFFFFFFFF);
        }

        tvYes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkStoragePermissionAndClear();
            }
        });
        tvNo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfirmClearDialog.this.dismiss();
            }
        });
    }

    private void checkStoragePermissionAndClear() {
        Context context = getContext();
        final Activity finalActivity = getOwnerActivity() != null ? getOwnerActivity() : (context instanceof Activity ? (Activity) context : null);
        if (finalActivity == null) {
            ToastHelper.showToast(context, "需要存储权限才能执行此操作");
            return;
        }

        // Android 11+ (API 30+) 检查 MANAGE_EXTERNAL_STORAGE 特殊权限
        // 注意：只有当应用的 targetSdkVersion >= 30 时才需要检查此权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && finalActivity.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                doClear();
            } else {
                new AlertDialog.Builder(finalActivity)
                        .setTitle("权限提醒")
                        .setMessage("需要所有文件访问权限才能清理数据。是否前往设置开启权限？")
                        .setPositiveButton("去设置", (dialog, which) -> {
                            XXPermissions.startPermissionActivity(finalActivity, DefaultConfig.StoragePermissionGroup());
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
            return;
        }

        // Android 10 及以下，或者 targetSdkVersion < 30 的应用使用传统存储权限
        if (XXPermissions.isGranted(finalActivity, DefaultConfig.StoragePermissionGroup())) {
            doClear();
            return;
        }

        // 申请权限
        XXPermissions.with(finalActivity)
                .permission(DefaultConfig.StoragePermissionGroup())
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean all) {
                        doClear();
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {
                        new AlertDialog.Builder(finalActivity)
                                .setTitle("权限提醒")
                                .setMessage("存储权限被拒绝，无法清理数据。是否前往设置开启权限？")
                                .setPositiveButton("去设置", (dialog, which) -> {
                                    XXPermissions.startPermissionActivity(finalActivity, permissions);
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
    }

    private void doClear() {
        Context context = getContext();
        if ("Collect".equals(mType)) {
            ToastHelper.showToast(context, "正在清理收藏数据");
            RoomDataManger.deleteVodCollectAll();
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_COLLECT_REFRESH));
            ToastHelper.showToast(context, "收藏数据已清理完成");
        } else if ("History".equals(mType)) {
            ToastHelper.showToast(context, "正在清理历史记录");
            List<VodInfo> vodInfoList = new ArrayList<>();
            RoomDataManger.deleteVodRecordAll();
            CacheManager.deleteAll();
            UpdateCheckManager.get().clearCache();
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH));
            ToastHelper.showToast(context, "历史记录已清理完成");
        } else if ("Cache".equals(mType)) {
            ToastHelper.showToast(context, "正在清理所有数据，请稍候");
            ApiConfig.get().clearAllCache();
            CacheManager.deleteAll();
            UpdateCheckManager.get().clearCache();
            RoomDataManger.deleteVodRecordAll();
            RoomDataManger.deleteVodCollectAll();
            RoomDataManger.deleteSearchHistoryAll();
            RoomDataManger.deleteStorageDriveAll();
            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_APP_REFRESH));
            ToastHelper.showToast(context, "所有数据已清理完成");
        }

        ConfirmClearDialog.this.dismiss();
    }

}