# 小米 15 温控与亮屏时长守护 App — 开发需求与 Agent 执行说明（修订版）

> **2026-11-14 增补**：使用者已确认新增可选的「字体防回退（实验）」及「20 分钟无操作调暗」，实现与验收范围见 §12、§13；本文件原有阶段需求保留作为 MVP 基线，最新实测记录以 `TESTING.md` 为准。

> **交给本地编码 Agent 的任务：** 根据本文件，在当前工作目录创建一个可在 **小米 15 / Android 16（API 36）/ HyperOS 3（澎湃 OS 3）** 运行的原生 Android 应用。首版以 **Kotlin + Android SDK** 实现，UI 使用 Jetpack Compose + Material 3；产出完整源代码、构建说明、测试与可安装的 Debug APK（若本地 Android SDK/构建环境具备）。优先保证合法 API、可用性、低耗电和防误触发。
>
> **功能目标：** 持续观察电池温度与系统热状态；温度过高时强制锁屏（**未充电 45.0°C，充电中 46.0°C**）；连续亮屏超过指定时间（默认 30 分钟）时强制锁屏。必须支持用户明确开启/暂停/停止守护，并显示当前状态及最近触发原因。

## 执行规则（Agent 必读）

1. **分阶段执行。** 按 §10 的四个阶段逐个实现；每个阶段结束后**停下**，输出该阶段的完成报告（做了什么、测了什么、哪些未验证），等待使用者确认后再进入下一阶段。不要一次性实现全部阶段。
2. **不编造验证结果。** Agent 通常无法接触真机：除非本机已通过 adb 连接小米 15，否则所有真机项在 `TESTING.md` 中一律标记为「未执行（待人工）」。不得声称未测试过的功能已经在真机验证。
3. **构建环境不要凭记忆写版本号。** 先检查本机已有的 JDK、Android SDK（需含 `platforms;android-36`）、Gradle 与 AGP 的可用版本，选择与 `compileSdk = 36` 兼容的 AGP / Gradle / Kotlin / Compose BOM 组合，并把实际版本记录到 README。依赖无法下载或构建失败时，如实报告，不得伪造“构建成功”或 APK。
4. **包名与应用名由使用者指定。** 未指定时使用占位 `applicationId = "com.example.thermalguard"`，并在 README 中标注为占位。
5. **遇到本文件没有覆盖的决策**（尤其涉及额外权限、特权、绕过系统限制），停下来向使用者说明并等待决定，不要自行选择。
6. 若本文件的 §2 默认值表与正文冲突，**以 §2 表格为准**，并在阶段报告中指出冲突。

## 0. 技术选型结论（必须遵循）

- **MVP 不使用无障碍、Shizuku、无线 ADB、Root 或 `WRITE_SECURE_SETTINGS`**。这些都不是本项目主要功能的必要条件。
- **锁屏：** 用户主动激活的 **Device Admin（设备管理员）** + `DevicePolicyManager.lockNow()`；`device_admin.xml` 必须声明 `force-lock` 策略。
- **持续监听：** 在用户启动守护后使用一个有明确常驻通知的 **Foreground Service**。首选官方支持的 `systemExempted` 前台服务类型（官方 API 文档把 Device Admin 应用列为该类型的适用范围之一；**只有确认当前应用的 Device Admin 已激活后才启动**）。该类型能否在目标设备上稳定运行必须实测，异常需显示为错误，不能无声失败。
- **亮屏事件：** 在运行中的 Service 动态注册 `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`，用 `PowerManager.isInteractive()` 判定当前是否交互中。MVP 以系统“交互状态”为连续亮屏定义，并在 UI 说明；若需求严格限定显示面板点亮，再额外核查 `Display.getState()`。
- **温度与充电状态：** 动态注册 `ACTION_BATTERY_CHANGED`（sticky），读取 `BatteryManager.EXTRA_TEMPERATURE`（**电池温度，单位 0.1°C**）和 `BatteryManager.EXTRA_PLUGGED`（是否接入外部电源，非 0 即视为“充电中”，含有线、无线和底座；**不需要额外权限**）。使用 `PowerManager.getCurrentThermalStatus()` 与 `addThermalStatusListener()` 读取独立的 **系统热状态等级**。热状态是等级，不可当成摄氏度；不要假装能无 Root 可靠读取所有 CPU/GPU 核心温度。
- **计时：** `SystemClock.elapsedRealtime()` + 单次目标时刻检查（Handler/协程延时），事件驱动，必要时少量状态巡检。到时任务触发后**一律用 `elapsedRealtime` 重新计算**，不依赖延时本身的精度。**不要用每 1–2 秒轮询或 `WorkManager` 实现 30 分钟精确计时；MVP 不请求精确闹钟权限。**
- **限制：** Android/HyperOS 可能杀死进程或禁止后台重启；任何普通三方 APP 都不能保证“永久不死”。必须显式记录运行状态、提供设置引导和恢复测试，不得用错误宣传代替兼容性验证。

## 1. 运行环境和工程规范

