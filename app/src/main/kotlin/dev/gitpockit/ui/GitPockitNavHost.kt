package dev.gitpockit.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.gitpockit.ui.addrepo.AddRepoScreen
import dev.gitpockit.ui.clone.CloneScreen
import dev.gitpockit.ui.credentials.AddCredentialScreen
import dev.gitpockit.ui.credentials.CredentialsScreen
import dev.gitpockit.ui.files.EditorScreen
import dev.gitpockit.ui.files.ImageScreen
import dev.gitpockit.ui.home.HomeScreen
import dev.gitpockit.ui.repo.BlameScreen
import dev.gitpockit.ui.repo.CommitScreen
import dev.gitpockit.ui.repo.CompareScreen
import dev.gitpockit.ui.repo.FileHistoryScreen
import dev.gitpockit.ui.repo.ConflictsScreen
import dev.gitpockit.ui.repo.EditConflictScreen
import dev.gitpockit.ui.repo.ReflogScreen
import dev.gitpockit.ui.repo.StashesScreen
import dev.gitpockit.ui.repo.SyncLogScreen
import dev.gitpockit.ui.repo.TagsScreen
import dev.gitpockit.ui.repo.DiffScreen
import dev.gitpockit.ui.repo.RepoScreen
import dev.gitpockit.ui.repo.RepoSettingsScreen
import dev.gitpockit.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val CLONE = "clone"
    const val ADD_REPO = "add?create={create}"
    const val SETTINGS = "settings"
    const val CREDENTIALS = "credentials"
    const val ADD_CREDENTIAL = "credentials/add"
    const val REPO = "repo/{repoId}"
    const val REPO_SETTINGS = "repo/{repoId}/settings"
    const val COMMIT = "repo/{repoId}/commit/{commit}"
    const val DIFF = "repo/{repoId}/diff?path={path}&commit={commit}&staged={staged}&from={from}&to={to}"
    const val FILE_HISTORY = "repo/{repoId}/file-history?path={path}"
    const val EDITOR = "repo/{repoId}/edit?path={path}"
    const val IMAGE = "repo/{repoId}/image?path={path}"
    const val BLAME = "repo/{repoId}/blame?path={path}"
    const val COMPARE = "repo/{repoId}/compare?from={from}&to={to}"
    const val CONFLICTS = "repo/{repoId}/conflicts"
    const val EDIT_CONFLICT = "repo/{repoId}/conflicts/edit?path={path}"
    const val SYNC_LOG = "repo/{repoId}/log"
    const val STASHES = "repo/{repoId}/stashes"
    const val TAGS = "repo/{repoId}/tags"
    const val REFLOG = "repo/{repoId}/reflog"

    fun addRepo(create: Boolean) = "add?create=$create"
    fun repo(id: Long) = "repo/$id"
    fun repoSettings(id: Long) = "repo/$id/settings"
    fun commit(id: Long, commit: String) = "repo/$id/commit/$commit"
    fun conflicts(id: Long) = "repo/$id/conflicts"
    fun editConflict(id: Long, path: String) = "repo/$id/conflicts/edit?path=${Uri.encode(path)}"
    fun syncLog(id: Long) = "repo/$id/log"
    fun stashes(id: Long) = "repo/$id/stashes"
    fun tags(id: Long) = "repo/$id/tags"
    fun reflog(id: Long) = "repo/$id/reflog"
    fun diff(id: Long, path: String, commit: String? = null, staged: Boolean = false) =
        "repo/$id/diff?path=${Uri.encode(path)}&commit=${commit.orEmpty()}&staged=$staged"
    fun compareDiff(id: Long, from: String, to: String, path: String) =
        "repo/$id/diff?path=${Uri.encode(path)}&from=${Uri.encode(from)}&to=${Uri.encode(to)}"
    fun editor(id: Long, path: String) = "repo/$id/edit?path=${Uri.encode(path)}"
    fun image(id: Long, path: String) = "repo/$id/image?path=${Uri.encode(path)}"
    fun fileHistory(id: Long, path: String) = "repo/$id/file-history?path=${Uri.encode(path)}"
    fun blame(id: Long, path: String) = "repo/$id/blame?path=${Uri.encode(path)}"
    fun compare(id: Long, from: String, to: String) = "repo/$id/compare?from=${Uri.encode(from)}&to=${Uri.encode(to)}"
}

