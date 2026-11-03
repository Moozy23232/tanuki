package com.tanuki.runtime

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.tanuki.MainActivity
import com.tanuki.TanukiApp
import com.tanuki.R
import com.tanuki.ui.label
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

class RuntimeService : Service() {
    private val manager get() = (application as TanukiApp).runtime
    private val appUpdates get() = (application as TanukiApp).appUpdates
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null
    private var wakeRenewal: Job? = null
    private var lastStartId=0
    private var idleExit=false
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("runtime", "本机开发环境", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notification("准备运行环境"))
        scope.launch {
            var notifiedAt = 0L
            combine(manager.state, manager.fileTransfers.state, appUpdates.state) { snapshot, transfers, apk -> Triple(snapshot, transfers, apk) }.collectLatest { (s, transfers, apk) ->
                val now = android.os.SystemClock.elapsedRealtime()
                if ((!s.operationActive && !apk.busy) || now - notifiedAt >= 500) {
                    getSystemService(NotificationManager::class.java).notify(1, notification(when {
                        s.operationActive -> s.stage
                        apk.busy -> apk.message
                        transfers.isNotEmpty() -> transfers.first().label
                        else -> "环境 ${s.environment.label} · 服务 ${s.service.label}"
                    }, s, transfers, apk))
                    notifiedAt = now
                }
                val needed = s.operationActive || apk.busy || transfers.isNotEmpty() || s.service == ServiceState.RUNNING
                if (needed) keepAwake() else releaseWake()
                if(idle(s)) {
                    delay(500)
                    while(idle(manager.state.value) && (manager.hasActiveOperation || (application as TanukiApp).updates.hasActiveOperation || appUpdates.hasActiveOperation))delay(100)
                    if(idle(manager.state.value) && stopSelfResult(lastStartId)){idleExit=true;stopForeground(STOP_FOREGROUND_REMOVE)}
                }
            }
        }
    }
    private fun notification(text: String, snapshot: Snapshot = manager.state.value,
        transfers: List<FileTransferProgress> = manager.fileTransfers.state.value,
        apk: com.tanuki.update.AppUpdateState = appUpdates.state.value): Notification {
        val content = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, RuntimeService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val active = snapshot.operationActive || apk.busy || transfers.isNotEmpty()
        val first = transfers.firstOrNull()
        val fraction = if (snapshot.operationActive) snapshot.measuredFraction()
            else if (apk.busy) {
                if (apk.total > 0 && apk.phase == com.tanuki.update.AppUpdatePhase.DOWNLOADING) (apk.received.toDouble() / apk.total).toFloat().coerceIn(0f, 1f) else null
            }
            else first?.takeIf { it.total > 0 }?.let { (it.received.toDouble() / it.total).toFloat().coerceIn(0f, 1f) }
        val timing = if (snapshot.operationActive) snapshot.timing else if (apk.busy) OperationTiming(startedAtMillis = apk.startedAt) else first?.timing ?: OperationTiming()
        return Notification.Builder(this, "runtime")
            .setSmallIcon(if (active) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.app_name) + if (active) " · 正在处理" else " 本机服务")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
            .apply {
                if (active) {
                    setProgress(100, fraction?.let { (it * 100).toInt() } ?: 0, fraction == null)
                    if (timing.startedAtMillis > 0) {
                        setWhen(System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - timing.startedAtMillis).coerceAtLeast(0))
                        setUsesChronometer(true)
                    }
                }
            }.addAction(Notification.Action.Builder(null, "停止（可能中断任务）", stop).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId=startId
        when (intent?.action) {
            "INIT" -> manager.initialize()
            "BASE" -> manager.initialize(true)
            "START" -> manager.start()
            "HEALTH" -> manager.healthCheck()
            "TOOLS" -> manager.installRequiredTools()
            "BACKGROUND" -> manager.backgroundProbe()
            "SLIM" -> manager.slimUbuntu()
            "UBUNTU_INSTALL" -> manager.components.install(RuntimeComponent.UBUNTU)
            "UBUNTU_REINSTALL" -> manager.components.install(RuntimeComponent.UBUNTU, true)
            "UBUNTU_UNINSTALL" -> manager.components.uninstall(RuntimeComponent.UBUNTU)
            "NODE_INSTALL" -> manager.components.install(RuntimeComponent.NODE)
            "NODE_REINSTALL" -> manager.components.install(RuntimeComponent.NODE, true)
            "NODE_UNINSTALL" -> manager.components.uninstall(RuntimeComponent.NODE)
            "DSH_UNINSTALL" -> manager.components.uninstallDsh(intent.getStringExtra("version"))
            "STOP" -> scope.launch {
                try {
                    manager.cancel(); (application as TanukiApp).updates.cancel(); appUpdates.cancel()
                    manager.stop(); releaseWake(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { manager.fail(error) }
            }
        }
        return START_NOT_STICKY
    }
    private fun idle(s:Snapshot)=!s.operationActive && !appUpdates.state.value.busy && manager.fileTransfers.state.value.isEmpty() && s.service in setOf(ServiceState.STOPPED,ServiceState.FAILED,ServiceState.INTERRUPTED)
    private fun keepAwake() {
        if (wakeRenewal?.isActive == true) return
        val lease = wake ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tanuki:runtime")
            .apply { setReferenceCounted(false) }.also { wake = it }
        wakeRenewal = scope.launch {
            while (isActive) {
                // Renew independently of state changes; a lost owner still releases within two minutes.
                lease.acquire(120_000L)
                delay(90_000L)
            }
        }
    }
    private fun releaseWake() {
        wakeRenewal?.cancel(); wakeRenewal = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
    }
    override fun onDestroy() {
        releaseWake();scope.cancel()
        // An intentionally idle service must not cancel work belonging to its replacement.
        if(!idleExit){manager.cancel();(application as TanukiApp).updates.cancel();appUpdates.cancel();manager.stopAfterServiceLoss()}
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
