package com.example.timelock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.timelock.data.local.AppDatabase
import com.example.timelock.data.local.entity.UsageSessionEntity
import com.example.timelock.domain.manager.SessionEvaluationState
import com.example.timelock.domain.manager.SessionManager
import com.example.timelock.service.overlay.WindowOverlayManager
import com.example.timelock.ui.main.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppMonitorService : LifecycleService() {

    private lateinit var usageStatsMonitor: UsageStatsMonitor
    private lateinit var windowOverlayManager: WindowOverlayManager
    private lateinit var sessionManager: SessionManager

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val restrictedAppDao by lazy { db.restrictedAppDao() }
    private val usageSessionDao by lazy { db.usageSessionDao() }

    private var heartbeatJob: Job? = null
    private var currentMonitoredPackage: String? = null
    private var currentActiveSession: UsageSessionEntity? = null
    private var currentAccumulatedMs: Long = 0L

    // Single Source of Truth 即時剩餘時間流
    private val _remainingMsFlow = MutableStateFlow(0L)
    val remainingMsFlow = _remainingMsFlow.asStateFlow()

    // 60秒快速回覆目標 Package 鎖定
    private var isQuickReplyActive: Boolean = false
    private var quickReplyTargetPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        usageStatsMonitor = UsageStatsMonitor(this)
        windowOverlayManager = WindowOverlayManager(this)
        sessionManager = SessionManager(restrictedAppDao, usageSessionDao)

        startForegroundServiceNotification()
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "timelock_monitor_channel"
        val channelName = "TimeLock 監控服務"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "正在背景偵測限制名單內的 App 啟動狀態"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("TimeLock 有意識手機守護中")
            .setContentText("正在為您的專注與時間把關")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startMonitoring() {
        lifecycleScope.launch {
            usageStatsMonitor.observeForegroundPackage(intervalMs = 500L).collectLatest { foregroundPackage ->
                handleForegroundPackageChange(foregroundPackage)
            }
        }
    }

    private suspend fun handleForegroundPackageChange(foregroundPackage: String) {
        val isRestricted = restrictedAppDao.isAppRestricted(foregroundPackage)

        if (isRestricted) {
            val appEntity = restrictedAppDao.getRestrictedApp(foregroundPackage)
            val appName = appEntity?.appName ?: foregroundPackage

            if (currentMonitoredPackage != null && currentMonitoredPackage != foregroundPackage) {
                windowOverlayManager.dismissFloatingTimer()
                heartbeatJob?.cancel()
                heartbeatJob = null
                flushCurrentSessionToDb()
                // 跨受限 App (如 WePlay -> Threads) 共享會話預算，不主動 finishSession，由 evaluateSessionState 統一無縫接續
            }
            currentMonitoredPackage = foregroundPackage

            when (val state = sessionManager.evaluateSessionState(foregroundPackage)) {
                is SessionEvaluationState.ResumeSession -> {
                    startHeartbeat(state.session, appName)
                }
                is SessionEvaluationState.InGlobalCooldown -> {
                    showCooldownOverlay(
                        packageName = foregroundPackage,
                        appName = appName,
                        remainingCooldownMs = state.remainingCooldownMs,
                        allowQuickReply = state.allowQuickReply
                    )
                }
                is SessionEvaluationState.RequireSetup -> {
                    showSetupOverlay(foregroundPackage, appName)
                }
            }
        } else {
            if (currentMonitoredPackage != null) {
                flushCurrentSessionToDb()
            }
            currentMonitoredPackage = null
            windowOverlayManager.dismissFloatingTimer()
            heartbeatJob?.cancel()
            heartbeatJob = null
        }
    }

    private fun showSetupOverlay(packageName: String, appName: String) {
        windowOverlayManager.showTimeSetupOverlay(
            packageName = packageName,
            appName = appName,
            onTimeSelected = { minutes ->
                lifecycleScope.launch {
                    val createdSession = sessionManager.createNewSession(packageName, minutes)
                    isQuickReplyActive = false
                    quickReplyTargetPackage = null
                    startHeartbeat(createdSession, appName)
                }
            },
            onCancel = {
                sendUserToHomeScreen()
            }
        )
    }

    private fun showCooldownOverlay(
        packageName: String,
        appName: String,
        remainingCooldownMs: Long,
        allowQuickReply: Boolean
    ) {
        windowOverlayManager.dismissFloatingTimer()
        windowOverlayManager.showCooldownOverlay(
            appName = appName,
            remainingCooldownMs = remainingCooldownMs,
            allowQuickReply = allowQuickReply,
            onQuickReplyClicked = {
                lifecycleScope.launch {
                    val quickSession = sessionManager.createQuickReplySession(packageName)
                    isQuickReplyActive = true
                    quickReplyTargetPackage = packageName
                    startHeartbeat(quickSession, appName)
                }
            },
            onExitClicked = {
                sendUserToHomeScreen()
            }
        )
    }

    private fun showBlockingOverlay(
        appName: String,
        session: UsageSessionEntity
    ) {
        windowOverlayManager.dismissFloatingTimer()
        windowOverlayManager.showBlockingOverlay(
            appName = appName,
            onExitClicked = {
                lifecycleScope.launch {
                    sessionManager.finishSession(session, isTimeout = true)
                    isQuickReplyActive = false
                    quickReplyTargetPackage = null
                    sendUserToHomeScreen()
                }
            }
        )
    }

    private fun startHeartbeat(session: UsageSessionEntity, appName: String) {
        heartbeatJob?.cancel()
        currentActiveSession = session
        currentAccumulatedMs = session.accumulatedUsageMs
        isQuickReplyActive = session.isQuickReply
        quickReplyTargetPackage = if (session.isQuickReply) session.packageName else null

        var notifiedWarning = false
        val initialRemaining = (session.expectedDurationMs - currentAccumulatedMs).coerceAtLeast(0L)
        _remainingMsFlow.value = initialRemaining

        windowOverlayManager.showFloatingTimer(
            remainingMsFlow = remainingMsFlow,
            totalDurationMs = session.expectedDurationMs
        )

        heartbeatJob = lifecycleScope.launch {
            var dbSyncTickCounter = 0
            while (isActive && currentMonitoredPackage != null) {
                delay(1000L)
                val now = System.currentTimeMillis()
                currentAccumulatedMs += 1000L
                dbSyncTickCounter++

                val remainingMs = (session.expectedDurationMs - currentAccumulatedMs).coerceAtLeast(0L)
                _remainingMsFlow.value = remainingMs

                // 每 5 秒節流寫入 Room DB
                if (dbSyncTickCounter >= 5) {
                    sessionManager.updateHeartbeat(session.sessionId, currentAccumulatedMs, now)
                    dbSyncTickCounter = 0
                }

                // 剩餘 1 分鐘預警 (非快速回覆模式)
                if (remainingMs in 1L..60_000L && !notifiedWarning && !session.isQuickReply) {
                    showOneMinuteWarningNotification(appName)
                    notifiedWarning = true
                }

                // 0ms 瞬間阻斷
                if (remainingMs <= 0L) {
                    val updatedSession = session.copy(accumulatedUsageMs = currentAccumulatedMs, lastHeartbeatTime = now)
                    sessionManager.updateHeartbeat(session.sessionId, currentAccumulatedMs, now)
                    showBlockingOverlay(appName, updatedSession)
                    break
                }
            }
        }
    }

    private suspend fun flushCurrentSessionToDb() {
        val session = currentActiveSession ?: return
        val now = System.currentTimeMillis()
        sessionManager.updateHeartbeat(session.sessionId, currentAccumulatedMs, now)
    }

    private fun showOneMinuteWarningNotification(appName: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "timelock_warning_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "超時倒數警告 (最後一分鐘)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "在限制時間剩下最後 1 分鐘時顯示橫幅提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("⏰ 剩下最後 1 分鐘！")
            .setContentText("本次娛樂使用時間即將在 1 分鐘後結束，請準備收尾囉！")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(WARNING_NOTIFICATION_ID, notification)
    }

    private fun sendUserToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        lifecycleScope.launch {
            flushCurrentSessionToDb()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            flushCurrentSessionToDb()
        }
        windowOverlayManager.release()
        heartbeatJob?.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val WARNING_NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, AppMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AppMonitorService::class.java)
            context.stopService(intent)
        }
    }
}
