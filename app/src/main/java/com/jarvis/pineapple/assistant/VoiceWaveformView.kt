package com.jarvis.pineapple.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * 默认助手 overlay 声纹条。振幅由 [rms] 驱动，无输入时仍保持轻微呼吸动画。
 */
class VoiceWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD54F.toInt()
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.FILL
    }

    var rms: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private var phase = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val bars = 24
        val gap = w / bars
        val mid = h / 2f
        val minH = h * 0.12f
        val maxH = h * 0.46f
        for (i in 0 until bars) {
            val wave = (sin((phase + i * 0.45f).toDouble()).toFloat() + 1f) / 2f
            val amp = minH + (maxH - minH) * (0.25f + 0.75f * rms) * (0.35f + 0.65f * wave)
            val cx = gap * i + gap / 2f
            canvas.drawRoundRect(cx - 4f, mid - amp, cx + 4f, mid + amp, 4f, 4f, barPaint)
        }
        phase += 0.18f
        postInvalidateOnAnimation()
    }
}
