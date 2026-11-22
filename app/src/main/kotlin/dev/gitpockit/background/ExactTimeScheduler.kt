package dev.gitpockit.background

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gitpockit.core.data.model.Repo
import dev.gitpockit.sync.DailySchedule
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Daily at 09:00 and 21:30" schedules, using exact alarms so syncs run on the minute even in
 * Doze. Each repo has one pending alarm for its next time; when it fires, [SyncAlarmReceiver]
 * queues the sync and arms the following one.
 *
 * Without the "Alarms & reminders" permission (Android 12+), a slightly inexact alarm is used.
 */
@Singleton
class ExactTimeScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("exact_alarms", Context.MODE_PRIVATE)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    /** Opens the system screen where the user allows exact alarms (Android 12+). */
    fun requestExactPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Arms the next alarm for every repo with daily times; cancels alarms of the rest. */
    @Synchronized
    fun reconcile(repos: List<Repo>) {
        val wanted = repos.filter { it.sync.dailyTimes.isNotEmpty() }
        wanted.forEach(::scheduleNext)
        val wantedIds = wanted.map { it.id.toString() }.toSet()
        val previous = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty()
        (previous - wantedIds).forEach { cancel(it.toLong()) }
        prefs.edit().putStringSet(KEY_IDS, wantedIds).apply()
    }

    fun scheduleNext(repo: Repo, now: ZonedDateTime = ZonedDateTime.now()) {
        val next = DailySchedule.next(repo.sync.dailyTimes, now) ?: return cancel(repo.id)
        val at = next.toInstant().toEpochMilli()
        val pi = pendingIntent(repo.id)
        if (canScheduleExact()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // Best effort: may run a few minutes late in Doze.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(repoId: Long) {
        alarms.cancel(pendingIntent(repoId))
    }

    private fun pendingIntent(repoId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        repoId.toInt(),
        Intent(context, SyncAlarmReceiver::class.java)
            .setAction(SyncAlarmReceiver.ACTION_ALARM)
            // A distinct data URI keeps each repo's PendingIntent separate.
            .setData(Uri.parse("gitpockit://alarm/$repoId"))
            .putExtra(SyncAlarmReceiver.EXTRA_REPO_ID, repoId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val KEY_IDS = "scheduled_repo_ids"
    }
}
