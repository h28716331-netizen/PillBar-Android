package com.pillbar.indicator.store

import android.content.Context
import android.content.SharedPreferences

/**
 * 小白条配置，基于 SharedPreferences。
 * 进程内（设置页）写入后，无障碍服务通过 OnSharedPreferenceChangeListener 实时收到变更并重新布局。
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** 小白条宽度，单位 dp。iOS 18 约为 134dp。范围 [80, 250]。 */
    var widthDp: Float
        get() = prefs.getFloat(KEY_WIDTH_DP, DEFAULT_WIDTH_DP)
        set(v) = prefs.edit().putFloat(KEY_WIDTH_DP, v).apply()

    /** 小白条高度，单位 dp。iOS 18 约为 5dp。范围 [2, 12]。 */
    var heightDp: Float
        get() = prefs.getFloat(KEY_HEIGHT_DP, DEFAULT_HEIGHT_DP)
        set(v) = prefs.edit().putFloat(KEY_HEIGHT_DP, v).apply()

    /** 距屏幕底部的距离，单位 dp。范围 [0, 60]。 */
    var bottomMarginDp: Float
        get() = prefs.getFloat(KEY_BOTTOM_MARGIN_DP, DEFAULT_BOTTOM_MARGIN_DP)
        set(v) = prefs.edit().putFloat(KEY_BOTTOM_MARGIN_DP, v).apply()

    /** 是否根据背景自动切换黑白。 */
    var autoColor: Boolean
        get() = prefs.getBoolean(KEY_AUTO_COLOR, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_COLOR, v).apply()

    /** 手动颜色："black" 或 "white"，仅在 autoColor=false 时生效。 */
    var manualColor: String
        get() = prefs.getString(KEY_MANUAL_COLOR, VALUE_WHITE) ?: VALUE_WHITE
        set(v) = prefs.edit().putString(KEY_MANUAL_COLOR, v).apply()

    /** 颜色采样间隔，单位毫秒。范围 [100, 1500]。 */
    var sampleIntervalMs: Long
        get() = prefs.getLong(KEY_SAMPLE_INTERVAL_MS, DEFAULT_SAMPLE_INTERVAL_MS)
        set(v) = prefs.edit().putLong(KEY_SAMPLE_INTERVAL_MS, v).apply()

    /** 桌面（启动器）时自动隐藏小白条。 */
    var hideOnDesktop: Boolean
        get() = prefs.getBoolean(KEY_HIDE_ON_DESKTOP, false)
        set(v) = prefs.edit().putBoolean(KEY_HIDE_ON_DESKTOP, v).apply()

    // ---- 沉浸模式 ----

    /** 沉浸模式：无操作 N 秒后自动隐藏小白条，触摸屏幕时恢复。 */
    var immersiveEnabled: Boolean
        get() = prefs.getBoolean(KEY_IMMERSIVE_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_IMMERSIVE_ENABLED, v).apply()

    /** 沉浸模式超时秒数。范围 [1, 10]。 */
    var immersiveTimeoutSec: Int
        get() = prefs.getInt(KEY_IMMERSIVE_TIMEOUT_SEC, DEFAULT_IMMERSIVE_TIMEOUT_SEC)
        set(v) = prefs.edit().putInt(KEY_IMMERSIVE_TIMEOUT_SEC, v).apply()

    // ---- 弹跳动画 ----

    /** 触碰小白条时弹跳动画开关。 */
    var bounceEnabled: Boolean
        get() = prefs.getBoolean(KEY_BOUNCE_ENABLED, true)
        set(v) = prefs.edit().putBoolean(KEY_BOUNCE_ENABLED, v).apply()

    /** 弹跳幅度，单位 dp。范围 [2, 20]。 */
    var bounceAmplitudeDp: Float
        get() = prefs.getFloat(KEY_BOUNCE_AMPLITUDE_DP, DEFAULT_BOUNCE_AMPLITUDE_DP)
        set(v) = prefs.edit().putFloat(KEY_BOUNCE_AMPLITUDE_DP, v).apply()

    /** 弹跳持续时间，单位毫秒。范围 [100, 1000]。 */
    var bounceDurationMs: Int
        get() = prefs.getInt(KEY_BOUNCE_DURATION_MS, DEFAULT_BOUNCE_DURATION_MS)
        set(v) = prefs.edit().putInt(KEY_BOUNCE_DURATION_MS, v).apply()

    /** 弹跳速度曲线："linear" / "ease_out" / "ease_in_out" / "spring"。 */
    var bounceEasing: String
        get() = prefs.getString(KEY_BOUNCE_EASING, VALUE_EASE_IN_OUT) ?: VALUE_EASE_IN_OUT
        set(v) = prefs.edit().putString(KEY_BOUNCE_EASING, v).apply()

    // ---- 出现/消失动画 ----

    /** 出现/消失动画开关。关闭后直接显示/隐藏，无过渡。 */
    var showHideAnimationEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHOW_HIDE_ANIM, true)
        set(v) = prefs.edit().putBoolean(KEY_SHOW_HIDE_ANIM, v).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PREFS_NAME = "pillbar_settings"

        const val KEY_ENABLED = "enabled"
        const val KEY_WIDTH_DP = "width_dp"
        const val KEY_HEIGHT_DP = "height_dp"
        const val KEY_BOTTOM_MARGIN_DP = "bottom_margin_dp"
        const val KEY_AUTO_COLOR = "auto_color"
        const val KEY_MANUAL_COLOR = "manual_color"
        const val KEY_SAMPLE_INTERVAL_MS = "sample_interval_ms"
        const val KEY_HIDE_ON_DESKTOP = "hide_on_desktop"

        const val KEY_IMMERSIVE_ENABLED = "immersive_enabled"
        const val KEY_IMMERSIVE_TIMEOUT_SEC = "immersive_timeout_sec"
        const val KEY_BOUNCE_ENABLED = "bounce_enabled"
        const val KEY_BOUNCE_AMPLITUDE_DP = "bounce_amplitude_dp"
        const val KEY_BOUNCE_DURATION_MS = "bounce_duration_ms"
        const val KEY_BOUNCE_EASING = "bounce_easing"
        const val KEY_SHOW_HIDE_ANIM = "show_hide_anim"

        const val VALUE_BLACK = "black"
        const val VALUE_WHITE = "white"

        const val VALUE_LINEAR = "linear"
        const val VALUE_EASE_OUT = "ease_out"
        const val VALUE_EASE_IN_OUT = "ease_in_out"
        const val VALUE_SPRING = "spring"

        const val DEFAULT_WIDTH_DP = 134f
        const val DEFAULT_HEIGHT_DP = 5f
        const val DEFAULT_BOTTOM_MARGIN_DP = 4f
        const val DEFAULT_SAMPLE_INTERVAL_MS = 300L

        const val DEFAULT_IMMERSIVE_TIMEOUT_SEC = 2
        const val DEFAULT_BOUNCE_AMPLITUDE_DP = 8f
        const val DEFAULT_BOUNCE_DURATION_MS = 300
    }
}
