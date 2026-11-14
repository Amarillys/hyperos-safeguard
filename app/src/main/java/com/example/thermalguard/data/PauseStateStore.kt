package com.example.thermalguard.data

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * 暂停状态判定接口（支持注入用于 T-24 单元测试）
 */
interface PauseStateStore {
    /**
     * 保存临时暂停状态
     * @param deadlineElapsedMs 暂停截止时的单调时钟毫秒数
     * @param bootCount 当前系统开机计数标识
     */
    fun savePause(deadlineElapsedMs: Long, bootCount: Int)

    /**
     * 清除暂停状态
     */
    fun clearPause()

    /**
     * 检查暂停是否仍然有效
     * 规则 (§4.2, T-24):
     * 1. 只有在同一次开机内 (bootCount 匹配) 暂停才有效
     * 2. 若跨越开机 (bootCount 变化) 暂停立即失效
     * 3. nowElapsedMs < deadlineElapsedMs 期间暂停生效
     */
    fun isPauseActive(nowElapsedMs: Long, currentBootCount: Int): Boolean

    /**
     * 获取剩余暂停毫秒数
     */
    fun getRemainingPauseMs(nowElapsedMs: Long, currentBootCount: Int): Long
}

/**
 * 基于 SharedPreferences 的本地暂停持久化实现
 */
class SharedPrefsPauseStateStore(private val context: Context) : PauseStateStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun savePause(deadlineElapsedMs: Long, bootCount: Int) {
        prefs.edit()
            .putLong(KEY_DEADLINE_ELAPSED, deadlineElapsedMs)
            .putInt(KEY_BOOT_COUNT, bootCount)
            .apply()
        Log.i(TAG, "Pause saved: deadlineElapsed=$deadlineElapsedMs, bootCount=$bootCount")
    }

    override fun clearPause() {
        prefs.edit()
            .remove(KEY_DEADLINE_ELAPSED)
            .remove(KEY_BOOT_COUNT)
            .apply()
        Log.i(TAG, "Pause cleared")
    }

    override fun isPauseActive(nowElapsedMs: Long, currentBootCount: Int): Boolean {
        val savedBootCount = prefs.getInt(KEY_BOOT_COUNT, -1)
        if (savedBootCount == -1 || savedBootCount != currentBootCount) {
            // 跨开机则暂停失效 (§4.2)
            return false
        }
        val deadline = prefs.getLong(KEY_DEADLINE_ELAPSED, 0L)
        return nowElapsedMs < deadline
    }

    override fun getRemainingPauseMs(nowElapsedMs: Long, currentBootCount: Int): Long {
        if (!isPauseActive(nowElapsedMs, currentBootCount)) return 0L
        val deadline = prefs.getLong(KEY_DEADLINE_ELAPSED, 0L)
        return (deadline - nowElapsedMs).coerceAtLeast(0L)
    }

    companion object {
        private const val TAG = "PauseStateStore"
        private const val PREFS_NAME = "guardian_pause_state"
        private const val KEY_DEADLINE_ELAPSED = "pause_deadline_elapsed"
        private const val KEY_BOOT_COUNT = "pause_boot_count"

        /**
         * 读取系统当前开机计数 (Settings.Global.BOOT_COUNT)
         */
        fun readSystemBootCount(context: Context): Int {
            return try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 1)
            } catch (e: Exception) {
                1
            }
        }
    }
}
