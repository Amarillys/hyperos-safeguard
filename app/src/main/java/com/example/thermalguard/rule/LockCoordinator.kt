package com.example.thermalguard.rule

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.example.thermalguard.admin.GuardianDeviceAdminReceiver
import com.example.thermalguard.util.DefaultTimeProvider
import com.example.thermalguard.util.TimeProvider

/**
 * 触发锁屏的原因
 */
enum class LockReason(val displayName: String) {
    MANUAL_TEST("用户手动测试"),
    SCREEN_TIMEOUT("连续亮屏超时"),
    BATTERY_OVERHEAT("电池高温超限"),
    THERMAL_CRITICAL("系统严重过热(Critical)")
}

/**
 * 锁屏请求结果
 */
sealed class LockResult {
    data class Success(val reason: LockReason, val timestampMs: Long) : LockResult()
    data object AdminNotActive : LockResult()
    data object ScreenNotInteractive : LockResult()
    data class RejectedMinInterval(val remainingMs: Long) : LockResult()
    data class RejectedCooldown(val remainingMs: Long) : LockResult()
    data object RejectedPaused : LockResult()
    data object RejectedGuardianDisabled : LockResult()
    data class SecurityError(val message: String) : LockResult()
    data class ExecutionError(val message: String) : LockResult()

    val isSuccessful: Boolean get() = this is Success
}

/**
 * 底层执行接口，抽象 Android 系统 API 以支持无缝 JVM 单元测试
 */
interface LockExecutor {
    fun isAdminActive(): Boolean
    fun isInteractive(): Boolean
    fun performLock()
}

/**
 * 真实的 Android 锁屏执行器
 */
class AndroidLockExecutor(
    private val context: Context,
    private val adminComponent: ComponentName = GuardianDeviceAdminReceiver.getComponentName(context)
) : LockExecutor {

    private val devicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun isAdminActive(): Boolean {
        return try {
            devicePolicyManager?.isAdminActive(adminComponent) == true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking admin status", e)
            false
        }
    }

    override fun isInteractive(): Boolean {
        return try {
            powerManager?.isInteractive == true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking interactive status", e)
            false
        }
    }

    override fun performLock() {
        val dpm = devicePolicyManager ?: throw IllegalStateException("DevicePolicyManager unavailable")
        dpm.lockNow()
    }

    companion object {
        private const val TAG = "AndroidLockExecutor"
    }
}

/**
 * 统一锁屏协调器（收拢所有 lockNow 路径）
 * 满足全局最小间隔防抖、并发安全拦截、权限校验及异常捕获。
 */
class LockCoordinator(
    private val lockExecutor: LockExecutor,
    private val timeProvider: TimeProvider = DefaultTimeProvider()
) {
    private val lock = Any()

    @Volatile
    private var lastLockElapsedMs: Long = 0L

    companion object {
        private const val TAG = "LockCoordinator"
        /**
         * 全局最小两次 lockNow() 调用间隔（5 秒）
         */
        const val MIN_LOCK_INTERVAL_MS = 5_000L
    }

    /**
     * 统一锁屏入口
     * @param reason 锁屏触发原因
     * @param isGuardianEnabled 当前守护总开关是否开启（手动测试时可为 true）
     * @param isPaused 当前是否处于暂停保护状态（手动测试时不受暂停影响）
     * @param bypassInteractiveCheck 是否跳过屏幕交互检查（默认 false，已熄屏时不执行）
     */
    fun requestLock(
        reason: LockReason,
        isGuardianEnabled: Boolean = true,
        isPaused: Boolean = false,
        bypassInteractiveCheck: Boolean = false
    ): LockResult {
        synchronized(lock) {
            val now = timeProvider.elapsedRealtime()

            // 1. 手动测试豁免守护开关与暂停；自动触发则严格要求开启且未暂停
            if (reason != LockReason.MANUAL_TEST) {
                if (!isGuardianEnabled) {
                    Log.w(TAG, "Lock rejected: guardian disabled")
                    return LockResult.RejectedGuardianDisabled
                }
                if (isPaused) {
                    Log.w(TAG, "Lock rejected: guardian is paused")
                    return LockResult.RejectedPaused
                }
            }

            // 2. 检查设备管理员激活状态
            if (!lockExecutor.isAdminActive()) {
                Log.e(TAG, "Lock rejected: Device Admin not active")
                return LockResult.AdminNotActive
            }

            // 3. 检查屏幕交互状态（已熄屏时避免无意义重复锁屏）
            if (!bypassInteractiveCheck && !lockExecutor.isInteractive()) {
                Log.d(TAG, "Lock rejected: device is not interactive")
                return LockResult.ScreenNotInteractive
            }

            // 4. 全局最小调用间隔保护（5 秒防抖）
            val elapsedSinceLast = now - lastLockElapsedMs
            if (lastLockElapsedMs > 0 && elapsedSinceLast < MIN_LOCK_INTERVAL_MS) {
                val remaining = MIN_LOCK_INTERVAL_MS - elapsedSinceLast
                Log.w(TAG, "Lock rejected: rate limit cooldown, remaining ${remaining}ms")
                return LockResult.RejectedMinInterval(remaining)
            }

            // 5. 执行锁屏并捕获异常
            return try {
                lockExecutor.performLock()
                lastLockElapsedMs = now
                Log.i(TAG, "Device lock executed successfully. Reason: ${reason.displayName}")
                LockResult.Success(reason, now)
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException during lockNow()", se)
                LockResult.SecurityError(se.message ?: "权限不足，设备管理员可能已被撤销")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected exception during lockNow()", e)
                LockResult.ExecutionError(e.message ?: "锁屏执行失败")
            }
        }
    }

    /**
     * 重置上次锁屏时间戳（供测试使用）
     */
    fun resetLastLockTimeForTesting() {
        synchronized(lock) {
            lastLockElapsedMs = 0L
        }
    }
}
