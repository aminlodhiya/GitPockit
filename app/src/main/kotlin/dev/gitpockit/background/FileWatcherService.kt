package dev.gitpockit.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.gitpockit.core.data.model.Repo
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.sync.RepoOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Keeps an eye on the folders of repos with "Sync when files change" enabled and queues a sync
 * shortly after edits stop. Runs as a low-priority foreground service, because Android stops
 * background file watchers otherwise.
 */
@AndroidEntryPoint
class FileWatcherService : Service() {
    @Inject lateinit var repos: RepoRepository
    @Inject lateinit var ops: RepoOps
    @Inject lateinit var scheduler: SyncScheduler
    @Inject lateinit var notifications: SyncNotifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watchers = HashMap<Long, Pair<String, RecursiveFileObserver>>()
    private val pending = ConcurrentHashMap<Long, Job>()
    /** When each repo last finished a sync; changes right after our own pull are ignored. */
    private val lastBusyEnd = ConcurrentHashMap<Long, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(0)
        scope.launch {
            repos.repos
                .map { list -> list.filter { it.sync.onFileChange } }
                .distinctUntilChanged()
                .collect(::update)
        }
        scope.launch {
            var previous = emptySet<Long>()
            ops.busy.collect { now ->
                (previous - now).forEach { lastBusyEnd[it] = System.currentTimeMillis() }
                previous = now
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    @Synchronized
    private fun update(watched: List<Repo>) {
        if (watched.isEmpty()) {
            stopSelf()
            return
        }
        val wanted = watched.associateBy { it.id }
        (watchers.keys - wanted.keys).forEach { id -> watchers.remove(id)?.second?.stop() }
        for (repo in watched) {
            val path = repo.directory.path
            if (watchers[repo.id]?.first == path) continue
            watchers.remove(repo.id)?.second?.stop()
            if (!repo.directory.isDirectory) continue
            val observer = RecursiveFileObserver(repo.directory) { onChange(repo.id) }
            observer.start()
            watchers[repo.id] = path to observer
        }
        startForegroundCompat(watchers.size)
    }

    private fun onChange(repoId: Long) {
        // Ignore the writes our own sync makes (pull) and a short grace period after it.
        if (repoId in ops.busy.value) return
        if (System.currentTimeMillis() - (lastBusyEnd[repoId] ?: 0L) < GRACE_MS) return
        pending.remove(repoId)?.cancel()
        pending[repoId] = scope.launch {
            delay(DEBOUNCE_MS) // wait for the editing burst to finish
            pending.remove(repoId)
            scheduler.request(repoId, SyncTrigger.FILE_CHANGE)
        }
    }

    private fun startForegroundCompat(count: Int) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, SyncNotifications.ID_WATCHING, notifications.watchingNotification(count), type)
    }

    override fun onDestroy() {
        synchronized(this) { watchers.values.forEach { it.second.stop() } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val DEBOUNCE_MS = 20_000L
        private const val GRACE_MS = 5_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FileWatcherService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FileWatcherService::class.java))
        }
    }
}