| 项目 | 要求 |
|---|---|
| 目标设备 | Xiaomi 15，Android 16，HyperOS 3 |
| 语言 | Kotlin |
| Android SDK | `compileSdk = 36`、`targetSdk = 36`（如果构建依赖暂不可用，记录原因并采用可编译的最近版本，最终仍应测试 Android 16 行为） |
| 最低版本 | **`minSdk = 34`**。`systemExempted` 类型与 `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` 权限自 API 34 起才存在，且本项目只面向 Xiaomi 15；更低版本的分支无法验证，不要写 |
| UI | 简洁的 Material 3 设置页 / 状态页 / 事件日志。`targetSdk = 36` 下 edge-to-edge 为强制且无法退出，需正确处理 WindowInsets；预测性返回默认开启，不使用已废弃的 `onBackPressed` |
| 架构 | 单 Activity + Foreground Service + DeviceAdminReceiver + BootReceiver + 独立规则引擎 |
| 数据保存 | Preferences DataStore，或更简单可靠的本地持久化；日志本地最多保留 200～500 条，**写日志要节流，不要每次采样都落盘** |
| 联网 | **不需要 `INTERNET` 权限**；禁止上传温度、时长、设备信息 |
| 备份 | `android:allowBackup="false"`（或在备份/数据提取规则中排除日志与设置），兑现“默认只在本地保存” |
| 其他 | 不使用系统隐藏 API、不静默启用设备管理员、不绕过系统安全限制 |

## 2. 可配置规则（初始推荐值，仅用于测试，不代表官方安全温度）

| 设置 | 默认值 | 允许范围 / 说明 |
|---|---:|---|
| 主守护开关 | 关闭 | 安装后必须用户主动启用；首次引导授权 |
| 温度预警 | 42.0°C | 电池温度达到即显示警告，不锁屏 |
| 温度锁屏（未充电） | 45.0°C | 达到后经抗抖确认再锁屏 |
| **温度锁屏（充电中）** | **46.0°C** | 接入外部电源时使用；**必须 ≥ 未充电锁屏温度** |
| 温度抗抖 | 10 秒 | 5–60 秒；至少两次有效样本且间隔 ≥ 该值 |
| 温度恢复阈值 | **41.0°C** | 降到**低于**该值才结束本轮高温状态并清零计数（滞回）；预警与锁屏共用 |
| 高温锁屏冷却 | 3 分钟（连续锁屏时递增：3 → 5 → 10 分钟） | 见 §3.2；防止解锁后立刻又被锁 |
| 连续亮屏上限 | 30 分钟 | 1–240 分钟；每次进入交互状态开始计时，退出交互状态立即清零 |
| 系统热状态联动 | 默认开启 | `THERMAL_STATUS_CRITICAL` 及以上时立即触发一次锁屏（需屏幕交互中）；仅 `SEVERE` 时提示，不按摄氏温度处理；**不受充电状态放宽** |
| 临时暂停 | 15 分钟 | 用户可从 App 或通知暂停；暂停期间绝不自动锁屏 |
| 开机自动恢复 | 开启（用户可关） | 已授权且用户上次主动开启守护时尝试恢复；不保证厂商 ROM 一定允许 |

**字段应可单独关闭：** 温度守护、连续亮屏守护、热状态联动。

**边界校验：** 所有温度限定在 30.0–60.0°C，并满足 `恢复 < 预警 < 锁屏（未充电） ≤ 锁屏（充电中）`，校验失败时拒绝保存并提示原因。所有温度比较统一转换为整数十分之一摄氏度，避免浮点误差。

**术语统一：** 本文“冷却”即“重复锁屏间隔”；“充电中”指 `EXTRA_PLUGGED != 0`（接入外部电源，满电或限充时仍算接入）。

## 3. 核心业务逻辑

### 3.1 连续亮屏判定

1. 运行中的 Service 收到 `ACTION_SCREEN_ON` 或初始化时发现 `isInteractive == true`，若尚未有有效会话，记录 `sessionStartElapsedMs = SystemClock.elapsedRealtime()`。重复的 `SCREEN_ON` 回调不得重置已有会话。
2. 设置一个到时任务，目标时刻 `sessionStartElapsedMs + maxScreenOnDurationMs`。**任务触发时必须重新读取系统交互状态、开关、会话开始时间和当前时间**，不能直接无条件锁屏。
3. 若超过上限仍连续交互，调用统一 `LockCoordinator.requestLock(reason = SCREEN_TIMEOUT)`。
4. 收到 `ACTION_SCREEN_OFF`，立即终止/清空本次会话，取消目标任务；下一次亮屏重新从零计数。**锁屏本身产生的熄屏事件也会使计时清零。**
5. 暂停守护时取消计时；恢复时若屏幕处于交互状态，从恢复时刻开始新的一轮计时（不追算暂停期间）。
6. **服务被系统回收后重新启动：一律从重新启动时刻开始新的会话计时，并在日志写明一次“监测空窗”。** 不恢复旧会话——服务被杀期间可能漏掉 `SCREEN_OFF`，恢复旧会话会导致误锁。
7. 计时语义为“屏幕交互状态持续时间”，不是 Digital Wellbeing 意义上的前台 App 使用时长，也不是全天累计亮屏时间。锁屏界面点亮时可能也属于交互状态，UI 须说明。
8. MVP 不做前台应用豁免（导航、视频、通话中同样会触发）；用户可用“暂停 15 分钟”应对。需要豁免属于后续版本，且会引入额外权限，须先询问使用者。

### 3.2 温度守护

**读数与样本**

