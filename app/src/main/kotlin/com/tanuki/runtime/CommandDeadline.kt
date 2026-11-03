package com.tanuki.runtime

import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** 命令自身超时属于失败；用户取消和外层任务取消仍原样传播。 */
internal class CommandTimeoutException(val timeoutMs: Long) : IOException("命令执行超时（${timeoutMs / 1000} 秒）")

internal suspend fun <T : Any> withCommandDeadline(timeoutMs: Long, block: suspend () -> T): T {
    require(timeoutMs > 0) { "命令超时必须大于零" }
    return withTimeoutOrNull(timeoutMs) { block() } ?: throw CommandTimeoutException(timeoutMs)
}
