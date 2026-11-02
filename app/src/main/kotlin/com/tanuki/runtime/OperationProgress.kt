package com.tanuki.runtime

/** Monotonic timestamps. A ticking UI clock is not evidence that the worker made progress. */
data class OperationTiming(val startedAtMillis: Long = 0, val lastActivityAtMillis: Long = 0)

val Snapshot.operationActive: Boolean get() = operation != null ||
    environment in setOf(EnvironmentState.CHECKING, EnvironmentState.DOWNLOADING, EnvironmentState.INSTALLING, EnvironmentState.VERIFYING) ||
    service in setOf(ServiceState.STARTING, ServiceState.STOPPING) || health == HealthState.CHECKING ||
    update in setOf(UpdateState.CHECKING, UpdateState.STAGING, UpdateState.TESTING, UpdateState.BACKUP, UpdateState.SWITCHING, UpdateState.RESTORING)

internal data class OperationSignal(
    val active: Boolean, val operation: String?, val stage: String,
    val environment: EnvironmentState, val service: ServiceState, val health: HealthState, val update: UpdateState,
    val received: Long, val total: Long, val percent: Int?, val source: String?
)
internal fun Snapshot.operationSignal() = OperationSignal(operationActive, operation, stage, environment, service, health, update,
    download?.received ?: bytes, download?.total ?: total, phasePercent, download?.source)

/** Only new bytes, stages, worker output or an actual operation start reset the quiet timer. */
internal class OperationClock {
    private var previous: OperationSignal? = null
    private var outputAt = 0L
    private var timing = OperationTiming()
    fun observe(signal: OperationSignal, lastOutputAt: Long, now: Long): OperationTiming {
        timing = when {
            !signal.active -> OperationTiming()
            previous?.active != true -> OperationTiming(now, now)
            signal != previous || lastOutputAt != outputAt -> timing.copy(lastActivityAtMillis = now)
            else -> timing
        }
        previous = signal
        outputAt = lastOutputAt
        return timing
    }
}

internal fun operationLabel(command: String): String = when (command) {
    "component-ubuntu" -> "准备安装 Ubuntu"
    "component-node" -> "准备安装 Node.js"
    "component-tools" -> "准备检查基础工具"
    "component-uninstall-ubuntu" -> "准备卸载 Ubuntu"
    "component-uninstall-node" -> "准备卸载 Node.js"
    "component-uninstall-dsh" -> "准备卸载 DSH"
    "initialize" -> "准备运行环境"
    "health" -> "检查运行环境"
    "background-probe" -> "执行后台运行检查"
    "ubuntu-cache" -> "整理 Ubuntu 缓存"
    else -> "准备执行操作"
}

/** Progress is for this phase, never a fabricated overall installation percentage. */
fun Snapshot.measuredFraction(): Float? = when {
    phasePercent != null -> phasePercent.coerceIn(0, 100) / 100f
    download != null && download.total > 0 -> (download.received.toDouble() / download.total).toFloat().coerceIn(0f, 1f)
    total > 0 -> (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
    else -> null
}
