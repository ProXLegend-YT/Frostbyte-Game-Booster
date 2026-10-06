package com.frostbyte.gamebooster.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.frostbyte.gamebooster.util.dp

/**
 * Touch Lock: a see-through layer that swallows touches so a pocket/palm can't hit anything.
 * Android has no API to switch the touchscreen off, so this is the safest practical version.
 * System gestures, volume keys and the notification shade still work. Hold the pill 1.2s to unlock.
 */
class TouchLockOverlay(private val ctx: Context, private val onUnlock: () -> Unit) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (root != null) return true
        val r = FrameLayout(ctx)
        r.setBackgroundColor(Color.argb(1, 0, 0, 0))
        r.setOnTouchListener { _, _ -> true }

        val unlock = Runnable { onUnlock() }
        val pill = TextView(ctx).apply {
            text = "\uD83D\uDD12  Touch locked - hold to unlock"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
            alpha = 0.6f
            background = GradientDrawable().apply {
                setColor(Color.argb(170, 6, 12, 24))
                cornerRadius = ctx.dp(18).toFloat()
                setStroke(ctx.dp(1), Color.argb(120, 56, 208, 255))
            }
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { alpha = 1f; handler.postDelayed(unlock, 1200) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { alpha = 0.6f; handler.removeCallbacks(unlock) }
                }
                true
            }
        }
        r.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ctx.dp(10) })

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        return try {
            wm.addView(r, lp)
            root = r
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        root?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        root = null
    }
}
