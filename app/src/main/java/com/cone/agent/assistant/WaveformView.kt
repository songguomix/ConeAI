package com.cone.agent.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.sin

/**
 * The Gemini-style voice waveform: a row of rounded bars that breathe with the microphone level.
 * Call [setLevel] from the speech recognizer's RMS callback; the bars ease toward that level and
 * ripple with a travelling sine wave so it looks alive even at a steady volume.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3C4043.toInt()
        style = Paint.Style.FILL
    }
    private val barRect = RectF()

    private val barCount = 18
    private val barWidthDp = 3f
    private val barGapDp = 5f

    /** Smoothed 0..1 amplitude the bars ease toward. */
    private var level = 0.05f
    private var target = 0.05f
    private var phase = 0f
    private var animating = false

    var barColor: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }

    /** Feed a normalized 0..1 microphone level (map RMS dB before calling). */
    fun setLevel(normalized: Float) {
        target = normalized.coerceIn(0.03f, 1f)
        if (!animating) {
            animating = true
            postOnAnimation(ticker)
        }
    }

    fun reset() {
        target = 0.05f
        level = 0.05f
        phase = 0f
        invalidate()
    }

    private val ticker = object : Runnable {
        override fun run() {
            level += (target - level) * 0.25f
            phase += 0.45f
            // Decay the target so the wave settles when speech pauses.
            target = max(0.05f, target * 0.92f)
            invalidate()
            if (isAttachedToWindow) postOnAnimation(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val barWidth = barWidthDp * density
        val gap = barGapDp * density
        val totalWidth = barCount * barWidth + (barCount - 1) * gap
        val startX = (width - totalWidth) / 2f
        val centerY = height / 2f
        val maxBar = height * 0.9f
        val minBar = barWidth

        for (i in 0 until barCount) {
            // Bell-shaped envelope so the centre bars are tallest, plus a travelling ripple.
            val t = i.toFloat() / (barCount - 1)
            val envelope = sin(t * Math.PI).toFloat()
            val ripple = (sin(phase + i * 0.7f) + 1f) / 2f
            val h = (minBar + (maxBar - minBar) * level * (0.35f + 0.65f * ripple) * (0.4f + 0.6f * envelope))
                .coerceIn(minBar, maxBar)
            val left = startX + i * (barWidth + gap)
            barRect.set(left, centerY - h / 2f, left + barWidth, centerY + h / 2f)
            val r = barWidth / 2f
            canvas.drawRoundRect(barRect, r, r, paint)
        }
    }

    init {
        if (isInEditMode) paint.color = Color.GRAY
    }
}
