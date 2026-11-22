package dev.gitpockit.ui

import android.net.Uri
import androidx.compose.runtime.Composable
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
import dev.gitpockit.ui.home.HomeScreen
import dev.gitpockit.ui.repo.CommitScreen
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
    const val DIFF = "repo/{repoId}/diff?path={path}&commit={commit}&staged={staged}"

    fun addRepo(create: Boolean) = "add?create=$create"
    fun repo(id: Long) = "repo/$id"
    fun repoSettings(id: Long) = "repo/$id/settings"
    fun commit(id: Long, commit: String) = "repo/$id/commit/$commit"
    fun diff(id: Long, path: String, commit: String? = null, staged: Boolean = false) =
        "repo/$id/diff?path=${Uri.encode(path)}&commit=${commit.orEmpty()}&staged=$staged"
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

@Composable
fun GitPockitNavHost() {
    val nav = rememberNavController()
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
            )
        }
        composable(Routes.REPO_SETTINGS, arguments = listOf(repoIdArg)) { entry ->
            RepoSettingsScreen(
                onBack = { nav.popBackStack() },
                onRemoved = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onAddCredential = { nav.addCredential() },
                newCredentialId = entry.newCredentialId(),
            )
        }
        composable(Routes.COMMIT, arguments = listOf(repoIdArg, navArgument("commit") { type = NavType.StringType })) { entry ->
            val id = checkNotNull(entry.arguments?.getLong("repoId"))
            val commit = checkNotNull(entry.arguments?.getString("commit"))
            CommitScreen(onBack = { nav.popBackStack() }, onOpenDiff = { path -> nav.navigate(Routes.diff(id, path, commit = commit)) })
        }
        composable(
            Routes.DIFF,
            arguments = listOf(
                repoIdArg,
                navArgument("path") { type = NavType.StringType },
                navArgument("commit") { type = NavType.StringType; defaultValue = "" },
                navArgument("staged") { type = NavType.BoolType; defaultValue = false },
            ),
        ) {
            DiffScreen(onBack = { nav.popBackStack() })
        }
    }
}
