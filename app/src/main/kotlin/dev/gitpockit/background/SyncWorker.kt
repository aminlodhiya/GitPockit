package dev.gitpockit.background

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.sync.SyncDirection

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: SyncCoordinator,
    private val repos: RepoRepository,
    private val notifications: SyncNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repoId = inputData.getLong(KEY_REPO, -1L)
        val trigger = runCatching { SyncTrigger.valueOf(inputData.getString(KEY_TRIGGER)!!) }.getOrDefault(SyncTrigger.MANUAL)
        val direction = runCatching { SyncDirection.valueOf(inputData.getString(KEY_DIRECTION)!!) }.getOrDefault(SyncDirection.BOTH)
        val ids = if (repoId >= 0) listOf(repoId) else repos.all().map { it.id }

        var stillOffline = false
        for (id in ids) {
            // A retry job that is still offline asks WorkManager to retry itself (with backoff)
            // rather than queueing another job.
            val outcome = coordinator.run(id, trigger, direction, queueRetry = trigger != SyncTrigger.RETRY)
            if (trigger == SyncTrigger.RETRY && outcome.pendingNetwork) stillOffline = true
        }
        return if (stillOffline) Result.retry() else Result.success()
    }

    /** Needed for expedited work on Android 11 and older. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifications.syncingNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(SyncNotifications.ID_SYNCING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(SyncNotifications.ID_SYNCING, notification)
        }
    }

    companion object {
        private const val KEY_REPO = "repoId"
        private const val KEY_TRIGGER = "trigger"
        private const val KEY_DIRECTION = "direction"

        fun input(repoId: Long?, trigger: SyncTrigger, direction: SyncDirection): Data =
            workDataOf(KEY_REPO to (repoId ?: -1L), KEY_TRIGGER to trigger.name, KEY_DIRECTION to direction.name)
    }
}
