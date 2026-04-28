package com.github.tvbox.osc.ui.activity;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.BounceInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.app.AlertDialog;
import android.os.Environment;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.HawkConfig;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.XXPermissions;
import com.orhanobut.hawk.Hawk;

import java.util.List;

public class SplashActivity extends BaseActivity {

    private static final int SPLASH_DURATION = 1200;
    private Handler handler;
    private boolean permissionRequested = false;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_splash;
    }

    @Override
    protected void init() {
        handler = new Handler(Looper.getMainLooper());
        
        changeWallpaper(true);
        
        ImageView logoView = findViewById(R.id.splashLogo);
        LinearLayout textContainer = findViewById(R.id.textContainer);
        
        logoView.setAlpha(1f);
        logoView.setScaleX(1f);
        logoView.setScaleY(1f);
        logoView.setTranslationY(-500f);
        
        ObjectAnimator logoDrop = ObjectAnimator.ofFloat(logoView, "translationY", -500f, 0f);
        logoDrop.setDuration(800);
        logoDrop.setInterpolator(new BounceInterpolator());
        logoDrop.start();
        
        startTextShatterAnimation(textContainer);
        
        // 检查并申请存储权限
        requestStoragePermission();
    }

    private void requestStoragePermission() {
        // Android 11+ (API 30+) 检查 MANAGE_EXTERNAL_STORAGE 特殊权限
        // 注意：只有当应用的 targetSdkVersion >= 30 时才需要检查此权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                // 已有权限直接开始倒计时
                startSplashDelay();
                return;
            }
            // 没有权限，显示对话框引导用户去设置
            new AlertDialog.Builder(SplashActivity.this)
                    .setTitle("权限提醒")
                    .setMessage("需要所有文件访问权限才能使用备份恢复、本地字幕等功能。是否前往设置开启权限？")
                    .setPositiveButton("去设置", (dialog, which) -> {
                        XXPermissions.startPermissionActivity(SplashActivity.this, DefaultConfig.StoragePermissionGroup());
                        permissionRequested = true;
                        startSplashDelay();
                    })
                    .setNegativeButton("继续使用", (dialog, which) -> {
                        startSplashDelay();
                    })
                    .setCancelable(false)
                    .show();
            return;
        }

        // Android 10 及以下，或者 targetSdkVersion < 30 的应用使用传统存储权限
        if (XXPermissions.isGranted(this, DefaultConfig.StoragePermissionGroup())) {
            startSplashDelay();
            return;
        }

        // 申请权限
        permissionRequested = true;
        XXPermissions.with(this)
                .permission(DefaultConfig.StoragePermissionGroup())
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean all) {
                        // 权限已授予，开始倒计时
                        startSplashDelay();
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {
                        // 权限被拒绝，显示对话框提示
                        new AlertDialog.Builder(SplashActivity.this)
                                .setTitle("权限提醒")
                                .setMessage("存储权限被拒绝，备份恢复、本地字幕等功能将无法使用。是否前往设置开启权限？")
                                .setPositiveButton("去设置", (dialog, which) -> {
                                    XXPermissions.startPermissionActivity(SplashActivity.this, permissions);
                                    startSplashDelay();
                                })
                                .setNegativeButton("继续使用", (dialog, which) -> {
                                    startSplashDelay();
                                })
                                .setCancelable(false)
                                .show();
                    }
                });
    }

    private void startSplashDelay() {
        handler.postDelayed(() -> {
            navigateToHome();
        }, SPLASH_DURATION);
    }

    private void startTextShatterAnimation(LinearLayout container) {
        String appName = getString(R.string.app_name);
        container.removeAllViews();
        
        int charCount = appName.length();
        TextView[] charViews = new TextView[charCount];
        
        for (int i = 0; i < charCount; i++) {
            TextView charView = new TextView(this);
            charView.setText(String.valueOf(appName.charAt(i)));
            charView.setTextColor(getResources().getColor(android.R.color.white));
            charView.setTextSize(42);
            charView.setTypeface(null, android.graphics.Typeface.BOLD);
            charView.setAlpha(0f);
            
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            charView.setLayoutParams(params);
            container.addView(charView);
            charViews[i] = charView;
        }
        
        container.post(() -> {
            for (int i = 0; i < charCount; i++) {
                TextView charView = charViews[i];
                
                float randomX = (float) ((Math.random() - 0.5) * 400);
                float randomY = (float) ((Math.random() - 0.5) * 400);
                float randomScale = 0.5f + (float) (Math.random() * 0.5f);
                float randomRotation = (float) ((Math.random() - 0.5) * 180);
                
                charView.setTranslationX(randomX);
                charView.setTranslationY(randomY);
                charView.setScaleX(randomScale);
                charView.setScaleY(randomScale);
                charView.setRotation(randomRotation);
                
                ObjectAnimator alphaAnim = ObjectAnimator.ofFloat(charView, "alpha", 0f, 1f);
                ObjectAnimator translateXAnim = ObjectAnimator.ofFloat(charView, "translationX", randomX, 0f);
                ObjectAnimator translateYAnim = ObjectAnimator.ofFloat(charView, "translationY", randomY, 0f);
                ObjectAnimator scaleXAnim = ObjectAnimator.ofFloat(charView, "scaleX", randomScale, 1f);
                ObjectAnimator scaleYAnim = ObjectAnimator.ofFloat(charView, "scaleY", randomScale, 1f);
                ObjectAnimator rotateAnim = ObjectAnimator.ofFloat(charView, "rotation", randomRotation, 0f);
                
                AnimatorSet charAnim = new AnimatorSet();
                charAnim.playTogether(alphaAnim, translateXAnim, translateYAnim, scaleXAnim, scaleYAnim, rotateAnim);
                charAnim.setDuration(500 + (i * 50));
                charAnim.setStartDelay(200 + (i * 30));
                charAnim.start();
            }
        });
    }

    private void navigateToHome() {
        Intent intent = new Intent(this, HomeActivity.class);
        if (getIntent() != null && getIntent().hasExtra("useCache")) {
            intent.putExtra("useCache", getIntent().getBooleanExtra("useCache", false));
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void onBackPressed() {
        // 启动动画期间禁止返回
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }
}