/** Key under which the add-credential screen hands the new credential id back to its caller. */
private const val NEW_CREDENTIAL = "newCredentialId"

private val repoIdArg = navArgument("repoId") { type = NavType.LongType }

@Composable
private fun NavBackStackEntry.newCredentialId(): Long? {
    val id by savedStateHandle.getStateFlow<Long?>(NEW_CREDENTIAL, null).collectAsStateWithLifecycle()
    return id
}

private fun NavHostController.addCredential() = navigate(Routes.ADD_CREDENTIAL)

/** Where a notification asked to go: a repo, optionally straight to its conflicts. */
data class DeepLink(val repoId: Long, val conflicts: Boolean)

@Composable
fun GitPockitNavHost(deepLink: DeepLink? = null, onDeepLinkHandled: () -> Unit = {}) {
    val nav = rememberNavController()
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        nav.navigate(Routes.repo(link.repoId)) { popUpTo(Routes.HOME) }
        if (link.conflicts) nav.navigate(Routes.conflicts(link.repoId))
        onDeepLinkHandled()
    }
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onClone = { nav.navigate(Routes.CLONE) },
                onAddExisting = { nav.navigate(Routes.addRepo(create = false)) },
                onCreate = { nav.navigate(Routes.addRepo(create = true)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenRepo = { nav.navigate(Routes.repo(it)) },
            )
        }
        composable(Routes.CLONE) { entry ->
            CloneScreen(
                onBack = { nav.popBackStack() },
                onCloned = { id -> nav.navigate(Routes.repo(id)) { popUpTo(Routes.HOME) } },
                onAddCredential = { nav.addCredential() },
                newCredentialId = entry.newCredentialId(),
            )
        }
        composable(Routes.ADD_REPO, arguments = listOf(navArgument("create") { type = NavType.BoolType; defaultValue = false })) { entry ->
            AddRepoScreen(
                onBack = { nav.popBackStack() },
                onAdded = { id -> nav.navigate(Routes.repo(id)) { popUpTo(Routes.HOME) } },
                onAddCredential = { nav.addCredential() },
                newCredentialId = entry.newCredentialId(),
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, onCredentials = { nav.navigate(Routes.CREDENTIALS) })
        }
        composable(Routes.CREDENTIALS) {
            CredentialsScreen(onBack = { nav.popBackStack() }, onAdd = { nav.addCredential() })
        }
        composable(Routes.ADD_CREDENTIAL) {
            AddCredentialScreen(
                onBack = { nav.popBackStack() },
                onSaved = { id ->
                    nav.previousBackStackEntry?.savedStateHandle?.set(NEW_CREDENTIAL, id)
                    nav.popBackStack()
                },
            )
        }
        composable(Routes.REPO, arguments = listOf(repoIdArg)) { entry ->
            val id = checkNotNull(entry.arguments?.getLong("repoId"))
            RepoScreen(
                onBack = { nav.popBackStack() },
                onOpenSettings = { nav.navigate(Routes.repoSettings(id)) },
                onOpenCommit = { nav.navigate(Routes.commit(id, it)) },
                onOpenDiff = { path, staged -> nav.navigate(Routes.diff(id, path, staged = staged)) },
                onOpenConflicts = { nav.navigate(Routes.conflicts(id)) },
                onOpenLog = { nav.navigate(Routes.syncLog(id)) },
                onOpenStashes = { nav.navigate(Routes.stashes(id)) },
                onOpenTags = { nav.navigate(Routes.tags(id)) },
                onOpenReflog = { nav.navigate(Routes.reflog(id)) },
                onCompare = { from, to -> nav.navigate(Routes.compare(id, from, to)) },
                onOpenEditor = { nav.navigate(Routes.editor(id, it)) },
                onOpenImage = { nav.navigate(Routes.image(id, it)) },
                onFileHistory = { nav.navigate(Routes.fileHistory(id, it)) },
            )
        }
        composable(Routes.REPO_SETTINGS, arguments = listOf(repoIdArg)) { entry ->
            RepoSettingsScreen(
                onBack = { nav.popBackStack() },
                onRemoved = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onAddCredential = { nav.addCredential() },
                onOpenLog = { nav.navigate(Routes.syncLog(checkNotNull(entry.arguments?.getLong("repoId")))) },
                newCredentialId = entry.newCredentialId(),
            )
        }
        composable(Routes.CONFLICTS, arguments = listOf(repoIdArg)) { entry ->
            val id = checkNotNull(entry.arguments?.getLong("repoId"))
            ConflictsScreen(onBack = { nav.popBackStack() }, onEdit = { path -> nav.navigate(Routes.editConflict(id, path)) })
        }
        composable(Routes.EDIT_CONFLICT, arguments = listOf(repoIdArg, navArgument("path") { type = NavType.StringType })) {
            EditConflictScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SYNC_LOG, arguments = listOf(repoIdArg)) {
            SyncLogScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.STASHES, arguments = listOf(repoIdArg)) { entry ->
            val id = checkNotNull(entry.arguments?.getLong("repoId"))
            StashesScreen(onBack = { nav.popBackStack() }, onOpenConflicts = { nav.navigate(Routes.conflicts(id)) })
        }
        composable(Routes.TAGS, arguments = listOf(repoIdArg)) { entry ->
            val id = checkNotNull(entry.arguments?.getLong("repoId"))
            TagsScreen(onBack = { nav.popBackStack() }, onOpenCommit = { nav.navigate(Routes.commit(id, it)) })
        }
        composable(Routes.REFLOG, arguments = listOf(repoIdArg)) { entry ->
            val id = checkNotNull(entry.arguments?.getLong("repoId"))
            ReflogScreen(onBack = { nav.popBackStack() }, onOpenCommit = { nav.navigate(Routes.commit(id, it)) })
        }
        composable(Routes.COMMIT, arguments = listOf(repoIdArg, navArgument("commit") { type = NavType.StringType })) { entry ->
            val id = checkNotNull(entry.arguments?.getLong("repoId"))
            val commit = checkNotNull(entry.arguments?.getString("commit"))
            CommitScreen(
                onBack = { nav.popBackStack() },
                onOpenDiff = { path -> nav.navigate(Routes.diff(id, path, commit = commit)) },
                onOpenConflicts = { nav.navigate(Routes.conflicts(id)) },
            )
        }
        composable(
            Routes.DIFF,
            arguments = listOf(
                repoIdArg,
                navArgument("path") { type = NavType.StringType },
                navArgument("commit") { type = NavType.StringType; defaultValue = "" },
                navArgument("staged") { type = NavType.BoolType; defaultValue = false },
                navArgument("from") { type = NavType.StringType; defaultValue = "" },
                navArgument("to") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val id = checkNotNull(entry.arguments?.getLong("repoId"))
            DiffScreen(
                onBack = { nav.popBackStack() },
                onFileHistory = { nav.navigate(Routes.fileHistory(id, it)) },
                onBlame = { nav.navigate(Routes.blame(id, it)) },
            )
        }
        composable(Routes.EDITOR, arguments = listOf(repoIdArg, navArgument("path") { type = NavType.StringType })) {
            EditorScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.IMAGE, arguments = listOf(repoIdArg, navArgument("path") { type = NavType.StringType })) {
            ImageScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.FILE_HISTORY, arguments = listOf(repoIdArg, navArgument("path") { type = NavType.StringType })) { entry ->
            val id = checkNotNull(entry.arguments?.getLong("repoId"))
            FileHistoryScreen(onBack = { nav.popBackStack() }, onOpenDiff = { commit, path -> nav.navigate(Routes.diff(id, path, commit = commit)) })
        }
        composable(Routes.BLAME, arguments = listOf(repoIdArg, navArgument("path") { type = NavType.StringType })) { entry ->
            val id = checkNotNull(entry.arguments?.getLong("repoId"))
            BlameScreen(onBack = { nav.popBackStack() }, onOpenCommit = { nav.navigate(Routes.commit(id, it)) })
        }
        composable(
            Routes.COMPARE,
            arguments = listOf(repoIdArg, navArgument("from") { type = NavType.StringType }, navArgument("to") { type = NavType.StringType }),
        ) { entry ->
            val id = checkNotNull(entry.arguments?.getLong("repoId"))
            CompareScreen(
                onBack = { nav.popBackStack() },
                onSwap = { from, to -> nav.navigate(Routes.compare(id, from, to)) { popUpTo(Routes.COMPARE) { inclusive = true } } },
                onOpenCommit = { nav.navigate(Routes.commit(id, it)) },
                onOpenDiff = { from, to, path -> nav.navigate(Routes.compareDiff(id, from, to, path)) },
            )
        }
    }
}
