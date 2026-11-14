package com.example.thermalguard.monitor

enum class FontGuardOutcome(val displayName: String) {
    ABORT_REQUESTED("已请求中止，实际防回退效果待观察"),
    UNORDERED("广播无序，无法中止"),
    ABORT_NOT_ALLOWED("广播不允许中止"),
    FAILED("请求中止失败")
}

data class FontGuardResult(
    val outcome: FontGuardOutcome,
    val errorMessage: String? = null
)

/** Keeps the decision testable without treating a successful API call as verified protection. */
object FontGuardBroadcastHandler {
    const val CHECK_TIME_UP = "miui.intent.action.CHECK_TIME_UP"
    const val CHECK_THEME_UPDATE = "miui.intent.action.CHECK_THEME_UPDATE"
    val actions: Set<String> = setOf(CHECK_TIME_UP, CHECK_THEME_UPDATE)

    fun handle(
        action: String?,
        isOrdered: Boolean,
        allowsAbort: Boolean,
        abort: () -> Unit
    ): FontGuardResult? {
        if (action !in actions) return null
        if (!isOrdered) return FontGuardResult(FontGuardOutcome.UNORDERED)
        if (!allowsAbort) return FontGuardResult(FontGuardOutcome.ABORT_NOT_ALLOWED)
        return try {
            abort()
            FontGuardResult(FontGuardOutcome.ABORT_REQUESTED)
        } catch (e: RuntimeException) {
            FontGuardResult(FontGuardOutcome.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }
}
