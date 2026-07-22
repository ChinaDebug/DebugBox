package com.github.tvbox.osc.ui.tv.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class BatteryView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect textBounds = new Rect();
    private int level = 100;
    private boolean charging = false;

    public BatteryView(Context context) {
        super(context);
    }

    public BatteryView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public BatteryView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setLevel(int level) {
        this.level = Math.max(0, Math.min(100, level));
        invalidate();
    }

    public void setCharging(boolean charging) {
        this.charging = charging;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float padding = Math.min(w, h) * 0.04f;
        float stroke = Math.max(1.5f, Math.min(w, h) * 0.055f);
        float corner = Math.min(w, h) * 0.1f;
        float tipWidth = w * 0.12f;
        float tipHeight = h * 0.28f;

        float bodyLeft = padding;
        float bodyTop = padding + tipHeight * 0.1f;
        float bodyRight = w - padding - tipWidth;
        float bodyBottom = h - padding - tipHeight * 0.1f;

        // 半透明黑色背景，让白色填充/文字在各种背景下都清晰
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66000000);
        RectF bodyBg = new RectF(bodyLeft, bodyTop, bodyRight, bodyBottom);
        canvas.drawRoundRect(bodyBg, corner, corner, paint);

        // 填充颜色：充电绿色、低电量红色、正常半透明浅灰
        paint.setStyle(Paint.Style.FILL);
        if (charging) {
            paint.setColor(0xFF4CAF50);
        } else if (level <= 20) {
            paint.setColor(0xFFFF5722);
        } else {
            paint.setColor(0xB3D0D0D0);
        }

        float fillPadding = stroke + padding * 0.5f;
        float fillLeft = bodyLeft + fillPadding;
        float fillTop = bodyTop + fillPadding;
        float fillRight = bodyRight - fillPadding;
        float fillBottom = bodyBottom - fillPadding;
        float fillWidth = fillRight - fillLeft;
        float currentFillRight = fillLeft + fillWidth * level / 100f;
        RectF fill = new RectF(fillLeft, fillTop, currentFillRight, fillBottom);
        canvas.drawRoundRect(fill, corner * 0.6f, corner * 0.6f, paint);

        // 电池外框
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(0xFFFFFFFF);
        RectF body = new RectF(bodyLeft, bodyTop, bodyRight, bodyBottom);
        canvas.drawRoundRect(body, corner, corner, paint);

        // 电池正极小凸起
        float tipLeft = bodyRight + stroke * 0.5f;
        float tipTop = h / 2f - tipHeight / 2f;
        float tipRight = tipLeft + tipWidth - stroke;
        float tipBottom = tipTop + tipHeight;
        RectF tip = new RectF(tipLeft, tipTop, tipRight, tipBottom);
        canvas.drawRoundRect(tip, corner * 0.5f, corner * 0.5f, paint);

        // 在电池内部绘制电量百分比（白色带黑色描边，确保在任意填充色上都可见）
        String text = String.valueOf(level);
        float textSize = (bodyBottom - bodyTop) * 0.72f;
        paint.setTextSize(textSize);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.getTextBounds(text, 0, text.length(), textBounds);
        float textX = (bodyLeft + bodyRight) / 2f;
        float textY = (bodyTop + bodyBottom) / 2f - textBounds.exactCenterY();

        float outlineWidth = Math.max(2.0f, textSize * 0.15f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(outlineWidth);
        paint.setColor(0xFF000000);
        canvas.drawText(text, textX, textY, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        canvas.drawText(text, textX, textY, paint);

    }
}
