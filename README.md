# ScanPlus (Android 高性能条码与二维码扫描库)

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-24-blue.svg)](https://developer.android.com/about/dashboards)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-purple.svg)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/Base-Huawei%20ScanKit%20Plus-orange.svg)](https://developer.huawei.com/consumer/cn/hms/huawei-scankit/)

**ScanPlus** 是一款基于华为统一扫码服务（**Huawei ScanKit Plus 2.15.0.301**）核心算法深度重构与全面增强的现代化 Android 扫码库。

不仅完整保留了 ScanKit 业界顶尖的计算机视觉与深度学习识码能力（**抗反光、强光、暗光、微小远距、折痕、污损、倾斜角度变形**），更彻底重构了底层相机采集管道，解决了原版 ScanKit 在现代 Android 系统及多样化硬件平台上的痛点。

---

## ✨ 核心特性与升级亮点

### 1. 🔄 双相机引擎与智能自适应降级 (`Camera1` + `Camera2`)
* **`CameraMode.AUTO` (默认推荐)**：优先调用经典 `Camera1`；在部分现代设备因驱动限制或权限异常导致开启失败时，**全自动无感降级至现代 `Camera2`**，确保 100% 打开成功率与极佳设备兼容性。
* **显式模式支持**：支持根据项目需要强行指定 `CameraMode.CAMERA1` 或 `CameraMode.CAMERA2`。

### 2. 🔇 全链路日志降噪与精细化受控 (`ScanPlusConfig`)
* **消除遥测刷屏**：原版 ScanKit 中高频的帧分析日志和华为分析框架（HATool / HiAnalytics）后台上报日志会被自动静默，还你纯净 Logcat。
* **丰富管控 API**：提供全局开关 `isLogEnabled`、最小日志级别过滤 `minLogLevel`、一键全静音 `disableAllLogs()` 以及自定义日志委托 `logDelegate`。

### 3. 🎨 现代化组件与 Jetpack Compose 开箱即用
* **`Camera2ScanView`**：封装好的现代化扫码 View，内置高质感激光扫描动画、半透明取景遮罩、角标指示器。
* **原生手势交互**：内置双指缩放（Pinch-to-Zoom）与轻触对焦（Tap-to-Focus）。
* **Compose 友好**：支持在 Jetpack Compose 中通过 `AndroidView` 几行代码快速集成。

### 4. 🧭 屏幕朝向协议与异形屏自由校正
* 完美兼容 ScanKit 原版屏幕朝向规范，横竖屏切换无拉伸畸变。
* 支持通过 `ScanPlusConfig.customDisplayOrientation` 自定义预览旋转角度，轻松适配工业手持机 (PDA)、车载屏、收银机等异形设备。

### 5. 🧠 完整本地离线 AI 加速与多格式支持
* 内置完整离线深度学习模型文件（`detect.ms`, `angle.ms`, `corner.ms`）与高性能 C++ 动态库（`libscannative.so`），**无需联网，不依赖 HMS Core 客户端**。
* **支持 13 种主流码制**：
  * **一维码**：EAN-8, EAN-13, UPC-A, UPC-E, Codabar, Code 39, Code 93, Code 128, ITF-14
  * **二维码**：QR Code, Data Matrix, PDF417, Aztec

### 6. 📦 丰富形态全支持
* **Default View**：一行代码唤起全屏扫码界面；
* **Customized View**：高度自定义扫码界面（`RemoteView`）；
* **Bitmap Decode**：相册图片/内存位图快速解码；
* **MultiProcessor**：同屏多码同时识别（同步 / 异步）；
* **Barcode Generator**：一维码与个性化二维码生成。

---

## 🏗 工程结构

```
scanplus/
├── scanplus/                           # 核心库模块 (Android Library)
│   ├── libs/
│   │   └── scanplus-core.jar           # 剥离相机层后的 ScanKit 算法核心
│   ├── src/main/
│   │   ├── jniLibs/                    # arm64-v8a / armeabi-v7a 原生解码 .so
│   │   ├── assets/                     # 离线深度学习模型 (detect.ms / angle.ms 等)
│   │   ├── aidl/                       # AIDL 进程间通信定义
│   │   └── java/com/huawei/hms/scankit/
│   │       ├── ScanPlusConfig.kt       # 全局配置中心 (相机模式/日志等级/屏幕朝向)
│   │       ├── camera/                 # 统一相机驱动抽象 (ICameraManager, Camera1, Camera2)
│   │       ├── camera2/                # 现代化组件 (Camera2ScanManager, Camera2ScanView)
│   │       ├── p/                      # 内部桥接实现 (j0 调度中枢, o4 日志拦截)
│   │       └── util/                   # 图像 NV21 转换与日志输出
├── app/                                # 示例工程 (Demo App)
│   └── src/main/java/
│       ├── com/example/scankitdemokotlin/ # 官方经典 6 大模式演示
│       └── com/wzvideni/scanplus/         # Jetpack Compose + Camera2ScanView 现代化演示
└── gradle/libs.versions.toml           # 统一依赖版本控制
```

---

## 🚀 快速接入

### 1. 添加仓库地址

在项目根目录的 `settings.gradle.kts` 中添加华为 Maven 镜像库：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://developer.huawei.com/repo/") }
    }
}
```

### 2. 添加模块依赖

在应用的 `build.gradle.kts` 中引入 `:scanplus` 模块：

```kotlin
android {
    defaultConfig {
        minSdk = 24
        
        ndk {
            // 指定支持的 ABI 架构
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }
}

dependencies {
    implementation(project(":scanplus"))
}
```

### 3. 配置权限

在 `AndroidManifest.xml` 中声明所需权限：

```xml
<uses-feature android:name="android.hardware.camera.any" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.FLASHLIGHT" />
```

> **注意**：Android 6.0 (API 23) 及以上系统，请确保在启动扫码前动态申请并获得 `Manifest.permission.CAMERA` 运行时权限。

---

## 💻 使用指南

### 1. 全局配置 (`ScanPlusConfig`)

在 `Application.onCreate` 或扫码组件初始化前进行全局设置：

```kotlin
import com.huawei.hms.scankit.ScanPlusConfig
import com.huawei.hms.scankit.camera.CameraMode
import android.util.Log

// 1. 设置相机后端模式：AUTO (默认自动兼容降级) / CAMERA1 / CAMERA2
ScanPlusConfig.cameraMode = CameraMode.AUTO

// 2. 日志控制 (默认关闭高频日志，仅打印 WARN 和 ERROR)
ScanPlusConfig.isLogEnabled = false      // 设为 true 可开启详细调试日志
ScanPlusConfig.minLogLevel = Log.WARN    // 设置过滤的最低优先级
ScanPlusConfig.disableAllLogs()          // 一键静默全部日志 (包括错误)

// 3. (可选) 异形屏幕/特定工控机预览方向手动覆盖 (0, 90, 180, 270)
// ScanPlusConfig.customDisplayOrientation = 90

// 4. (可选) 自定义日志输出拦截器
ScanPlusConfig.logDelegate = { priority, tag, message, throwable ->
    // 接入你自己的日志框架 (如 Timber, XLog 等)
}
```

---

### 2. 使用现代化组件 (`Camera2ScanView`)

#### XML 布局方式

在布局文件中引入 `Camera2ScanView`：

```xml
<com.huawei.hms.scankit.camera2.Camera2ScanView
    android:id="@+id/scanView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

在 Activity 中绑定结果回调与生命周期：

```kotlin
class ScanActivity : AppCompatActivity() {
    private lateinit var scanView: Camera2ScanView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        scanView = findViewById(R.id.scanView)

        // 监听扫描结果
        scanView.onScanResult = { results ->
            results.firstOrNull()?.let { scan ->
                Log.i("ScanResult", "识别到内容: ${scan.originalValue}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scanView.startScan()
    }

    override fun onPause() {
        super.onPause()
        scanView.stopScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanView.release()
    }
}
```

#### 手电筒开关

```kotlin
// 打开或关闭手电筒
scanView.setTorch(true)
```

---

### 3. 在 Jetpack Compose 中使用

使用 `AndroidView` 快速嵌入扫码视图：

```kotlin
@Composable
fun ScanScreen(modifier: Modifier = Modifier) {
    var scanView: Camera2ScanView? by remember { mutableStateOf(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> scanView?.startScan()
                Lifecycle.Event.ON_PAUSE -> scanView?.stopScan()
                Lifecycle.Event.ON_DESTROY -> scanView?.release()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            scanView?.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            Camera2ScanView(ctx).apply {
                this.onScanResult = { results ->
                    results.firstOrNull()?.let { scan ->
                        println("扫码结果: ${scan.originalValue}")
                    }
                }
                scanView = this
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
```

---

### 4. 经典 Default View (一行代码拉起内置扫码界面)

```kotlin
import com.huawei.hms.hmsscankit.ScanUtil
import com.huawei.hms.ml.scan.HmsScan
import com.huawei.hms.ml.scan.HmsScanAnalyzerOptions

val REQUEST_CODE_SCAN = 0X01

// 启动扫码界面
ScanUtil.startScan(
    this,
    REQUEST_CODE_SCAN,
    HmsScanAnalyzerOptions.Creator().setHmsScanTypes(HmsScan.ALL_SCAN_TYPE).create()
)

// 在 onActivityResult 中接收结果
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (resultCode != RESULT_OK || data == null) return

    if (requestCode == REQUEST_CODE_SCAN) {
        val obj: HmsScan? = data.getParcelableExtra(ScanUtil.RESULT)
        println("扫码结果: ${obj?.originalValue}")
    }
}
```

---

### 5. 自定义扫码界面 (`RemoteView`)

若需要完全自定义全屏或小窗口的相机渲染流：

```kotlin
val remoteView = RemoteView.Builder()
    .setContext(this)
    .setBoundingBox(rect) // 取景框区域，null 为默认居中
    .setFormat(HmsScan.ALL_SCAN_TYPE)
    .build()

remoteView.onCreate(savedInstanceState)
frameLayout.addView(remoteView)

// 结果回调
remoteView.setOnResultCallback { result ->
    result.firstOrNull()?.let { scan ->
        println("识别内容: ${scan.originalValue}")
    }
}

// 绑定生命周期
override fun onStart() { super.onStart(); remoteView.onStart() }
override fun onResume() { super.onResume(); remoteView.onResume() }
override fun onPause() { super.onPause(); remoteView.onPause() }
override fun onStop() { super.onStop(); remoteView.onStop() }
override fun onDestroy() { super.onDestroy(); remoteView.onDestroy() }
```

---

### 6. 相册与静态图片识别 (`Bitmap Decode`)

无需开启相机，直接从 `Bitmap` 图片解析条码：

```kotlin
val options = HmsScanAnalyzerOptions.Creator()
    .setHmsScanTypes(HmsScan.ALL_SCAN_TYPE)
    .setPhotoMode(true)
    .create()

val result: Array<HmsScan>? = ScanUtil.decodeWithBitmap(context, bitmap, options)
result?.firstOrNull()?.let { scan ->
    println("图片解析结果: ${scan.originalValue}")
}
```

---

### 7. 条码与二维码生成 (`buildBitmap`)

```kotlin
val content = "https://github.com"
val type = HmsScan.QRCODE_SCAN_TYPE
val width = 400
val height = 400

val options = HmsBuildBitmapOption.Creator()
    .setBitmapMargin(1)
    .setBitmapColor(Color.BLACK)
    .setBitmapBackgroundColor(Color.WHITE)
    .create()

val qrBitmap: Bitmap? = ScanUtil.buildBitmap(content, type, width, height, options)
imageView.setImageBitmap(qrBitmap)
```

---

## 🛡 混淆配置 (ProGuard / R8)

`scanplus` 模块已内置 `consumer-rules.pro`，引入依赖后将**自动生效**，宿主工程无需额外手动配置。若有需要，规则如下：

```proguard
-keep class com.huawei.hms.scankit.** { *; }
-keep class com.huawei.hms.hmsscankit.** { *; }
-keep class com.huawei.hms.ml.scan.** { *; }
```

---

## 📋 常见问题与排查 (FAQ)

### Q: 为什么扫码时某些平板/收银机画面旋转了 90 度或倒置？
> **A:** ScanPlus 默认遵循手机纵向物理传感器标准。如果遇到特殊定制横屏机或工控设备，可以通过 `ScanPlusConfig.customDisplayOrientation` 直接强制指定旋转角度：
> ```kotlin
> ScanPlusConfig.customDisplayOrientation = 0 // 或 180, 270
> ```

### Q: 某些特定机型打开相机报错崩溃？
> **A:** 确保开启了 `CameraMode.AUTO`（默认已启用），此时若 `Camera1` 因底层驱动异常被抛出，库会自动无缝降级到 `Camera2`。

### Q: 打包 APK 时体积过大如何精简？
> **A:** 库中包含 `arm64-v8a` 和 `armeabi-v7a` 两套 so 库。现如今绝大多数 Android 设备均为 64 位，可在应用模块中的 `build.gradle.kts` 仅保留 `arm64-v8a`：
> ```kotlin
> ndk {
>     abiFilters.add("arm64-v8a")
> }
> ```

---

## 📄 开源与致谢

* 本项目底层算法基于 **Huawei ScanKit (2.15.0.301)**。
* 华为统一扫码服务官方文档：[Huawei ScanKit 开发者指南](https://developer.huawei.com/consumer/cn/doc/development/HMSCore-Guides/service-introduction-0000001050041994)
