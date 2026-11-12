package com.tvbox.kotlin.core.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class BatteryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textBounds = Rect()
    private var level = 100
    private var charging = false

    fun setLevel(level: Int) {
        this.level = level.coerceIn(0, 100)
        invalidate()
    }

    fun setCharging(charging: Boolean) {
        this.charging = charging
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        val padding = Math.min(w, h) * 0.04f
        val stroke = Math.max(1.5f, Math.min(w, h) * 0.055f)
        val corner = Math.min(w, h) * 0.1f
        val tipWidth = w * 0.12f
        val tipHeight = h * 0.28f

        val bodyLeft = padding
        val bodyTop = padding + tipHeight * 0.1f
        val bodyRight = w - padding - tipWidth
        val bodyBottom = h - padding - tipHeight * 0.1f

        paint.style = Paint.Style.FILL
        paint.color = 0x66000000
        val bodyBg = RectF(bodyLeft, bodyTop, bodyRight, bodyBottom)
        canvas.drawRoundRect(bodyBg, corner, corner, paint)

        paint.style = Paint.Style.FILL
        paint.color = when {
            charging -> 0xFF4CAF50.toInt()
            level <= 20 -> 0xFFFF5722.toInt()
            else -> 0xB3D0D0D0.toInt()
        }

        val fillPadding = stroke + padding * 0.5f
        val fillLeft = bodyLeft + fillPadding
        val fillTop = bodyTop + fillPadding
        val fillRight = bodyRight - fillPadding
        val fillBottom = bodyBottom - fillPadding
        val fillWidth = fillRight - fillLeft
        val currentFillRight = fillLeft + fillWidth * level / 100f
        val fill = RectF(fillLeft, fillTop, currentFillRight, fillBottom)
        canvas.drawRoundRect(fill, corner * 0.6f, corner * 0.6f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = 0xFFFFFFFF.toInt()
        val body = RectF(bodyLeft, bodyTop, bodyRight, bodyBottom)
        canvas.drawRoundRect(body, corner, corner, paint)

        val tipLeft = bodyRight + stroke * 0.5f
        val tipTop = h / 2f - tipHeight / 2f
        val tipRight = tipLeft + tipWidth - stroke
        val tipBottom = tipTop + tipHeight
        val tip = RectF(tipLeft, tipTop, tipRight, tipBottom)
        canvas.drawRoundRect(tip, corner * 0.5f, corner * 0.5f, paint)

        val text = level.toString()
        val textSize = (bodyBottom - bodyTop) * 0.72f
        paint.textSize = textSize
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = true
        paint.getTextBounds(text, 0, text.length, textBounds)
        val textX = (bodyLeft + bodyRight) / 2f
        val textY = (bodyTop + bodyBottom) / 2f - textBounds.exactCenterY()

        val outlineWidth = Math.max(2.0f, textSize * 0.15f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outlineWidth
        paint.color = 0xFF000000.toInt()
        canvas.drawText(text, textX, textY, paint)

        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawText(text, textX, textY, paint)
    }
}
