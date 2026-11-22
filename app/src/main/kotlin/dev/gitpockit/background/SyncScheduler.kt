package dev.gitpockit.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gitpockit.core.data.model.Repo
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.sync.SyncDirection
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queues syncs with WorkManager. Work survives app restarts and reboots, which is what makes the
 * app offline-first: a sync that couldn't reach the network is retried as soon as it can.
 */
@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext context: Context) {
    private val wm = WorkManager.getInstance(context)

    /** Run a sync soon (expedited when quota allows). [repoId] null = every repository. */
    fun request(repoId: Long?, trigger: SyncTrigger, direction: SyncDirection = SyncDirection.BOTH) {
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(SyncWorker.input(repoId, trigger, direction))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        // APPEND_OR_REPLACE: a trigger arriving mid-sync runs right after it instead of being dropped.
        wm.enqueueUniqueWork("sync-${repoId ?: "all"}", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    /** Retry once the network (or Wi-Fi) is back. Survives reboots. */
    fun scheduleRetry(repoId: Long, wifiOnly: Boolean) {
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(SyncWorker.input(repoId, SyncTrigger.RETRY, SyncDirection.BOTH))
            .setConstraints(networkConstraint(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        wm.enqueueUniqueWork(retryName(repoId), ExistingWorkPolicy.KEEP, work)
    }

    fun cancelRetry(repoId: Long) {
        wm.cancelUniqueWork(retryName(repoId))
    }

    /** Makes the periodic work match each repo's interval; cancels work of removed repos. */
    suspend fun reconcilePeriodic(repos: List<Repo>) {
        val wanted = repos.filter { it.sync.intervalMinutes > 0 }
        for (repo in wanted) {
            val minutes = repo.sync.intervalMinutes.coerceAtLeast(15).toLong()
            val work = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
                .setInputData(SyncWorker.input(repo.id, SyncTrigger.SCHEDULE, SyncDirection.BOTH))
                // No network constraint: offline runs still commit locally and queue a retry.
                .addTag(PERIODIC_TAG)
                .addTag(repoTag(repo.id))
                .addTag("interval:$minutes")
                .build()
            wm.enqueueUniquePeriodicWork(periodicName(repo.id), ExistingPeriodicWorkPolicy.UPDATE, work)
        }
        val wantedTags = wanted.map { repoTag(it.id) }.toSet()
        wm.getWorkInfosByTagFlow(PERIODIC_TAG).first()
            .filter { info -> info.tags.none { it in wantedTags } }
            .forEach { wm.cancelWorkById(it.id) }
    }

    private fun networkConstraint(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    private fun retryName(id: Long) = "retry-$id"
    private fun periodicName(id: Long) = "periodic-$id"
    private fun repoTag(id: Long) = "repo:$id"

    private companion object {
        const val TAG = "gitpockit-sync"
        const val PERIODIC_TAG = "gitpockit-periodic"
    }
}
