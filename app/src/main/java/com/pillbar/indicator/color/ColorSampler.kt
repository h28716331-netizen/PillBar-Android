package com.pillbar.indicator.color

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 颜色自适应采样器。
 *
 * 工作原理：
 * 1) 通过 AccessibilityService.takeScreenshot() 获取整屏 HardwareBuffer（API 30+）；
 * 2) 把 HardwareBuffer 转为可读软件 Bitmap，计算「小白条正上方一小条」区域的平均亮度；
 * 3) 平均亮度 > 阈值 → 背景偏亮 → 小白条用黑色；否则用白色。
 *
 * Bitmap 转换降级链：
 *   1) Bitmap.wrapHardwareBuffer() + copy() —— API 34+ 公开方法
 *   2) 反射 Bitmap.createFromHardwareBuffer(HardwareBuffer, ColorSpace)
 *   3) 反射 Bitmap.createFromHardwareBuffer(HardwareBuffer) 单参
 *   4) 直接把 HardwareBuffer 写入 ByteBuffer 并手动解析（NDK 层 lock 反射）
 */
class ColorSampler(
    private val service: AccessibilityService,
    private val onColor: (Int) -> Unit,
    private val getSampleRegion: () -> Rect
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sampleExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PillBar-ColorSampler").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val pendingThrottled = AtomicBoolean(false)
    private var lastColor: Int = Color.WHITE
    private var failureCount = 0
    private var onFailure: ((Int, String) -> Unit)? = null

    /** 上一次计算的原始亮度（0~1），用于滞回判断。 */
    private var lastLuminance = 0.5

    /** 上次 takeScreenshot 请求时刻（elapsedRealtime）。系统对截图有最小间隔限制，过快调用返回错误码 3。 */
    private var lastRequestElapsedMs = 0L

    /**
     * 设置失败监听器。
     * code: 错误码（ERROR_DECODE / ERROR_THROW 或系统原始错误码）
     * detail: 失败详情，用于排查
     */
    fun setOnFailureListener(listener: ((Int, String) -> Unit)?) {
        onFailure = listener
    }

    /**
     * 发起一次采样。系统对 takeScreenshot 有最小调用间隔（约 333ms），
     * 间隔内调用会返回错误码 3（INTERVAL_TIME_SHORT）。这里做节流：
     * 距上次请求不足 MIN_INTERVAL_MS 时，延迟补足间隔后再发起，而不是直接失败。
     */
    fun sample() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastRequestElapsedMs
        if (sinceLast < MIN_INTERVAL_MS) {
            // 间隔不足：排队延迟发起；已有待执行请求时跳过（避免堆积）
            if (pendingThrottled.compareAndSet(false, true)) {
                mainHandler.postDelayed({
                    pendingThrottled.set(false)
                    doSample()
                }, MIN_INTERVAL_MS - sinceLast)
            }
            return
        }
        doSample()
    }

    private fun doSample() {
        if (!running.compareAndSet(false, true)) return
        lastRequestElapsedMs = SystemClock.elapsedRealtime()
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                sampleExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        var bmp: Bitmap? = null
                        try {
                            val hb = result.hardwareBuffer
                            val cs = result.colorSpace
                            Log.d(TAG, "Screenshot onSuccess: ${hb.width}x${hb.height}, format=${hb.format}")
                            bmp = hardwareBufferToBitmap(hb, cs)
                            if (bmp != null) {
                                failureCount = 0
                                val lum = sampleLuminance(bmp, getSampleRegion())
                                // 滞回：当前是白色时，亮度需超过上阈值才转黑；
                                // 当前是黑色时，亮度需低于下阈值才转白。避免阈值附近抖动，
                                // 也让中等灰色更稳定地保持白色。
                                val newColor = when {
                                    lastColor == Color.WHITE && lum > THRESHOLD_TO_BLACK -> Color.BLACK
                                    lastColor == Color.BLACK && lum < THRESHOLD_TO_WHITE -> Color.WHITE
                                    else -> lastColor
                                }
                                lastLuminance = lum
                                if (newColor != lastColor) {
                                    lastColor = newColor
                                    mainHandler.post { onColor(newColor) }
                                }
                            } else {
                                val msg = "所有 Bitmap 转换策略都失败了"
                                Log.w(TAG, msg)
                                recordFailure(ERROR_DECODE, msg)
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "sample decode failed", e)
                            recordFailure(ERROR_DECODE, "解码异常: ${e.message}")
                        } finally {
                            try { bmp?.recycle() } catch (_: Throwable) {}
                            try { result.hardwareBuffer.close() } catch (_: Throwable) {}
                            running.set(false)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (errorCode == ERROR_INTERVAL_TOO_SHORT) {
                            // 系统限流（正常已被节流避免）：不视为失败，静默跳过本轮
                            Log.d(TAG, "takeScreenshot throttled by system (code 3), skip this round")
                            running.set(false)
                            return
                        }
                        val msg = "系统返回错误码: $errorCode"
                        Log.w(TAG, "takeScreenshot onFailure: $msg")
                        recordFailure(errorCode, msg)
                        running.set(false)
                    }
                }
            )
        } catch (e: Throwable) {
            Log.w(TAG, "takeScreenshot throw", e)
            recordFailure(ERROR_THROW, "调用 takeScreenshot 异常: ${e.message}")
            running.set(false)
        }
    }

    private fun recordFailure(code: Int, detail: String) {
        failureCount++
        if (failureCount == 3) {
            mainHandler.post { onFailure?.invoke(code, detail) }
        }
    }

    /**
     * 采样区域亮度。使用中位数而非平均值，避免区域内少量亮色/暗色异常像素
     * （例如图标、文字边缘）把整体亮度拉偏，导致灰色背景误判。
     */
    private fun sampleLuminance(bmp: Bitmap, region: Rect): Double {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return lastLuminance
        val left = region.left.coerceIn(0, w - 1)
        val right = region.right.coerceIn(left + 1, w)
        val top = region.top.coerceIn(0, h - 1)
        val bottom = region.bottom.coerceIn(top + 1, h)
        val widthRange = right - left
        val heightRange = bottom - top
        val stepX = (widthRange / 24).coerceAtLeast(1)
        val stepY = (heightRange / 4).coerceAtLeast(1)
        val list = ArrayList<Double>(64)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val px = bmp.getPixel(x, y)
                val r = Color.red(px) / 255.0
                val g = Color.green(px) / 255.0
                val b = Color.blue(px) / 255.0
                list.add(0.299 * r + 0.587 * g + 0.114 * b)
                x += stepX
            }
            y += stepY
        }
        if (list.isEmpty()) return lastLuminance
        list.sort()
        // 中位数：偶数个时取中间两值平均
        val mid = list.size / 2
        return if (list.size % 2 == 0) {
            (list[mid - 1] + list[mid]) / 2.0
        } else {
            list[mid]
        }
    }

    /**
     * 多策略把 HardwareBuffer 转为软件 Bitmap（ARGB_8888，可 getPixel）。
     */
    private fun hardwareBufferToBitmap(hb: HardwareBuffer, colorSpace: ColorSpace?): Bitmap? {
        val cs = colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
        val details = StringBuilder()

        // 策略 1: 公开 API Bitmap.wrapHardwareBuffer (API 34+)
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Log.d(TAG, "try strategy 1: wrapHardwareBuffer (public API)")
                val wrapped = Bitmap.wrapHardwareBuffer(hb, cs)
                if (wrapped != null) {
                    val sw = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                    wrapped.recycle()
                    if (sw != null) {
                        Log.d(TAG, "strategy 1 succeeded")
                        return sw
                    }
                    details.append("wrapHardwareBuffer copy返回null; ")
                } else {
                    details.append("wrapHardwareBuffer返回null; ")
                }
            } catch (e: Throwable) {
                details.append("wrapHardwareBuffer失败: ${e.javaClass.simpleName} ${e.message}; ")
                Log.d(TAG, "strategy 1 failed: ${e.message}", e)
            }
        }

        // 策略 2: 反射 Bitmap.createFromHardwareBuffer(HardwareBuffer, ColorSpace)
        try {
            Log.d(TAG, "try strategy 2: createFromHardwareBuffer(hb, cs)")
            val method = Bitmap::class.java.getDeclaredMethod(
                "createFromHardwareBuffer",
                HardwareBuffer::class.java,
                ColorSpace::class.java
            )
            method.isAccessible = true
            val bmp = method.invoke(null, hb, cs) as? Bitmap
            if (bmp != null) {
                val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                bmp.recycle()
                if (sw != null) {
                    Log.d(TAG, "strategy 2 succeeded")
                    return sw
                }
                details.append("createFromHardwareBuffer(2p) copy返回null; ")
            }
        } catch (e: Throwable) {
            details.append("createFromHardwareBuffer(2p)失败: ${e.javaClass.simpleName}; ")
            Log.d(TAG, "strategy 2 failed: ${e.message}")
        }

        // 策略 3: 反射单参数 createFromHardwareBuffer(HardwareBuffer)
        try {
            Log.d(TAG, "try strategy 3: createFromHardwareBuffer(hb)")
            val method = Bitmap::class.java.getDeclaredMethod(
                "createFromHardwareBuffer",
                HardwareBuffer::class.java
            )
            method.isAccessible = true
            val bmp = method.invoke(null, hb) as? Bitmap
            if (bmp != null) {
                val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                bmp.recycle()
                if (sw != null) {
                    Log.d(TAG, "strategy 3 succeeded")
                    return sw
                }
                details.append("createFromHardwareBuffer(1p) copy返回null; ")
            }
        } catch (e: Throwable) {
            details.append("createFromHardwareBuffer(1p)失败: ${e.javaClass.simpleName}; ")
            Log.d(TAG, "strategy 3 failed: ${e.message}")
        }

        // 策略 4: 反射 nativeCopyPixelsFromHardwareBuffer
        try {
            Log.d(TAG, "try strategy 4: nativeCopyPixelsFromHardwareBuffer")
            val width = hb.width
            val height = hb.height
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val nativeHandle = hb.javaClass.getMethod("getNativeHandle").invoke(hb) as Long
            val method = Bitmap::class.java.getDeclaredMethod(
                "nativeCopyPixelsFromHardwareBuffer",
                Long::class.javaPrimitiveType,
                Bitmap::class.java
            )
            method.isAccessible = true
            method.invoke(null, nativeHandle, bmp)
            Log.d(TAG, "strategy 4 succeeded")
            return bmp
        } catch (e: Throwable) {
            details.append("nativeCopyPixels失败: ${e.javaClass.simpleName}; ")
            Log.d(TAG, "strategy 4 failed: ${e.message}")
        }

        Log.w(TAG, "all strategies failed: $details")
        lastFailureDetail = details.toString()
        return null
    }

    private var lastFailureDetail: String? = null

    fun shutdown() {
        try { sampleExecutor.shutdown() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "PillBar/ColorSampler"

        /**
         * 双阈值滞回：
         * - THRESHOLD_TO_BLACK：当前白色，亮度超过此值才转黑（背景需足够亮）
         * - THRESHOLD_TO_WHITE：当前黑色，亮度低于此值才转白（背景需足够暗）
         * 中等灰色（亮度约 0.40~0.60）会保持当前颜色，避免在阈值附近抖动，
         * 且灰色背景更倾向于保持白色小白条（iOS 风格）。
         */
        private const val THRESHOLD_TO_BLACK = 0.60
        private const val THRESHOLD_TO_WHITE = 0.40

        const val ERROR_DECODE = -1001
        const val ERROR_THROW = -1002

        /** 系统错误码：调用间隔过短（ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT）。 */
        const val ERROR_INTERVAL_TOO_SHORT = 3

        /**
         * 两次 takeScreenshot 的最小安全间隔。系统内部限流约 333ms，
         * 取 350ms 留出余量。低于此值会被节流排队。
         */
        const val MIN_INTERVAL_MS = 350L
    }
}
