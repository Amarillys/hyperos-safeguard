# HyperOS Safeguard

适用于 Android 16 / 小米澎湃 OS 3 的原生守护 App，提供电池温控锁屏、连续亮屏计时、无操作调暗，以及可选的字体防回退实验功能。

当前版本 **1.2.0（versionCode 3）**。最低支持 Android 14（API 34），目前在 **Xiaomi 15 / Android 16 / OS3.0.307.0.WOCCNXM** 上构建与实测，其他设备的兼容性仍需验证。

应用使用 Kotlin、Jetpack Compose 和 Material 3。所有功能共用一个前台 `GuardianService` 和一条状态通知；不使用 Root、Shizuku、无障碍或隐藏 API，不申请联网权限。

> 当前 applicationId 为 `com.example.thermalguard`，仍是开发阶段的占位包名。

## 功能

| 功能 | 行为 |
|---|---|
| 电池温控 | 默认 42°C 预警；未接电达到 45°C、接电达到 46°C 时，经 10 秒确认后请求锁屏；支持滞回与递增冷却 |
| 系统热状态联动 | `CRITICAL` 及以上请求锁屏；`SEVERE` 提示。热状态等级与电池温度分开处理 |
| 连续亮屏守护 | 默认连续亮屏 30 分钟后锁屏；熄屏后重新计时，可修改阈值 |
| 无操作调暗 | 20 分钟没有系统用户活动后，将亮度设置降至原值的 20%；后续操作约 5 秒内恢复原亮度和原自动/手动模式 |
| 字体防回退（实验） | 尝试中止小米主题校验的有序广播；实际防回退效果仍需长期观察 |
| 暂停与日志 | 支持暂停 15 分钟、停止守护、规则设置、本地事件日志与文本导出 |

连续亮屏和无操作是两个独立条件：持续使用手机会重置无操作期限，但仍受连续亮屏锁屏规则约束。

## 安装与首次使用

1. 按下文构建 Debug APK，安装到手机。
2. 打开 App，按引导激活设备管理员，再开启守护。
3. 按需要允许通知，并在 HyperOS 设置中配置后台自启动、省电无限制及后台卡片加锁。
4. 字体防回退与无操作调暗均有独立开关，新安装默认关闭。

设备管理员通过 `DevicePolicyManager.lockNow()` 锁屏。触发后，系统通常要求 PIN、密码或图案，首次解锁可能不能直接使用指纹或人脸。

### 无操作调暗授权

该功能需要两个额外授权：

| 授权 | 用途 |
|---|---|
| `android.permission.DUMP` | 读取电源服务的结构化诊断，解析默认显示组最近的用户活动时间。该权限本身也能读取其他系统诊断 |
| `WRITE_SETTINGS` | 临时调整系统亮度及自动/手动模式，并恢复原设置 |

使用电脑 ADB 授权：

```sh
adb shell pm grant com.example.thermalguard android.permission.DUMP
adb shell appops set com.example.thermalguard WRITE_SETTINGS allow
```

也可以通过 App 的“允许修改系统设置”按钮打开系统授权页。授权后点击“重新检查”，再开启“20 分钟无操作调暗”。HyperOS 可能在覆盖安装后重置亮度 AppOp，更新后需检查授权状态。

正常亮度时，服务等待预计的无操作期限后再检查；只有调暗期间才每 5 秒检查一次。电源诊断使用 `--proto` 格式，原始诊断不落盘、不上传。

“试用调暗 10 秒”可检查实际亮度变化：触摸后提前恢复，或者在试用结束时自动恢复；正式期限始终为 20 分钟。

恢复与边界：

- 暂停、停止守护或熄屏时恢复原亮度及模式。
- 用户手动改亮度或自动模式后，保留用户的新设置。
- 修改前同步保存恢复记录，守护服务重建时优先恢复；强行停止应用后，需要重新开启守护才能执行恢复。
- 系统先熄屏时不会唤醒。实测手机原系统休眠时间为 10 分钟，因此此功能主要覆盖视频等持续亮屏场景，不延长系统休眠时间。
- 某些应用自行指定窗口亮度，可能覆盖系统亮度设置。

