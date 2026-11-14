# 测试方案与真机验证清单 (TESTING.md)

本项目遵循严格的质量与测试策略，所有非真机项均由 JVM 单元测试全量覆盖；真机验证项如实标记执行状态，在真机实际核验前一律标记为「待人工验证」。

## 2026-11-14：20 分钟无操作调暗（1.2.0）

使用者已确认「没有动作」指没有触摸、滑动或按键，并同意通过 ADB 授予 `DUMP` 和「修改系统设置」。
新增功能复用原守护服务，正式期限固定为 20 分钟；调暗目标为原亮度的 20%，恢复检查间隔为 5 秒。

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 通过，累计 **50 项测试，0 失败、0 错误**；Lint 无错误，保留警告。
- 新增 14 项测试：无操作期限与重新操作、熄屏与异常时间、相对亮度下限、电源诊断默认显示组解析、恢复记录与部分写入故障、用户修改优先。
- 已相同签名覆盖安装 1.2.0（3），`MY_PACKAGE_REPLACED` 恢复原 `GuardianService`；仍使用通知 ID 1001。
- ADB 已确认 `android.permission.DUMP: granted=true` 和 `WRITE_SETTINGS: allow`。本机覆盖安装后曾重置亮度 AppOp，已重新授权；后续更新应再次检查。
- 真机调暗、触摸恢复、试用自动恢复、手动亮度修改优先，以及关闭/暂停/停止/熄屏恢复均已验证。最终调暗开关开启，原亮度 41 和自动模式 1 已恢复，恢复记录为空。
- 实机文本电源诊断约 603 KB，初版触发读取上限并安全停止；已改用约 2 KB 的 `--proto` 输出，仅解析 `UserActivityProto`。修复后重新构建、测试、覆盖安装并完成下表检查。
- 正式连续 20 分钟的实时时长、视频窗口自设亮度、重启恢复及长期耗电：**未验证**；短时试用和单元测试不能代替这些检查。

证据：构建与设备摘要（本地：`diagnostics/idle-dimming-verification/verification-summary.json`）、
单元测试统计（本地：`diagnostics/idle-dimming-verification/unit-test-summary.json`）、
Lint 结果（本地：`diagnostics/idle-dimming-verification/lint-results-debug.xml`）。原始证据仅保存在本地，`diagnostics/` 不纳入 Git。

### 本次调暗实机结果（2026-11-14 16:55，UTC+8）

| 检查项 | 结果 | 实际观察 |
|---|---|---|
| 电源活动时间读取 | 通过 | App 内读取结构化诊断，解析最近交互时间并等待约 1200000 ms 期限 |
| 10 秒试用自动恢复 | 通过 | 亮度 41 / 自动模式 1 → 亮度 8 / 手动模式 0；约 10.8 秒观察到恢复 41 / 1 |
| 触摸后恢复 | 通过 | 操作后约 4.356 秒恢复，早于试用自动结束 |
| 用户修改亮度优先 | 通过 | 调暗后手动设为 9，后续检查保留 9 / 手动模式并清除恢复记录；测试后还原原设置 |
| 关闭功能 | 通过 | 约 0.14 秒观察到恢复，原守护继续运行 |
| 暂停守护 | 通过 | 约 0.13 秒观察到恢复，随后恢复守护 |
| 停止守护 | 通过 | 约 0.13 秒观察到恢复，随后重新开启原服务 |
| 熄屏 | 通过 | 约 0.89 秒观察到恢复，服务继续运行，不唤醒屏幕 |
| 正式完整 20 分钟 | 未验证 | 期限边界经 JVM 测试；未保持实机持续亮屏等待完整期限 |

本地补充证据：`diagnostics/idle-dimming-verification/device-checks.json`、
`preview-auto-restore-samples.json`、`final-brightness-values.json` 及各阶段 UI XML。

### 正式使用与短时试用

在主页开启「20 分钟无操作调暗」并保持守护运行。屏幕互动中到达无操作期限才调暗；
系统先熄屏时不唤醒，原 30 分钟连续亮屏锁屏规则仍按原配置执行。
「试用调暗 10 秒」只用于检查实际亮度读写和恢复，不改变正式期限。

测试中如手动改亮度或自动模式，应保留用户新设置；正常恢复应还原进入调暗前的亮度及模式。
修改前同步保存恢复记录，异常时保留记录并停止继续调暗。
本机系统无操作熄屏时间仍为 600000 ms（10 分钟）。

