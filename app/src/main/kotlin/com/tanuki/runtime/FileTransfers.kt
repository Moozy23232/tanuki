package com.tanuki.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.InputStream
import java.io.OutputStream

/** Independent of installation state: a web download must not overwrite a runtime operation. */
data class FileTransferProgress(val id: Long, val label: String, val received: Long, val total: Long,
    val timing: OperationTiming)

class FileTransfers(private val clock: () -> Long) {
    val state = MutableStateFlow<List<FileTransferProgress>>(emptyList())
    private var sequence = 0L
    @Synchronized fun begin(label: String, total: Long = -1): Long {
        val id = ++sequence
        val now = clock()
        state.value = state.value + FileTransferProgress(id, label, 0, total, OperationTiming(now, now))
        return id
    }
    @Synchronized fun update(id: Long, received: Long, total: Long = -1) {
        state.value = state.value.map { item ->
            if (item.id != id) item else item.copy(received = received.coerceAtLeast(0), total = total,
                timing = if (received != item.received || total != item.total) item.timing.copy(lastActivityAtMillis = clock()) else item.timing)
        }
    }
    @Synchronized fun finish(id: Long) { state.value = state.value.filterNot { it.id == id } }
    suspend fun <T> track(label: String, total: Long = -1, block: suspend (Long) -> T): T {
        val id = begin(label, total)
        try { return block(id) } finally { finish(id) }
    }
    /** Caller owns stream close/flush; progress stays active until track() exits. */
    suspend fun copy(input: InputStream, output: OutputStream, id: Long, total: Long = -1) {
        val buffer = ByteArray(64 * 1024)
        var received = 0L
        var published = clock()
        update(id, 0, total)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            received += count
            val now = clock()
            if (now - published >= 200) { update(id, received, total); published = now }
        }
        currentCoroutineContext().ensureActive()
        check(total < 0 || received == total) { "文件长度与声明不一致，请重新下载" }
        update(id, received, total)
    }
}
