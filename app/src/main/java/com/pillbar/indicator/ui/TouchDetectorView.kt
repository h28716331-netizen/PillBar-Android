package com.pillbar.indicator.ui

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

/**
 * 0×0 辅助监听窗口：通过 ACTION_OUTSIDE 感知屏幕触摸（不消费事件）。
 *
 * 关键设计：
 * - 窗口大小 0×0：屏幕上任何触摸都在"窗口之外"，因此系统会发 ACTION_OUTSIDE
 * - ACTION_OUTSIDE 的 event.rawX/rawY 是触摸的全屏坐标
 * - 不加 FLAG_NOT_TOUCHABLE：否则系统不会给该窗口派发 ACTION_OUTSIDE
 *   （0×0 面积天然不会拦截任何触摸，无需 FLAG_NOT_TOUCHABLE）
 * - onTouchEvent 返回 false：ACTION_OUTSIDE 不消费事件
 *
 * 用途：为沉浸模式/弹跳提供触摸感知，无需 canPerformGestures，
 * 因此不会触发 Android 16 上系统手势导航失效的问题，系统原生动画完整保留。
 */
class TouchDetectorView(context: Context) : android.view.View(context) {

    /** 收到 ACTION_OUTSIDE 时回调，参数为触摸的全屏坐标。 */
    var onTouchAt: ((x: Float, y: Float) -> Unit)? = null

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(true)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                onTouchAt?.invoke(event.rawX, event.rawY)
            }
        }
        return false
    }

    companion object {
        fun createParams(): WindowManager.LayoutParams {
            return WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = PixelFormat.TRANSLUCENT
                // 不加 FLAG_NOT_TOUCHABLE，否则 ACTION_OUTSIDE 不会派发；
                // 0×0 面积不会拦截任何触摸
                flags = (FLAG_NOT_FOCUSABLE
                        or FLAG_LAYOUT_IN_SCREEN
                        or FLAG_LAYOUT_NO_LIMITS
                        or FLAG_WATCH_OUTSIDE_TOUCH)
                gravity = Gravity.TOP or Gravity.START
                width = 0
                height = 0
                x = 0
                y = 0
                title = "PillBarDetector"
            }
        }
    }
}