## 2026-11-14：字体防回退实验功能（1.1.0）

验收截止时间：**2026-11-14 16:04（UTC+8）**。以下是通过 ADB 和手机界面实际执行的专项验证；未执行的项目另列为待观察。

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 已通过；累计 36 项 JVM 测试，0 失败、0 错误。
- 新增 5 项广播处理测试：无序广播不尝试中止、禁止中止时不尝试、两种校验广播只报告请求、无关广播不受影响、平台异常不导致锁屏服务崩溃。
- Lint 无错误；保留原有代码/依赖等警告。`systemExempted` 服务的权限检查仅在该服务节点抑制：本应用使用官方允许的已激活 Device Admin 条件，并在调用前校验，未添加精确闹钟权限。
- 已使用相同签名覆盖安装至 Xiaomi 15 / Android 16 / OS3.0.307.0.WOCCNXM；版本 1.1.0（2）。原设置保留，`MY_PACKAGE_REPLACED` 自动恢复同一个 `GuardianService` 前台服务已验证。
- 真机 UI 开关已验证：关闭时仅注销字体监听，原前台服务继续运行；重新开启时恢复注册。首次开启前后亮屏计时从 00:33 继续到 01:03，进程和服务未重启。
- 两种包内定向有序自检广播均记录 `ABORT_REQUESTED`；带 `FLAG_RECEIVER_NO_ABORT` 的广播记录 `ABORT_NOT_ALLOWED`。三次自检均明确标记，累计校验广播数保持 0。
- 暂停锁屏后字体监听仍注册，恢复后仍运行；停止守护后监听与前台服务均停止；重新开启后监听恢复，字体开关仍为开启。同一进程内始终只使用原 `GuardianService` / 通知 ID 1001。
- 最终手机状态：守护运行中，字体实验开关开启，原锁屏设置及设备管理员授权保留。证据保存在 `diagnostics/font-guard-verification/`，包含 UI XML、生命周期检查、日志和摘要。
- 系统真实定时广播的跨进程拦截、超过三天的字体保留、开机后实机恢复、长期耗电：**未验证**。定向自检不能替代这些验证。

### 本次真机验证结果

| 检查项 | 结果 | 观察到的行为 |
|---|---|---|
| 覆盖安装与更新恢复 | 通过 | 1.1.0（2）安装成功，原设置保留，`MY_PACKAGE_REPLACED` 恢复前台守护 |
| 字体开关关闭 / 开启 | 通过 | 仅注销 / 注册字体监听，原锁屏服务持续运行 |
| 切换开关时亮屏计时 | 通过 | 从 00:33 继续到 01:03，同一进程和服务未重启 |
| `CHECK_TIME_UP` 定向有序自检 | 通过 | 记录 `ABORT_REQUESTED`，未声称系统校验已被阻止 |
| `CHECK_THEME_UPDATE` 定向有序自检 | 通过 | 记录 `ABORT_REQUESTED` |
| `FLAG_RECEIVER_NO_ABORT` 自检 | 通过 | 记录 `ABORT_NOT_ALLOWED`，未尝试中止 |
| 暂停 / 恢复锁屏 | 通过 | 前台服务与字体监听均持续运行 |
| 停止 / 重新开启守护 | 通过 | 停止时监听注销；重新开启后按保存的开关恢复 |
| 自检计数隔离 | 通过 | 三次自检后，真实校验广播计数仍为 0 |
| 后台与权限 | 通过 | 仍使用原 `GuardianService` 和通知 ID 1001，权限声明未增加 |
| 系统真实定时校验的拦截效果 | 未验证 | 本次仅发送限定本应用包名的自检广播 |
| 超过三天的字体保留、重启恢复、长期耗电 | 未验证 | 需要后续正常使用观察 |

### 验证证据

- 验收摘要（本地：`diagnostics/font-guard-verification/verification-summary.json`）：版本、APK 哈希、测试统计、最终开关状态与待验证项。
- 服务生命周期检查（本地：`diagnostics/font-guard-verification/lifecycle-checks.json`）：开关、暂停/恢复和停止/重启六个阶段的断言结果。
- 实机运行日志（本地：`diagnostics/font-guard-verification/device-functional-checks.log`）：广播处理与监听注册/注销记录。
- 安装包权限清单（本地：`diagnostics/font-guard-verification/apk-permissions.txt`）：与更新前比对，无新增权限。
- 最终应用界面 XML（本地：`diagnostics/font-guard-verification/ui-restarted.xml`）：守护运行中、字体开关开启及最近自检结果。
- 单元测试摘要（本地：`diagnostics/font-guard-verification/unit-test-summary.json`） 与 Lint 结果（本地：`diagnostics/font-guard-verification/lint-results-debug.xml`）。

