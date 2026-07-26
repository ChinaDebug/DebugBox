package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

/**
 * 壁纸设置弹窗：集中管理换壁纸与壁纸虚化开关
 */
public class WallpaperDialog extends BaseDialog {

    private final BaseActivity mActivity;
    private final TextView tvWallBlur;
    private OnWallpaperChangeListener changeListener;

    public WallpaperDialog(@NonNull Context context) {
        super(context);
        this.mActivity = context instanceof BaseActivity ? (BaseActivity) context : null;
        setContentView(R.layout.dialog_wallpaper);
        setCanceledOnTouchOutside(true);

        tvWallBlur = findViewById(R.id.tvWallBlur);
        refreshBlurText();

        findViewById(R.id.llWallChange).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                if (changeListener != null) {
                    changeListener.onChangeWallpaper();
                }
                dismiss();
            }
        });

        findViewById(R.id.llWallBlur).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                boolean newState = !Hawk.get(HawkConfig.WALLPAPER_BLUR, true);
                Hawk.put(HawkConfig.WALLPAPER_BLUR, newState);
                refreshBlurText();
                // 清空全局壁纸缓存，确保所有 Activity 按最新开关状态重新加载
                BaseActivity.invalidateWallpaper();
                // 立即刷新当前 Activity 的壁纸效果，避免 Activity 已销毁时操作窗口
                if (mActivity != null && !mActivity.isFinishing()) {
                    mActivity.changeWallpaper(true);
                }
            }
        });
    }

    private void refreshBlurText() {
        tvWallBlur.setText(Hawk.get(HawkConfig.WALLPAPER_BLUR, true) ? R.string.state_on : R.string.state_off);
    }

    public void setOnWallpaperChangeListener(OnWallpaperChangeListener listener) {
        this.changeListener = listener;
    }

    public interface OnWallpaperChangeListener {
        void onChangeWallpaper();
    }
}
