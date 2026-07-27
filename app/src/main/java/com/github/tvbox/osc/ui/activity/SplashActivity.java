package com.github.tvbox.osc.ui.activity;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.BounceInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.util.AppNavigator;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.DeviceUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.XXPermissions;
import com.hjq.permissions.permission.base.IPermission;
import com.orhanobut.hawk.Hawk;

import java.util.List;

public class SplashActivity extends BaseActivity {

    private static final int SPLASH_DURATION = 1200;
    private Handler handler;
    private boolean permissionRequested = false;
    private boolean splashStarted = false;
    private int permissionRetryCount = 0;
    private boolean isRequestingPermission = false;
    private final Runnable splashRunnable = this::navigateToHome;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_splash;
    }

    @Override
    protected void init() {
        handler = new Handler(Looper.getMainLooper());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // 启动动画期间禁止返回
            }
        });

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

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置页或权限弹窗返回后，若尚未进入主界面且未在申请权限，检查权限状态
        if (permissionRequested && !splashStarted && !isRequestingPermission) {
            if (DefaultConfig.isStoragePermissionGranted(this)) {
                startSplashDelay();
            } else {
                requestStoragePermission();
            }
        }
    }

    private void requestStoragePermission() {
        // 防止系统权限弹窗与自定义弹窗并发申请，避免多层弹窗叠加
        if (isRequestingPermission) {
            return;
        }

        // Android 11+ (API 30+) 检查 MANAGE_EXTERNAL_STORAGE 特殊权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                startSplashDelay();
                return;
            }
            // TV 设备提示语需说明遥控器操作
            String message = DeviceUtils.isTvDevice(this)
                    ? "TV 设备需要所有文件访问权限才能使用备份恢复、本地字幕等功能，请使用遥控器在设置中开启该权限。"
                    : "需要所有文件访问权限才能使用备份恢复、本地字幕等功能。是否前往设置开启权限？";
            new AlertDialog.Builder(SplashActivity.this)
                    .setTitle("权限提醒")
                    .setMessage(message)
                    .setPositiveButton("去设置", (dialog, which) -> {
                        XXPermissions.startPermissionActivity(SplashActivity.this,
                                XXPermissions.getDeniedPermissions(SplashActivity.this, DefaultConfig.storagePermissionList()));
                        permissionRequested = true;
                        // 跳转设置页是异步的，等 onResume 回来再判断
                    })
                    .setNegativeButton("跳过授权", (dialog, which) -> startSplashDelay())
                    .setCancelable(false)
                    .show();
            return;
        }

        // Android 10 及以下使用传统存储权限
        if (DefaultConfig.isStoragePermissionGranted(this)) {
            startSplashDelay();
            return;
        }

        isRequestingPermission = true;
        permissionRequested = true;
        DefaultConfig.withStoragePermission(XXPermissions.with(this))
                .request(new OnPermissionCallback() {
                    @Override
                    public void onPermissionResult(List<IPermission> grantedList, List<IPermission> deniedList) {
                        isRequestingPermission = false;
                        if (deniedList.isEmpty()) {
                            startSplashDelay();
                            return;
                        }

                        // 区分普通拒绝与永久拒绝（不再询问）
                        boolean neverAskAgain = false;
                        for (IPermission permission : deniedList) {
                            if (permission.isDoNotAskAgainPermission(SplashActivity.this)) {
                                neverAskAgain = true;
                                break;
                            }
                        }

                        if (neverAskAgain) {
                            showPermissionSettingDialog(deniedList, true);
                            return;
                        }

                        // 普通拒绝时先给一次重新申请的机会，避免部分 ROM 首次拒绝即误判为永久拒绝
                        if (permissionRetryCount < 1) {
                            permissionRetryCount++;
                            new AlertDialog.Builder(SplashActivity.this)
                                    .setTitle("权限提醒")
                                    .setMessage("存储权限被拒绝，TVBox 需要该权限才能使用备份恢复、本地字幕等功能。是否重新申请？")
                                    .setPositiveButton("重新申请", (dialog, which) -> requestStoragePermission())
                                    .setNegativeButton("跳过授权", (dialog, which) -> startSplashDelay())
                                    .setCancelable(false)
                                    .show();
                            return;
                        }

                        showPermissionSettingDialog(deniedList, false);
                    }
                });
    }

    private void showPermissionSettingDialog(List<IPermission> deniedList, boolean neverAskAgain) {
        String message = neverAskAgain
                ? "存储权限已被永久拒绝，请前往应用设置手动开启，否则备份恢复、本地字幕等功能将无法使用。"
                : "存储权限被拒绝，是否前往设置开启权限？";
        new AlertDialog.Builder(SplashActivity.this)
                .setTitle("权限提醒")
                .setMessage(message)
                .setPositiveButton("去设置", (dialog, which) -> {
                    XXPermissions.startPermissionActivity(SplashActivity.this, deniedList);
                    // 跳转设置页是异步的，不立即开始倒计时，等 onResume 回来再判断
                })
                .setNegativeButton("跳过授权", (dialog, which) -> startSplashDelay())
                .setCancelable(false)
                .show();
    }

    private void startSplashDelay() {
        if (splashStarted || isFinishing() || isDestroyed()) {
            return;
        }
        splashStarted = true;
        handler.postDelayed(splashRunnable, SPLASH_DURATION);
    }

    private void startTextShatterAnimation(LinearLayout container) {
        String appName = getString(R.string.app_name);
        container.removeAllViews();
        
        int charCount = appName.length();
        TextView[] charViews = new TextView[charCount];
        
        for (int i = 0; i < charCount; i++) {
            TextView charView = new TextView(this);
            charView.setText(String.valueOf(appName.charAt(i)));
            charView.setTextColor(ContextCompat.getColor(this, android.R.color.white));
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

    @SuppressWarnings("deprecation")
    private void navigateToHome() {
        Bundle bundle = null;
        if (getIntent() != null && getIntent().hasExtra("useCache")) {
            bundle = new Bundle();
            bundle.putBoolean("useCache", getIntent().getBooleanExtra("useCache", false));
        }
        AppNavigator.startHome(this, bundle);
        finish();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out);
        } else {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }
}
