package com.example.thermalguard.rule

import com.example.thermalguard.util.TimeProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 可控测试时钟
 */
class FakeTimeProvider(var currentMs: Long = 100_000L) : TimeProvider {
    override fun elapsedRealtime(): Long = currentMs
    override fun currentTimeMillis(): Long = currentMs

    fun advanceTime(deltaMs: Long) {
        currentMs += deltaMs
    }
}

/**
 * 可注入测试的 FakeLockExecutor
 */
class FakeLockExecutor(
    var adminActive: Boolean = true,
    var interactive: Boolean = true,
    var shouldThrowSecurityException: Boolean = false,
    var shouldThrowGenericException: Boolean = false
) : LockExecutor {

    val lockCount = AtomicInteger(0)

    override fun isAdminActive(): Boolean = adminActive
    override fun isInteractive(): Boolean = interactive

    override fun performLock() {
        if (shouldThrowSecurityException) {
            throw SecurityException("Mock security exception: Admin not active")
        }
        if (shouldThrowGenericException) {
            throw RuntimeException("Mock system failure")
        }
        lockCount.incrementAndGet()
    }
}

class LockCoordinatorTest {

    private lateinit var fakeTime: FakeTimeProvider
    private lateinit var fakeExecutor: FakeLockExecutor
    private lateinit var coordinator: LockCoordinator

    @Before
    fun setUp() {
        fakeTime = FakeTimeProvider(100_000L)
        fakeExecutor = FakeLockExecutor(adminActive = true, interactive = true)
        coordinator = LockCoordinator(fakeExecutor, fakeTime)
    }

    @Test
    fun testManualTestSuccess() {
        val result = coordinator.requestLock(reason = LockReason.MANUAL_TEST)
        assertTrue("Manual lock should succeed", result.isSuccessful)
        assertEquals(1, fakeExecutor.lockCount.get())
    }

    @Test
    fun testRejectedWhenAdminNotActive() {
        fakeExecutor.adminActive = false
        val result = coordinator.requestLock(reason = LockReason.MANUAL_TEST)
        assertTrue("Should reject when admin is not active", result is LockResult.AdminNotActive)
        assertEquals(0, fakeExecutor.lockCount.get())
    }

    @Test
    fun testRejectedWhenScreenNotInteractive() {
        fakeExecutor.interactive = false
        val result = coordinator.requestLock(reason = LockReason.SCREEN_TIMEOUT)
        assertTrue("Should reject when screen is not interactive", result is LockResult.ScreenNotInteractive)
        assertEquals(0, fakeExecutor.lockCount.get())
    }

    @Test
    fun testRejectedWhenGuardianDisabledForAutoTrigger() {
        val result = coordinator.requestLock(
            reason = LockReason.SCREEN_TIMEOUT,
            isGuardianEnabled = false
        )
        assertTrue("Auto lock should reject when guardian disabled", result is LockResult.RejectedGuardianDisabled)
        assertEquals(0, fakeExecutor.lockCount.get())
    }

    @Test
    fun testRejectedWhenPausedForAutoTrigger() {
        val result = coordinator.requestLock(
            reason = LockReason.BATTERY_OVERHEAT,
            isGuardianEnabled = true,
            isPaused = true
        )
        assertTrue("Auto lock should reject when guardian paused", result is LockResult.RejectedPaused)
        assertEquals(0, fakeExecutor.lockCount.get())
    }

    /**
     * T-21: 任意两次 lockNow() 间隔 < 5 秒的第二次被拒绝
     */
    @Test
    fun testT21_MinIntervalRateLimit() {
        // 第一次成功
        val first = coordinator.requestLock(reason = LockReason.MANUAL_TEST)
        assertTrue(first.isSuccessful)
        assertEquals(1, fakeExecutor.lockCount.get())

        // 推进 2 秒 (不足 5 秒)
        fakeTime.advanceTime(2_000L)
        val second = coordinator.requestLock(reason = LockReason.MANUAL_TEST)
        assertTrue("Second call within 5s must be rejected", second is LockResult.RejectedMinInterval)
        assertEquals(3_000L, (second as LockResult.RejectedMinInterval).remainingMs)
        assertEquals(1, fakeExecutor.lockCount.get())

        // 再推进 3.1 秒 (累计 5.1 秒，超过 5 秒)
        fakeTime.advanceTime(3_100L)
        val third = coordinator.requestLock(reason = LockReason.MANUAL_TEST)
        assertTrue("Call after 5s should succeed", third.isSuccessful)
        assertEquals(2, fakeExecutor.lockCount.get())
    }

    /**
     * T-20: 并发调用时只产生一次成功锁屏
     */
    @Test
    fun testT20_ConcurrentLockCalls() {
        val numThreads = 10
        val latch = CountDownLatch(1)
        val doneLatch = CountDownLatch(numThreads)
        val executor = Executors.newFixedThreadPool(numThreads)
        val successCount = AtomicInteger(0)

        for (i in 0 until numThreads) {
            executor.submit {
                try {
                    latch.await()
                    val res = coordinator.requestLock(
                        reason = if (i % 2 == 0) LockReason.BATTERY_OVERHEAT else LockReason.SCREEN_TIMEOUT
                    )
                    if (res.isSuccessful) {
                        successCount.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // 统一发令
        latch.countDown()
        doneLatch.await()
        executor.shutdown()

        assertEquals("Only one lock should succeed concurrently", 1, successCount.get())
        assertEquals(1, fakeExecutor.lockCount.get())
    }

    /**
     * T-19 部分场景：异常捕获不崩溃
     */
    @Test
    fun testT19_SecurityExceptionHandledGracefully() {
        fakeExecutor.shouldThrowSecurityException = true
        val result = coordinator.requestLock(reason = LockReason.MANUAL_TEST)
        assertTrue("SecurityException should be captured", result is LockResult.SecurityError)
        assertEquals(0, fakeExecutor.lockCount.get())
    }

    @Test
    fun testT19_GenericExceptionHandledGracefully() {
        fakeExecutor.shouldThrowGenericException = true
        val result = coordinator.requestLock(reason = LockReason.MANUAL_TEST)
        assertTrue("Generic exception should be captured", result is LockResult.ExecutionError)
        assertEquals(0, fakeExecutor.lockCount.get())
    }
}
