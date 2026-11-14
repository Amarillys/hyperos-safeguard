package com.example.thermalguard.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 内存中用于验证 T-24 规则的 FakePauseStateStore
 */
class FakePauseStateStore : PauseStateStore {
    private var deadlineElapsedMs: Long = 0L
    private var bootCount: Int = -1

    override fun savePause(deadlineElapsedMs: Long, bootCount: Int) {
        this.deadlineElapsedMs = deadlineElapsedMs
        this.bootCount = bootCount
    }

    override fun clearPause() {
        this.deadlineElapsedMs = 0L
        this.bootCount = -1
    }

    override fun isPauseActive(nowElapsedMs: Long, currentBootCount: Int): Boolean {
        if (this.bootCount == -1 || this.bootCount != currentBootCount) {
            return false
        }
        return nowElapsedMs < deadlineElapsedMs
    }

    override fun getRemainingPauseMs(nowElapsedMs: Long, currentBootCount: Int): Long {
        if (!isPauseActive(nowElapsedMs, currentBootCount)) return 0L
        return (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)
    }
}

class PauseStateStoreTest {

    private lateinit var store: FakePauseStateStore

    @Before
    fun setUp() {
        store = FakePauseStateStore()
    }

    /**
     * T-24 部分 1: 同一次开机内按剩余时间继续暂停
     */
    @Test
    fun testT24_SameBootSessionPreservesPauseRemainingTime() {
        val initialElapsed = 100_000L
        val bootCount = 5
        val pauseDuration = 15 * 60 * 1000L // 15 分钟 = 900,000ms

        // 保存暂停 (截止为 1,000,000L)
        store.savePause(deadlineElapsedMs = initialElapsed + pauseDuration, bootCount = bootCount)

        // 推进 5 分钟 (300,000ms)，此时 elapsed 为 400,000L
        val checkElapsed = initialElapsed + 300_000L
        assertTrue("Pause must be active in same boot count", store.isPauseActive(checkElapsed, bootCount))
        assertEquals("Remaining time should be 10 minutes", 600_000L, store.getRemainingPauseMs(checkElapsed, bootCount))

        // 推进 16 分钟 (超过 15 分钟)，暂停到期
        val expiredElapsed = initialElapsed + 960_000L
        assertFalse("Pause must expire when deadline passes", store.isPauseActive(expiredElapsed, bootCount))
        assertEquals(0L, store.getRemainingPauseMs(expiredElapsed, bootCount))
    }

    /**
     * T-24 部分 2: 开机标识变化 (跨开机) 则暂停失效
     */
    @Test
    fun testT24_DifferentBootCountInvalidatesPause() {
        val initialElapsed = 50_000L
        val oldBootCount = 3
        val pauseDuration = 15 * 60 * 1000L

        store.savePause(deadlineElapsedMs = initialElapsed + pauseDuration, bootCount = oldBootCount)

        // 手机重启后，开机计数自增为 4，且单调时钟重置从 10,000L 开始
        val rebootBootCount = 4
        val rebootElapsed = 10_000L

        // 虽然数值上 rebootElapsed < deadlineElapsedMs，但因为 bootCount 变了，跨开机必须失效！(§4.2, T-24)
        assertFalse("Cross-boot pause must be invalidated immediately", store.isPauseActive(rebootElapsed, rebootBootCount))
        assertEquals(0L, store.getRemainingPauseMs(rebootElapsed, rebootBootCount))
    }
}