1. 动态注册 `ACTION_BATTERY_CHANGED`，读取 `EXTRA_TEMPERATURE`（原始值 450 = 45.0°C）和 `EXTRA_PLUGGED`。缺失、或超出 −20.0°C～100.0°C 的数值一律标记为“未知”，不要以 0°C 处理，也不参与规则判断。
2. 事件式更新为主；用户正在亮屏期间，可每 **15～30 秒**最多补一次读取 sticky 电池状态。**广播刷新频率与温度传感器精度受 ROM 控制，不能保证严格实时。**
3. **有效样本**：在交互状态下、以服务自身的 `elapsedRealtime` 打点的一次有效读数（sticky 广播本身没有时间戳），**不要求数值变化**。

**有效锁屏阈值**

4. 每个样本按其采集时刻的充电状态确定有效锁屏阈值：充电中用 `chargingLockTenthsC`（默认 460），否则用 `lockTenthsC`（默认 450）。预警阈值与恢复阈值不区分充电状态。

**预警**

5. 达到预警阈值时更新通知/日志，**同一轮高温只提示一次**（不要每次采样都发通知）；降到恢复阈值以下才重新武装。

**确认与锁屏**

6. 当样本 ≥ 有效锁屏阈值且屏幕处于交互状态时进入确认：需要**两次有效样本且至少跨越抗抖秒数**，再尝试锁屏。出现以下任一情况，**重置确认窗口**：
   - 某个样本低于其有效阈值；
   - **有效阈值发生变化**（插上或拔掉电源）；
   - 屏幕熄灭（`SCREEN_OFF`）。
7. **熄屏期间已处于高温时，亮屏不会立即锁屏**：亮屏后必须重新完成一次完整确认。熄屏期间只记录温度异常，不调用 `lockNow()`。
8. 充电与拔线示例（用于单元测试）：充电中 45.5°C 不锁；此时拔线，有效阈值变为 45.0，**从拔线后的样本开始新的确认**，不得立即锁；确认过程中插线，有效阈值变为 46.0，45.5°C 低于阈值，确认窗口重置。

**冷却与递增**

9. `lockNow()` 成功后记录原因/时间/温度/是否充电，进入冷却，并把“本轮连续高温锁屏次数”加一。冷却时长按次数递增：第 1 次 3 分钟，第 2 次 5 分钟，第 3 次及以后 10 分钟。
10. 冷却期内即便用户解锁且仍然高温，也不重复强制锁屏。冷却结束后，若仍 ≥ 有效锁屏阈值且屏幕交互中，**重新完成确认后**才可再次锁屏；若低于锁屏阈值但不低于恢复阈值，保持预警状态并保留次数；**若低于恢复阈值，回到 NORMAL 并清零次数。**
11. 电池温度路径与热状态路径**共享同一个高温冷却**；亮屏超时路径不受高温冷却限制（它只针对新的亮屏会话）。
12. 实现防抖/滞回，不能在阈值附近狂发提醒。**高温锁屏的目的主要是减少持续使用，锁屏不等于硬件断电或主动给电池降温。**

**热状态联动**

13. 若热状态为 `CRITICAL` 或更严重，且已启用联动、屏幕交互中、没有暂停，则可绕过 10 秒确认执行一次锁屏（受高温冷却限制，**不因充电而放宽**）。`SEVERE` 仅提示。热状态 API 异常或不可用时，界面标记为不可用，继续使用电池温度路径。

### 3.3 安全防护与锁屏统一入口

所有调用 `lockNow()` 的路径必须经过单一的 `LockCoordinator`，至少满足：

- 用户已主动开启守护，未暂停，服务处于有效工作状态。
- `DevicePolicyManager.isAdminActive(adminComponent)` 为真且声明了 `force-lock`。
- 当前设备 `PowerManager.isInteractive()` 为真；已经熄屏时不要无意义重复锁。
- 高温触发需通过确认和冷却检查；亮屏超时只针对本次尚未执行锁屏的亮屏会话。
- **全局最小间隔：任意两次 `lockNow()` 调用至少相隔 5 秒。**
- 避免并发竞争（同一时间高温+时长触发最多执行一次，日志可记两个原因）。
- 捕获 `SecurityException`、服务启动异常及厂商特例，通知用户修复授权；**不能崩溃、无限重试或持续强制锁屏**。
- 用户能从常驻通知直接暂停 15 分钟或停止守护；主页面也能立即关闭规则。不可设计为无法取消的强制管控软件。
- 不主动修改 PIN/密码、不申请设备所有者（Device Owner）模式、不禁止用户卸载应用、不阻止用户解除设备管理员授权。

**重要 UX 限制：** `DevicePolicyManager.lockNow()` 之后，系统通常要求使用 **PIN、密码或图案等强认证**解锁，不能保证继续使用指纹或人脸快速解锁。如果用户只想“黑屏但不进强认证”，MVP **不应通过隐藏 API、模拟按电源键等方式绕过此限制**；应在授权页醒目标注并做真机测试。每次触发锁屏都意味着用户要输入一次密码，这也是冷却时长递增的原因。

## 4. 前台服务、开机恢复和厂商兼容

### 4.1 Foreground Service

