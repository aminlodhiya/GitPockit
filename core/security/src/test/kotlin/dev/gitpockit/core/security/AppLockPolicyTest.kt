package dev.gitpockit.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    private val minute = 60_000L

    @Test fun disabledNeverLocks() {
        assertFalse(AppLockPolicy.shouldLock(enabled = false, backgroundSinceMs = null, nowMs = 0, timeoutMs = 0))
        assertFalse(AppLockPolicy.shouldLock(false, 0, 99 * minute, 0))
    }

    @Test fun coldStartAlwaysLocks() {
        assertTrue(AppLockPolicy.shouldLock(enabled = true, backgroundSinceMs = null, nowMs = 5, timeoutMs = 30 * minute))
    }

    @Test fun immediateTimeoutLocksOnEveryReturn() {
        assertTrue(AppLockPolicy.shouldLock(true, backgroundSinceMs = 1000, nowMs = 1000, timeoutMs = 0))
    }

    @Test fun shortTripsAwayStayUnlocked() {
        assertFalse(AppLockPolicy.shouldLock(true, backgroundSinceMs = 0, nowMs = 4 * minute, timeoutMs = 5 * minute))
        assertTrue(AppLockPolicy.shouldLock(true, backgroundSinceMs = 0, nowMs = 5 * minute, timeoutMs = 5 * minute))
    }

    @Test fun clockMovedBackwardsLocks() {
        assertTrue(AppLockPolicy.shouldLock(true, backgroundSinceMs = 10 * minute, nowMs = 2 * minute, timeoutMs = 30 * minute))
    }
}
