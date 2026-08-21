package com.cone.agent.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cone.agent.R
import com.cone.agent.ui.theme.ConeColors
import com.cone.agent.vision.ImageUtils
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen "circle to search"-style region picker shown over the assistant: displays [source]
 * (the screenshot of the app behind), lets the user drag a selection rectangle, and returns the
 * cropped bitmap via [onConfirm] (or null on cancel / empty selection).
 *
 * The crop is confirmed automatically the moment the user lifts their finger after framing a region —
 * no separate "confirm" tap is needed (just 圈 to capture). Only an explicit 取消 button remains.
 * Accent colors follow the app's Material 3 dynamic palette ("可变色彩").
 */
@SuppressLint("ViewConstructor")
class SnipOverlayView(
    context: Context,
    source: Bitmap,
    private val onConfirm: (Bitmap?) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {

    private val primary = ConeColors.tokens(context).primary
    private val softwareSource = ImageUtils.toSoftwareBitmap(source)
    private val selector = SelectorView(context, softwareSource, primary) { onConfirm(it) }

    init {
        setBackgroundColor(0xE6000000.toInt())
        isClickable = true // swallow taps so they don't fall through to the pill below
        addView(selector, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        addView(
            TextView(context).apply {
                text = context.getString(R.string.snip_hint)
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(44), dp(16), dp(12))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )

        // No confirm button — lifting the finger after a drag captures directly; only 取消 remains.
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(40))
        }
        row.addView(button(context.getString(R.string.action_cancel), 0x33FFFFFF) { onCancel() })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun button(label: String, bg: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(12), dp(22), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(bg) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Draws the screenshot fit-center and the drag selection, dimming everything outside it. */
    private class SelectorView(
        context: Context,
        private val bitmap: Bitmap,
        borderColor: Int,
        private val onSelectionDone: (Bitmap) -> Unit,
    ) : View(context) {
        private var imgRect = RectF()
        private var sel: RectF? = null
        private var startX = 0f
        private var startY = 0f
        private val dim = Paint().apply { color = 0xAA000000.toInt() }
        private val border = Paint().apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * resources.displayMetrics.density
            isAntiAlias = true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val scale = min(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
            val bw = bitmap.width * scale
            val bh = bitmap.height * scale
            val left = (w - bw) / 2f
            val top = (h - bh) / 2f
            imgRect = RectF(left, top, left + bw, top + bh)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            canvas.drawBitmap(bitmap, null, imgRect, null)
            val s = sel
            if (s != null) {
                // Dim the four regions around the selection, leaving it bright.
                canvas.drawRect(imgRect.left, imgRect.top, imgRect.right, s.top, dim)
                canvas.drawRect(imgRect.left, s.bottom, imgRect.right, imgRect.bottom, dim)
                canvas.drawRect(imgRect.left, s.top, s.left, s.bottom, dim)
                canvas.drawRect(s.right, s.top, imgRect.right, s.bottom, dim)
                canvas.drawRect(s, border)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x.coerceIn(imgRect.left, imgRect.right)
            val y = event.y.coerceIn(imgRect.top, imgRect.bottom)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = x; startY = y
                    sel = RectF(x, y, x, y)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    sel = RectF(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                    invalidate()
                }
                // Lifting the finger after framing a region captures it immediately (圈 to capture).
                // A too-small/empty selection is ignored so a stray tap doesn't fire a capture.
                MotionEvent.ACTION_UP -> crop()?.let { onSelectionDone(it) }
            }
            return true
        }

        fun crop(): Bitmap? {
            val s = sel ?: return null
            val minPx = 12f * resources.displayMetrics.density
            if (s.width() < minPx || s.height() < minPx || imgRect.width() <= 0f) return null
            val scale = bitmap.width / imgRect.width()
            val bx = ((s.left - imgRect.left) * scale).toInt().coerceIn(0, bitmap.width - 1)
            val by = ((s.top - imgRect.top) * scale).toInt().coerceIn(0, bitmap.height - 1)
            val bw = (s.width() * scale).toInt().coerceIn(1, bitmap.width - bx)
            val bh = (s.height() * scale).toInt().coerceIn(1, bitmap.height - by)
            return runCatching { Bitmap.createBitmap(bitmap, bx, by, bw, bh) }.getOrNull()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }
}

