package com.example.thermalguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.thermalguard.MainActivity
import com.example.thermalguard.admin.GuardianDeviceAdminReceiver
import com.example.thermalguard.data.GuardianLogRepository
import com.example.thermalguard.data.GuardianSettingsRepository
import com.example.thermalguard.data.FontGuardRepository
import com.example.thermalguard.data.IdleDimmingRepository
import com.example.thermalguard.data.PauseStateStore
import com.example.thermalguard.data.SharedPrefsPauseStateStore
import com.example.thermalguard.monitor.BatterySnapshot
import com.example.thermalguard.monitor.BatteryTemperatureMonitor
import com.example.thermalguard.monitor.ScreenInteractiveMonitor
import com.example.thermalguard.monitor.FontGuardMonitor
import com.example.thermalguard.monitor.IdleDimmingMonitor
import com.example.thermalguard.monitor.ThermalLevel
import com.example.thermalguard.monitor.ThermalStatusMonitor
import com.example.thermalguard.rule.AndroidLockExecutor
import com.example.thermalguard.rule.LockCoordinator
import com.example.thermalguard.rule.ScreenDurationRuleEngine
import com.example.thermalguard.rule.TemperatureRuleEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 温控与亮屏守护前台服务 (Foreground Service)
 * 采用 systemExempted 类型（API 34+ 规范），整合温度、充电、热状态、亮屏监控及暂停持久化
 */
