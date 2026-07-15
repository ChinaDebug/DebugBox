package com.github.tvbox.osc.ui.tv.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

@SuppressLint("AppCompatCustomView")
public class MarqueeTextView extends TextView {
    private boolean mSizeLocked;

    public MarqueeTextView(Context context) {
        this(context, null);
    }

    public MarqueeTextView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MarqueeTextView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setSelected(true);
        setSingleLine(true);
        setMarqueeRepeatLimit(-1);
        setEllipsize(TextUtils.TruncateAt.MARQUEE);
    }

    @Override
    public boolean isFocused() {
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mSizeLocked) return;
        post(() -> {
            int w = getWidth();
            int h = getHeight();
            ViewGroup.LayoutParams lp = getLayoutParams();
            if (w <= 0 || h <= 0 || lp == null) return;
            boolean changed = false;
            if (lp.width != w) { lp.width = w; changed = true; }
            if (lp.height != h) { lp.height = h; changed = true; }
            if (lp instanceof LinearLayout.LayoutParams
                    && ((LinearLayout.LayoutParams) lp).weight != 0f) {
                ((LinearLayout.LayoutParams) lp).weight = 0f;
                changed = true;
            }
            if (changed) setLayoutParams(lp);
            mSizeLocked = true;
        });
    }
}
