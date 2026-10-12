package com.pillbar.indicator.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.accessibility.AccessibilityManager
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.pillbar.indicator.PillAccessibilityService
import com.pillbar.indicator.color.ColorSampler
import com.pillbar.indicator.R
import com.pillbar.indicator.store.Settings as AppSettings

class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = AppSettings(this)

        findViewById<MaterialButton>(R.id.btn_open_accessibility).setOnClickListener {
            startActivity(Intent(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 启用开关
        findViewById<MaterialSwitch>(R.id.switch_enable).apply {
            isChecked = settings.enabled
            setOnCheckedChangeListener { _, v -> settings.enabled = v }
        }

        // 自动配色开关
        findViewById<MaterialSwitch>(R.id.switch_auto_color).apply {
            isChecked = settings.autoColor
            setOnCheckedChangeListener { _, v ->
                settings.autoColor = v
                updateManualColorEnabled()
            }
        }

        // 宽度 80~250dp，max=170，progress = widthDp - 80
        bindSeekBar(
            R.id.seek_width, R.id.value_width,
            (settings.widthDp - 80f).toInt(), 170
        ) { progress ->
            val v = 80f + progress
            settings.widthDp = v
            String.format("%.0f dp", v)
        }

        // 高度 2~12dp，max=10，progress = heightDp - 2
        bindSeekBar(
            R.id.seek_height, R.id.value_height,
            (settings.heightDp - 2f).toInt(), 10
        ) { progress ->
            val v = 2f + progress
            settings.heightDp = v
            String.format("%.0f dp", v)
        }

        // 底部距离 0~60dp，max=60，progress = bottomMarginDp
        bindSeekBar(
            R.id.seek_bottom_margin, R.id.value_bottom_margin,
            settings.bottomMarginDp.toInt(), 60
        ) { progress ->
            val v = progress.toFloat()
            settings.bottomMarginDp = v
            String.format("%.0f dp", v)
        }

        // 采样间隔 350~1500ms（系统 takeScreenshot 最小间隔约 333ms，低于必失败），max=1150
        bindSeekBar(
            R.id.seek_sample_interval, R.id.value_sample_interval,
            (settings.sampleIntervalMs - ColorSampler.MIN_INTERVAL_MS).toInt(), 1150
        ) { progress ->
            val v = (ColorSampler.MIN_INTERVAL_MS + progress)
            settings.sampleIntervalMs = v
            String.format("%d ms", v)
        }

        // 手动颜色
        val rg = findViewById<android.widget.RadioGroup>(R.id.rg_manual_color)
        if (settings.manualColor == AppSettings.VALUE_BLACK) rg.check(R.id.rb_black)
        else rg.check(R.id.rb_white)
        rg.setOnCheckedChangeListener { _, id ->
            settings.manualColor =
                if (id == R.id.rb_black) AppSettings.VALUE_BLACK else AppSettings.VALUE_WHITE
        }
        updateManualColorEnabled()

        // 桌面时自动隐藏
        findViewById<MaterialSwitch>(R.id.switch_hide_on_desktop).apply {
            isChecked = settings.hideOnDesktop
            setOnCheckedChangeListener { _, v -> settings.hideOnDesktop = v }
        }

        // 出现/消失动画
        findViewById<MaterialSwitch>(R.id.switch_show_hide_anim).apply {
            isChecked = settings.showHideAnimationEnabled
            setOnCheckedChangeListener { _, v -> settings.showHideAnimationEnabled = v }
        }

        // 沉浸模式
        findViewById<MaterialSwitch>(R.id.switch_immersive).apply {
            isChecked = settings.immersiveEnabled
            setOnCheckedChangeListener { _, v -> settings.immersiveEnabled = v }
        }

        // 沉浸超时 1~10秒，max=9，progress = timeout - 1
        bindSeekBar(
            R.id.seek_immersive_timeout, R.id.value_immersive_timeout,
            settings.immersiveTimeoutSec - 1, 9
        ) { progress ->
            val v = 1 + progress
            settings.immersiveTimeoutSec = v
            String.format("%d 秒", v)
        }

        // 弹跳开关
        findViewById<MaterialSwitch>(R.id.switch_bounce).apply {
            isChecked = settings.bounceEnabled
            setOnCheckedChangeListener { _, v -> settings.bounceEnabled = v }
        }

        // 弹跳幅度 2~20dp，max=18，progress = amplitude - 2
        bindSeekBar(
            R.id.seek_bounce_amp, R.id.value_bounce_amp,
            (settings.bounceAmplitudeDp - 2f).toInt(), 18
        ) { progress ->
            val v = 2f + progress
            settings.bounceAmplitudeDp = v
            String.format("%.0f dp", v)
        }

        // 弹跳持续 100~1000ms，max=900，progress = duration - 100
        bindSeekBar(
            R.id.seek_bounce_dur, R.id.value_bounce_dur,
            settings.bounceDurationMs - 100, 900
        ) { progress ->
            val v = 100 + progress
            settings.bounceDurationMs = v
            String.format("%d ms", v)
        }

        // 弹跳速度曲线
        val rgEasing = findViewById<android.widget.RadioGroup>(R.id.rg_bounce_easing)
        when (settings.bounceEasing) {
            AppSettings.VALUE_LINEAR -> rgEasing.check(R.id.rb_ease_linear)
            AppSettings.VALUE_EASE_OUT -> rgEasing.check(R.id.rb_ease_out)
            AppSettings.VALUE_EASE_IN_OUT -> rgEasing.check(R.id.rb_ease_in_out)
            AppSettings.VALUE_SPRING -> rgEasing.check(R.id.rb_ease_spring)
        }
        rgEasing.setOnCheckedChangeListener { _, id ->
            settings.bounceEasing = when (id) {
                R.id.rb_ease_linear -> AppSettings.VALUE_LINEAR
                R.id.rb_ease_out -> AppSettings.VALUE_EASE_OUT
                R.id.rb_ease_spring -> AppSettings.VALUE_SPRING
                else -> AppSettings.VALUE_EASE_IN_OUT
            }
        }
    }

    private fun updateManualColorEnabled() {
        val enabled = !settings.autoColor
        findViewById<RadioButton>(R.id.rb_black).isEnabled = enabled
        findViewById<RadioButton>(R.id.rb_white).isEnabled = enabled
    }

    private fun bindSeekBar(
        seekId: Int,
        valueId: Int,
        initialProgress: Int,
        max: Int,
        onChange: (Int) -> String
    ) {
        val seek = findViewById<SeekBar>(seekId)
        val tv = findViewById<TextView>(valueId)
        seek.max = max
        val init = initialProgress.coerceIn(0, max)
        seek.progress = init
        tv.text = onChange(init)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                tv.text = onChange(progress)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val tv = findViewById<TextView>(R.id.text_service_status)
        tv.text = if (isAccessibilityEnabled())
            getString(R.string.service_enabled) else getString(R.string.service_disabled)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        val expected = ComponentName(this, PillAccessibilityService::class.java)
        return list.any {
            val si = it.resolveInfo.serviceInfo
            si.packageName == expected.packageName && si.name == expected.className
        }
    }
}
