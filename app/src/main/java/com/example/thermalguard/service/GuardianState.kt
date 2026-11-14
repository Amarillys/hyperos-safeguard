package com.example.thermalguard.service

import com.example.thermalguard.monitor.ThermalLevel
import com.example.thermalguard.rule.TemperatureState

/**
 * 前台守护服务状态迁移表 (§8.1)
 */
enum class ServiceStatus(val displayName: String) {
    STOPPED("已停止"),
    NEEDS_ADMIN("需要设备管理员授权"),
    STARTING("启动中"),
    RUNNING("守护中"),
    PAUSED("已暂停"),
    ERROR("运行异常")
}

/**
 * 守护服务对 UI 暴露的可观察状态数据（阶段三：包含完整温控与热状态数据）
 */
data class GuardianServiceState(
    val status: ServiceStatus = ServiceStatus.STOPPED,
    val errorMessage: String? = null,
    val isInteractive: Boolean = false,
    val interactiveDurationMs: Long = 0L,
    val remainingTimeoutMs: Long = 0L,
    val maxScreenOnMinutes: Int = 30,
    val currentTempTenthsC: Int? = null,
    val isPlugged: Boolean = false,
    val effectiveLockThresholdTenthsC: Int = 450,
    val thermalLevel: ThermalLevel = ThermalLevel.NONE,
    val temperatureState: TemperatureState = TemperatureState.NORMAL,
    val remainingCooldownMs: Long = 0L,
    val consecutiveLockCount: Int = 0,
    val lastTriggerReason: String? = null
)
