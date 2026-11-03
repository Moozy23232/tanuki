package com.tanuki

import com.tanuki.runtime.CommandTimeoutException
import com.tanuki.runtime.withCommandDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CommandDeadlineTest {
    @Test fun completedCommandReturnsItsValue() = runBlocking {
        assertEquals("done", withCommandDeadline(1000) { "done" })
    }

    @Test fun ownTimeoutIsAnActionableFailureInsteadOfUserCancellation() = runBlocking {
        try { withCommandDeadline(20) { delay(500); "late" }; fail("deadline ignored") }
        catch (error: CommandTimeoutException) {
            assertEquals(20L, error.timeoutMs)
            assertTrue(error.message.orEmpty().contains("超时"))
        }
    }

    @Test fun explicitCancellationIsNeverConvertedToFailureOrRetried() = runBlocking {
        val cancelled = CancellationException("synthetic user cancel")
        var attempts = 0
        try { withCommandDeadline<String>(1000) { attempts++; throw cancelled }; fail("cancel swallowed") }
        catch (error: CancellationException) { assertEquals(cancelled.message, error.message) }
        assertEquals(1, attempts)
    }

    @Test fun parentTimeoutKeepsItsCancellationSemantics() = runBlocking {
        try { withTimeout(20) { withCommandDeadline(1000) { delay(500); "late" } }; fail("parent deadline ignored") }
        catch (_: TimeoutCancellationException) { }
    }
}
