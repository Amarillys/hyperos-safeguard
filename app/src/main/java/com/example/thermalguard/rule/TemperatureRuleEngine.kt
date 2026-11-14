package com.example.thermalguard.rule

import com.example.thermalguard.data.GuardianLogRepository
import com.example.thermalguard.data.GuardianSettings
import com.example.thermalguard.monitor.ThermalLevel
import com.example.thermalguard.util.DefaultTimeProvider
import com.example.thermalguard.util.TimeProvider

/**
 * 温度状态机枚举 (§8.2)
 */
enum class TemperatureState(val displayName: String) {
    NORMAL("温度正常"),
    WARNING("高温预警中"),
    CONFIRMING("高温确认抗抖中"),
    LOCKED_COOLDOWN("高温锁屏冷却中")
}

/**
 * 电池温度与热状态规则引擎 (§3.2, §8.2)
 * 解耦系统时钟与外部环境，支持 100% 覆盖的纯 JVM 自动化单测。
 */
class TemperatureRuleEngine(
    private val lockCoordinator: LockCoordinator,
    private val timeProvider: TimeProvider = DefaultTimeProvider(),
    var settings: GuardianSettings = GuardianSettings()
) {
    private val lock = Any()

    var isEnabled: Boolean = true
    var isPaused: Boolean = false

    private var currentState: TemperatureState = TemperatureState.NORMAL

    // 最近有效样本数据
    var lastTempTenthsC: Int? = null
        private set
    var isPlugged: Boolean = false
        private set
    var lastThermalLevel: ThermalLevel = ThermalLevel.NONE
        private set

    // 预警滞回标记：同一轮高温只提示一次，降到恢复阈值以下才重新武装 (T-17)
    private var hasWarnedThisEpisode: Boolean = false

    // 抗抖确认窗口数据 (T-06 ~ T-13)
    private var confirmStartElapsedMs: Long? = null
    private var confirmLastSampleElapsedMs: Long? = null
    private var confirmSampleCount: Int = 0
    private var confirmPluggedState: Boolean? = null

    // 连续高温锁屏次数与冷却截止时刻 (T-14, T-15)
    var consecutiveLockCount: Int = 0
        private set
    private var cooldownEndElapsedMs: Long = 0L

    val state: TemperatureState
        get() = synchronized(lock) { currentState }

    val remainingCooldownMs: Long
        get() = synchronized(lock) {
            val remaining = cooldownEndElapsedMs - timeProvider.elapsedRealtime()
            remaining.coerceAtLeast(0L)
        }

    /**
     * 当前生效的锁屏阈值（0.1°C 整数）
     */
    val effectiveLockThresholdTenthsC: Int
        get() = if (isPlugged) settings.chargingLockTempTenthsC else settings.lockTempTenthsC

    /**
     * 处理新的电池读数样本
     * @param tempTenthsC 电池温度 (0.1°C，例如 450 为 45.0°C；null 为无效/缺失)
     * @param plugged 是否正在充电
     * @param isInteractive 屏幕是否处于交互点亮中
     */
    fun onBatterySample(
        tempTenthsC: Int?,
        plugged: Boolean,
        isInteractive: Boolean
    ): LockResult? {
        synchronized(lock) {
            // 缺失或异常数值直接忽略，不参与规则判定 (T-19)
            if (tempTenthsC == null) {
                return null
            }

            val now = timeProvider.elapsedRealtime()
            val oldPlugged = isPlugged
            isPlugged = plugged
            lastTempTenthsC = tempTenthsC

            // 1. 检查是否降至恢复阈值以下（滞回重置）(T-16)
            if (tempTenthsC < settings.recoveryTempTenthsC) {
                if (currentState != TemperatureState.NORMAL || hasWarnedThisEpisode) {
                    GuardianLogRepository.addLog(
                        "温控恢复",
                        "电池温度 (${tempTenthsC / 10.0}°C) 降至恢复阈值 (${settings.recoveryTempTenthsC / 10.0}°C) 以下，本轮高温状态解除，连续锁屏次数清零"
                    )
                }
                currentState = TemperatureState.NORMAL
                hasWarnedThisEpisode = false
                consecutiveLockCount = 0
                cooldownEndElapsedMs = 0L
                resetConfirmWindow()
                return null
            }

            // 2. 检查冷却期 (T-14, T-15)
            if (currentState == TemperatureState.LOCKED_COOLDOWN) {
                if (now < cooldownEndElapsedMs) {
                    // 冷却期未结束，不重复锁屏 (T-14)
                    return null
                } else {
                    // 冷却期结束，重新评估
                    val effectiveThreshold = effectiveLockThresholdTenthsC
                    if (tempTenthsC >= effectiveThreshold && isInteractive) {
                        currentState = TemperatureState.CONFIRMING
                        startConfirmWindow(now, plugged)
                    } else {
                        currentState = TemperatureState.WARNING
                        resetConfirmWindow()
                    }
                }
            }

            // 3. 电源状态变化导致有效阈值变化 -> 立即重置确认窗口 (T-11, T-12)
            if (confirmStartElapsedMs != null && oldPlugged != plugged) {
                GuardianLogRepository.addLog(
                    "电源切换",
                    "充电状态变动 (${if (plugged) "已插线" else "已拔线"})，有效阈值变更为 ${effectiveLockThresholdTenthsC / 10.0}°C，重置抗抖确认窗口"
                )
                resetConfirmWindow()
            }

            // 4. 屏幕非交互熄屏时：不执行锁屏，重置确认窗口 (T-13)
            if (!isInteractive) {
                if (currentState == TemperatureState.CONFIRMING) {
                    currentState = TemperatureState.WARNING
                }
                resetConfirmWindow()
                return null
            }

            // 5. 校验守护开关与暂停状态
            if (!isEnabled || isPaused || !settings.isTempGuardEnabled) {
                resetConfirmWindow()
                return null
            }

            val effectiveThreshold = effectiveLockThresholdTenthsC

            // 6. 达到预警阈值但低于锁屏阈值 (T-17)
            if (tempTenthsC >= settings.warningTempTenthsC && tempTenthsC < effectiveThreshold) {
                resetConfirmWindow()
                if (!hasWarnedThisEpisode) {
                    hasWarnedThisEpisode = true
                    currentState = TemperatureState.WARNING
                    GuardianLogRepository.addLog(
                        "温度预警",
                        "电池温度达 ${tempTenthsC / 10.0}°C (>= 预警阈值 ${settings.warningTempTenthsC / 10.0}°C)，请注意散热",
                        isWarningOrError = true
                    )
                }
                return null
            }

            // 7. 达到或超过有效锁屏阈值 -> 进入抗抖确认流程 (T-06, T-07, T-09, T-10)
            if (tempTenthsC >= effectiveThreshold) {
                currentState = TemperatureState.CONFIRMING

                if (confirmStartElapsedMs == null) {
                    // 第一次有效超温样本
                    startConfirmWindow(now, plugged)
                    return null
                } else {
                    // 后续有效超温样本
                    confirmLastSampleElapsedMs = now
                    confirmSampleCount++

                    val durationSinceFirst = now - (confirmStartElapsedMs ?: now)
                    val antiShakeDurationMs = settings.antiShakeSeconds * 1000L

                    // 必须满足两次有效样本且至少跨越抗抖秒数 (T-06, T-07)
                    if (confirmSampleCount >= 2 && durationSinceFirst >= antiShakeDurationMs) {
                        GuardianLogRepository.addLog(
                            "超温确认",
                            "电池温度持续达 ${tempTenthsC / 10.0}°C (>= ${effectiveThreshold / 10.0}°C) 且持续达 ${durationSinceFirst / 1000} 秒，触发锁屏",
                            isWarningOrError = true
                        )
                        return executeOverheatLock(now, LockReason.BATTERY_OVERHEAT)
                    }
                }
            } else {
                // 温度掉到有效锁屏阈值以下 -> 重置确认窗口 (T-08)
                if (currentState == TemperatureState.CONFIRMING) {
                    currentState = TemperatureState.WARNING
                }
                resetConfirmWindow()
            }

            return null
        }
    }

    /**
     * 处理系统热状态联动 (§3.2, T-22)
     */
    fun onThermalStatusChanged(level: ThermalLevel, isInteractive: Boolean): LockResult? {
        synchronized(lock) {
            lastThermalLevel = level

            if (!settings.isThermalLinkageEnabled || !isEnabled || isPaused) {
                return null
            }

            if (level == ThermalLevel.SEVERE) {
                GuardianLogRepository.addLog(
                    "系统发热",
                    "系统热状态达到 SEVERE，当前仅提示",
                    isWarningOrError = true
                )
                return null
            }

            // CRITICAL 及以上且屏幕交互中 -> 绕过 10 秒确认，触发锁屏 (受高温冷却期限制，不因充电放宽) (T-22)
            if (level.statusCode >= ThermalLevel.CRITICAL.statusCode) {
                if (!isInteractive) return null

                val now = timeProvider.elapsedRealtime()
                if (currentState == TemperatureState.LOCKED_COOLDOWN && now < cooldownEndElapsedMs) {
                    // 受高温冷却限制
                    return null
                }

                GuardianLogRepository.addLog(
                    "极度过热",
                    "系统热状态达到 ${level.displayName}，紧急触发锁屏",
                    isWarningOrError = true
                )
                return executeOverheatLock(now, LockReason.THERMAL_CRITICAL)
            }

            return null
        }
    }

    /**
     * 熄屏事件同步：重置确认窗口 (T-13)
     */
    fun onScreenOff() {
        synchronized(lock) {
            if (currentState == TemperatureState.CONFIRMING) {
                currentState = TemperatureState.WARNING
            }
            resetConfirmWindow()
        }
    }

    /**
     * 暂停守护
     */
    fun onPause() {
        synchronized(lock) {
            isPaused = true
            resetConfirmWindow()
        }
    }

    /**
     * 恢复守护
     */
    fun onResume() {
        synchronized(lock) {
            isPaused = false
            resetConfirmWindow()
        }
    }

    private fun startConfirmWindow(now: Long, plugged: Boolean) {
        confirmStartElapsedMs = now
        confirmLastSampleElapsedMs = now
        confirmSampleCount = 1
        confirmPluggedState = plugged
    }

    private fun resetConfirmWindow() {
        confirmStartElapsedMs = null
        confirmLastSampleElapsedMs = null
        confirmSampleCount = 0
        confirmPluggedState = null
    }

    /**
     * 执行高温锁屏并计算递增冷却时长 (3 -> 5 -> 10 分钟) (T-15)
     */
    private fun executeOverheatLock(now: Long, reason: LockReason): LockResult {
        val result = lockCoordinator.requestLock(
            reason = reason,
            isGuardianEnabled = isEnabled,
            isPaused = isPaused,
            bypassInteractiveCheck = false
        )

        if (result.isSuccessful) {
            consecutiveLockCount++
            val cooldownMinutes = when (consecutiveLockCount) {
                1 -> 3
                2 -> 5
                else -> 10
            }
            val cooldownMs = cooldownMinutes * 60 * 1000L
            cooldownEndElapsedMs = now + cooldownMs
            currentState = TemperatureState.LOCKED_COOLDOWN
            resetConfirmWindow()

            GuardianLogRepository.addLog(
                "冷却开始",
                "锁屏成功，进入第 $consecutiveLockCount 次高温冷却 ($cooldownMinutes 分钟)，冷却期内不重复锁屏"
            )
        }
        return result
    }
}