- 使用 `startForegroundService()` 并尽快 `startForeground()`。**必须使用带类型的重载**（如 `ServiceCompat.startForeground(service, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)`），类型须与 Manifest 声明一致。
- 常驻通知为低优先级、不可误解的“温控守护正在运行”，显示当前温度、已持续交互时长、暂停/运行状态。
- **Foreground Service 类型 `systemExempted`：** 仍必须在真机确认已授权后启动成功；捕获 `ForegroundServiceTypeNotAllowedException`、`MissingForegroundServiceTypeException`（类型缺失或不匹配）、`SecurityException` 和 `ForegroundServiceStartNotAllowedException`。
- 严禁为了“保活”伪装成音乐播放、导航、定位等不符合用途的类型。不能把 `systemExempted` 理解为免疫系统杀进程。
- **暂停期间 Service 必须保持前台运行**（只是停止计时和锁屏判断）。本项目不使用精确闹钟，后台也难以重新拉起前台服务，暂停到期只能由常驻的 Service 自己恢复。
- 在不必要时（用户停止、管理员被撤销）停止所有工作并调用 `stopForeground` / `stopSelf`，避免后台耗电。
- Android 13+ 通知权限 `POST_NOTIFICATIONS`：请求授权并处理拒绝情况；通知不可见可能影响用户感知，但不应谎称服务绝对无法启动。
- **通知可能被用户划掉，用户也可以在系统的“正在运行的应用”里直接停止服务**（据我所知 Android 14 起多数前台服务通知可被划走，需真机确认）。因此：主页必须是完整的备用入口（暂停、停止、状态）；日志要区分“用户停止”“被系统杀死”和“异常”。

### 4.2 重启和恢复

- Manifest 中接收 `BOOT_COMPLETED` **和 `MY_PACKAGE_REPLACED`**（覆盖安装/调试重装后服务会被杀，且不会收到开机广播）。仅当用户原先开启守护且 Device Admin 仍有效时尝试恢复；处理启动限制异常，**恢复失败只尝试一次，不循环重试**。首次启动须用户在 UI 授权，**不允许静默自授予设备管理员**。
- 官方文档中 Android 15 起 `BOOT_COMPLETED` 接收器不能启动部分类型的前台服务（如 `dataSync`、`mediaPlayback`），`systemExempted` 不在其中，但仍须在真机验证开机恢复是否成功。
- 进程因内存压力退出可使用 `START_STICKY` 提高被重建的机会，但它不是保证；被用户“强行停止”或厂商限制后可能不会自动恢复。
- **暂停状态的持久化：** 暂停截止时刻用 `elapsedRealtime` 保存，并同时保存开机标识（如 `Settings.Global.BOOT_COUNT`）。同一次开机内服务重建，按剩余时间继续暂停；**跨开机则暂停失效**，回到运行状态并记日志。
- 需要提供 HyperOS 设置引导，不要硬编码可能随版本变化的设置入口：后台自启动、后台电池策略为不限制、最近任务锁定、通知允许。引导只能提示用户手动操作，不能借助私有权限暗改设置。
- 必须能发现权限被撤销（`onDisabled` 回调，以及每次锁屏前和主页面打开时的 `isAdminActive` 检查）：Service 停止/降级为不可用；主页面显示原因，不再尝试锁屏。

### 4.3 卸载与退出授权

已激活的设备管理员会阻止直接卸载，必须先取消激活。为确保用户随时能退出：

- 主页提供“**停止守护并取消设备管理员**”按钮：停止服务、清除“用户希望开启”标志、调用 `DevicePolicyManager.removeActiveAdmin(component)`。
- README 写明手动卸载步骤（先在系统的设备管理应用中取消激活，再卸载）；具体设置入口随 ROM 版本变化，只写引导，不硬编码路径。
- 不在用户取消授权后自动重新申请或自动重启强制锁屏。

## 5. UI / 交互（MVP）

**主页面：**

- 总开关（开启 / 停止）、“暂停 15 分钟”按钮、“停止守护并取消设备管理员”按钮。
- 权限状态：设备管理员是否已授权、通知是否获准、前台服务是否正在运行。
- 实时读数：电池温度（例如 `41.5°C`）、**是否充电中、当前生效的锁屏阈值（如“45.0°C（未充电）/ 46.0°C（充电中）”）**、热状态级别、当前连续亮屏时长、下一次超时剩余时间。
- 单独开关及阈值输入：温度预警、温度锁屏（未充电）、温度锁屏（充电中）、恢复阈值、抗抖秒数、连续亮屏分钟数、热状态联动、冷却时间。输入需执行 §2 的边界校验。
- 清晰提示：`lockNow()` 可能让指纹/人脸暂时不可用，需输入密码解锁。
- **测试按钮**：只允许用户在屏幕上明确点击“立即测试锁屏”，再次确认后执行锁屏；避免调试时意外锁住设备。
- 状态展示（正常/预警/高温/暂停/错误）不得仅靠颜色区分，需同时有文字或图标。

**事件日志：** 显示近 200～500 条时间、事件类型、温度、是否充电、触发原因、结果及错误。**导出文本使用系统文件选择器（`ACTION_CREATE_DOCUMENT`），不申请存储权限**；支持清空；默认只在本地保存。

**常驻通知：** `守护中 · 电池 40.2°C · 本次亮屏 12:36`（充电中追加“· 充电中”）；操作为“暂停 15 分钟”和“停止守护”。为了减少通知刷新，按分钟或关键状态变动刷新，不必逐秒刷新。

## 6. 权限与资源声明（建议起点，Agent 负责补齐项目）

**`AndroidManifest.xml` 必要部分：**

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<application
    android:allowBackup="false"
    ... >
    <receiver
        android:name=".admin.GuardianDeviceAdminReceiver"
        android:permission="android.permission.BIND_DEVICE_ADMIN"
        android:exported="true">
        <meta-data
            android:name="android.app.device_admin"
            android:resource="@xml/device_admin" />
        <intent-filter>
            <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
        </intent-filter>
    </receiver>

    <service
        android:name=".service.GuardianService"
        android:exported="false"
        android:foregroundServiceType="systemExempted" />

    <receiver
        android:name=".receiver.BootReceiver"
        android:enabled="true"
        android:exported="false">
        <intent-filter>
            <action android:name="android.intent.action.BOOT_COMPLETED" />
            <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
        </intent-filter>
    </receiver>
