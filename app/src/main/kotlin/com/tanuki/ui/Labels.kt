package com.tanuki.ui

import com.tanuki.runtime.*

val EnvironmentState.label: String get() = when(this) {
    EnvironmentState.ABSENT -> "未完成安装"; EnvironmentState.CHECKING -> "检查中"; EnvironmentState.DOWNLOADING -> "下载中"; EnvironmentState.INSTALLING -> "安装中"; EnvironmentState.VERIFYING -> "验证中"; EnvironmentState.READY -> "已就绪"; EnvironmentState.CANCELLED -> "已取消"; EnvironmentState.FAILED -> "安装失败"; EnvironmentState.REPAIR -> "需要继续安装或修复"
}
val Confinement.label: String get() = when(this) { Confinement.UNKNOWN -> "尚未检查"; Confinement.FULL -> "上游探测通过"; Confinement.PARTIAL -> "上游报告部分支持"; Confinement.UNAVAILABLE -> "不可用" }
val HealthState.label: String get() = when(this) { HealthState.UNKNOWN -> "待检查"; HealthState.CHECKING -> "检查中"; HealthState.HEALTHY -> "正常"; HealthState.FAILED -> "异常" }
val ServiceState.label: String get() = when(this) { ServiceState.STOPPED -> "已停止"; ServiceState.STARTING -> "启动中"; ServiceState.RUNNING -> "运行中"; ServiceState.STOPPING -> "停止中"; ServiceState.FAILED -> "启动失败"; ServiceState.INTERRUPTED -> "已中断，任务结果待确认" }
val WebState.label: String get() = when(this) { WebState.DISCONNECTED -> "未连接"; WebState.CONNECTING -> "连接中"; WebState.AUTHENTICATING -> "认证中"; WebState.CONNECTED -> "认证已连接"; WebState.AUTH_EXPIRED -> "认证失效"; WebState.INCOMPATIBLE -> "WebView 需要更新"; WebState.RENDER_ERROR -> "渲染进程异常" }
val UpdateState.label: String get() = when(this) {
    UpdateState.UNCHECKED -> "没有检查"; UpdateState.CHECKING -> "检查中"; UpdateState.CHECK_FAILED -> "检查失败"; UpdateState.AVAILABLE -> "有候选版本"; UpdateState.CURRENT -> "没有更新"; UpdateState.STAGING -> "暂存中"; UpdateState.TESTING -> "隔离检查中"; UpdateState.WAITING -> "等待停止"; UpdateState.BACKUP -> "备份中"; UpdateState.SWITCHING -> "切换中"; UpdateState.SUCCESS -> "成功"; UpdateState.RESTORING -> "恢复中"; UpdateState.RESTORED_PENDING_CHECK -> "数据已恢复，待启动检查"; UpdateState.FAILED -> "更新失败"; UpdateState.RECOVERY_REQUIRED -> "需要恢复"
}