class GuardianService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var scheduledTimeoutJob: Job? = null
    private var notificationTickerJob: Job? = null
    private var pauseExpirationJob: Job? = null
    private var fontGuardJob: Job? = null
    private lateinit var fontGuardMonitor: FontGuardMonitor
    private var idleDimmingJob: Job? = null
    private lateinit var idleDimmingMonitor: IdleDimmingMonitor

    private lateinit var lockCoordinator: LockCoordinator
    private lateinit var screenRuleEngine: ScreenDurationRuleEngine
    private lateinit var tempRuleEngine: TemperatureRuleEngine

    private lateinit var screenMonitor: ScreenInteractiveMonitor
    private lateinit var batteryMonitor: BatteryTemperatureMonitor
    private lateinit var thermalMonitor: ThermalStatusMonitor

    private lateinit var settingsRepo: GuardianSettingsRepository
    private lateinit var pauseStore: PauseStateStore

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "GuardianService onCreate")

        settingsRepo = GuardianSettingsRepository(this)
        FontGuardRepository.initialize(this)
        fontGuardMonitor = FontGuardMonitor(this)
        IdleDimmingRepository.initialize(this)
        idleDimmingMonitor = IdleDimmingMonitor(this, serviceScope)
        pauseStore = SharedPrefsPauseStateStore(this)
        val currentSettings = settingsRepo.loadSettings()

        val lockExecutor = AndroidLockExecutor(this)
        lockCoordinator = LockCoordinator(lockExecutor)
        screenRuleEngine = ScreenDurationRuleEngine(
            lockCoordinator = lockCoordinator,
            maxScreenOnMinutes = currentSettings.maxScreenOnMinutes
        )
        tempRuleEngine = TemperatureRuleEngine(
            lockCoordinator = lockCoordinator,
            settings = currentSettings
        )

        screenMonitor = ScreenInteractiveMonitor(
            context = this,
            onScreenOn = ::handleScreenOn,
            onScreenOff = ::handleScreenOff
        )

        batteryMonitor = BatteryTemperatureMonitor(
            context = this,
            onBatteryChanged = ::handleBatteryChanged
        )

        thermalMonitor = ThermalStatusMonitor(
            context = this,
            onThermalStatusChanged = ::handleThermalChanged
        )

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "GuardianService onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_PREVIEW_DIMMING -> {
                if (idleDimmingJob != null && _serviceStateFlow.value.status == ServiceStatus.RUNNING) {
                    idleDimmingMonitor.refresh(preview = true)
                    return START_STICKY
                }
                if (idleDimmingJob == null) stopSelf()
                return if (idleDimmingJob == null) START_NOT_STICKY else START_STICKY
            }
            ACTION_STOP -> {
                handleStopUser()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                handlePauseUser(15)
                return START_STICKY
            }
            ACTION_RESUME -> {
                handleResumeUser()
                return START_STICKY
            }
        }

        // 验证设备管理员资格 (§0)
        val adminActive = (getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager)
            ?.isAdminActive(GuardianDeviceAdminReceiver.getComponentName(this)) == true

        if (!adminActive) {
            Log.e(TAG, "Start aborted: Device admin is not active.")
            _serviceStateFlow.value = _serviceStateFlow.value.copy(
                status = ServiceStatus.NEEDS_ADMIN,
                errorMessage = "未激活设备管理员，无法启动守护服务"
            )
            stopSelf()
            return START_NOT_STICKY
        }

        val success = startForegroundWithExemption()
        if (!success) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 标记用户希望开启守护
        settingsRepo.setGuardianDesired(true)

        if (fontGuardJob == null) {
            fontGuardJob = serviceScope.launch {
                FontGuardRepository.state.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                    fontGuardMonitor.setEnabled(enabled)
                }
            }
        }

        // 启动动态广播与监听
        screenMonitor.start()
        batteryMonitor.start()
        thermalMonitor.start()

        // 补充读取初次粘性广播
        batteryMonitor.readCurrent()?.let {
            tempRuleEngine.onBatterySample(it.tempTenthsC, it.isPlugged, isInteractive = screenMonitor.isInteractive())
        }

        val isInteractive = screenMonitor.isInteractive()
        if (flags and START_FLAG_REDELIVERY != 0 || flags and START_FLAG_RETRY != 0) {
            screenRuleEngine.onServiceRestart(isInteractive)
        } else {
            if (isInteractive) {
                screenRuleEngine.onScreenInteractive()
            }
        }

        // 检查恢复暂停状态 (§4.2, T-24)
        checkAndRestorePauseState()

        if (idleDimmingJob == null) {
            IdleDimmingRepository.refreshPermissions(this)
            idleDimmingMonitor.setPaused(screenRuleEngine.isPaused)
            idleDimmingJob = serviceScope.launch {
                IdleDimmingRepository.state.map { Triple(it.enabled, it.permissionRevision, it.hasDumpPermission to it.canWriteSettings) }
                    .distinctUntilChanged().collect { idleDimmingMonitor.refresh() }
            }
        }

        val initialStatus = if (screenRuleEngine.isPaused) ServiceStatus.PAUSED else ServiceStatus.RUNNING
        updateServiceState(initialStatus)
        scheduleNextTimeoutCheck()
        startNotificationTicker()

        GuardianLogRepository.addLog("服务", "温控守护服务已启动成功")
        return START_STICKY
    }

    private fun startForegroundWithExemption(): Boolean {
        return try {
            val notification = buildNotification("温控亮屏守护运行中")
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            Log.i(TAG, "startForeground success with type systemExempted")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed with exception", e)
            _serviceStateFlow.value = _serviceStateFlow.value.copy(
                status = ServiceStatus.ERROR,
                errorMessage = "前台服务启动异常: ${e.javaClass.simpleName} - ${e.message}"
            )
            GuardianLogRepository.addLog("错误", "前台服务启动失败: ${e.message}", isWarningOrError = true)
            false
        }
    }

    private fun checkAndRestorePauseState() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val currentBootCount = SharedPrefsPauseStateStore.readSystemBootCount(this)
        if (pauseStore.isPauseActive(nowElapsed, currentBootCount)) {
            val remainingMs = pauseStore.getRemainingPauseMs(nowElapsed, currentBootCount)
            screenRuleEngine.onPause()
            tempRuleEngine.onPause()
            GuardianLogRepository.addLog("暂停恢复", "检测到同次开机内存在未结束的暂停，继续暂停 (剩余 ${remainingMs / 1000} 秒)")
            schedulePauseExpiration(remainingMs)
        } else {
            pauseStore.clearPause()
        }
    }

    private fun handleBatteryChanged(snapshot: BatterySnapshot) {
        val lockResult = tempRuleEngine.onBatterySample(
            tempTenthsC = snapshot.tempTenthsC,
            plugged = snapshot.isPlugged,
            isInteractive = screenMonitor.isInteractive()
        )
        if (lockResult != null && lockResult.isSuccessful) {
            updateServiceState(ServiceStatus.RUNNING, "电池高温达到 ${tempRuleEngine.effectiveLockThresholdTenthsC / 10.0}°C 触发锁屏")
        }
        updateServiceState(if (tempRuleEngine.isPaused) ServiceStatus.PAUSED else ServiceStatus.RUNNING)
        refreshNotification()
    }

    private fun handleThermalChanged(level: ThermalLevel) {
        val lockResult = tempRuleEngine.onThermalStatusChanged(
            level = level,
            isInteractive = screenMonitor.isInteractive()
        )
        if (lockResult != null && lockResult.isSuccessful) {
            updateServiceState(ServiceStatus.RUNNING, "系统极度高温 (${level.displayName}) 紧急锁屏")
        }
        updateServiceState(if (tempRuleEngine.isPaused) ServiceStatus.PAUSED else ServiceStatus.RUNNING)
        refreshNotification()
    }

    private fun handleScreenOn() {
        idleDimmingMonitor.refresh()
        screenRuleEngine.onScreenInteractive()
        batteryMonitor.readCurrent()?.let {
            tempRuleEngine.onBatterySample(it.tempTenthsC, it.isPlugged, isInteractive = true)
        }
        scheduleNextTimeoutCheck()
        updateServiceState(if (screenRuleEngine.isPaused) ServiceStatus.PAUSED else ServiceStatus.RUNNING)
        refreshNotification()
    }

    private fun handleScreenOff() {
        idleDimmingMonitor.refresh()
        scheduledTimeoutJob?.cancel()
        screenRuleEngine.onScreenNonInteractive()
        tempRuleEngine.onScreenOff()
        updateServiceState(if (screenRuleEngine.isPaused) ServiceStatus.PAUSED else ServiceStatus.RUNNING)
        refreshNotification()
    }

    private fun scheduleNextTimeoutCheck() {
        scheduledTimeoutJob?.cancel()
        if (!screenRuleEngine.isInInteractiveSession || screenRuleEngine.isPaused) return

        val remainingMs = screenRuleEngine.remainingTimeoutMs
        scheduledTimeoutJob = serviceScope.launch {
            if (remainingMs > 0) {
                delay(remainingMs)
            }
            val isInteractive = screenMonitor.isInteractive()
            val lockResult = screenRuleEngine.checkTimeout(isInteractive)
            if (lockResult != null && lockResult.isSuccessful) {
                updateServiceState(
                    ServiceStatus.RUNNING,
                    lastReason = "连续亮屏达到 ${screenRuleEngine.maxScreenOnMinutes} 分钟"
                )
            }
            refreshNotification()
        }
    }

    private fun handlePauseUser(minutes: Int) {
        idleDimmingMonitor.setPaused(true)
        scheduledTimeoutJob?.cancel()
        screenRuleEngine.onPause()
        tempRuleEngine.onPause()

        val pauseMs = minutes * 60 * 1000L
        val nowElapsed = SystemClock.elapsedRealtime()
        val currentBootCount = SharedPrefsPauseStateStore.readSystemBootCount(this)
        pauseStore.savePause(nowElapsed + pauseMs, currentBootCount)

        updateServiceState(ServiceStatus.PAUSED)
        refreshNotification()
        schedulePauseExpiration(pauseMs)
    }

    private fun schedulePauseExpiration(delayMs: Long) {
        pauseExpirationJob?.cancel()
        pauseExpirationJob = serviceScope.launch {
            delay(delayMs)
            if (screenRuleEngine.isPaused) {
                handleResumeUser()
            }
        }
    }

    private fun handleResumeUser() {
        idleDimmingMonitor.setPaused(false)
        pauseExpirationJob?.cancel()
        pauseStore.clearPause()
        val isInteractive = screenMonitor.isInteractive()
        screenRuleEngine.onResume(isInteractive)
        tempRuleEngine.onResume()
        updateServiceState(ServiceStatus.RUNNING)
        scheduleNextTimeoutCheck()
        refreshNotification()
    }

    private fun handleStopUser() {
        pauseExpirationJob?.cancel()
        pauseStore.clearPause()
        settingsRepo.setGuardianDesired(false)
        GuardianLogRepository.addLog("服务", "用户主动停止守护服务")
        updateServiceState(ServiceStatus.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startNotificationTicker() {
        notificationTickerJob?.cancel()
        notificationTickerJob = serviceScope.launch {
            while (isActive) {
                delay(30_000L)
                refreshNotification()
                updateServiceState(if (screenRuleEngine.isPaused) ServiceStatus.PAUSED else ServiceStatus.RUNNING)
            }
        }
    }

    private fun updateServiceState(status: ServiceStatus, lastReason: String? = null) {
        _serviceStateFlow.value = GuardianServiceState(
            status = status,
            isInteractive = screenMonitor.isInteractive(),
            interactiveDurationMs = screenRuleEngine.currentInteractiveDurationMs,
            remainingTimeoutMs = screenRuleEngine.remainingTimeoutMs,
            maxScreenOnMinutes = screenRuleEngine.maxScreenOnMinutes,
            currentTempTenthsC = tempRuleEngine.lastTempTenthsC,
            isPlugged = tempRuleEngine.isPlugged,
            effectiveLockThresholdTenthsC = tempRuleEngine.effectiveLockThresholdTenthsC,
            thermalLevel = tempRuleEngine.lastThermalLevel,
            temperatureState = tempRuleEngine.state,
            remainingCooldownMs = tempRuleEngine.remainingCooldownMs,
            consecutiveLockCount = tempRuleEngine.consecutiveLockCount,
            lastTriggerReason = lastReason ?: _serviceStateFlow.value.lastTriggerReason
        )
    }

    private fun refreshNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val elapsedMinutes = screenRuleEngine.currentInteractiveDurationMs / 1000 / 60
        val tempText = tempRuleEngine.lastTempTenthsC?.let { "${it / 10.0}°C" } ?: "未知"
        val pluggedSuffix = if (tempRuleEngine.isPlugged) " · 充电中" else ""

        val content = if (screenRuleEngine.isPaused) {
            "守护已临时暂停 · 点击恢复"
        } else {
            "守护中 · 电池 $tempText$pluggedSuffix · 本次亮屏 $elapsedMinutes 分钟"
        }
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pausePendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GuardianService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, GuardianService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("温控与亮屏守护")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent)
            .apply {
                if (!screenRuleEngine.isPaused) {
                    addAction(android.R.drawable.ic_media_pause, "暂停15分钟", pausePendingIntent)
                } else {
                    val resumePendingIntent = PendingIntent.getService(
                        this@GuardianService,
                        3,
                        Intent(this@GuardianService, GuardianService::class.java).apply { action = ACTION_RESUME },
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    addAction(android.R.drawable.ic_media_play, "立即恢复", resumePendingIntent)
                }
                addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止守护", stopPendingIntent)
            }
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "守护常驻服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示守护服务的当前运行状态与温控/亮屏数据"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "GuardianService onDestroy")
        fontGuardMonitor.stop()
        idleDimmingMonitor.stop()
        screenMonitor.stop()
        batteryMonitor.stop()
        thermalMonitor.stop()
        serviceScope.cancel()
        updateServiceState(ServiceStatus.STOPPED)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "GuardianService"
        private const val CHANNEL_ID = "guardian_service_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.example.thermalguard.action.STOP"
        const val ACTION_PAUSE = "com.example.thermalguard.action.PAUSE"
        const val ACTION_RESUME = "com.example.thermalguard.action.RESUME"
        private const val ACTION_PREVIEW_DIMMING = "com.example.thermalguard.action.PREVIEW_DIMMING"

        private val _serviceStateFlow = MutableStateFlow(GuardianServiceState())
        val serviceStateFlow: StateFlow<GuardianServiceState> = _serviceStateFlow.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, GuardianService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, GuardianService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, GuardianService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun previewIdleDimming(context: Context) {
            if (_serviceStateFlow.value.status != ServiceStatus.RUNNING) return
            context.startService(Intent(context, GuardianService::class.java).apply { action = ACTION_PREVIEW_DIMMING })
        }

        fun resume(context: Context) {
            val intent = Intent(context, GuardianService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }
    }
}