需要撤销授权时，先关闭调暗并确认亮度恢复，再执行：

```sh
adb shell pm revoke com.example.thermalguard android.permission.DUMP
adb shell appops set com.example.thermalguard WRITE_SETTINGS default
```

### 字体防回退实验功能

在主页开启后，手动重新应用所需字体。它与锁屏守护共用后台，不导入字体，也不修改字体授权文件。

实现参考 Zyper 1.0.1 的广播处理思路，独立监听 `miui.intent.action.CHECK_TIME_UP` 与 `miui.intent.action.CHECK_THEME_UPDATE`，仅对允许中止的有序广播请求 `abortBroadcast()`。没有引入 Zyper 的代码或联网组件。

暂停锁屏时，字体监听继续运行；停止守护后注销监听。开关、最近一次结果和累计广播次数保存在本机。

**“已请求中止”不等于系统校验已经停止。** Android 16 不保证跨进程的广播优先顺序，是否能持续保留字体仍需观察。详见 [Android 官方说明](https://developer.android.com/about/versions/16/behavior-changes-all#ordered-broadcast-priority-scope-no-longer-global)。

## 本地构建

准备 JDK 21、Android SDK Platform 36 与 Build Tools，通过 Android Studio 配置 SDK，或在本地 `local.properties` 中设置 `sdk.dir`。

```sh
git clone https://github.com/Amarillys/hyperos-safeguard.git
cd hyperos-safeguard
```

Windows PowerShell：

```powershell
# JAVA_HOME 指向本机 JDK 21 安装目录
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

macOS / Linux：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。覆盖安装需要相同签名；Debug 签名由本地开发环境生成。

已使用的构建版本：

| 组件 | 版本 |
|---|---|
| JDK | 21.0.5 |
| compileSdk / targetSdk | 36 |
| minSdk | 34 |
| Gradle | 9.3.1 |
| Android Gradle Plugin | 9.1.0 |
| Kotlin / Compose 编译器插件 | 2.2.21 |
| Compose BOM | 2025.02.00 |

## 验证状态

**50 项 JVM 单元测试通过，构建与 Lint 检查通过；Lint 仍有警告。**

2026-11-14 的 Xiaomi 15 实机检查包括：

- 覆盖安装后自动恢复原守护服务；继续使用同一个前台服务和通知。
- 亮度原值 41 调至 8，短时试用约 10 秒后恢复至 41 和自动模式。
- 触摸后约 4.4 秒恢复；手动修改亮度后保留新设置。
- 关闭调暗、暂停、停止及熄屏时恢复亮度。
- 字体开关、两种定向广播自检及服务生命周期检查通过；自检单独标记，不计入真实校验次数。

仍待验证：正式持续 20 分钟的实时时长、不同视频应用的窗口亮度覆盖、重启与强行停止后的恢复、长期耗电，以及字体跨越三天的实际防回退效果。

完整测试范围见 [TESTING.md](TESTING.md)。原始设备日志、截图和反编译分析保存在本地 `diagnostics/`，该目录不纳入 Git；公开文档记录结论和验证范围。

## 代码结构

```text
app/src/main/java/com/example/thermalguard/
├── admin/       设备管理员
├── data/        配置、状态、日志与恢复记录
├── monitor/     电池、热状态、屏幕、字体广播与调暗监听
├── receiver/    开机和更新恢复
├── rule/        锁屏与无操作调暗规则
├── service/     共用前台守护服务
└── ui/          Compose 界面
app/src/test/    JVM 单元测试
```

开发基线与后续增补见 [开发需求](Android16_HyperOS3_温控亮屏守护_开发需求_修订版.md)。

## 停止与卸载

可在 App 中暂停或停止守护。卸载前，通过 App 的“停止守护并取消设备管理员”入口，或系统设备管理员设置撤销授权，再卸载应用。

后台服务仍受 Android / HyperOS 的进程与省电管理约束，不能保证永久存活。
