package com.dastyab.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ScrollView

/**
 * DastyabMotion — Tactile native Android micro-interactions and entrance choreography.
 *
 * Pure Android SDK (`android.view.ViewPropertyAnimator`, `ValueAnimator`) — zero external
 * libraries required and 100% respects system animator disable/accessibility settings.
 */
object DastyabMotion {

    private val decel = DecelerateInterpolator(1.6f)
    private val overshoot = OvershootInterpolator(1.45f)

    fun enhanceActivityAfterLayout(activity: Activity) {
        if (!UiSafety.isActivityAlive(activity)) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: return
        root.post {
            if (!UiSafety.isActivityAlive(activity)) return@post
            attachPressSpringsRecursively(root)
            playEntranceChoreography(root)
        }
    }

    fun attachPressSpringsRecursively(root: View) {
        if (root is Button || (root.isClickable && root !is ScrollView)) {
            attachPressSpring(root)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                attachPressSpringsRecursively(root.getChildAt(i))
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachPressSpring(view: View) {
        if (view.getTag(R.id.dastyab_press_spring_bound) == true) return
        view.setTag(R.id.dastyab_press_spring_bound, true)
        view.setOnTouchListener { v, event ->
            if (!v.isEnabled || !ValueAnimator.areAnimatorsEnabled()) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(0.972f)
                        .scaleY(0.972f)
                        .setDuration(85L)
                        .setInterpolator(decel)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(200L)
                        .setInterpolator(overshoot)
                        .start()
                }
            }
            false
        }
    }

    fun playEntranceChoreography(root: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        if (root.getTag(R.id.dastyab_entrance_done) == true) return
        root.setTag(R.id.dastyab_entrance_done, true)

        val column = if (root is ScrollView && root.childCount > 0) {
            root.getChildAt(0) as? ViewGroup
        } else {
            root as? ViewGroup
        } ?: return

        val density = root.resources.displayMetrics.density
        val shiftPx = 14f * density
        var animatedIndex = 0

        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            // Skip tiny 1dp/14dp spacer views
            if (child.javaClass == View::class.java && child.height <= (24 * density)) continue

            val delay = (animatedIndex.coerceAtMost(9) * 32L)
            child.alpha = 0f
            child.translationY = shiftPx
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(280L)
                .setInterpolator(decel)
                .start()
            animatedIndex++
        }
    }

    fun switchModeContainer(target: View, others: List<View>, slideDirection: Int) {
        for (other in others) {
            if (other !== target) {
                other.animate().cancel()
                other.visibility = View.GONE
                other.alpha = 1f
                other.translationX = 0f
            }
        }
        val wasVisible = target.visibility == View.VISIBLE
        target.visibility = View.VISIBLE
        if (!ValueAnimator.areAnimatorsEnabled() || wasVisible) {
            target.alpha = 1f
            target.translationX = 0f
            return
        }
        val density = target.resources.displayMetrics.density
        val startOffset = (if (slideDirection >= 0) 18f else -18f) * density
        target.animate().cancel()
        target.alpha = 0f
        target.translationX = startOffset
        target.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(220L)
            .setInterpolator(decel)
            .start()
    }

    fun revealPanel(view: View) {
        val alreadyVisible = view.visibility == View.VISIBLE && view.alpha >= 0.99f
        view.visibility = View.VISIBLE
        if (alreadyVisible || !ValueAnimator.areAnimatorsEnabled()) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        val shift = 12f * view.resources.displayMetrics.density
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = shift
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(decel)
            .start()
    }

    fun pulseAttention(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        view.animate().cancel()
        view.animate()
            .scaleX(1.022f)
            .scaleY(1.022f)
            .setDuration(140L)
            .setInterpolator(decel)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(220L)
                    .setInterpolator(overshoot)
                    .start()
            }
            .start()
    }
}