</application>
```

**`res/xml/device_admin.xml`：**

```xml
<?xml version="1.0" encoding="utf-8"?>
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies>
        <force-lock />
    </uses-policies>
</device-admin>
```

**管理员授权：** 显示解释说明后，通过系统 `DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN` 启动系统授权界面；用 `DevicePolicyManager.isAdminActive(component)` 验证，不能假定用户同意。首次启动 FGS 前先验证资格。Manifest 中不要申请 `BIND_DEVICE_ADMIN` 作为普通 uses-permission；它属于 Receiver 的系统绑定保护声明。

**代码接口提示（非完整实现）：**

```kotlin
// 温度：0.1°C 整数；缺失或明显异常为 null，不得当作 0°C
val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
val tempTenthsC: Int? = raw.takeIf { it != Int.MIN_VALUE && it in -200..1000 }

// 充电状态：接入外部电源（有线/无线/底座）即为充电中
val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

// 有效锁屏阈值（0.1°C 整数）
val effectiveLockTenthsC =
    if (plugged) settings.chargingLockTenthsC else settings.lockTenthsC

val interactive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
val now = SystemClock.elapsedRealtime()

if (devicePolicyManager.isAdminActive(adminComponent)) {
    devicePolicyManager.lockNow() // 确保实际调用前已通过统一 LockCoordinator 校验
}
```

屏幕与电池状态广播必须**运行时动态注册**，不能只靠 Manifest Receiver 监听；服务销毁时解除注册。

## 7. 建议代码结构

```text
app/src/main/java/<package>/
├── MainActivity.kt
├── ui/GuardianScreen.kt
├── ui/SettingsScreen.kt
├── ui/LogScreen.kt
├── admin/GuardianDeviceAdminReceiver.kt
├── service/GuardianService.kt
├── receiver/BootReceiver.kt            # BOOT_COMPLETED + MY_PACKAGE_REPLACED
├── monitor/BatteryTemperatureMonitor.kt # 输出 (温度, 是否充电)
├── monitor/ThermalStatusMonitor.kt
├── monitor/ScreenInteractiveMonitor.kt
├── rule/GuardianRuleEngine.kt
├── rule/LockCoordinator.kt
├── data/GuardianSettingsRepository.kt
├── data/GuardianLogRepository.kt
├── data/PauseStateStore.kt
└── util/TimeProvider.kt                # 含可注入的单调时钟与开机标识
```

使用可注入的时钟和接口把规则引擎与真实 Android 系统解耦，方便 JVM 单元测试。UI 与 Service 不要分别实现同一套规则；所有设备状态集中到可观察状态存储层。

## 8. 状态机与场景

### 8.1 服务状态迁移表

| 当前状态 | 事件 | 下一状态 | 动作 |
|---|---|---|---|
| STOPPED | 用户开启，管理员未激活 | NEEDS_ADMIN | 显示说明并拉起系统授权界面 |
| STOPPED | 用户开启，管理员已激活 | STARTING | 启动前台服务 |
| NEEDS_ADMIN | `isAdminActive` 变为真 | STARTING | 启动前台服务 |
| NEEDS_ADMIN | 用户取消授权 | STOPPED | 清除“用户希望开启”标志 |
| STARTING | `startForeground` 成功 | RUNNING | 注册监听、开始计时 |
| STARTING | 启动异常 | ERROR | 记录日志并在主页显示原因，不自动重试 |
| RUNNING | 用户暂停 | PAUSED | 保持前台服务；取消计时与确认；保存暂停截止时刻 |
| PAUSED | 到期或用户恢复 | RUNNING | 若交互中，从此刻开始新会话 |
| RUNNING / PAUSED | 用户停止 | STOPPED | `stopForeground` + `stopSelf`；清除“用户希望开启”标志 |
| 任意 | 管理员被撤销 | NEEDS_ADMIN | 停止服务，不再尝试锁屏，通知并在主页说明 |
| ERROR | 用户点击重试 | STARTING | 重新校验管理员后启动 |
| 任意（进程被杀） | `START_STICKY` 重建 / 开机 / 应用更新 | STARTING | 校验管理员和用户意愿；新会话计时；记录监测空窗 |

### 8.2 温度规则状态

`NORMAL → WARNING（≥ 预警）→ CONFIRMING（≥ 有效锁屏阈值且交互中）→ LOCKED_COOLDOWN（锁屏成功）`。冷却结束后：仍 ≥ 有效阈值且交互中 → `CONFIRMING`；低于锁屏阈值但不低于恢复阈值 → `WARNING`（保留连续锁屏次数）。**任意状态下温度 < 恢复阈值 → `NORMAL`（清零次数）。** 屏幕熄灭或有效阈值变化时，`CONFIRMING` 回到 `WARNING` 并重置窗口。

### 8.3 屏幕规则状态

`NON_INTERACTIVE ↔ INTERACTIVE_TIMING → TIMEOUT_LOCKED → NON_INTERACTIVE`。

### 8.4 必须覆盖的场景

| 编号 | 场景 | 对应测试 |
|---|---|---|
| S-01 | 同时满足超温与亮屏超时，只执行一次锁屏 | T-20 |
| S-02 | 第 29 分钟手动熄屏，再亮屏必须重新计时 | T-04 |
| S-03 | 温度仍达阈值时解锁，不可瞬间陷入反复锁屏循环（冷却+递增） | T-14、T-15 |
| S-04 | 暂停 15 分钟，期间任何条件都不可锁屏；结束后开始新亮屏会话 | T-18、M-10 |
| S-05 | 用户撤销设备管理员：安全退出或进入错误状态，不崩溃 | T-19、M-09 |
| S-06 | 充电期间息屏，高温仍可记录，但不调用锁屏 | T-13 |
| S-07 | `ACTION_BATTERY_CHANGED` 暂未更新、温度缺失、热状态 API 异常时，不中断其他守护功能 | T-19 |
| S-08 | App UI 被划走、系统回收 Service、重启手机、用户强行停止、应用更新，分别测试；不得把“理论上恢复”写成“已验证” | M-11～M-15 |
| S-09 | 设备以 PIN/密码解锁后的用户体验需记录（强认证提醒） | M-02 |
| S-10 | 用户能快速暂停或关闭守护，不妨碍紧急操作；不在用户试图解除授权时自动重启强制锁屏 | M-08、M-09 |
| S-11 | 充电中 45.5°C 不锁，46.0°C 持续达标才锁；充电状态变化时重新确认 | T-09～T-12 |
| S-12 | 熄屏期间已经高温，亮屏后不立即锁，需重新确认 | T-13 |
| S-13 | 通知被划掉或被“正在运行的应用”停止后，主页仍可完整操作，日志区分原因 | M-16 |
| S-14 | 暂停期间进程被回收：同次开机内按剩余时间继续暂停；重启后暂停失效 | T-24、M-17 |

## 9. 验收标准与测试策略

### 9.1 自动化测试（规则引擎 JVM 单元测试，Agent 必须实现并运行）

| 编号 | 内容 |
|---|---|
| T-01 | 亮屏 0 → 29:59 不锁 |
| T-02 | 30:00 且仍交互：触发一次 |
| T-03 | 提前熄屏：不触发 |
| T-04 | 熄屏后再亮屏：重新从零计时 |
| T-05 | 重复 `SCREEN_ON` 回调不重置已有会话 |
| T-06 | 未充电：44.9 → 45.0 持续不足 10 秒不锁 |
| T-07 | 未充电：持续达标且满足两次有效样本、跨越 10 秒才锁 |
| T-08 | 确认中途样本降到阈值以下：窗口重置 |
| T-09 | 充电中 45.5°C：不锁 |
| T-10 | 充电中 46.0°C 持续 ≥ 10 秒：锁 |
| T-11 | 充电中 45.5°C 时拔线：从拔线后的样本重新确认，不立即锁；确认后锁 |
| T-12 | 确认中插线（45.5°C）：窗口重置 |
| T-13 | 熄屏期间高温：不调用 `lockNow()`；亮屏后不立即锁，重新确认后才锁 |
| T-14 | 锁屏后冷却期内解锁：不重复锁 |
| T-15 | 冷却结束仍高温：重新确认后再锁，冷却依次为 3 → 5 → 10 → 10 分钟 |
| T-16 | 温度降到恢复阈值以下：状态回到 NORMAL，连续次数清零 |
| T-17 | 预警在 42°C 附近（41.9 / 42.0 / 41.5 / 40.9）不抖动，同一轮只提示一次 |
| T-18 | 暂停期间任何条件不锁；到期后开始新会话 |
| T-19 | 停用、管理员权限被撤销、温度不可用、热状态异常均不抛未处理异常 |
| T-20 | 同时两个触发条件并发调用，只产生一次 `lockNow()` 请求 |
| T-21 | 任意两次 `lockNow()` 间隔 < 5 秒的第二次被拒绝 |
| T-22 | 热状态 `CRITICAL` 绕过确认锁一次且受冷却限制；充电中不放宽；`SEVERE` 仅提示 |
| T-23 | 阈值与范围校验（`恢复 < 预警 < 锁屏 ≤ 充电锁屏`，30.0–60.0°C，抗抖 5–60 秒，亮屏 1–240 分钟） |
| T-24 | 暂停持久化：同次开机内按剩余时间继续；开机标识变化则暂停失效 |

### 9.2 真机手动验证清单（小米 15 / HyperOS 3）

**以下全部在使用者亲自执行并记录前，一律标为“未执行（待人工）”，不得由 Agent 填写结果。**

| 编号 | 内容 | 状态 |
|---|---|---|
| M-01 | 首次授权设备管理员，首次启动 `systemExempted` 服务，检查是否报异常 | 未执行 |
| M-02 | 将亮屏阈值临时设为 1 分钟，实测自动锁屏且日志正确，确认指纹/人脸解锁的变化 | 未执行 |
| M-03 | 42°C 预警与温控模拟（见下） | 未执行 |
| M-04 | 45°C 未充电锁屏、46°C 充电锁屏的实测（用模拟） | 未执行 |
| M-05 | 充电状态切换（插线/拔线/无线充）下阈值切换是否正确 | 未执行 |
| M-06 | 息屏、通知暂停、屏幕重新点亮 | 未执行 |
| M-07 | 低电量模式 | 未执行 |
| M-08 | 从通知和主页快速暂停/停止 | 未执行 |
| M-09 | 撤销设备管理员；使用“停止并取消设备管理员”后卸载 | 未执行 |
| M-10 | 暂停 15 分钟到期后的恢复 | 未执行 |
| M-11～M-15 | 划掉最近任务、系统回收、重启手机、强行停止、覆盖安装，各自恢复情况 | 未执行 |
| M-16 | 通知被划掉 / 被“正在运行的应用”停止后的表现 | 未执行 |
| M-17 | 暂停期间重启手机后的状态 | 未执行 |
| M-18 | 息屏 2 小时/整夜、正常使用数小时的耗电与后台存活（标明实际测试时长和结果） | 未执行 |

**温度与热状态模拟（严禁为了测试刻意让电池过热）。** 以下 adb 命令据我所知可在多数设备上使用，**是否在 HyperOS 3 上生效需先实测**，测完必须复位：

```bash
adb shell dumpsys battery set temp 450      # 模拟 45.0°C（单位 0.1°C）
adb shell dumpsys battery set ac 1          # 模拟接入外部电源（或 set usb 1）
adb shell dumpsys battery unplug            # 模拟拔线
adb shell dumpsys battery reset             # 复位，测完务必执行

