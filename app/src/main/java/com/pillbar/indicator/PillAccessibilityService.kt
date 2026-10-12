package com.pillbar.indicator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.pillbar.indicator.color.ColorSampler
import com.pillbar.indicator.store.Settings
import com.pillbar.indicator.ui.PillView
import com.pillbar.indicator.ui.TouchDetectorView
import kotlin.math.abs

/**
 * 无障碍服务：在屏幕底部添加一个小白条 overlay。
 *
 * 触摸方案（全穿透 + 0×0 监听窗）：
 * - 小白条窗口 FLAG_NOT_TOUCHABLE：触摸完全穿透给系统手势导航 → 系统手势与原生动画 100% 保留
 * - 0×0 辅助窗口 + FLAG_WATCH_OUTSIDE_TOUCH：屏幕任意触摸会发 ACTION_OUTSIDE（带坐标），
 *   仅用于"感知触摸"（沉浸唤醒 + 判断是否在小白条区域以触发弹跳），不消费事件
 * - canPerformGestures="false"，不进入手势派发链
 *
 * 窗口设计：小白条宿主窗口上下各留 verticalRoom 空白供动画使用，胶囊居中绘制。
 */
class PillAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var settings: Settings
    private lateinit var pillView: PillView
    private var pillParams: WindowManager.LayoutParams? = null
    private var pillAdded = false

    // 0×0 触摸监听窗（不消费事件，仅感知触摸用于沉浸唤醒/弹跳）
    private var detectorView: TouchDetectorView? = null
    private var detectorAdded = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sampler: ColorSampler? = null
    private var sampleRunnable: Runnable? = null

    // ---- 窗口存活保障 ----
    private var watchdogRunnable: Runnable? = null
    private var screenReceiver: BroadcastReceiver? = null

    // ---- 桌面隐藏 ----
    private var launcherPackages: Set<String> = emptySet()
    private var isOnDesktop = false

    // ---- 沉浸模式 ----
    private var immersiveRunnable: Runnable? = null
    private var isImmersiveHidden = false
    private var lastMotionTime: Long = 0

    // ---- 弹跳节流 ----
    private var lastBounceTime: Long = 0
    private val bounceCooldownMs = 500L

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) mainHandler.post { reconfigure() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        // 保留截图能力（历史逻辑）
        try {
            val info = serviceInfo
            val caps = info.javaClass.getMethod("getCapabilities").invoke(info) as Int
            if (caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0) {
                info.javaClass.getMethod(
                    "setCapabilities", Int::class.javaPrimitiveType
                ).invoke(info, caps or AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT)
                setServiceInfo(info)
            }
        } catch (_: Throwable) {}

        // 关键：不再设置 motionEventSources（已删除，避免 Android 16 手势失效）

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settings = Settings(this)
        pillView = PillView(this)
        lastMotionTime = System.currentTimeMillis()
        settings.registerListener(prefsListener)
        reconfigure()
        startWatchdog()
        registerScreenReceiver()
        refreshLauncherPackages()
    }

    /** 查询系统中所有桌面启动器的包名。 */
    private fun refreshLauncherPackages() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val pm = packageManager
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            launcherPackages = list.map { it.activityInfo.packageName }.toSet()
        } catch (_: Throwable) {
            launcherPackages = emptySet()
        }
    }

    private fun isLauncherPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return launcherPackages.any { it == pkg }
    }

    // ------------------------------------------------------------------
    //  配置 & 窗口管理
    // ------------------------------------------------------------------

    private fun reconfigure() {
        updatePillViewConfig()
        if (settings.enabled && !shouldHideOnDesktop()) {
            ensurePillAdded()
            updatePillLayout()
            applyColorFromSettings()
            restartSampler()
            ensureDetectorAdded()
            if (!settings.immersiveEnabled) {
                if (isImmersiveHidden) {
                    isImmersiveHidden = false
                    pillView.showWithAnimation()
                }
                stopImmersiveTimer()
            } else {
                startImmersiveTimer()
            }
        } else {
            removePill()
            removeDetector()
            stopSampler()
            stopImmersiveTimer()
            isImmersiveHidden = false
        }
    }

    private fun updatePillViewConfig() {
        pillView.bounceEnabled = settings.bounceEnabled
        pillView.bounceAmplitudePx = dpToPx(settings.bounceAmplitudeDp)
        pillView.bounceDurationMs = settings.bounceDurationMs
        pillView.bounceEasing = settings.bounceEasing
        pillView.showHideAnimationEnabled = settings.showHideAnimationEnabled
    }

    private fun shouldHideOnDesktop(): Boolean {
        if (!settings.hideOnDesktop) return false
        return isOnDesktop
    }

    private fun onForegroundPackageChanged(pkg: String?) {
        val wasOnDesktop = isOnDesktop
        isOnDesktop = isLauncherPackage(pkg)
        if (wasOnDesktop != isOnDesktop) {
            reconfigure()
        }
    }

    // ------------------------------------------------------------------
    //  小白条窗口
    // ------------------------------------------------------------------

    private fun ensurePillAdded() {
        if (pillAdded && isPillAttached()) return
        if (pillAdded) {
            try { windowManager.removeView(pillView) } catch (_: Throwable) {}
            pillAdded = false
        }
        // 全穿透：小白条窗口不接收触摸，触摸直达系统手势导航 → 原生动画完整保留
        // 触摸感知由 0×0 监听窗的 ACTION_OUTSIDE 完成（见 ensureDetectorAdded）
        pillView.onTap = null
        pillView.onTouchOutside = null
        val params = createPillParams()
        try {
            windowManager.addView(pillView, params)
            pillParams = params
            pillAdded = true
            pillView.setPillSize(dpToPx(settings.widthDp), dpToPx(settings.heightDp))
            pillView.resetVisible()
            if (isImmersiveHidden) pillView.setHiddenImmediate()
        } catch (e: Throwable) {}
    }

    private fun isPillAttached(): Boolean {
        return try {
            pillView.isShown || pillView.windowToken != null || pillView.parent != null
        } catch (_: Throwable) {
            false
        }
    }

    /** 窗口上下为动画预留的空白：至少 10dp，且大于弹跳幅度 + 4dp。 */
    private fun verticalRoomPx(): Float {
        return maxOf(dpToPx(10f), dpToPx(settings.bounceAmplitudeDp) + dpToPx(4f))
    }

    private fun createPillParams(): WindowManager.LayoutParams {
        val room = verticalRoomPx()
        return WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            // 全穿透：FLAG_NOT_TOUCHABLE 让触摸直达系统手势导航层，原生手势动画 100% 保留
            flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            width = dpToPx(settings.widthDp).toInt()
            height = (dpToPx(settings.heightDp) + 2 * room).toInt()
            y = (dpToPx(settings.bottomMarginDp) - room).toInt()
            title = "PillBar"
        }
    }

    private fun updatePillLayout() {
        if (!pillAdded) return
        val p = pillParams ?: return
        val room = verticalRoomPx()
        p.width = dpToPx(settings.widthDp).toInt()
        p.height = (dpToPx(settings.heightDp) + 2 * room).toInt()
        p.y = (dpToPx(settings.bottomMarginDp) - room).toInt()
        pillView.setPillSize(dpToPx(settings.widthDp), dpToPx(settings.heightDp))
        try {
            windowManager.updateViewLayout(pillView, p)
        } catch (_: Throwable) {}
    }

    private fun applyColorFromSettings() {
        if (!settings.autoColor) {
            val c = if (settings.manualColor == Settings.VALUE_BLACK) Color.BLACK else Color.WHITE
            pillView.setPillColor(c)
        }
    }

    // ------------------------------------------------------------------
    //  颜色采样
    // ------------------------------------------------------------------

    private fun restartSampler() {
        stopSampler()
        if (!settings.autoColor) {
            applyColorFromSettings()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            pillView.setPillColor(Color.WHITE)
            return
        }
        sampler = ColorSampler(
            service = this,
            onColor = { color -> pillView.setPillColor(color) },
            getSampleRegion = { computeSampleRegion() }
        ).apply {
            setOnFailureListener { code, detail ->
                Toast.makeText(
                    this@PillAccessibilityService,
                    "颜色采样失败（码$code）: $detail\n可在设置中关闭自动黑白改用手动",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        val r = object : Runnable {
            override fun run() {
                sampler?.sample()
                // 系统对 takeScreenshot 有最小调用间隔（约 333ms），下限取 350ms，
                // 低于此值只会触发错误码 3（INTERVAL_TIME_SHORT）
                val interval = settings.sampleIntervalMs.coerceIn(ColorSampler.MIN_INTERVAL_MS, 5000L)
                mainHandler.postDelayed(this, interval)
            }
        }
        sampleRunnable = r
        mainHandler.post(r)
    }

    private fun stopSampler() {
        sampleRunnable?.let { mainHandler.removeCallbacks(it) }
        sampleRunnable = null
        sampler?.shutdown()
        sampler = null
    }

    private fun computeSampleRegion(): Rect {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val pillHeightPx = dpToPx(settings.heightDp)
        val bottomMarginPx = dpToPx(settings.bottomMarginDp)
        val pillTopY = screenH - bottomMarginPx - pillHeightPx
        val stripTop = (pillTopY - dpToPx(12f)).toInt().coerceAtLeast(0)
        val stripBottom = (pillTopY - dpToPx(2f)).toInt().coerceAtLeast(stripTop + 1)
        val halfWidth = dpToPx(settings.widthDp / 2f)
        val left = (screenW / 2 - halfWidth).toInt().coerceAtLeast(0)
        val right = (screenW / 2 + halfWidth).toInt().coerceAtMost(screenW)
        return Rect(left, stripTop, right, stripBottom)
    }

    // ------------------------------------------------------------------
    //  沉浸模式
    // ------------------------------------------------------------------

    private fun startImmersiveTimer() {
        stopImmersiveTimer()
        if (!settings.immersiveEnabled) return
        val r = object : Runnable {
            override fun run() {
                immersiveRunnable = null
                if (!settings.immersiveEnabled || !settings.enabled || shouldHideOnDesktop()) return
                if (!isImmersiveHidden) {
                    isImmersiveHidden = true
                    pillView.hideWithAnimation()
                }
            }
        }
        immersiveRunnable = r
        val timeout = settings.immersiveTimeoutSec.coerceIn(1, 30) * 1000L
        mainHandler.postDelayed(r, timeout)
    }

    private fun stopImmersiveTimer() {
        immersiveRunnable?.let { mainHandler.removeCallbacks(it) }
        immersiveRunnable = null
    }

    /**
     * 屏幕任意触摸（0×0 监听窗 ACTION_OUTSIDE 触发，带全屏坐标）：
     * - 沉浸唤醒
     * - 若坐标落在小白条区域附近，则触发弹跳
     * 触摸本身穿透给系统，系统手势导航原生处理，动画不受影响。
     */
    private fun onScreenTouch(x: Float, y: Float) {
        lastMotionTime = System.currentTimeMillis()
        if (isImmersiveHidden) {
            isImmersiveHidden = false
            pillView.showWithAnimation()
        }
        startImmersiveTimer()

        // 弹跳：触摸坐标落在小白条区域（上下各扩 12dp，左右各扩 16dp）才触发
        if (settings.bounceEnabled && !isImmersiveHidden) {
            val now = System.currentTimeMillis()
            if (now - lastBounceTime < bounceCooldownMs) return
            val m = resources.displayMetrics
            val pillH = dpToPx(settings.heightDp)
            val pillTop = m.heightPixels - dpToPx(settings.bottomMarginDp) - pillH
            val pillBottom = m.heightPixels - dpToPx(settings.bottomMarginDp)
            val halfW = dpToPx(settings.widthDp / 2f) + dpToPx(16f)
            if (y >= pillTop - dpToPx(12f) && y <= pillBottom + dpToPx(12f)
                && abs(x - m.widthPixels / 2f) <= halfW
            ) {
                lastBounceTime = now
                pillView.bounce()
            }
        }
    }

    // ------------------------------------------------------------------
    //  0×0 触摸监听窗（不消费事件，仅感知）
    // ------------------------------------------------------------------

    private fun ensureDetectorAdded() {
        if (detectorAdded && detectorView != null) return
        removeDetector()
        val v = TouchDetectorView(this).apply {
            onTouchAt = { x, y -> onScreenTouch(x, y) }
        }
        detectorView = v
        try {
            windowManager.addView(v, TouchDetectorView.createParams())
            detectorAdded = true
        } catch (_: Throwable) {}
    }

    private fun removeDetector() {
        detectorView?.let {
            try { windowManager.removeView(it) } catch (_: Throwable) {}
        }
        detectorAdded = false
        detectorView = null
    }

    // ------------------------------------------------------------------
    //  窗口存活保障：Watchdog + 广播
    // ------------------------------------------------------------------

    private fun startWatchdog() {
        stopWatchdog()
        val r = object : Runnable {
            override fun run() {
                if (settings.enabled && !shouldHideOnDesktop()) {
                    if (!isPillAttached()) {
                        pillAdded = false
                        ensurePillAdded()
                        updatePillLayout()
                    }
                    if (!detectorAdded) ensureDetectorAdded()
                }
                mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
        watchdogRunnable = r
        mainHandler.postDelayed(r, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun registerScreenReceiver() {
        unregisterScreenReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> {
                        mainHandler.post {
                            if (settings.enabled && !shouldHideOnDesktop()) {
                                pillAdded = false
                                ensurePillAdded()
                                updatePillLayout()
                                ensureDetectorAdded()
                                if (settings.autoColor) sampler?.sample()
                                if (settings.immersiveEnabled) {
                                    lastMotionTime = System.currentTimeMillis()
                                    startImmersiveTimer()
                                }
                            }
                        }
                    }
                }
            }
        }
        screenReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            registerReceiver(receiver, filter)
        } catch (_: Throwable) {}
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Throwable) {}
        }
        screenReceiver = null
    }

    // ------------------------------------------------------------------
    //  移除 & 生命周期
    // ------------------------------------------------------------------

    private fun removePill() {
        if (!pillAdded) return
        try { windowManager.removeView(pillView) } catch (_: Throwable) {}
        pillAdded = false
        pillParams = null
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null) onForegroundPackageChanged(pkg)
            if (settings.autoColor) sampler?.sample()
        }
        if (settings.enabled && !shouldHideOnDesktop() && !isPillAttached()) {
            mainHandler.post {
                pillAdded = false
                ensurePillAdded()
                updatePillLayout()
            }
        }
    }

    override fun onInterrupt() {
        // 系统中断反馈 ≠ 服务结束，窗口继续保留
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun cleanup() {
        try { settings.unregisterListener(prefsListener) } catch (_: Throwable) {}
        stopWatchdog()
        unregisterScreenReceiver()
        stopImmersiveTimer()
        removePill()
        removeDetector()
        stopSampler()
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 3000L
    }
}
