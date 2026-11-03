package com.tanuki

import com.tanuki.browser.BrowserIdleReturnPolicy
import org.junit.Assert.*
import org.junit.Test

class BrowserIdleReturnTest {
    @Test fun temporaryTakeoverReturnsExactlyAfterThirtySeconds() {
        val policy = BrowserIdleReturnPolicy()
        policy.pause(1_000)
        assertEquals(1L, policy.remaining(30_999))
        assertFalse(policy.claim(30_999, true))
        assertTrue(policy.claim(31_000, true))
        assertNull(policy.remaining(31_001))
        assertFalse(policy.claim(60_000, true))
    }
    @Test fun everyHumanInteractionRestartsTheWholeInterval() {
        val policy = BrowserIdleReturnPolicy()
        policy.pause(0); policy.interact(29_000)
        assertFalse(policy.claim(30_000, true))
        assertEquals(29_000L, policy.remaining(30_000))
        assertTrue(policy.claim(59_000, true))
    }
    @Test fun explicitRevocationCannotBeOverriddenByTheTimer() {
        val policy = BrowserIdleReturnPolicy()
        policy.pause(0)
        assertFalse(policy.claim(30_000, false))
        policy.cancel()
        assertFalse(policy.claim(60_000, true))
    }
    @Test fun ordinaryInteractionDoesNotCreateAnAuthorization() {
        val policy = BrowserIdleReturnPolicy()
        policy.interact(0)
        assertNull(policy.remaining(30_000))
        assertFalse(policy.claim(30_000, true))
    }
    @Test fun closeOrGrantCancelsThePendingReturn() {
        val policy = BrowserIdleReturnPolicy()
        policy.pause(0); policy.cancel()
        assertFalse(policy.claim(30_000, true))
    }
    @Test fun aClockResetDoesNotCauseImmediateGrant() {
        val policy = BrowserIdleReturnPolicy()
        policy.pause(1_000)
        assertEquals(30_000L, policy.remaining(500))
        assertFalse(policy.claim(500, true))
    }
}
