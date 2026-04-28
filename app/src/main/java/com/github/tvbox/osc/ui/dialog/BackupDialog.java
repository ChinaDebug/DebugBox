package com.github.tvbox.osc.ui.dialog;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.github.tvbox.osc.util.ToastHelper;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.ui.adapter.BackupAdapter;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.XXPermissions;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BackupDialog extends BaseDialog {

    public BackupDialog(@NonNull @NotNull Context context) {
        super(context);
        if (context instanceof Activity) {
            setOwnerActivity((Activity) context);
        }
        setContentView(R.layout.dialog_backup);
        TvRecyclerView tvRecyclerView = findViewById(R.id.list);
        BackupAdapter adapter = new BackupAdapter();
        tvRecyclerView.setAdapter(adapter);
        adapter.setNewData(allBackup());
        adapter.setOnItemChildClickListener(new BaseQuickAdapter.OnItemChildClickListener() {
            @Override
            public void onItemChildClick(BaseQuickAdapter adapter, View view, int position) {
                if (view.getId() == R.id.tvName) {
                    checkStoragePermission(() -> {
                        restore((String) adapter.getItem(position));
                    });
                } else if (view.getId() == R.id.tvDel) {
                    checkStoragePermission(() -> {
                        delete((String) adapter.getItem(position));
                        adapter.setNewData(allBackup());
                    });
                }
            }
        });
        findViewById(R.id.backupNow).setOnClickListener(v -> {
            checkStoragePermission(() -> {
                backup();
                adapter.setNewData(allBackup());
            });
        });
    }

    private interface PermissionCallback {
        void onGranted();
    }

    private void checkStoragePermission(PermissionCallback callback) {
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
                callback.onGranted();
            } else {
                new AlertDialog.Builder(finalActivity)
                        .setTitle("权限提醒")
                        .setMessage("需要所有文件访问权限才能使用备份恢复功能。是否前往设置开启权限？")
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
            callback.onGranted();
            return;
        }

        // 申请权限
        XXPermissions.with(finalActivity)
                .permission(DefaultConfig.StoragePermissionGroup())
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean all) {
                        callback.onGranted();
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {
                        new AlertDialog.Builder(finalActivity)
                                .setTitle("权限提醒")
                                .setMessage("存储权限被拒绝，备份恢复功能无法使用。是否前往设置开启权限？")
                                .setPositiveButton("去设置", (dialog, which) -> {
                                    XXPermissions.startPermissionActivity(finalActivity, permissions);
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
    }

    List<String> allBackup() {
        ArrayList<String> result = new ArrayList<>();
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File file = new File(root + "/tvbox_backup/");
            File[] list = file.listFiles();
            Arrays.sort(list, new Comparator<File>() {
                @Override
                public int compare(File o1, File o2) {
                    if (o1.isDirectory() && o2.isFile()) return -1;
                    return o1.isFile() && o2.isDirectory() ? 1 : o2.getName().compareTo(o1.getName());
                }
            });
            if (file.exists()) {
                for (File f : list) {
                    if (result.size() > 10) {
                        FileUtils.recursiveDelete(f);
                        continue;
                    }
                    if (f.isDirectory()) {
                        result.add(f.getName());
                    }
                }
            }
        } catch (Throwable e) {
            LOG.e(e);
        }
        return result;
    }

    void restore(String dir) {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File backup = new File(root + "/tvbox_backup/" + dir);
            if (backup.exists()) {
                File db = new File(backup, "sqlite");
                if (AppDataManager.restore(db)) {
                    byte[] data = FileUtils.readSimple(new File(backup, "hawk"));
                    if (data != null) {
                        String hawkJson = new String(data, "UTF-8");
                        JSONObject jsonObject = new JSONObject(hawkJson);
                        Iterator<String> it = jsonObject.keys();
                        SharedPreferences sharedPreferences = App.getInstance().getSharedPreferences("Hawk2", Context.MODE_PRIVATE);
                        while (it.hasNext()) {
                            String key = it.next();
                            String value = jsonObject.getString(key);
                            if (key.equals("cipher_key")) {
                                App.getInstance().getSharedPreferences("crypto.KEY_256", Context.MODE_PRIVATE).edit().putString(key, value).commit();
                            } else {
                                sharedPreferences.edit().putString(key, value).commit();
                            }
                        }
                        ToastHelper.showToast(getContext(), HomeActivity.getRes().getString(R.string.set_rest_ok));
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                restartApp();
                            }
                        }, 3000);
                    } else {
                        ToastHelper.showToast(getContext(), HomeActivity.getRes().getString(R.string.set_rest_fail_hk));
                    }
                } else {
                    ToastHelper.showToast(getContext(), HomeActivity.getRes().getString(R.string.set_rest_fail_db));
                }
            }
        } catch (Throwable e) {
            LOG.e(e);
        }
    }
    private void restartApp() {
        Context context = getContext();
        if (context != null) {
            Intent i = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(i);
                System.exit(0);
            }
        }
    }

    void backup() {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File file = new File(root + "/tvbox_backup/");
            if (!file.exists())
                file.mkdirs();
            Date now = new Date();
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.getDefault());
            File backup = new File(file, f.format(now));
            backup.mkdirs();
            File db = new File(backup, "sqlite");
            if (AppDataManager.backup(db)) {
                SharedPreferences sharedPreferences = App.getInstance().getSharedPreferences("Hawk2", Context.MODE_PRIVATE);
                JSONObject jsonObject = new JSONObject();
                for (Map.Entry<String, ?> entry : sharedPreferences.getAll().entrySet()) {
                    jsonObject.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
                }
                sharedPreferences = App.getInstance().getSharedPreferences("crypto.KEY_256", Context.MODE_PRIVATE);
                for (Map.Entry<String, ?> entry : sharedPreferences.getAll().entrySet()) {
                    jsonObject.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
                }
                if (!FileUtils.writeSimple(jsonObject.toString().getBytes("UTF-8"), new File(backup, "hawk"))) {
                    backup.delete();
                    ToastHelper.showToast(getContext(), HomeActivity.getRes().getString(R.string.set_bkup_fail_hk));
                } else {
                    ToastHelper.showToast(getContext(), HomeActivity.getRes().getString(R.string.set_bkup_ok));
                }
            } else {
                ToastHelper.showToast(getContext(), HomeActivity.getRes().getString(R.string.set_bkup_fail_db));
                backup.delete();
            }
        } catch (Throwable e) {
            LOG.e(e);
            ToastHelper.showToast(getContext(), HomeActivity.getRes().getString(R.string.set_bkup_fail));
        }
    }

    void delete(String dir) {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            File backup = new File(root + "/tvbox_backup/" + dir);
            FileUtils.recursiveDelete(backup);
            ToastHelper.showToast(getContext(), HomeActivity.getRes().getString(R.string.set_bkup_del));
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

}
