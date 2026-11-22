package dev.gitpockit.background

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import dagger.hilt.android.AndroidEntryPoint
import dev.gitpockit.core.data.model.AppTrigger
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.core.data.repository.RepoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Opt-in accessibility service that only looks at *which app is in the foreground*
 * (window-state changes). It never reads screen content. When an app chosen in a repo's
 * settings opens or closes, that repo is synced, e.g. pull when Obsidian opens, push when it closes.
 */
@AndroidEntryPoint
class AppTriggerService : AccessibilityService() {
    @Inject lateinit var repos: RepoRepository
    @Inject lateinit var scheduler: SyncScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var triggers: List<AppTrigger> = emptyList()
    private var current: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope.launch { repos.appTriggers.collect { triggers = it } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in IGNORED || pkg in keyboards()) return // overlays, not real app switches
        if (pkg == current) return
        val previous = current
        current = pkg
        val list = triggers
        if (previous != null) {
            list.filter { it.packageName == previous && it.onClose }.forEach { scheduler.request(it.repoId, SyncTrigger.APP_CLOSE) }
        }
        list.filter { it.packageName == pkg && it.onOpen }.forEach { scheduler.request(it.repoId, SyncTrigger.APP_OPEN) }
    }

    private fun keyboards(): Set<String> = runCatching {
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        val IGNORED = setOf("com.android.systemui", "android")
    }
}