### 后续观察方式

在实验开关已开启、守护运行的状态下，手动重新应用所需字体，记录应用时间。
后续在主页查看「最近一次」和事件日志；标为「最近自检」的记录只说明自检处理结果。
即使显示 `ABORT_REQUESTED`，仍需以系统校验后的字体是否保留为准；跨越三天、重启和待机耗电应分别记录实际观察时长与结果。

可使用定向广播验证本应用的接收和分支处理（开启实验开关后运行）：

```powershell
adb shell am broadcast --user 0 -p com.example.thermalguard -a miui.intent.action.CHECK_TIME_UP --ez com.example.thermalguard.extra.FONT_GUARD_SELF_TEST true
adb shell am broadcast --user 0 -p com.example.thermalguard -a miui.intent.action.CHECK_THEME_UPDATE --ez com.example.thermalguard.extra.FONT_GUARD_SELF_TEST true
adb shell am broadcast --user 0 -p com.example.thermalguard -a miui.intent.action.CHECK_TIME_UP -f 0x08000000 --ez com.example.thermalguard.extra.FONT_GUARD_SELF_TEST true
```

第三条带 `FLAG_RECEIVER_NO_ABORT`，应记录「广播不允许中止」。自检需同时限定本应用包名并带自检标记；会明确显示为自检，不计入真实校验次数，也不会触发系统主题接收器。

---

## 1. 自动化单元测试 (JVM)

### 1.1 运行方式
在项目根目录下通过 PowerShell 执行：
```powershell
$env:JAVA_HOME = "D:\develop\android-studio\jbr"
.\gradlew.bat test
```

### 1.2 原有单元测试用例执行结果（31 项；加上 5 项字体、14 项调暗测试，累计 50 项全部通过）

