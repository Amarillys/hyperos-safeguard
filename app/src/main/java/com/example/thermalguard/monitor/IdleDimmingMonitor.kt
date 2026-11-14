package com.example.thermalguard.monitor

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.example.thermalguard.data.IdleDimmingRepository
import com.example.thermalguard.data.IdleDimmingStatus
import com.example.thermalguard.rule.BrightnessOverride
import com.example.thermalguard.rule.BrightnessRestoreResult
import com.example.thermalguard.rule.InactivityDimmingDecision
import com.example.thermalguard.rule.InactivityDimmingRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Runs only inside GuardianService, sleeping until the inactivity deadline while undimmed. */
class IdleDimmingMonitor(context: Context, private val scope: CoroutineScope) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val source = PowerActivitySource()
    private val brightness = BrightnessOverride(AndroidBrightnessPort(context), SharedPrefsBrightnessLeaseStore(context))
    private var job: Job? = null
    private var paused = false

    fun refresh(preview: Boolean = false) {
        job?.cancel()
        job = null
        if (!restore("重新检查")) return
        val state = IdleDimmingRepository.state.value
        if (!state.enabled) {
            IdleDimmingRepository.runtime(IdleDimmingStatus.STOPPED)
            return
        }
        if (!state.hasDumpPermission || !state.canWriteSettings) {
            IdleDimmingRepository.runtime(IdleDimmingStatus.NEEDS_PERMISSION)
            return
        }
        if (paused) {
            IdleDimmingRepository.runtime(IdleDimmingStatus.PAUSED)
            return
        }
        if (!power.isInteractive) {
            IdleDimmingRepository.runtime(IdleDimmingStatus.SCREEN_OFF)
            return
        }
        IdleDimmingRepository.runtime(IdleDimmingStatus.MONITORING)
        job = scope.launch { monitor(preview) }
    }

    fun setPaused(value: Boolean) {
        paused = value
        refresh()
    }

    fun stop() {
        job?.cancel()
        job = null
        if (restore("停止守护")) IdleDimmingRepository.runtime(IdleDimmingStatus.STOPPED)
    }

    private suspend fun monitor(preview: Boolean) {
        var dimmedForActivity: Long? = null
        var previewDeadline: Long? = null
        var earliestDimTime = 0L
        var pendingPreview = preview
        try {
            while (currentCoroutineContext().isActive) {
                if (!power.isInteractive) {
                    if (restore("屏幕熄灭")) IdleDimmingRepository.runtime(IdleDimmingStatus.SCREEN_OFF)
                    return
                }
                val lastActivity = source.readLastActivityUptimeMs()
                val now = SystemClock.uptimeMillis()
                if (lastActivity > now || lastActivity < 0) throw IllegalStateException("系统交互时间异常，已暂停调暗")
                if (dimmedForActivity != null && !brightness.isStillOwned()) {
                    dimmedForActivity = null
                    previewDeadline = null
                    earliestDimTime = now + InactivityDimmingRule.TIMEOUT_MS
                    IdleDimmingRepository.log("检测到亮度或自动模式已被修改，保留新设置")
                    IdleDimmingRepository.runtime(IdleDimmingStatus.MONITORING)
                }
                val decision = when {
                    pendingPreview -> InactivityDimmingDecision.Dim
                    previewDeadline != null && now >= previewDeadline -> InactivityDimmingDecision.Restore
                    dimmedForActivity == null && now < earliestDimTime -> InactivityDimmingDecision.Wait(earliestDimTime - now)
                    else -> InactivityDimmingRule.decide(now, lastActivity, true, dimmedForActivity)
                }
                when (decision) {
                    InactivityDimmingDecision.Dim -> {
                        if (brightness.dim()) {
                            dimmedForActivity = lastActivity
                            if (pendingPreview) previewDeadline = now + 10_000L
                            IdleDimmingRepository.runtime(IdleDimmingStatus.DIMMED, dimmed = true)
                            IdleDimmingRepository.log(
                                if (pendingPreview) "[试用] 已调暗到原亮度的 20%，操作或 10 秒后恢复"
                                else "已连续 20 分钟无系统用户活动，调暗到原亮度的 20%"
                            )
                        } else {
                            earliestDimTime = now + InactivityDimmingRule.TIMEOUT_MS
                            IdleDimmingRepository.log("原亮度已处于最低水平，保持当前亮度")
                        }
                        pendingPreview = false
                    }
                    InactivityDimmingDecision.Restore -> {
                        if (!restore("检测到操作或试用结束")) return
                        dimmedForActivity = null
                        previewDeadline = null
                        IdleDimmingRepository.runtime(IdleDimmingStatus.MONITORING)
                    }
                    is InactivityDimmingDecision.Wait -> {
                        if (dimmedForActivity == null) {
                            Log.d(TAG, "Last activity uptime=$lastActivity; idleMs=${now - lastActivity}; nextCheckMs=${decision.delayMs}")
                        }
                        delay(decision.delayMs)
                    }
                    InactivityDimmingDecision.Stop -> return
                    InactivityDimmingDecision.Unavailable -> throw IllegalStateException("无法确定无操作时长")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            restore("检测异常")
            val message = e.message ?: e.javaClass.simpleName
            IdleDimmingRepository.runtime(IdleDimmingStatus.ERROR, dimmed = brightness.hasLease, error = message)
            IdleDimmingRepository.log(message, warning = true)
            Log.e(TAG, "Idle dimming stopped", e)
        }
    }

    private fun restore(reason: String): Boolean {
        return try {
            when (brightness.restore()) {
                BrightnessRestoreResult.RESTORED -> IdleDimmingRepository.log("$reason：已恢复原亮度及自动亮度模式")
                BrightnessRestoreResult.USER_CHANGED -> IdleDimmingRepository.log("$reason：保留手动修改后的亮度设置")
                BrightnessRestoreResult.NONE -> Unit
            }
            true
        } catch (e: Exception) {
            val message = "亮度恢复未完成，请检查系统亮度及授权：${e.message ?: e.javaClass.simpleName}"
            IdleDimmingRepository.runtime(IdleDimmingStatus.ERROR, dimmed = true, error = message)
            IdleDimmingRepository.log(message, warning = true)
            Log.e(TAG, "Brightness recovery failed; lease retained", e)
            false
        }
    }

    companion object {
        private const val TAG = "IdleDimming"
    }
}
