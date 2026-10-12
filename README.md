# PillBar

一个面向 **Android 16（API 36）** 的 iOS 18 风格「导航小白条」应用。
通过无障碍服务（AccessibilityService）在屏幕底部绘制一个**纯视觉**的胶囊形小白条，**不拦截任何触摸事件**，从而完整保留系统原有的返回 / 最近任务 / Home 手势动画。

## 特性

- iOS 18 风格的底部胶囊小白条（home indicator）
- 大小（宽度 / 高度）、距底部位置**实时可调**
- **根据背景自动黑白**：背景偏亮显示黑色，偏暗显示白色
- 关闭自动时可手动指定黑色 / 白色
- 采样间隔可调，兼顾响应速度与功耗
- **不影响系统动画**：小白条仅作视觉装饰，触摸事件原样穿透给系统手势层

## 与 EdgeGesture 的关键差异

| 维度 | EdgeGesture (`helloklf/EdgeGesture`) | PillBar |
|------|--------------------------------------|---------|
| 触摸处理 | `TouchBarView.onTouchEvent()` 返回 `true` 消费触摸 | `FLAG_NOT_TOUCHABLE`，根本不接收触摸 |
| 手势动作 | 触发 `performGlobalAction` + 自定义 `AppSwitchActivity` | 不触发任何动作 |
| 系统动画 | 应用切换走 `AppSwitchAnimation` 自定义动画 | 完全保留系统原生动画 |
| 权限 | `SYSTEM_ALERT_WINDOW` + `WRITE_SECURE_SETTINGS` + ADB 辅助 | 仅无障碍服务，无需任何额外权限 |
| 配色方案 | ADB + 本地 HTTP + 后台截屏线程 | `AccessibilityService.takeScreenshot()`（API 30+ 原生） |

简言之：EdgeGesture 把小白条当作**手势触发区**并替换了系统动画；PillBar 把小白条当作**纯视觉装饰**，所以系统动画一丝不动。

## 技术架构

```
PillBar/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts          # compileSdk=36, minSdk=30, targetSdk=36
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml   # 声明 MainActivity + AccessibilityService
        ├── res/
        │   ├── xml/accessibility_config.xml   # canTakeScreenshots=true，不消费触摸
        │   ├── layout/activity_main.xml       # 设置页
        │   ├── drawable/ic_launcher_foreground.xml
        │   ├── mipmap-anydpi-v26/ic_launcher*.xml
        │   └── values/{strings,colors,themes}.xml
        └── java/com/pillbar/indicator/
            ├── PillAccessibilityService.kt   # 核心：添加 overlay + 不拦截触摸 + 配置监听
            ├── ui/PillView.kt                # 纯视觉胶囊 View
            ├── ui/MainActivity.kt            # 设置界面
            ├── color/ColorSampler.kt         # takeScreenshot 亮度采样
            └── store/Settings.kt             # SharedPreferences 配置
```

### 核心实现要点

1. **Overlay 类型**：使用 `TYPE_ACCESSIBILITY_OVERLAY`，无需 `SYSTEM_ALERT_WINDOW` 权限，且层级高于普通应用窗口。
2. **触摸穿透**：窗口 flags 同时设置 `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`，触摸事件直接穿透给系统手势导航层，小白条不参与任何输入分发。
3. **配色自适应**：
   - `AccessibilityService.takeScreenshot()` 取整屏画面（API 30+）；
   - 把 `HardwareBuffer` 转为软件 `Bitmap`（优先公开 `Bitmap.wrapHardwareBuffer`，回退反射 `createFromHardwareBuffer`）；
   - 仅在小白条**正上方 2~12dp** 的一条横带内稀疏采样平均亮度，避开小白条自身避免反馈；
   - 平均亮度 > 0.55 → 黑色，否则白色；仅当颜色变化时下发，避免无谓重绘。
4. **响应及时**：周期采样（默认 300ms）+ 监听 `TYPE_WINDOW_STATE_CHANGED` 在切换 App 时立即补一次采样；采样在后台单线程，`AtomicBoolean` 防重入。
5. **配置实时生效**：设置页写入 `SharedPreferences`，服务通过 `OnSharedPreferenceChangeListener` 收到变更后重新布局 / 重启采样，无需重启服务。

## 构建与运行

### 方式一：Android Studio（推荐）

1. Android Studio（Hedgehog / Iguana / Koala / Ladybug 或更新版本），JDK 17。
2. `File → Open` 选择 `PillBar` 目录。Studio 会自动识别 Gradle 项目；若 `gradle-wrapper.jar` 缺失，会提示生成，确认即可。
3. 等待 Gradle Sync 完成。
4. 连接 Android 设备（开发者选项 + USB 调试），点击 Run。

### 方式二：命令行

需先安装 Gradle 8.9+：

```bash
cd PillBar
gradle wrapper          # 首次生成 gradlew / gradle-wrapper.jar
./gradlew assembleDebug # 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 本仓库未随附 `gradlew` 脚本与 `gradle-wrapper.jar`（二进制），需要由 `gradle wrapper` 生成或由 Android Studio 自动补全。

## 使用步骤

1. 安装 App 后打开「PillBar」。
2. 在首页点击「**开启无障碍服务**」，跳转到系统无障碍设置，找到「PillBar 导航小白条」并打开开关。
3. 返回 App，状态显示「已启用」后，屏幕底部即出现小白条。
4. 调整：
   - 宽度 / 高度 / 距底部距离
   - 「根据背景自动黑白」开关
   - 关闭自动时手动选黑 / 白
   - 颜色采样间隔

## 注意事项与限制

- **手势导航下的系统小白条**：Android 在手势导航模式下本身就会绘制一条系统小白条。本 App 的小白条会与之重叠，有两种处理方式：
  1. 在系统设置切换为「3 按键导航」，则只有本 App 的小白条显示；
  2. 调大「距底部距离」让两者错开。
- **takeScreenshot 兼容性**：该 API 在少数厂商 ROM（尤其定制化深的国产 ROM）上可能被限制或返回失败。此时自动配色会静默失败，小白条保持默认白色；可关闭「自动黑白」改用手动。
- **首次启用**：无障碍服务启用后系统会有 10 秒确认倒计时（Android 安全机制），属正常现象。
- **进程保活**：无障碍服务由系统常驻，App 主进程被杀也不会影响小白条显示与配色，除非在系统设置中关闭服务。
- **目标设备**：minSdk = 30（Android 11），完整能力需 Android 14+ 以使用公开的 `Bitmap.wrapHardwareBuffer`；在 Android 11~13 上通过反射兼容。
