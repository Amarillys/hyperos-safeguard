package com.example.thermalguard.data

/**
 * 守护配置数据类（温度均以 0.1°C 整数表示，如 450 = 45.0°C）
 */
data class GuardianSettings(
    val warningTempTenthsC: Int = 420,          // 预警温度：默认 42.0°C
    val lockTempTenthsC: Int = 450,             // 未充电锁屏温度：默认 45.0°C
    val chargingLockTempTenthsC: Int = 460,     // 充电中锁屏温度：默认 46.0°C
    val recoveryTempTenthsC: Int = 410,         // 恢复阈值：默认 41.0°C
    val antiShakeSeconds: Int = 10,             // 抗抖时间：默认 10 秒 (5~60秒)
    val maxScreenOnMinutes: Int = 30,           // 连续亮屏上限：默认 30 分钟 (1~240分钟)
    val isThermalLinkageEnabled: Boolean = true,// 热状态联动开关：默认开启
    val isTempGuardEnabled: Boolean = true,     // 温度守护子开关：默认开启
    val isScreenGuardEnabled: Boolean = true    // 亮屏守护子开关：默认开启
) {
    /**
     * 校验规则 (§2 与 T-23):
     * 1. 恢复 < 预警 < 锁屏(未充电) <= 锁屏(充电中)
     * 2. 温度限定在 30.0°C ~ 60.0°C (300 ~ 600)
     * 3. 抗抖秒数限定在 5 ~ 60 秒
     * 4. 连续亮屏限定在 1 ~ 240 分钟
     */
    fun validate(): ValidationResult {
        if (warningTempTenthsC !in 300..600) {
            return ValidationResult.Invalid("预警温度必须在 30.0°C ~ 60.0°C 之间")
        }
        if (lockTempTenthsC !in 300..600) {
            return ValidationResult.Invalid("未充电锁屏温度必须在 30.0°C ~ 60.0°C 之间")
        }
        if (chargingLockTempTenthsC !in 300..600) {
            return ValidationResult.Invalid("充电中锁屏温度必须在 30.0°C ~ 60.0°C 之间")
        }
        if (recoveryTempTenthsC !in 300..600) {
            return ValidationResult.Invalid("恢复阈值温度必须在 30.0°C ~ 60.0°C 之间")
        }
        if (recoveryTempTenthsC >= warningTempTenthsC) {
            return ValidationResult.Invalid("恢复阈值 (${recoveryTempTenthsC / 10.0}°C) 必须低于预警温度 (${warningTempTenthsC / 10.0}°C)")
        }
        if (warningTempTenthsC >= lockTempTenthsC) {
            return ValidationResult.Invalid("预警温度 (${warningTempTenthsC / 10.0}°C) 必须低于未充电锁屏温度 (${lockTempTenthsC / 10.0}°C)")
        }
        if (lockTempTenthsC > chargingLockTempTenthsC) {
            return ValidationResult.Invalid("未充电锁屏温度 (${lockTempTenthsC / 10.0}°C) 不得高于充电中锁屏温度 (${chargingLockTempTenthsC / 10.0}°C)")
        }
        if (antiShakeSeconds !in 5..60) {
            return ValidationResult.Invalid("抗抖时间必须在 5 ~ 60 秒之间")
        }
        if (maxScreenOnMinutes !in 1..240) {
            return ValidationResult.Invalid("连续亮屏上限必须在 1 ~ 240 分钟之间")
        }
        return ValidationResult.Valid
    }
}

sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()

    val isValid: Boolean get() = this is Valid
}
