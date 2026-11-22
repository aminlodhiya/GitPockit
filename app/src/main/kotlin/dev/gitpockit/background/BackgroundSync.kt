package dev.gitpockit.background

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.gitpockit.core.data.repository.RepoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps scheduled syncs and the file watcher in line with each repo's sync settings. */
@Singleton
class BackgroundSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repos: RepoRepository,
    private val scheduler: SyncScheduler,
    private val exactTimes: ExactTimeScheduler,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            repos.repos
                .map { list -> list.map { Triple(it.id, it.sync.intervalMinutes, it.sync.wifiOnly) } to list }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (_, list) -> runCatching { scheduler.reconcilePeriodic(list) }.onFailure { Log.w(TAG, "schedule", it) } }
        }
        scope.launch {
            repos.repos
                .map { list -> list.map { it.id to it.sync.dailyTimes } to list }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (_, list) -> runCatching { exactTimes.reconcile(list) }.onFailure { Log.w(TAG, "alarms", it) } }
        }
        scope.launch {
            repos.repos
                .map { list -> list.any { it.sync.onFileChange } }
                .distinctUntilChanged()
                .collect { watch -> updateWatcher(watch) }
        }
    }

    /** Called after boot, app update, clock/timezone change and exact-alarm permission change. */
    suspend fun restoreAfterBoot() {
        val all = repos.all()
        runCatching { exactTimes.reconcile(all) }.onFailure { Log.w(TAG, "alarms", it) }
        updateWatcher(all.any { it.sync.onFileChange })
    }

    private fun updateWatcher(watch: Boolean) {
        runCatching {
            if (watch) FileWatcherService.start(context) else FileWatcherService.stop(context)
        }.onFailure { Log.w(TAG, "file watcher", it) } // e.g. background-start restrictions
    }

    private companion object {
        const val TAG = "BackgroundSync"
    }
}