| 编号 | 测试用例名 | 验证内容 | 结果 |
|---|---|---|---|
| **T-01** | `testT01_ScreenOnUnder30MinutesDoesNotLock` | 亮屏 0 → 29:59 期间不触发锁屏 | **通过 (Passed)** |
| **T-02** | `testT02_ScreenOnReach30MinutesTriggersLock` | 亮屏达到 30:00 且设备仍交互中：触发锁屏且同一会话不重复锁 | **通过 (Passed)** |
| **T-03** | `testT03_ScreenOffBeforeTimeoutDoesNotLock` | 提前熄屏取消计时，后续不触发锁屏 | **通过 (Passed)** |
| **T-04** | `testT04_ScreenOffThenOnResetsDurationToZero` | 熄屏后再亮屏：清空旧会话，重新从零起计 | **通过 (Passed)** |
| **T-05** | `testT05_DuplicateScreenOnDoesNotResetSession` | 重复收到 `SCREEN_ON` 回调不重置已有有效会话 | **通过 (Passed)** |
| **T-06** | `testT06_UnderAntiShakeDurationDoesNotLock` | 未充电：44.9 → 45.0 持续不足 10 秒不锁屏 | **通过 (Passed)** |
| **T-07** | `testT07_ReachAntiShakeDurationLocks` | 未充电：持续达标且满足两次有效样本、跨越 10 秒才锁屏 | **通过 (Passed)** |
| **T-08** | `testT08_DropBelowThresholdResetsWindow` | 确认中途样本降到阈值以下：抗抖确认窗口立即重置 | **通过 (Passed)** |
| **T-09** | `testT09_Charging455DoesNotLock` | 充电中 45.5°C：低于充电锁屏阈值 (46.0°C)，不锁屏 | **通过 (Passed)** |
| **T-10** | `testT10_Charging460LocksAfter10Seconds` | 充电中 46.0°C 持续 ≥ 10 秒：锁屏并进入冷却 | **通过 (Passed)** |
| **T-11** | `testT11_UnplugAt455StartsNewConfirmation` | 充电中 45.5°C 时拔线：从拔线后的样本重新确认，不立即锁；确认后锁 | **通过 (Passed)** |
| **T-12** | `testT12_PlugInDuringConfirmationResetsWindow` | 确认中插线 (45.5°C)：阈值变为 46.0°C，低于阈值，确认窗口重置 | **通过 (Passed)** |
| **T-13** | `testT13_ScreenOffHighTempDoesNotLockUntilConfirmedAfterScreenOn` | 熄屏期间高温：不调用 `lockNow()`；亮屏后不立即锁，重新确认后才锁 | **通过 (Passed)** |
| **T-14** | `testT14_CooldownRejectsLockEvenIfHighTemp` | 锁屏后冷却期内解锁：即便仍处于高温也绝不重复强制锁屏 | **通过 (Passed)** |
| **T-15** | `testT15_CooldownEscalationSequence` | 冷却结束仍高温：重新确认后再锁，冷却时长依次递增：3 → 5 → 10 分钟 | **通过 (Passed)** |
| **T-16** | `testT16_DropBelowRecoveryResetsToNormal` | 温度降到恢复阈值 (41.0°C) 以下：状态回到 NORMAL，连续次数清零 | **通过 (Passed)** |
| **T-17** | `testT17_WarningHysteresisDoesNotJitter` | 预警在 42°C 附近（41.9 / 42.0 / 41.5 / 40.9）不抖动，同一轮只提示一次 | **通过 (Passed)** |
| **T-18** | `testPauseAndResumeBehavior` | 暂停期间任何条件均不锁屏，恢复后点亮重新起计 | **通过 (Passed)** |
| **T-19** | `testT19_SecurityExceptionHandledGracefully` | 权限被撤销时不崩溃，优雅返回 `SecurityError` | **通过 (Passed)** |
| **T-19** | `testT19_GenericExceptionHandledGracefully` | 系统未知异常捕获并不崩溃 | **通过 (Passed)** |
| **T-20** | `testT20_ConcurrentLockCalls` | 并发两个以上触发条件时只产生单次 `lockNow()` 执行 | **通过 (Passed)** |
| **T-21** | `testT21_MinIntervalRateLimit` | 两次锁屏间隔 < 5 秒时第二次被拒绝，冷却后恢复可用 | **通过 (Passed)** |
| **T-22** | `testT22_ThermalStatusCriticalBypassesConfirmation` | 热状态 `CRITICAL` 绕过确认锁一次且受冷却限制；充电中不放宽；`SEVERE` 仅提示 | **通过 (Passed)** |
| **T-23** | `testT23_SettingsValidation` | 阈值与范围校验 (`恢复 < 预警 < 锁屏 ≤ 充电锁屏`，30.0–60.0°C，抗抖 5–60 秒，亮屏 1–240 分钟) | **通过 (Passed)** |
| **T-24** | `testT24_SameBootSessionPreservesPauseRemainingTime` | 暂停持久化：同一次开机内进程重建，按剩余时间继续保持暂停 | **通过 (Passed)** |
| **T-24** | `testT24_DifferentBootCountInvalidatesPause` | 暂停持久化：开机标识变化 (跨开机重启手机)，暂停立即失效 | **通过 (Passed)** |
| 边界 | `testManualTestSuccess` | 正常状态下手动的 `MANUAL_TEST` 锁屏成功执行 | **通过 (Passed)** |
| 边界 | `testRejectedWhenAdminNotActive` | 设备管理员未激活时拒绝锁屏 | **通过 (Passed)** |
| 边界 | `testRejectedWhenScreenNotInteractive` | 屏幕熄屏（非交互）状态下拒绝锁屏 | **通过 (Passed)** |
| 边界 | `testRejectedWhenGuardianDisabledForAutoTrigger` | 守护总开关关闭时拒绝自动锁屏 | **通过 (Passed)** |
| 边界 | `testRejectedWhenPausedForAutoTrigger` | 守护处于暂停状态时拒绝自动锁屏 | **通过 (Passed)** |

---

## 2. 真机手动验证清单（Xiaomi 15 / HyperOS 3 / Android 16）

> **当前连接真机**：`24129PN74C` (Xiaomi 15)，Android 16，API 36。
> **说明**：以下原功能清单按实际执行范围记录；未完整执行的项目保留为「待人工验证」或「部分验证」。字体实验功能的专项结果见本页开头。

