package com.example.thermalguard.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 结构化日志条目
 */
data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val eventType: String,
    val message: String,
    val isWarningOrError: Boolean = false
) {
    val formattedTime: String by lazy {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
    }
}

/**
 * 守护日志存储管理（内存环形队列 + 响应式流，最大容量 300 条）
 */
object GuardianLogRepository {

    private const val MAX_LOGS = 300
    private val idCounter = AtomicLong(1L)
    private val lock = Any()

    private val logList = ArrayDeque<LogEntry>(MAX_LOGS)
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    /**
     * 追加单条日志
     */
    fun addLog(eventType: String, message: String, isWarningOrError: Boolean = false) {
        val entry = LogEntry(
            id = idCounter.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            eventType = eventType,
            message = message,
            isWarningOrError = isWarningOrError
        )
        synchronized(lock) {
            if (logList.size >= MAX_LOGS) {
                logList.removeFirst()
            }
            logList.addLast(entry)
            _logsFlow.value = logList.toList().reversed()
        }
    }

    /**
     * 清空所有日志
     */
    fun clearLogs() {
        synchronized(lock) {
            logList.clear()
            _logsFlow.value = emptyList()
        }
    }
}
