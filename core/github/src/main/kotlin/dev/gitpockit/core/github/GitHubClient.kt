package dev.gitpockit.core.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * GitHub REST API client (v3) for the features GitPockit shows: account, repositories, issues,
 * pull requests and Actions. [token] supplies the OAuth or personal access token per request.
 */
class GitHubClient(
    engine: HttpClientEngine,
    private val token: suspend () -> String?,
    private val apiUrl: String = "https://api.github.com",
) : AutoCloseable {

    val json = JSON

    private val http = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
        defaultRequest {
            header("Accept", "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            header("User-Agent", "GitPockit")
        }
        expectSuccess = false
    }

    // ---- account & repositories

    suspend fun user(): GhUser = get("/user")

    /** Repositories the user owns or can access, most recently updated first. */
    suspend fun repos(page: Int = 1, perPage: Int = 100): List<GhRepo> = get("/user/repos") {
        parameter("sort", "updated")
        parameter("per_page", perPage)
        parameter("page", page)
    }

    suspend fun repo(slug: RepoSlug): GhRepo = get("/repos/${slug.fullName}")

    // ---- issues

    /** Issues only (GitHub's issue list also contains pull requests; they're filtered out). */
    suspend fun issues(slug: RepoSlug, state: String = "open", page: Int = 1): List<GhIssue> =
        get<List<GhIssue>>("/repos/${slug.fullName}/issues") {
            parameter("state", state)
            parameter("per_page", 50)
            parameter("page", page)
        }.filter { it.pullRequest == null }

    suspend fun searchIssues(slug: RepoSlug, query: String, state: String = "open"): List<GhIssue> {
        val q = buildString {
            append("repo:${slug.fullName} is:issue ")
            if (state != "all") append("is:$state ")
            append(query)
        }
        return get<SearchResult>("/search/issues") {
            parameter("q", q)
            parameter("per_page", 50)
        }.items
    }

    suspend fun issue(slug: RepoSlug, number: Int): GhIssue = get("/repos/${slug.fullName}/issues/$number")

    /** Conversation comments; also used for pull requests (same numbering). */
    suspend fun comments(slug: RepoSlug, number: Int): List<GhComment> =
        get("/repos/${slug.fullName}/issues/$number/comments") { parameter("per_page", 100) }

    suspend fun createIssue(slug: RepoSlug, title: String, body: String): GhIssue =
        send(HttpMethod.POST, "/repos/${slug.fullName}/issues", NewIssue(title, body))

    suspend fun comment(slug: RepoSlug, number: Int, body: String): GhComment =
        send(HttpMethod.POST, "/repos/${slug.fullName}/issues/$number/comments", NewComment(body))

    suspend fun setIssueOpen(slug: RepoSlug, number: Int, open: Boolean): GhIssue =
        send(HttpMethod.PATCH, "/repos/${slug.fullName}/issues/$number", StateChange(if (open) "open" else "closed"))

    // ---- pull requests

    suspend fun pulls(slug: RepoSlug, state: String = "open", page: Int = 1): List<GhPull> =
        get("/repos/${slug.fullName}/pulls") {
            parameter("state", state)
            parameter("per_page", 50)
            parameter("page", page)
        }

    suspend fun pull(slug: RepoSlug, number: Int): GhPull = get("/repos/${slug.fullName}/pulls/$number")

    suspend fun pullFiles(slug: RepoSlug, number: Int): List<GhPullFile> =
        get("/repos/${slug.fullName}/pulls/$number/files") { parameter("per_page", 100) }

    suspend fun createPull(slug: RepoSlug, title: String, body: String, head: String, base: String, draft: Boolean): GhPull =
        send(HttpMethod.POST, "/repos/${slug.fullName}/pulls", NewPull(title, body, head, base, draft))

    suspend fun merge(slug: RepoSlug, number: Int, method: MergeMethod): GhMergeResult =
        send(HttpMethod.PUT, "/repos/${slug.fullName}/pulls/$number/merge", MergeRequest(method.api))

    suspend fun setPullOpen(slug: RepoSlug, number: Int, open: Boolean): GhPull =
        send(HttpMethod.PATCH, "/repos/${slug.fullName}/pulls/$number", StateChange(if (open) "open" else "closed"))

    // ---- Actions & checks

    suspend fun workflowRuns(slug: RepoSlug, branch: String? = null): List<GhWorkflowRun> =
        get<RunsPage>("/repos/${slug.fullName}/actions/runs") {
            parameter("per_page", 30)
            branch?.let { parameter("branch", it) }
        }.workflowRuns

    suspend fun runJobs(slug: RepoSlug, runId: Long): List<GhJob> =
        get<JobsPage>("/repos/${slug.fullName}/actions/runs/$runId/jobs") { parameter("per_page", 100) }.jobs

    suspend fun rerun(slug: RepoSlug, runId: Long, failedOnly: Boolean) {
        val path = "/repos/${slug.fullName}/actions/runs/$runId/" + if (failedOnly) "rerun-failed-jobs" else "rerun"
        sendNoBody(HttpMethod.POST, path)
    }

    /** Combined CI state of a commit from its check runs. */
    suspend fun ciState(slug: RepoSlug, sha: String): CiState {
        val runs = get<CheckRunsPage>("/repos/${slug.fullName}/commits/$sha/check-runs") { parameter("per_page", 100) }.checkRuns
        return when {
            runs.isEmpty() -> CiState.NONE
            runs.any { it.status != "completed" } -> CiState.PENDING
            runs.any { it.conclusion in FAILED } -> CiState.FAILURE
            else -> CiState.SUCCESS
        }
    }

    override fun close() = http.close()

    // ---- plumbing

    private enum class HttpMethod { POST, PUT, PATCH }

    private suspend inline fun <reified T> get(path: String, crossinline block: HttpRequestBuilder.() -> Unit = {}): T {
        val bearer = token()
        val response = call { http.get(apiUrl + path) { auth(bearer); block() } }
        return decode(response)
    }

    private suspend inline fun <reified B : Any, reified T> send(method: HttpMethod, path: String, body: B): T {
        val bearer = token()
        val response = call {
            val build: HttpRequestBuilder.() -> Unit = { auth(bearer); contentType(ContentType.Application.Json); setBody(body) }
            when (method) {
                HttpMethod.POST -> http.post(apiUrl + path, build)
                HttpMethod.PUT -> http.put(apiUrl + path, build)
                HttpMethod.PATCH -> http.patch(apiUrl + path, build)
            }
        }
        return decode(response)
    }

    private suspend fun sendNoBody(method: HttpMethod, path: String) {
        val bearer = token()
        val response = call {
            when (method) {
                HttpMethod.POST -> http.post(apiUrl + path) { auth(bearer) }
                HttpMethod.PUT -> http.put(apiUrl + path) { auth(bearer) }
                HttpMethod.PATCH -> http.patch(apiUrl + path) { auth(bearer) }
            }
        }
        check(response)
    }

    private fun HttpRequestBuilder.auth(bearer: String?) {
        bearer?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
    }

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: IOException) {
        throw GitHubNetworkException(e)
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T {
        check(response)
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun check(response: HttpResponse) {
        if (response.status.isSuccess()) return
        val message = runCatching {
            json.parseToJsonElement(response.bodyAsText()).let { it as? JsonObject }?.get("message")?.jsonPrimitive?.content
        }.getOrNull()
        throw when (response.status) {
            HttpStatusCode.Unauthorized -> GitHubAuthException()
            HttpStatusCode.NotFound -> GitHubException("Not found, or your account has no access to it", 404)
            HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests ->
                if (response.headers["x-ratelimit-remaining"] == "0") {
                    val reset = response.headers["x-ratelimit-reset"]?.toLongOrNull()
                    val minutes = reset?.let { ((it * 1000 - System.currentTimeMillis()) / 60_000).coerceAtLeast(1) }
                    GitHubException("GitHub rate limit reached" + (minutes?.let { ". Try again in $it min" } ?: ""), response.status.value)
                } else {
                    GitHubException(message ?: "Not allowed (HTTP 403). The token may lack the needed permission.", 403)
                }
            else -> GitHubException(message ?: "GitHub error (HTTP ${response.status.value})", response.status.value)
        }
    }

    @Serializable private data class SearchResult(val items: List<GhIssue> = emptyList())

    @Serializable private data class RunsPage(@kotlinx.serialization.SerialName("workflow_runs") val workflowRuns: List<GhWorkflowRun> = emptyList())

    @Serializable private data class JobsPage(val jobs: List<GhJob> = emptyList())

    @Serializable private data class CheckRunsPage(@kotlinx.serialization.SerialName("check_runs") val checkRuns: List<GhCheckRun> = emptyList())

    @Serializable private data class NewIssue(val title: String, val body: String)

    @Serializable private data class NewComment(val body: String)

    @Serializable private data class StateChange(val state: String)

    @Serializable private data class NewPull(val title: String, val body: String, val head: String, val base: String, val draft: Boolean)

    @Serializable private data class MergeRequest(@kotlinx.serialization.SerialName("merge_method") val mergeMethod: String)

    companion object {
        /** JSON settings for GitHub's API (also used to cache responses). */
        val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

        private val FAILED = setOf("failure", "timed_out", "cancelled", "action_required", "startup_failure")
    }
}
