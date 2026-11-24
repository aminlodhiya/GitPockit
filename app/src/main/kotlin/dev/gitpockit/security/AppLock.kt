package dev.gitpockit.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.gitpockit.core.security.AppLockPolicy
import dev.gitpockit.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides when GitPockit is locked. Starts locked when the lock is on; locks again after the app
 * has been in the background for the chosen time ([AppLockPolicy]).
 */
@Singleton
class AppLock @Inject constructor(private val settings: SettingsRepository) : DefaultLifecycleObserver {
    /** null until settings are read; true = show the lock screen. */
    private val _locked = MutableStateFlow<Boolean?>(null)
    val locked: StateFlow<Boolean?> = _locked

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled

    @Volatile private var timeoutMs = 0L
    @Volatile private var backgroundSince: Long? = null
    /** Set while our own fingerprint/PIN prompt is open: leaving for the system PIN screen isn't "going away". */
    @Volatile private var verifying = false

    /** Call before showing a confirmation prompt from inside the unlocked app. */
    fun beginVerification() {
        verifying = true
    }

    fun endVerification() {
        verifying = false
        backgroundSince = null
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            var first = true
            combine(settings.appLockEnabled, settings.appLockTimeoutMinutes) { on, minutes -> on to minutes }.collect { (on, minutes) ->
                _enabled.value = on
                timeoutMs = minutes * 60_000L
                when {
                    first -> _locked.value = AppLockPolicy.shouldLock(on, backgroundSinceMs = null, nowMs = 0, timeoutMs = timeoutMs)
                    !on -> _locked.value = false
                    // Turned on just now: the user was just verified, so stay unlocked.
                }
                first = false
            }
        }
        scope.launch(Dispatchers.Main) { ProcessLifecycleOwner.get().lifecycle.addObserver(this@AppLock) }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (_locked.value == false && !verifying) backgroundSince = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundSince ?: return
        if (AppLockPolicy.shouldLock(_enabled.value, since, System.currentTimeMillis(), timeoutMs)) _locked.value = true
        backgroundSince = null
    }

    fun unlock() {
        _locked.value = false
        backgroundSince = null
    }
}

/** Fingerprint/face with the phone's PIN, pattern or password as fallback. */
object Biometric {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: (String?) -> Unit = {},
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure(errString.toString())
            },
        )
        // With DEVICE_CREDENTIAL allowed there's no cancel button; the PIN screen is the fallback.
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }
}

fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}
