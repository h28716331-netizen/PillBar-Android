package com.pillbar.indicator.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/**
 * 纯视觉小白条。
 *
 * 宿主窗口比胶囊大（上下留动画空间），胶囊居中绘制。
 * 窗口 FLAG_NOT_TOUCHABLE：触摸完全穿透给系统手势导航 → 系统手势与原生动画 100% 保留。
 * 触摸感知（沉浸唤醒/弹跳）由服务的 0×0 监听窗 + ACTION_OUTSIDE 完成。
 */
class PillView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private var currentColor: Int = Color.WHITE

    // 胶囊自身尺寸（窗口大于胶囊，胶囊居中）
    private var pillWidthPx = 0f
    private var pillHeightPx = 0f

    // ---- 动画状态 ----
    private var currentAlpha: Float = 1f
    private var currentTranslationY: Float = 0f
    private var showAnim: ValueAnimator? = null
    private var hideAnim: ValueAnimator? = null
    private var bounceAnim: ValueAnimator? = null
    private var isHidden = false

    // ---- 弹跳配置（由服务设置）----
    var bounceAmplitudePx: Float = 0f
    var bounceDurationMs: Int = 300
    var bounceEasing: String = "ease_in_out"
    var bounceEnabled: Boolean = true

    // ---- 出现/消失动画配置 ----
    var showHideAnimationEnabled: Boolean = true

    // 注：窗口全穿透，本 View 不接收触摸；触摸感知由服务的 0×0 监听窗完成。
    // 保留空回调字段以兼容服务端赋值（设为 null）。
    var onTap: (() -> Unit)? = null
    var onTouchOutside: (() -> Unit)? = null

    /** 设置胶囊绘制尺寸（窗口尺寸之外的实际胶囊大小）。 */
    fun setPillSize(wPx: Float, hPx: Float) {
        if (wPx == pillWidthPx && hPx == pillHeightPx) return
        pillWidthPx = wPx
        pillHeightPx = hPx
        invalidate()
    }

    fun setPillColor(color: Int) {
        if (color == currentColor) return
        currentColor = color
        paint.color = color
        invalidate()
    }

    fun getPillColor(): Int = currentColor

    /** 带动画显示：淡入 + 从下方滑入。 */
    fun showWithAnimation() {
        if (!isHidden && currentAlpha >= 1f) return
        hideAnim?.cancel()
        hideAnim = null

        if (!showHideAnimationEnabled) {
            currentAlpha = 1f
            currentTranslationY = 0f
            isHidden = false
            applyState()
            return
        }

        currentAlpha = 0f
        currentTranslationY = SLIDE_DP * density()
        isHidden = false
        applyState()

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f) // ease-out
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                currentAlpha = t
                currentTranslationY = SLIDE_DP * density() * (1f - t)
                applyState()
            }
        }
        showAnim = anim
        anim.start()
    }

    /** 带动画隐藏：淡出 + 向下滑出。 */
    fun hideWithAnimation() {
        if (isHidden && currentAlpha <= 0f) return
        showAnim?.cancel()
        showAnim = null
        bounceAnim?.cancel()
        bounceAnim = null

        if (!showHideAnimationEnabled) {
            setHiddenImmediate()
            return
        }

        val startAlpha = currentAlpha
        val startTY = currentTranslationY
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = PathInterpolator(0.4f, 0f, 1f, 1f) // ease-in
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                currentAlpha = startAlpha * (1f - t)
                currentTranslationY = startTY + SLIDE_DP * density() * t
                applyState()
            }
        }
        hideAnim = anim
        anim.start()
        isHidden = true
    }

    /** 立即隐藏（无动画），用于窗口重建等场景。 */
    fun setHiddenImmediate() {
        showAnim?.cancel()
        hideAnim?.cancel()
        bounceAnim?.cancel()
        currentAlpha = 0f
        currentTranslationY = 0f
        isHidden = true
        applyState()
    }

    /** 弹跳动画：向上弹起再回落。 */
    fun bounce() {
        if (!bounceEnabled || isHidden) return
        bounceAnim?.cancel()

        val amplitude = bounceAmplitudePx
        val duration = bounceDurationMs.toLong()
        val interpolator = when (bounceEasing) {
            "ease_out" -> PathInterpolator(0f, 0f, 0.3f, 1f)
            "ease_in_out" -> PathInterpolator(0.42f, 0f, 0.58f, 1f)
            "spring" -> OvershootInterpolator(2f)
            else -> null // linear
        }

        val anim = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            this.duration = duration
            if (interpolator != null) {
                this.interpolator = interpolator
            }
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                currentTranslationY = -amplitude * t
                applyState()
            }
        }
        bounceAnim = anim
        anim.start()
    }

    private fun applyState() {
        alpha = currentAlpha
        translationY = currentTranslationY
        invalidate()
    }

    /** 重置到完全可见状态（无动画），用于窗口重建后。 */
    fun resetVisible() {
        showAnim?.cancel()
        hideAnim?.cancel()
        bounceAnim?.cancel()
        currentAlpha = 1f
        currentTranslationY = 0f
        isHidden = false
        applyState()
    }

    fun isHiddenState(): Boolean = isHidden

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || pillWidthPx <= 0f || pillHeightPx <= 0f) return
        // 胶囊在窗口内居中绘制
        val left = (w - pillWidthPx) / 2f
        val top = (h - pillHeightPx) / 2f
        val r = pillHeightPx / 2f
        canvas.drawRoundRect(left, top, left + pillWidthPx, top + pillHeightPx, r, r, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 小白条区域内按下：弹跳 + 沉浸唤醒
                onTap?.invoke()
            }
            MotionEvent.ACTION_OUTSIDE -> {
                // 窗口外触摸：沉浸唤醒
                onTouchOutside?.invoke()
            }
        }
        // 关键：返回 false，不消费事件，触摸继续派发给系统手势层（原生手势动画不受影响）
        return false
    }

    private fun density(): Float = resources.displayMetrics.density

    companion object {
        private const val SLIDE_DP = 6f
    }

    init {
        isClickable = true
        isFocusable = false
        setWillNotDraw(false)
    }
}
