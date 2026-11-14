package com.example.thermalguard.rule

import com.example.thermalguard.data.GuardianLogRepository
import com.example.thermalguard.util.DefaultTimeProvider
import com.example.thermalguard.util.TimeProvider

/**
 * 亮屏计时判定引擎（解耦系统时钟，便于全覆盖 JVM 单测）
 * 遵循 §3.1 业务规范：
 * 1. 进入交互状态开始计时；重复 SCREEN_ON 不重置已有会话。
 * 2. 退出交互状态立即清零。
 * 3. 达到设定时长且设备仍在交互中，触发锁屏协调器执行锁屏。
 * 4. 同一亮屏会话只触发一次超时锁屏。
 */
class ScreenDurationRuleEngine(
    private val lockCoordinator: LockCoordinator,
    private val timeProvider: TimeProvider = DefaultTimeProvider(),
    var maxScreenOnMinutes: Int = 30
) {
    private val lock = Any()

    // 当前会话开始流逝时间（null 表示当前非交互/无会话）
    private var sessionStartElapsedMs: Long? = null

    // 本次会话是否已经触发过超时锁屏
    private var hasLockedThisSession: Boolean = false

    // 守护是否启用
    var isEnabled: Boolean = true

    // 是否处于暂停状态
    var isPaused: Boolean = false

    val maxDurationMs: Long
        get() = maxScreenOnMinutes * 60 * 1000L

    /**
     * 当前会话已持续交互的毫秒数
     */
    val currentInteractiveDurationMs: Long
        get() = synchronized(lock) {
            val start = sessionStartElapsedMs ?: return 0L
            (timeProvider.elapsedRealtime() - start).coerceAtLeast(0L)
        }

    /**
     * 距离超时的剩余毫秒数（若未在会话中则返回最大值）
     */
    val remainingTimeoutMs: Long
        get() = synchronized(lock) {
            val start = sessionStartElapsedMs ?: return maxDurationMs
            val elapsed = timeProvider.elapsedRealtime() - start
            (maxDurationMs - elapsed).coerceAtLeast(0L)
        }

    /**
     * 当前是否正处于亮屏交互会话中
     */
    val isInInteractiveSession: Boolean
        get() = synchronized(lock) {
            sessionStartElapsedMs != null
        }

    /**
     * 收到 SCREEN_ON 广播或初始检查为 interactive
     */
    fun onScreenInteractive() {
        synchronized(lock) {
            if (!isEnabled || isPaused) return

            // 若尚未有有效会话，记录起始时间；重复 SCREEN_ON 回调不得重置已有会话 (T-05)
            if (sessionStartElapsedMs == null) {
                val now = timeProvider.elapsedRealtime()
                sessionStartElapsedMs = now
                hasLockedThisSession = false
                GuardianLogRepository.addLog("亮屏", "进入屏幕交互状态，开始计时")
            }
        }
    }

    /**
     * 收到 SCREEN_OFF 广播或退出交互状态
     */
    fun onScreenNonInteractive() {
        synchronized(lock) {
            if (sessionStartElapsedMs != null) {
                GuardianLogRepository.addLog("熄屏", "屏幕退出交互，清空本次计时")
            }
            sessionStartElapsedMs = null
            hasLockedThisSession = false
        }
    }

    /**
     * 计划任务到期时或定期状态核查触发此方法
     * @param isInteractive 当前系统的实时交互状态（通过 PowerManager 现场查验）
     * @return 若触发了锁屏则返回结果，否则返回 null
     */
    fun checkTimeout(isInteractive: Boolean): LockResult? {
        synchronized(lock) {
            if (!isEnabled || isPaused) return null

            // 现场验证系统是否依然处于交互状态
            if (!isInteractive) {
                onScreenNonInteractive()
                return null
            }

            val start = sessionStartElapsedMs ?: return null
            val now = timeProvider.elapsedRealtime()
            val duration = now - start

            // 检查是否已经达到或超过阈值，且本会话尚未锁屏 (T-01, T-02)
            if (duration >= maxDurationMs && !hasLockedThisSession) {
                GuardianLogRepository.addLog(
                    "超时",
                    "连续亮屏已达 ${duration / 1000 / 60} 分钟，请求锁屏",
                    isWarningOrError = true
                )
                val result = lockCoordinator.requestLock(
                    reason = LockReason.SCREEN_TIMEOUT,
                    isGuardianEnabled = isEnabled,
                    isPaused = isPaused,
                    bypassInteractiveCheck = false
                )
                if (result.isSuccessful) {
                    hasLockedThisSession = true
                }
                return result
            }
            return null
        }
    }

    /**
     * 暂停守护
     */
    fun onPause() {
        synchronized(lock) {
            isPaused = true
            sessionStartElapsedMs = null
            hasLockedThisSession = false
            GuardianLogRepository.addLog("暂停", "守护暂停，计时已取消")
        }
    }

    /**
     * 恢复守护
     * @param isInteractive 当前屏幕是否点亮
     */
    fun onResume(isInteractive: Boolean) {
        synchronized(lock) {
            isPaused = false
            GuardianLogRepository.addLog("恢复", "守护恢复运行")
            if (isInteractive) {
                sessionStartElapsedMs = timeProvider.elapsedRealtime()
                hasLockedThisSession = false
            }
        }
    }

    /**
     * 进程/服务重启恢复：记录监测空窗，不恢复旧会话 (S-08)
     */
    fun onServiceRestart(isInteractive: Boolean) {
        synchronized(lock) {
            GuardianLogRepository.addLog(
                "服务重建",
                "检测到服务重启，记录一次监测空窗，重置亮屏计时",
                isWarningOrError = true
            )
            sessionStartElapsedMs = null
            hasLockedThisSession = false
            if (isInteractive) {
                sessionStartElapsedMs = timeProvider.elapsedRealtime()
            }
        }
    }
}
