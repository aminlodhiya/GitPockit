package dev.gitpockit.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.data.SettingsRepository
import dev.gitpockit.sync.SyncDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Runs [block] off the main thread while keeping the receiver alive. */
private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/**
 * Lets automation apps trigger syncs (disabled until the user turns it on in Settings).
 *
 * Action `dev.gitpockit.action.SYNC`, sent to this app's package. Optional extras:
 * - `repo`: repository name (case-insensitive) or id; omit to sync everything.
 * - `direction`: `sync` (default), `pull` or `push`.
 */
@AndroidEntryPoint
class SyncIntentReceiver : BroadcastReceiver() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var repos: RepoRepository
    @Inject lateinit var scheduler: SyncScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SYNC) return
        async {
            if (!settings.automationEnabled.first()) {
                Log.i(TAG, "Ignoring sync intent: automation is disabled in Settings")
                return@async
            }
            val direction = when (intent.getStringExtra(EXTRA_DIRECTION)?.lowercase()) {
                "pull" -> SyncDirection.PULL
                "push" -> SyncDirection.PUSH
                else -> SyncDirection.BOTH
            }
            val target = intent.getStringExtra(EXTRA_REPO)?.trim()
            if (target.isNullOrEmpty()) {
                scheduler.request(null, SyncTrigger.INTENT, direction)
                return@async
            }
            val repo = repos.all().firstOrNull { it.name.equals(target, ignoreCase = true) || it.id.toString() == target }
            if (repo == null) Log.w(TAG, "No repository named \"$target\"") else scheduler.request(repo.id, SyncTrigger.INTENT, direction)
        }
    }

    companion object {
        const val ACTION_SYNC = "dev.gitpockit.action.SYNC"
        const val EXTRA_REPO = "repo"
        const val EXTRA_DIRECTION = "direction"
        private const val TAG = "SyncIntent"
    }
}

/** Fires at a repo's daily sync time: queues the sync and arms the next alarm. */
@AndroidEntryPoint
class SyncAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var repos: RepoRepository
    @Inject lateinit var scheduler: SyncScheduler
    @Inject lateinit var exact: ExactTimeScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM) return
        val repoId = intent.getLongExtra(EXTRA_REPO_ID, -1L).takeIf { it >= 0 } ?: return
        scheduler.request(repoId, SyncTrigger.SCHEDULE)
        async {
            val repo = repos.get(repoId) ?: return@async exact.cancel(repoId)
            exact.scheduleNext(repo)
        }
    }

    companion object {
        const val ACTION_ALARM = "dev.gitpockit.action.SYNC_ALARM"
        const val EXTRA_REPO_ID = "repoId"
    }
}

/**
 * Re-arms everything that doesn't survive on its own: alarms are cleared by a reboot and must be
 * recalculated when the clock, timezone or exact-alarm permission changes; the file watcher
 * needs restarting after a reboot or update. (WorkManager schedules persist by themselves.)
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var background: BackgroundSync

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        async { background.restoreAfterBoot() }
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
