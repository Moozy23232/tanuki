package com.tanuki.runtime

import com.tanuki.download.DownloadLimitException

/** GET response lengths are observations, not a prerequisite for installing a locked archive. */
internal class NpmDownloadProgress(count: Int, private val limit: Long) {
    init { require(count in 1..4000 && limit > 0) }
    private val received = LongArray(count)
    private val totals = LongArray(count) { -1L }
    private val verified = BooleanArray(count)

    data class Snapshot(val received: Long, val total: Long, val verified: Int, val archives: Int)

    @Synchronized fun update(index: Int, bytes: Long, total: Long): Snapshot {
        require(index in received.indices && bytes >= 0 && total >= -1 && (total < 0 || bytes <= total))
        // Check a mixed set of headers and bytes. Unknown/chunked responses must obey the
        // same aggregate cap as known-length responses, without overflowing a Long sum.
        var budget = limit
        for (i in received.indices) {
            val bound = if (i == index) maxOf(bytes, total) else maxOf(received[i], totals[i])
            if (bound > budget) throw DownloadLimitException()
            budget -= bound
        }
        received[index] = bytes
        totals[index] = total
        return snapshot()
    }

    @Synchronized fun complete(index: Int, bytes: Long): Snapshot {
        require(bytes > 0) { "依赖归档为空" }
        update(index, bytes, bytes)
        verified[index] = true
        return snapshot()
    }

    @Synchronized fun snapshot(): Snapshot = Snapshot(received.sum(),
        if (totals.all { it > 0 }) totals.sum() else -1L, verified.count { it }, received.size)
}