adb shell cmd thermalservice override-status 4   # 模拟 CRITICAL
adb shell cmd thermalservice reset               # 复位
```

规则引擎测试另可用可注入的 `FakeTemperatureSource`（仅限 debug 源集，正式版必须无法误用测试注入，且不得导出任何测试入口）。

### 9.3 交付物

1. 可直接用 Android Studio 打开的完整 Gradle 工程（含 `gradlew`）。
2. `README.md`：架构说明、授权步骤、卸载与取消设备管理员步骤、小米 HyperOS 省电设置建议、API 限制、实际使用的构建工具版本。
3. `TESTING.md`：自动化测试运行方式、真机测试清单（含 adb 模拟命令）；记录实际执行结果，未执行的项明确标注。
4. 若构建环境允许，构建 `app-debug.apk`，并注明 APK 路径、SDK 版本及是否做过真机实测。
5. 对 Android 16 / HyperOS 3 未验证的能力明确列为“待验证”，不能编造测试结论。

## 10. 开发顺序（分阶段任务，每阶段结束后停下等待确认）

| 阶段 | 范围 | 本阶段需要的章节 | 完成标准 |
|---|---|---|---|
| 一 | Device Admin 授权页 + 用户手动按钮触发锁屏 + 取消管理员入口 | §0、§1、§3.3、§4.3、§6 | 工程可编译；授权与手动锁屏可用；M-01、M-02 清单就绪 |
| 二 | `systemExempted` 前台服务 + 通知 + 屏幕事件 + 亮屏计时 + 日志 | §3.1、§4.1、§5、§8.1 | T-01～T-05 通过；服务状态迁移表实现 |
| 三 | 电池温度 + 充电状态 + 热状态 + 确认/滞回/冷却 + 单元测试 | §2、§3.2、§8.2 | T-06～T-17、T-20～T-23 通过 |
| 四 | 暂停/恢复/开机与更新恢复、错误恢复、HyperOS 引导、耗电测试清单 | §4.2、§8.4、§9.2 | T-18、T-19、T-24 通过；真机清单完整并标注未执行项 |

若第一阶段发现目标手机 `lockNow()` 的强认证体验不可接受，应先向使用者明确说明并停在可评估的阶段，**不要擅自换成无障碍或 Shizuku**；需要用户重新决定是否接受它们额外的稳定性和授权成本。

## 11. Android 官方参考资料（实现前检查）

- 前台服务类型 `systemExempted`：https://developer.android.com/develop/background-work/services/fgs/service-types
- 前台服务启动与限制：https://developer.android.com/develop/background-work/services/fgs/launch
- 后台启动限制与开机广播例外：https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android 16 前台服务变化：https://developer.android.com/develop/background-work/services/fgs/changes
- `DevicePolicyManager.lockNow()`：https://developer.android.com/reference/android/app/admin/DevicePolicyManager#lockNow()
- `DeviceAdminReceiver`：https://developer.android.com/reference/android/app/admin/DeviceAdminReceiver
- `BatteryManager` / `EXTRA_TEMPERATURE` / `EXTRA_PLUGGED`：https://developer.android.com/reference/android/os/BatteryManager
- `ACTION_BATTERY_CHANGED`、`ACTION_SCREEN_ON/OFF`、`ACTION_MY_PACKAGE_REPLACED`：https://developer.android.com/reference/android/content/Intent
- `PowerManager` 热状态 API：https://developer.android.com/reference/android/os/PowerManager
- `SystemClock.elapsedRealtime()`：https://developer.android.com/reference/android/os/SystemClock
- 精确闹钟的适用边界：https://developer.android.com/develop/background-work/services/alarms

---

**Agent 最终执行要求：** 不要只给概念设计或伪代码，按阶段真正创建工程、实现 MVP、补齐权限声明、编译并运行单测。对受设备厂商限制的功能给出具体可复现的测试步骤；遇到权限或系统限制时保留可用的降级路径并明确提示，不得使用未经用户批准的额外特权绕过系统限制。

## 12. 2026-11-14 已确认增补：字体防回退实验功能

### 12.1 背景与使用者决定

实机在 2026-11-14 15:14:34 由 `system_server` 中的
`miui.drm.ThemeReceiver$ValidateThemeTask` 调用 `ThemeRuntimeManager.resetDefault`，
随后自定义字体目录为空，系统标记为默认字体。另发现一份临时授权于当天 14:58:53 到期，
时间与回滚相近；现有日志未明确指出失败资源，不能将该授权认定为已确认的唯一原因。

使用者明确同意参考 Zyper 1.0.1 的广播处理原理，将实验功能合并至现有锁屏后台。
本节限定此项已确认扩展的实现与验收范围，不改变原有温控、锁屏规则和权限边界。

### 12.2 已实现的行为与边界

- 主页提供「字体防回退（实验）」独立开关，新安装默认关闭，选择保存在本机。
- 复用 `GuardianService`、同一进程和现有通知，动态监听
  `miui.intent.action.CHECK_TIME_UP` / `miui.intent.action.CHECK_THEME_UPDATE`，请求优先级 999。
- 仅对允许中止的有序广播调用公开 `abortBroadcast()` API；分别记录请求、无序、禁止中止和异常。
- 开关即时生效，不重启亮屏计时；暂停锁屏时继续监听，停止守护后注销监听，重新开启时按保存状态恢复。
- 不增加服务、轮询、唤醒锁或权限；保持无 Root、Shizuku、无障碍、隐藏 API 和联网组件的实现边界。
- 开启后由使用者手动重新应用字体；功能不导入字体、不修改授权文件，也不自动恢复字体。
- 「已请求中止」仅表示 API 调用结果，不能作为真实系统校验已停止或永久防回退的证明。
- 定向自检限定本应用包名并明确标记，不计入校验次数；最近结果和累计次数持久化，普通事件日志仍使用原内存队列。

### 12.3 本次交付与验收快照

截至 **2026-11-14 16:04（UTC+8）**，已覆盖安装 1.1.0（versionCode 2），
设备为 Xiaomi 15 / Android 16 / OS3.0.307.0.WOCCNXM。
原设置及管理员授权保留，守护运行中，字体实验开关已开启。

构建、36 项 JVM 单元测试与 Lint 检查通过；真机已检查更新恢复、开关即时生效、
两种定向广播处理、禁止中止标志、暂停/恢复和停止/重新开启的监听生命周期。
三次自检后，真实校验广播计数仍为 0。

系统真实定时广播的跨进程拦截、超过三天的字体保留、重启后的字体监听恢复和长期耗电，
均保留为 **未验证**。原 §9.2 中未完整执行的体验与温控项目不因本次专项自检而视为通过。

完整范围与证据见 [TESTING.md](TESTING.md)、
验收摘要（本地：`diagnostics/font-guard-verification/verification-summary.json`） 和
生命周期检查（本地：`diagnostics/font-guard-verification/lifecycle-checks.json`）。

## 13. 2026-11-14 已确认增补：20 分钟无操作调暗

### 13.1 已确认行为与授权

使用者确认「没有动作」指没有触摸、滑动或按键；同意 20 分钟无操作后降低到原亮度的 20%，
再次操作后约 5 秒内恢复原亮度及原自动/手动模式，继续共用现有后台。
使用者另明确同意通过 ADB 一次性授予 `android.permission.DUMP` 与「修改系统设置」。
此处为本功能的已批准权限增补；`DUMP` 本身可读取其他系统诊断，应用仅请求电源服务诊断并在内存中解析交互时间。

### 13.2 实现要求与故障处理

- 独立开关，默认关闭；原温控、字体监听和连续亮屏锁屏规则继续生效。
- 通过公开 `Debug.dumpService("power", ..., arrayOf("--proto"))` 获取结构化电源诊断，仅解析默认显示组最近用户活动时间，使用 `uptimeMillis` 比较；不使用连续亮屏时长或手机运动代替用户操作。
- 正常亮度下等待预计期限，到期重新检查实际活动时间；仅调暗期间每 5 秒检查一次，不额外保持屏幕亮起或增加后台服务、唤醒锁。
- 调暗前同步保存原亮度、原模式、目标值及恢复阶段；暂停、停止、熄屏和进程重建时尝试恢复。
- 恢复仅作用于仍由本功能控制的亮度；用户自行修改亮度或模式后保留新设置。
- 授权缺失、时间无效或诊断格式变化时不推断「已经无操作 20 分钟」；停止调暗并尝试恢复，失败保留恢复记录并提示。
- 提供「试用调暗 10 秒」，不改变正式期限；试用可因操作提前恢复，并在无人操作时自动结束。
- 系统先熄屏时不唤醒。部分应用自设窗口亮度可覆盖系统亮度，本功能对该情形不作全应用生效保证。

### 13.3 当前验证范围

1.2.0（versionCode 3）已构建并覆盖安装，累计 50 项 JVM 测试和 Lint 检查通过。
已完成 ADB 授权、更新后守护恢复、试用调暗与自动恢复、约 4.4 秒触摸恢复、用户修改优先及关闭/暂停/停止/熄屏恢复检查。
截至 2026-11-14 16:55（UTC+8），调暗开关开启，原亮度 41、自动模式 1 已恢复，原守护服务持续运行；详见 [TESTING.md](TESTING.md)。
正式 20 分钟的实时时长、不同视频应用、自启动恢复和长期耗电应分别记录，不能用短时试用替代长期验收。

参考：[系统诊断 API](https://developer.android.com/reference/android/os/Debug#dumpService(java.lang.String,%20java.io.FileDescriptor,%20java.lang.String[]))、
[系统设置写入授权](https://developer.android.com/reference/android/provider/Settings.System#canWrite(android.content.Context))、
[窗口亮度覆盖](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness)。