| 编号 | 验证项 | 验证步骤与关注点 | 状态 |
|---|---|---|---|
| M-01 | 首次授权设备管理员与启动服务 | 点击「前往激活设备管理员」，跳转至 HyperOS 设备管理激活页；激活后返回应用开启守护，验证常驻通知是否正常出现 (`systemExempted` 服务) | 待人工验证 |
| M-02 | 1分钟亮屏测试与强认证解锁 | 在设置中将连续亮屏阈值临时改为 1 分钟，连续使用手机 1 分钟，验证是否自动锁屏；唤醒屏幕验证是否提示输入 PIN/密码 解锁 | 待人工验证 |
| M-03 | 42°C 预警与温控模拟 | 执行 `adb shell dumpsys battery set temp 420`，观察通知与日志是否出现一次预警且不重复刷新 | 待人工验证 |
| M-04 | 45°C 未充电锁屏、46°C 充电锁屏 | 模拟 `450`（拔线）与 `460`（插线），验证抗抖 10 秒后是否自动锁屏并进入冷却 | 待人工验证 |
| M-05 | 充电状态切换阈值变动 | 拔线与插线 (`adb shell dumpsys battery set ac 1` / `unplug`)，观察当前生效阈值是否即时切换 | 待人工验证 |
| M-06 | 熄屏、通知暂停、重新点亮 | 手机熄屏期间观察通知，重新点亮屏幕后验证计时是否从零重新开始 | 待人工验证 |
| M-07 | 低电量模式兼容性 | 手机开启系统省电模式，观察前台守护服务与通知是否正常存活 | 待人工验证 |
| M-08 | 通知与主页快捷动作 | 通知栏和主页操作「暂停15分钟」与「停止守护」，验证服务状态响应 | 部分验证：2026-11-14 主页暂停/恢复/停止通过；通知栏操作待验证 |
| M-09 | 撤销管理员授权与卸载 | 点击「停止守护并取消设备管理员」，确认后注销权限，验证应用可直接长按图标卸载 | 待人工验证 |
| M-10 | 暂停 15 分钟到期后恢复 | 点击暂停 15 分钟（可临时在代码或调试中调短），验证到期后自动恢复守护且不追算暂停期间 | 待人工验证 |
| M-11 | 划掉最近任务卡片存活 | 多任务界面划掉本应用，验证常驻前台服务与通知是否持续运行不被杀 | 待人工验证 |
| M-12 | 系统内存回收重建 | 运行多款大型游戏或应用压迫内存，验证 Service 被重建后是否记录“监测空窗”并继续守护 | 待人工验证 |
| M-13 | 重启手机自启恢复 | 手机重启开机，解锁后观察 BootReceiver 是否在 1~2 分钟内按用户此前意向恢复前台服务 | 待人工验证 |
| M-14 | 强行停止后的表现 | 在系统应用信息中点击「强行停止」，验证服务停止，主界面再次打开时可正常重新启动 | 待人工验证 |
| M-15 | 覆盖安装/应用更新恢复 | 使用新版 APK 覆盖安装，验证 `MY_PACKAGE_REPLACED` 广播是否成功恢复守护服务 | 已验证：2026-11-14，ADB 覆盖更新 1.1.0 后自动恢复 |
| M-16 | 通知被划掉/系统停止服务表现 | 若在系统运行中服务里被关闭，再次打开主页验证状态是否标记为已停止，日志区分原因 | 待人工验证 |
| M-17 | 暂停期间重启手机表现 | 暂停期间重启手机，验证重启后开机计数改变导致暂停失效，回到正常守护状态 | 待人工验证 |
| M-18 | 熄屏长测与整夜耗电 | 整夜熄屏或待机数小时，对比电池消耗与日志记录，确认无异常高频唤醒或耗电 | 待人工验证 |

---

## 3. 真机调试与模拟命令参考

本次 1.2.0 使用本地构建产物覆盖安装：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### ADB 模拟命令（测试后务必执行 reset 复位）：
```bash
# 模拟电池温度（单位 0.1°C，450 代表 45.0°C）
adb shell dumpsys battery set temp 450

# 模拟接入外部电源充电
adb shell dumpsys battery set ac 1

# 模拟拔线
adb shell dumpsys battery unplug

# 电池状态复位（测完务必执行！）
adb shell dumpsys battery reset

# 模拟系统严重热状态 CRITICAL (等级 4)
adb shell cmd thermalservice override-status 4

# 热状态复位（测完务必执行！）
adb shell cmd thermalservice reset
```
