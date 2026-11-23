package dev.gitpockit.core.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** owner/name of a GitHub repository. */
@Serializable
data class RepoSlug(val owner: String, val name: String) {
    val fullName: String get() = "$owner/$name"

    companion object {
        private val PATTERNS = listOf(
            Regex("""^https?://(?:[^@/]+@)?github\.com/([^/]+)/([^/]+?)(?:\.git)?/?$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:ssh://)?git@github\.com[:/]([^/]+)/([^/]+?)(?:\.git)?/?$""", RegexOption.IGNORE_CASE),
        )

        /** Parses a github.com remote URL (HTTPS or SSH); null for other hosts. */
        fun fromRemoteUrl(url: String): RepoSlug? = PATTERNS.firstNotNullOfOrNull { it.matchEntire(url.trim()) }
            ?.let { RepoSlug(it.groupValues[1], it.groupValues[2]) }
    }
}

@Serializable
data class GhUser(
    val login: String,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class GhRepo(
    @SerialName("full_name") val fullName: String,
    val name: String,
    val owner: GhUser,
    val private: Boolean = false,
    val description: String? = null,
    val fork: Boolean = false,
    val archived: Boolean = false,
    @SerialName("clone_url") val cloneUrl: String,
    @SerialName("ssh_url") val sshUrl: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("default_branch") val defaultBranch: String = "main",
    @SerialName("stargazers_count") val stars: Int = 0,
    @SerialName("open_issues_count") val openIssues: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
    val language: String? = null,
)

@Serializable
data class GhLabel(val name: String, val color: String? = null)

@Serializable
data class GhIssue(
    val number: Int,
    val title: String,
    val body: String? = null,
    val state: String,
    val user: GhUser,
    val labels: List<GhLabel> = emptyList(),
    val comments: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    /** Present when this "issue" is really a pull request (GitHub lists both together). */
    @SerialName("pull_request") val pullRequest: PullRef? = null,
) {
    @Serializable
    data class PullRef(val url: String? = null)

    val isOpen: Boolean get() = state == "open"
}

@Serializable
data class GhComment(
    val id: Long,
    val body: String? = null,
    val user: GhUser,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class GhBranchRef(val ref: String, val sha: String, val label: String? = null)

@Serializable
data class GhPull(
    val number: Int,
    val title: String,
    val body: String? = null,
    val state: String,
    val user: GhUser,
    val draft: Boolean = false,
    val merged: Boolean = false,
    @SerialName("merged_at") val mergedAt: String? = null,
    /** null while GitHub is still computing it. */
    val mergeable: Boolean? = null,
    @SerialName("mergeable_state") val mergeableState: String? = null,
    val head: GhBranchRef,
    val base: GhBranchRef,
    val comments: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    @SerialName("changed_files") val changedFiles: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("html_url") val htmlUrl: String,
) {
    val isOpen: Boolean get() = state == "open"
}

@Serializable
data class GhPullFile(
    val filename: String,
    val status: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String? = null,
    @SerialName("previous_filename") val previousFilename: String? = null,
)

enum class MergeMethod(val api: String) { MERGE("merge"), SQUASH("squash"), REBASE("rebase") }

@Serializable
data class GhMergeResult(val merged: Boolean, val message: String? = null, val sha: String? = null)

@Serializable
data class GhWorkflowRun(
    val id: Long,
    val name: String? = null,
    @SerialName("display_title") val title: String? = null,
    @SerialName("head_branch") val branch: String? = null,
    @SerialName("head_sha") val sha: String,
    val event: String? = null,
    /** queued, in_progress, completed… */
    val status: String? = null,
    /** success, failure, cancelled, skipped… (when completed) */
    val conclusion: String? = null,
    @SerialName("run_number") val runNumber: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("html_url") val htmlUrl: String,
)

@Serializable
data class GhStep(val name: String, val status: String? = null, val conclusion: String? = null, val number: Int = 0)

@Serializable
data class GhJob(
    val id: Long,
    val name: String,
    val status: String? = null,
    val conclusion: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    val steps: List<GhStep> = emptyList(),
    @SerialName("html_url") val htmlUrl: String? = null,
)

@Serializable
data class GhCheckRun(val name: String, val status: String? = null, val conclusion: String? = null)

/** Overall CI state of a commit, from its check runs. */
enum class CiState { NONE, PENDING, SUCCESS, FAILURE }

/** The one-time code the user types on github.com during device sign-in. */
@Serializable
data class DeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int = 5,
)

/** Something went wrong talking to GitHub. [status] is the HTTP status, or 0 for network errors. */
open class GitHubException(message: String, val status: Int = 0, cause: Throwable? = null) : Exception(message, cause)

class GitHubAuthException(message: String = "GitHub sign-in expired or was revoked. Please sign in again.") : GitHubException(message, 401)

class GitHubNetworkException(cause: Throwable) : GitHubException("Can't reach GitHub: ${cause.message}", 0, cause)
