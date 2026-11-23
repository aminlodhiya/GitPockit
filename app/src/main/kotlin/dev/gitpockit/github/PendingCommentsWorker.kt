package dev.gitpockit.github

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Sends GitHub comments that were written offline, once the network is back. */
@HiltWorker
class PendingCommentsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val github: GitHubService,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (github.flushPending()) Result.retry() else Result.success()
}
