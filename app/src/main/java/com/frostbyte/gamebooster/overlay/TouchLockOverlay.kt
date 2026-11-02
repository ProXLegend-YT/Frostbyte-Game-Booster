package com.frostbyte.gamebooster.overlay

import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.frostbyte.gamebooster.util.dp

/**
 * Touch Lock: a see-through layer that swallows touches so a pocket/palm/ghost-touch can't hit anything.
 * Android has no API to switch the touchscreen off, so this is the safest practical version.
 * System gestures, volume keys and the notification shade still work. Hold the pill 5s to unlock
 * (long on purpose, so a ghost touch alone can't unlock it).
 */
class TouchLockOverlay(private val ctx: Context, private val onUnlock: () -> Unit) {
    companion object {
        private const val HOLD_MS = 5000L
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var holdAnimator: ValueAnimator? = null
    private var idlePulse: ObjectAnimator? = null
    private val accent = Color.parseColor("#38D0FF")

    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (root != null) return true
        val r = FrameLayout(ctx)
        r.setBackgroundColor(Color.argb(1, 0, 0, 0))
        r.setOnTouchListener { _, _ -> true }

        val ring = GradientDrawable().apply {
            setColor(Color.argb(170, 6, 12, 24))
            cornerRadius = ctx.dp(18).toFloat()
            setStroke(ctx.dp(1), accent)
        }
        val pill = TextView(ctx).apply {
            text = "\uD83D\uDD12  Touch locked \u00B7 hold 5s to unlock"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
            background = ring
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> startHold(this)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHold(this)
                }
                true
            }
        }
        r.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ctx.dp(10) })
        startIdlePulse(pill)

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

    /** A gentle idle pulse so the pill reads as "alive", not a dead sticker. */
    private fun startIdlePulse(v: View) {
        idlePulse?.cancel()
        val a = ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 0.95f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f),
        )
        a.duration = 1100
        a.repeatMode = ValueAnimator.REVERSE
        a.repeatCount = ValueAnimator.INFINITE
        a.start()
        idlePulse = a
    }

    /** Fills the ring over 5 real seconds of continuous holding; releasing early cancels it with no effect. */
    private fun startHold(v: TextView) {
        idlePulse?.cancel()
        v.alpha = 1f
        val ring = v.background as? GradientDrawable ?: return
        holdAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = HOLD_MS
        anim.addUpdateListener { a ->
            val t = a.animatedValue as Float
            ring.setStroke(ctx.dp(1) + (t * ctx.dp(3)).toInt(), accent)
            v.scaleX = 1f + t * 0.08f
            v.scaleY = 1f + t * 0.08f
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (holdAnimator === animation) onUnlock()
            }
        })
        anim.start()
        holdAnimator = anim
    }

    private fun cancelHold(v: TextView) {
        holdAnimator?.cancel()
        holdAnimator = null
        (v.background as? GradientDrawable)?.setStroke(ctx.dp(1), accent)
        v.scaleX = 1f
        v.scaleY = 1f
        startIdlePulse(v)
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        holdAnimator?.cancel()
        holdAnimator = null
        idlePulse?.cancel()
        idlePulse = null
        root?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        root = null
    }
}
