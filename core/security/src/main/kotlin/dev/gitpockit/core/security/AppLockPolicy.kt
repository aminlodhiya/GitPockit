package dev.gitpockit.core.security

/**
 * When the app lock should ask for the fingerprint/PIN again.
 *
 * - Lock disabled: never.
 * - Cold start (no background time recorded): always.
 * - Back from the background: once the app was away at least [timeoutMs]
 *   (0 = every time it leaves the screen).
 */
object AppLockPolicy {
    /** Choices offered in Settings, in minutes. */
    val TIMEOUT_CHOICES_MINUTES = listOf(0, 1, 5, 30)

    fun shouldLock(enabled: Boolean, backgroundSinceMs: Long?, nowMs: Long, timeoutMs: Long): Boolean = when {
        !enabled -> false
        backgroundSinceMs == null -> true
        // Clock moved backwards (manual change): be safe and lock.
        nowMs < backgroundSinceMs -> true
        else -> nowMs - backgroundSinceMs >= timeoutMs
    }
}
