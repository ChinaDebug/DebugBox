package com.github.tvbox.osc.ui.dialog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.ToastHelper;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.XXPermissions;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 描述
 *
 * @author pj567
 * @since 2020/12/27
 */
public class ResetDialog extends BaseDialog {
    private final TextView tvYes;
    private final TextView tvNo;

    @SuppressLint("MissingInflatedId")
    public ResetDialog(@NonNull @NotNull Context context) {
        super(context);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        setContentView(R.layout.dialog_reset);
        setCanceledOnTouchOutside(true);
        tvYes = findViewById(R.id.btnConfirm);
        tvNo = findViewById(R.id.btnCancel);
        tvYes.setText("确定");
        tvNo.setText("取消");
        tvYes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkStoragePermissionAndReset();
            }
        });
        tvNo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ResetDialog.this.dismiss();
            }
        });
    }

    private void checkStoragePermissionAndReset() {
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
                DefaultConfig.resetApp(context);
            } else {
                new AlertDialog.Builder(finalActivity)
                        .setTitle("权限提醒")
                        .setMessage("需要所有文件访问权限才能重置应用。是否前往设置开启权限？")
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
            DefaultConfig.resetApp(context);
            return;
        }

        // 申请权限
        XXPermissions.with(finalActivity)
                .permission(DefaultConfig.StoragePermissionGroup())
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean all) {
                        DefaultConfig.resetApp(context);
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {
                        new AlertDialog.Builder(finalActivity)
                                .setTitle("权限提醒")
                                .setMessage("存储权限被拒绝，无法重置应用。是否前往设置开启权限？")
                                .setPositiveButton("去设置", (dialog, which) -> {
                                    XXPermissions.startPermissionActivity(finalActivity, permissions);
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
    }
}
