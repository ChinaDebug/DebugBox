package com.github.tvbox.osc.subtitle.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Html;
import android.text.Layout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

import com.github.tvbox.osc.cache.CacheManager;
import com.github.tvbox.osc.subtitle.DefaultSubtitleEngine;
import com.github.tvbox.osc.subtitle.SubtitleEngine;
import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.util.StringUtils;

import org.jetbrains.annotations.Nullable;

import java.util.List;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * @author AveryZhong.
 */

@SuppressLint("AppCompatCustomView")
public class SimpleSubtitleView extends TextView
        implements SubtitleEngine, SubtitleEngine.OnSubtitleChangeListener,
        SubtitleEngine.OnSubtitlePreparedListener {

    private static final String EMPTY_TEXT = "";

    private SubtitleEngine mSubtitleEngine;

    public boolean isInternal = false;

    public boolean hasInternal = false;

    // 描边颜色
    private int strokeColor = Color.BLACK;

    public SimpleSubtitleView(final Context context) {
        super(context);
        init();
    }

    public SimpleSubtitleView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SimpleSubtitleView(final Context context, final AttributeSet attrs,
                              final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mSubtitleEngine = new DefaultSubtitleEngine();
        mSubtitleEngine.setOnSubtitlePreparedListener(this);
        mSubtitleEngine.setOnSubtitleChangeListener(this);
    }

    @Override
    public void onSubtitlePrepared(@Nullable final List<Subtitle> subtitles) {
        start();
    }

    @Override
    public void onSubtitleChanged(@Nullable final Subtitle subtitle) {
        if (StringUtils.isEmpty(subtitle) || subtitle.content == null) {
            setText(EMPTY_TEXT);
            return;
        }
        String text = subtitle.content;
        if (text.startsWith("Dialogue:") || text.startsWith("m ")) {
            setText(EMPTY_TEXT);
            return;
        }
        text = text.replaceAll("(?:\\r\\n)", "<br />");
        text = text.replaceAll("(?:\\r)", "<br />");
        text = text.replaceAll("(?:\\n)", "<br />");
        text = text.replaceAll("\\\\N", "<br />");
        text = text.replaceAll("\\{[\\s\\S]*?\\}", "");
        text = text.replaceAll("^.*?,.*?,.*?,.*?,.*?,.*?,.*?,.*?,.*?,", "");
        setText(Html.fromHtml(text));
    }

    @Override
    public void setSubtitlePath(final String path) {
        isInternal = false;
        mSubtitleEngine.setSubtitlePath(path);
    }

    @Override
    public void setSubtitleDelay(Integer mseconds) {
        mSubtitleEngine.setSubtitleDelay(mseconds);
    }

    public void setPlaySubtitleCacheKey(String cacheKey) {
        mSubtitleEngine.setPlaySubtitleCacheKey(cacheKey);
    }

    public String getPlaySubtitleCacheKey() {
        return mSubtitleEngine.getPlaySubtitleCacheKey();
    }

    public void clearSubtitleCache() {
        String subtitleCacheKey = getPlaySubtitleCacheKey();
        if (subtitleCacheKey != null && subtitleCacheKey.length() > 0) {
            CacheManager.delete(MD5.string2MD5(subtitleCacheKey), "");
        }
    }

    @Override
    public void reset() {
        mSubtitleEngine.reset();
    }

    @Override
    public void start() {
        mSubtitleEngine.start();
    }

    @Override
    public void pause() {
        mSubtitleEngine.pause();
    }

    @Override
    public void resume() {
        mSubtitleEngine.resume();
    }

    @Override
    public void stop() {
        mSubtitleEngine.stop();
    }

    @Override
    public void destroy() {
        mSubtitleEngine.destroy();
    }

    @Override
    protected void onDetachedFromWindow() {
        destroy();
        super.onDetachedFromWindow();
    }

    @Override
    public void setShadowLayer(float radius, float dx, float dy, int color) {
        // 只有当 radius > 0 时才更新描边颜色（用于阴影模式）
        // 否则保持当前的 strokeColor（用于描边模式）
        if (radius > 0) {
            this.strokeColor = color;
        }
        super.setShadowLayer(radius, dx, dy, color);
    }

    public void setBackGroundTextColor(int color) {
        if (color != this.strokeColor) {
            this.strokeColor = color;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 先绘制描边（在文字下方）
        drawStrokeText(canvas);
        // 再绘制正常文字
        super.onDraw(canvas);
    }

    private void drawStrokeText(Canvas canvas) {
        CharSequence text = getText();
        if (TextUtils.isEmpty(text)) return;

        // 获取文字布局信息
        Layout layout = getLayout();
        if (layout == null) return;

        // 创建描边画笔 - 只绘制轮廓，不填充
        TextPaint strokePaint = new TextPaint(getPaint());
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(3);
        strokePaint.setColor(strokeColor);

        // 保存 canvas 状态
        canvas.save();
        
        // 考虑 padding 偏移
        canvas.translate(getPaddingLeft(), getPaddingTop());

        // 绘制每一行的描边
        int lineCount = layout.getLineCount();
        for (int i = 0; i < lineCount; i++) {
            int lineStart = layout.getLineStart(i);
            int lineEnd = layout.getLineEnd(i);
            CharSequence lineText = text.subSequence(lineStart, lineEnd);

            // 获取行的坐标（相对于 layout）
            float x = layout.getLineLeft(i);
            float y = layout.getLineBaseline(i);

            // 只绘制描边轮廓
            canvas.drawText(lineText, 0, lineText.length(), x, y, strokePaint);
        }
        
        // 恢复 canvas 状态
        canvas.restore();
    }

    @Override
    public void bindToMediaPlayer(AbstractPlayer mediaPlayer) {
        mSubtitleEngine.bindToMediaPlayer(mediaPlayer);
    }

    @Override
    public void setOnSubtitlePreparedListener(OnSubtitlePreparedListener listener) {
        mSubtitleEngine.setOnSubtitlePreparedListener(listener);
    }

    @Override
    public void setOnSubtitleChangeListener(OnSubtitleChangeListener listener) {
        mSubtitleEngine.setOnSubtitleChangeListener(listener);
    }
}
