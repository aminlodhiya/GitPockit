package dev.gitpockit.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gitpockit.MainActivity
import dev.gitpockit.R
import dev.gitpockit.core.data.model.Repo
import dev.gitpockit.core.data.model.SyncResult
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.data.SettingsRepository
import dev.gitpockit.sync.SyncOutcome
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val nm = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    private fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CH_PROBLEMS, context.getString(R.string.channel_problems), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.channel_problems_desc) },
                NotificationChannel(CH_DONE, context.getString(R.string.channel_done), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_RUNNING, context.getString(R.string.channel_running), NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CH_WATCH, context.getString(R.string.channel_watch), NotificationManager.IMPORTANCE_MIN)
                    .apply { description = context.getString(R.string.channel_watch_desc) },
            ),
        )
    }

    suspend fun onResult(repo: Repo, trigger: SyncTrigger, outcome: SyncOutcome) {
        val id = ID_REPO_BASE + repo.id.toInt()
        when (outcome.result) {
            SyncResult.CONFLICTS -> post(
                id,
                CH_PROBLEMS,
                context.getString(R.string.notif_conflicts_title, repo.name),
                context.getString(R.string.notif_conflicts_text, outcome.conflicts.size.coerceAtLeast(1)),
                openRepo(repo.id, conflicts = true),
            )
            SyncResult.FAILED -> post(
                id,
                CH_PROBLEMS,
                context.getString(if (outcome.authFailed) R.string.notif_auth_title else R.string.notif_failed_title, repo.name),
                outcome.message.orEmpty(),
                openRepo(repo.id),
            )
            SyncResult.SYNCED -> {
                nm.cancel(id)
                if (trigger != SyncTrigger.MANUAL && settings.notifyOnSuccess.first()) {
                    post(id, CH_DONE, context.getString(R.string.notif_synced_title, repo.name), outcome.message.orEmpty(), openRepo(repo.id))
                }
            }
            // Offline isn't a problem: the app shows a badge and retries by itself.
            SyncResult.UP_TO_DATE, SyncResult.OFFLINE -> nm.cancel(id)
        }
    }

    fun syncingNotification(): Notification =
        NotificationCompat.Builder(context, CH_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(context.getString(R.string.notif_syncing))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    fun watchingNotification(count: Int): Notification =
        NotificationCompat.Builder(context, CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(context.getString(R.string.notif_watching, count))
            .setContentText(context.getString(R.string.notif_watching_text))
            .setContentIntent(openApp())
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    private fun post(id: Int, channel: String, title: String, text: String, intent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openRepo(repoId: Long, conflicts: Boolean = false): PendingIntent = PendingIntent.getActivity(
        context,
        repoId.toInt() * 2 + if (conflicts) 1 else 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_REPO_ID, repoId)
            .putExtra(MainActivity.EXTRA_OPEN_CONFLICTS, conflicts),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CH_PROBLEMS = "sync_problems"
        const val CH_DONE = "sync_done"
        const val CH_RUNNING = "sync_running"
        const val CH_WATCH = "file_watch"
        const val ID_SYNCING = 1
        const val ID_WATCHING = 2
        private const val ID_REPO_BASE = 1000
    }
}
