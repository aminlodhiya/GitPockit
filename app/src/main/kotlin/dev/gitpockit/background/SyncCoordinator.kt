package dev.gitpockit.background

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.sync.SyncDirection
import dev.gitpockit.sync.SyncEngine
import dev.gitpockit.sync.SyncOutcome
import dev.gitpockit.widget.GitPockitWidget
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the [SyncEngine] and handles what happens around it: queueing a retry when offline,
 * notifications, and refreshing the home-screen widget. Used by the UI and all background triggers.
 */
@Singleton
class SyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: SyncEngine,
    private val repos: RepoRepository,
    private val scheduler: SyncScheduler,
    private val notifications: SyncNotifications,
) {
    suspend fun run(
        repoId: Long,
        trigger: SyncTrigger,
        direction: SyncDirection = SyncDirection.BOTH,
        queueRetry: Boolean = true,
    ): SyncOutcome {
        val outcome = engine.sync(repoId, trigger, direction)
        val repo = repos.get(repoId)
        if (outcome.pendingNetwork) {
            if (queueRetry) scheduler.scheduleRetry(repoId, wifiOnly = repo?.sync?.wifiOnly == true)
        } else {
            scheduler.cancelRetry(repoId)
        }
        if (repo != null) notifications.onResult(repo, trigger, outcome)
        runCatching { GitPockitWidget().updateAll(context) }
        return outcome
    }
}
