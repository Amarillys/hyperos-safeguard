package com.example.thermalguard.rule

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScreenDurationRuleEngineTest {

    private lateinit var fakeTime: FakeTimeProvider
    private lateinit var fakeExecutor: FakeLockExecutor
    private lateinit var coordinator: LockCoordinator
    private lateinit var engine: ScreenDurationRuleEngine

    @Before
    fun setUp() {
        fakeTime = FakeTimeProvider(1_000_000L)
        fakeExecutor = FakeLockExecutor(adminActive = true, interactive = true)
        coordinator = LockCoordinator(fakeExecutor, fakeTime)
        engine = ScreenDurationRuleEngine(coordinator, fakeTime, maxScreenOnMinutes = 30)
    }

    /**
     * T-01: 亮屏 0 → 29:59 不锁
     */
    @Test
    fun testT01_ScreenOnUnder30MinutesDoesNotLock() {
        engine.onScreenInteractive()

        // 推进 29 分 59 秒 (29 * 60 + 59 = 1799 秒 = 1,799,000 毫秒)
        fakeTime.advanceTime(1_799_000L)
        val result = engine.checkTimeout(isInteractive = true)

        assertNull("29:59 should not trigger lock", result)
        assertEquals(0, fakeExecutor.lockCount.get())
        assertTrue("Duration should be 29:59", engine.currentInteractiveDurationMs >= 1_799_000L)
    }

    /**
     * T-02: 30:00 且仍交互：触发一次
     */
    @Test
    fun testT02_ScreenOnReach30MinutesTriggersLock() {
        engine.onScreenInteractive()

        // 推进恰好 30 分钟 (1,800,000 毫秒)
        fakeTime.advanceTime(1_800_000L)
        val result = engine.checkTimeout(isInteractive = true)

        assertNotNull("30:00 must trigger lock", result)
        assertTrue(result!!.isSuccessful)
        assertEquals(1, fakeExecutor.lockCount.get())

        // 之后再次 checkTimeout 不得在此会话重复触发锁屏
        val secondCheck = engine.checkTimeout(isInteractive = true)
        assertNull("Same session must not repeat lock", secondCheck)
        assertEquals(1, fakeExecutor.lockCount.get())
    }

    /**
     * T-03: 提前熄屏：不触发
     */
    @Test
    fun testT03_ScreenOffBeforeTimeoutDoesNotLock() {
        engine.onScreenInteractive()

        // 20 分钟时熄屏
        fakeTime.advanceTime(20 * 60 * 1000L)
        engine.onScreenNonInteractive()

        // 再过 15 分钟核查，由于当前非交互且会话已关闭，不触发
        fakeTime.advanceTime(15 * 60 * 1000L)
        val result = engine.checkTimeout(isInteractive = false)

        assertNull("Screen off must not trigger lock", result)
        assertEquals(0, fakeExecutor.lockCount.get())
    }

    /**
     * T-04: 熄屏后再亮屏：重新从零计时
     */
    @Test
    fun testT04_ScreenOffThenOnResetsDurationToZero() {
        engine.onScreenInteractive()

        // 亮屏 25 分钟
        fakeTime.advanceTime(25 * 60 * 1000L)
        assertEquals(25 * 60 * 1000L, engine.currentInteractiveDurationMs)

        // 熄屏，清空会话
        engine.onScreenNonInteractive()
        assertEquals(0L, engine.currentInteractiveDurationMs)

        // 重新亮屏
        engine.onScreenInteractive()
        assertEquals(0L, engine.currentInteractiveDurationMs)

        // 再亮屏 10 分钟（累计 35 分钟，但本次会话仅 10 分钟）
        fakeTime.advanceTime(10 * 60 * 1000L)
        val result = engine.checkTimeout(isInteractive = true)

        assertNull("Only 10 mins in current session, must not lock", result)
        assertEquals(10 * 60 * 1000L, engine.currentInteractiveDurationMs)
        assertEquals(0, fakeExecutor.lockCount.get())
    }

    /**
     * T-05: 重复 SCREEN_ON 回调不重置已有会话
     */
    @Test
    fun testT05_DuplicateScreenOnDoesNotResetSession() {
        engine.onScreenInteractive()

        // 推进 10 分钟
        fakeTime.advanceTime(10 * 60 * 1000L)
        assertEquals(10 * 60 * 1000L, engine.currentInteractiveDurationMs)

        // 系统再次发送 SCREEN_ON 回调（比如解锁或分屏变化）
        engine.onScreenInteractive()

        // 计时依然从最开始算起，不能被刷新为 0
        assertEquals(10 * 60 * 1000L, engine.currentInteractiveDurationMs)

        // 再过 20 分钟（总计 30 分钟）
        fakeTime.advanceTime(20 * 60 * 1000L)
        val result = engine.checkTimeout(isInteractive = true)

        assertNotNull("Should reach 30 minutes from original start", result)
        assertTrue(result!!.isSuccessful)
        assertEquals(1, fakeExecutor.lockCount.get())
    }

    /**
     * 补充测试：暂停期间即使超时也不触发，恢复后若屏幕点亮从零计时
     */
    @Test
    fun testPauseAndResumeBehavior() {
        engine.onScreenInteractive()
        fakeTime.advanceTime(20 * 60 * 1000L)

        // 暂停守护
        engine.onPause()
        fakeTime.advanceTime(20 * 60 * 1000L)

        // 暂停期间绝不锁屏
        val pauseResult = engine.checkTimeout(isInteractive = true)
        assertNull("Must not lock while paused", pauseResult)
        assertEquals(0, fakeExecutor.lockCount.get())

        // 恢复守护，若屏幕亮起，重新从当前时刻计时
        engine.onResume(isInteractive = true)
        assertEquals(0L, engine.currentInteractiveDurationMs)
    }
}
