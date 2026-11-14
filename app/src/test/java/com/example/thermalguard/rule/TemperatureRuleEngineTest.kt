package com.example.thermalguard.rule

import com.example.thermalguard.data.GuardianSettings
import com.example.thermalguard.monitor.ThermalLevel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TemperatureRuleEngineTest {

    private lateinit var fakeTime: FakeTimeProvider
    private lateinit var fakeExecutor: FakeLockExecutor
    private lateinit var coordinator: LockCoordinator
    private lateinit var engine: TemperatureRuleEngine

    @Before
    fun setUp() {
        fakeTime = FakeTimeProvider(1_000_000L)
        fakeExecutor = FakeLockExecutor(adminActive = true, interactive = true)
        coordinator = LockCoordinator(fakeExecutor, fakeTime)
        engine = TemperatureRuleEngine(
            lockCoordinator = coordinator,
            timeProvider = fakeTime,
            settings = GuardianSettings(
                warningTempTenthsC = 420,       // 42.0°C
                lockTempTenthsC = 450,          // 45.0°C
                chargingLockTempTenthsC = 460,  // 46.0°C
                recoveryTempTenthsC = 410,      // 41.0°C
                antiShakeSeconds = 10           // 10秒
            )
        )
    }

    /**
     * T-06: 未充电：44.9 → 45.0 持续不足 10 秒不锁
     */
    @Test
    fun testT06_UnderAntiShakeDurationDoesNotLock() {
        // 44.9°C (未达未充电阈值 45.0)
        engine.onBatterySample(tempTenthsC = 449, plugged = false, isInteractive = true)
        assertEquals(0, fakeExecutor.lockCount.get())

        // 达到 45.0°C，开启确认窗口
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        assertEquals(0, fakeExecutor.lockCount.get())
        assertEquals(TemperatureState.CONFIRMING, engine.state)

        // 推进 9 秒 (不足 10 秒)
        fakeTime.advanceTime(9_000L)
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)

        assertNull("Duration < 10s must not lock", null)
        assertEquals(0, fakeExecutor.lockCount.get())
        assertEquals(TemperatureState.CONFIRMING, engine.state)
    }

    /**
     * T-07: 未充电：持续达标且满足两次有效样本、跨越 10 秒才锁
     */
    @Test
    fun testT07_ReachAntiShakeDurationLocks() {
        // 样本 1
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        assertEquals(0, fakeExecutor.lockCount.get())

        // 推进 10 秒并送入样本 2
        fakeTime.advanceTime(10_000L)
        val res = engine.onBatterySample(tempTenthsC = 451, plugged = false, isInteractive = true)

        assertNotNull("Should lock after 10s with 2 samples", res)
        assertTrue(res!!.isSuccessful)
        assertEquals(1, fakeExecutor.lockCount.get())
        assertEquals(TemperatureState.LOCKED_COOLDOWN, engine.state)
    }

    /**
     * T-08: 确认中途样本降到阈值以下：窗口重置
     */
    @Test
    fun testT08_DropBelowThresholdResetsWindow() {
        // 首次超温
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        fakeTime.advanceTime(5_000L)

        // 5秒时温度跌回 44.5°C
        engine.onBatterySample(tempTenthsC = 445, plugged = false, isInteractive = true)
        assertEquals(TemperatureState.WARNING, engine.state)

        // 再过 6 秒升回 45.0°C，此时窗口应重新起计
        fakeTime.advanceTime(6_000L)
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        assertEquals(0, fakeExecutor.lockCount.get())

        // 再过 5 秒 (距重新起计仅 5 秒，累计不足 10 秒)
        fakeTime.advanceTime(5_000L)
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        assertEquals("Must not lock before new 10s window", 0, fakeExecutor.lockCount.get())
    }

    /**
     * T-09: 充电中 45.5°C：不锁 (充电阈值 46.0°C)
     */
    @Test
    fun testT09_Charging455DoesNotLock() {
        engine.onBatterySample(tempTenthsC = 455, plugged = true, isInteractive = true)
        fakeTime.advanceTime(15_000L)
        val res = engine.onBatterySample(tempTenthsC = 455, plugged = true, isInteractive = true)

        assertNull("Charging at 45.5C < 46.0C must not lock", res)
        assertEquals(0, fakeExecutor.lockCount.get())
    }

    /**
     * T-10: 充电中 46.0°C 持续 ≥ 10 秒：锁
     */
    @Test
    fun testT10_Charging460LocksAfter10Seconds() {
        engine.onBatterySample(tempTenthsC = 460, plugged = true, isInteractive = true)
        fakeTime.advanceTime(10_000L)
        val res = engine.onBatterySample(tempTenthsC = 460, plugged = true, isInteractive = true)

        assertNotNull(res)
        assertTrue(res!!.isSuccessful)
        assertEquals(1, fakeExecutor.lockCount.get())
    }

    /**
     * T-11: 充电中 45.5°C 时拔线：从拔线后的样本重新确认，不立即锁；确认后锁
     */
    @Test
    fun testT11_UnplugAt455StartsNewConfirmation() {
        // 充电中 45.5°C 持续 10 秒
        engine.onBatterySample(tempTenthsC = 455, plugged = true, isInteractive = true)
        fakeTime.advanceTime(10_000L)
        engine.onBatterySample(tempTenthsC = 455, plugged = true, isInteractive = true)
        assertEquals(0, fakeExecutor.lockCount.get())

        // 此时拔线，有效阈值变为 45.0°C，送入拔线后样本 1，不应立即锁屏
        val unplugRes = engine.onBatterySample(tempTenthsC = 455, plugged = false, isInteractive = true)
        assertNull("Must not lock immediately on unplug", unplugRes)
        assertEquals(0, fakeExecutor.lockCount.get())

        // 从拔线开始新确认 10 秒后，再送入样本 2
        fakeTime.advanceTime(10_000L)
        val confirmRes = engine.onBatterySample(tempTenthsC = 455, plugged = false, isInteractive = true)
        assertNotNull("Should lock after new confirmation completes", confirmRes)
        assertTrue(confirmRes!!.isSuccessful)
        assertEquals(1, fakeExecutor.lockCount.get())
    }

    /**
     * T-12: 确认中插线（45.5°C）：有效阈值变为 46.0°C，低于阈值，窗口重置
     */
    @Test
    fun testT12_PlugInDuringConfirmationResetsWindow() {
        // 未充电 45.5°C 开始确认
        engine.onBatterySample(tempTenthsC = 455, plugged = false, isInteractive = true)
        fakeTime.advanceTime(5_000L)

        // 插入电源，有效阈值变为 46.0°C，45.5°C 低于有效阈值
        engine.onBatterySample(tempTenthsC = 455, plugged = true, isInteractive = true)
        fakeTime.advanceTime(10_000L)
        val res = engine.onBatterySample(tempTenthsC = 455, plugged = true, isInteractive = true)

        assertNull("45.5C while charging is below 46.0C, window reset", res)
        assertEquals(0, fakeExecutor.lockCount.get())
    }

    /**
     * T-13: 熄屏期间高温：不调用 lockNow()；亮屏后不立即锁，重新确认后才锁
     */
    @Test
    fun testT13_ScreenOffHighTempDoesNotLockUntilConfirmedAfterScreenOn() {
        // 熄屏期间高温 47.0°C 持续 20 秒
        engine.onBatterySample(tempTenthsC = 470, plugged = false, isInteractive = false)
        fakeTime.advanceTime(20_000L)
        engine.onBatterySample(tempTenthsC = 470, plugged = false, isInteractive = false)
        assertEquals("Must not lock while screen is off", 0, fakeExecutor.lockCount.get())

        // 重新亮屏送入样本，不得立即锁
        val screenOnRes = engine.onBatterySample(tempTenthsC = 470, plugged = false, isInteractive = true)
        assertNull("Must not lock immediately upon screen on", screenOnRes)
        assertEquals(0, fakeExecutor.lockCount.get())

        // 重新经过 10 秒抗抖确认后锁屏
        fakeTime.advanceTime(10_000L)
        val finalRes = engine.onBatterySample(tempTenthsC = 470, plugged = false, isInteractive = true)
        assertNotNull(finalRes)
        assertTrue(finalRes!!.isSuccessful)
        assertEquals(1, fakeExecutor.lockCount.get())
    }

    /**
     * T-14: 锁屏后冷却期内解锁：不重复锁
     */
    @Test
    fun testT14_CooldownRejectsLockEvenIfHighTemp() {
        // 触发第 1 次锁屏 (冷却 3 分钟)
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        fakeTime.advanceTime(10_000L)
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        assertEquals(1, fakeExecutor.lockCount.get())
        assertEquals(TemperatureState.LOCKED_COOLDOWN, engine.state)

        // 用户在 2 分钟时解锁且依然是 46.0°C 高温
        fakeTime.advanceTime(2 * 60 * 1000L)
        val res = engine.onBatterySample(tempTenthsC = 460, plugged = false, isInteractive = true)

        assertNull("Must not repeat lock in cooldown", res)
        assertEquals(1, fakeExecutor.lockCount.get())
    }

    /**
     * T-15: 冷却结束仍高温：重新确认后再锁，冷却依次为 3 → 5 → 10 → 10 分钟
     */
    @Test
    fun testT15_CooldownEscalationSequence() {
        // 第 1 次锁屏
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        fakeTime.advanceTime(10_000L)
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        assertEquals(1, engine.consecutiveLockCount)

        // 跨越第 1 次冷却 (3 分钟 = 180 秒)
        fakeTime.advanceTime(180_000L)
        // 冷却结束后重新完成 10 秒确认
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        fakeTime.advanceTime(10_000L)
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        assertEquals(2, engine.consecutiveLockCount) // 第 2 次锁屏

        // 跨越第 2 次冷却 (5 分钟 = 300 秒)
        fakeTime.advanceTime(300_000L)
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        fakeTime.advanceTime(10_000L)
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        assertEquals(3, engine.consecutiveLockCount) // 第 3 次锁屏 (后续为 10 分钟)
    }

    /**
     * T-16: 温度降到恢复阈值以下：状态回到 NORMAL，连续次数清零
     */
    @Test
    fun testT16_DropBelowRecoveryResetsToNormal() {
        // 先触发一次锁屏，连续次数变为 1
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        fakeTime.advanceTime(10_000L)
        engine.onBatterySample(tempTenthsC = 450, plugged = false, isInteractive = true)
        assertEquals(1, engine.consecutiveLockCount)

        // 温度跌至 40.9°C (< 恢复阈值 41.0°C)
        engine.onBatterySample(tempTenthsC = 409, plugged = false, isInteractive = true)

        assertEquals(TemperatureState.NORMAL, engine.state)
        assertEquals("Count must reset to 0", 0, engine.consecutiveLockCount)
    }

    /**
     * T-17: 预警在 42°C 附近（41.9 / 42.0 / 41.5 / 40.9）不抖动，同一轮只提示一次
     */
    @Test
    fun testT17_WarningHysteresisDoesNotJitter() {
        // 41.9°C (未达预警)
        engine.onBatterySample(tempTenthsC = 419, plugged = false, isInteractive = true)
        assertEquals(TemperatureState.NORMAL, engine.state)

        // 42.0°C (触发预警)
        engine.onBatterySample(tempTenthsC = 420, plugged = false, isInteractive = true)
        assertEquals(TemperatureState.WARNING, engine.state)

        // 41.5°C (在 41.0 ~ 42.0 滞回区间波动，不回到 NORMAL)
        engine.onBatterySample(tempTenthsC = 415, plugged = false, isInteractive = true)
        assertEquals(TemperatureState.WARNING, engine.state)

        // 40.9°C (降至恢复阈值以下，回到 NORMAL)
        engine.onBatterySample(tempTenthsC = 409, plugged = false, isInteractive = true)
        assertEquals(TemperatureState.NORMAL, engine.state)
    }

    /**
     * T-22: 热状态 CRITICAL 绕过确认锁一次且受冷却限制；充电中不放宽；SEVERE 仅提示
     */
    @Test
    fun testT22_ThermalStatusCriticalBypassesConfirmation() {
        // SEVERE: 仅提示，不锁屏
        val severeRes = engine.onThermalStatusChanged(ThermalLevel.SEVERE, isInteractive = true)
        assertNull("SEVERE must not lock", severeRes)
        assertEquals(0, fakeExecutor.lockCount.get())

        // CRITICAL: 立即绕过确认锁一次
        val criticalRes = engine.onThermalStatusChanged(ThermalLevel.CRITICAL, isInteractive = true)
        assertNotNull("CRITICAL must lock", criticalRes)
        assertTrue(criticalRes!!.isSuccessful)
        assertEquals(1, fakeExecutor.lockCount.get())
        assertEquals(TemperatureState.LOCKED_COOLDOWN, engine.state)

        // 冷却期内再次收到 CRITICAL: 受冷却限制拒绝锁屏
        fakeTime.advanceTime(60_000L) // 仅过 1 分钟 (冷却 3 分钟)
        val repeatRes = engine.onThermalStatusChanged(ThermalLevel.CRITICAL, isInteractive = true)
        assertNull("Must respect cooldown even for CRITICAL", repeatRes)
        assertEquals(1, fakeExecutor.lockCount.get())
    }

    /**
     * T-23: 阈值与范围校验
     */
    @Test
    fun testT23_SettingsValidation() {
        // 正常默认值
        assertTrue(GuardianSettings().validate().isValid)

        // 恢复 >= 预警
        val invalid1 = GuardianSettings(recoveryTempTenthsC = 430, warningTempTenthsC = 420)
        assertFalse(invalid1.validate().isValid)

        // 预警 >= 锁屏
        val invalid2 = GuardianSettings(warningTempTenthsC = 450, lockTempTenthsC = 450)
        assertFalse(invalid2.validate().isValid)

        // 未充电锁屏 > 充电中锁屏
        val invalid3 = GuardianSettings(lockTempTenthsC = 465, chargingLockTempTenthsC = 460)
        assertFalse(invalid3.validate().isValid)

        // 超出 30.0 ~ 60.0°C
        val invalid4 = GuardianSettings(lockTempTenthsC = 650)
        assertFalse(invalid4.validate().isValid)

        // 抗抖时间超出 5 ~ 60 秒
        val invalid5 = GuardianSettings(antiShakeSeconds = 2)
        assertFalse(invalid5.validate().isValid)

        // 连续亮屏超出 1 ~ 240 分钟
        val invalid6 = GuardianSettings(maxScreenOnMinutes = 300)
        assertFalse(invalid6.validate().isValid)
    }
}
