package com.dastyab.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * StatusHaloView — Anti-aliased animated status orb with soft ambient aura and
 * an expanding radar pulse wave for live VPN, route, and geo-verification states.
 */
class StatusHaloView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class HaloState {
        IDLE,
        CONNECTING,
        CONNECTED,
        WARNING,
        ERROR
    }

    private var state: HaloState = HaloState.IDLE
    private var primaryColor: Int = Color.parseColor("#9CA3AF")
    private var pulseFraction: Float = 0f
    private var pulseAnimator: ValueAnimator? = null

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(115, 255, 255, 255)
    }

    init {
        applyStateColor()
    }

    fun setHaloState(newState: HaloState) {
        if (state == newState && pulseAnimator != null) return
        state = newState
        applyStateColor()
        restartAnimatorIfVisible()
        invalidate()
    }

    private fun applyStateColor() {
        primaryColor = when (state) {
            HaloState.CONNECTED -> ThemeManager.resolveColor(context, R.attr.dastyabStatusReady, R.color.colorStatusReady)
            HaloState.CONNECTING, HaloState.WARNING ->
                ThemeManager.resolveColor(context, R.attr.dastyabStatusWarning, R.color.colorStatusWarning)
            HaloState.ERROR -> ThemeManager.resolveColor(context, R.attr.dastyabStatusError, R.color.colorStatusError)
            HaloState.IDLE -> ThemeManager.resolveColor(context, R.attr.dastyabTextSecondary, R.color.colorTextSecondary)
        }
    }

    private fun shouldAnimate(): Boolean {
        return isAttachedToWindow &&
            visibility == VISIBLE &&
            state != HaloState.IDLE &&
            DastyabMotion.areAnimationsEnabled(context)
    }

    private fun restartAnimatorIfVisible() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        if (!shouldAnimate()) {
            pulseFraction = 0f
            return
        }
        val durationMs = when (state) {
            HaloState.CONNECTING -> 1050L
            HaloState.CONNECTED -> 2300L
            HaloState.WARNING -> 1600L
            HaloState.ERROR -> 1400L
            HaloState.IDLE -> 2400L
        }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator(1.35f)
            addUpdateListener { anim ->
                pulseFraction = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartAnimatorIfVisible()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            restartAnimatorIfVisible()
        } else {
            pulseAnimator?.cancel()
            pulseAnimator = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxR = minOf(w, h) * 0.5f
        val coreR = maxR * 0.44f

        val r = Color.red(primaryColor)
        val g = Color.green(primaryColor)
        val b = Color.blue(primaryColor)

        // Ambient breathing soft glow behind core
        val breathAlpha = if (state == HaloState.IDLE) 42 else (58 + (28 * (1f - pulseFraction))).toInt()
        auraPaint.color = Color.argb(breathAlpha.coerceIn(0, 255), r, g, b)
        canvas.drawCircle(cx, cy, coreR * 1.62f, auraPaint)

        // Expanding pulse wave ring
        if (state != HaloState.IDLE && pulseFraction > 0f) {
            val waveR = coreR + (maxR - coreR - 1.5f) * pulseFraction
            val waveAlpha = ((1f - pulseFraction) * 165f).toInt().coerceIn(0, 255)
            wavePaint.color = Color.argb(waveAlpha, r, g, b)
            wavePaint.strokeWidth = (maxR * 0.14f).coerceAtLeast(1.5f)
            canvas.drawCircle(cx, cy, waveR, wavePaint)
        }

        // Solid crisp core orb
        corePaint.color = primaryColor
        canvas.drawCircle(cx, cy, coreR, corePaint)

        // Tiny top-left specular highlight
        canvas.drawCircle(cx - coreR * 0.28f, cy - coreR * 0.28f, coreR * 0.24f, specularPaint)
    }
}
