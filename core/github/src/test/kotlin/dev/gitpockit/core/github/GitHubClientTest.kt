package dev.gitpockit.core.github

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GitHubClientTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitHubClient(MockEngine { req -> requests += req; handler(req) }, token = { "tok123" })

    private fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, jsonHeaders)

    private val slug = RepoSlug("octo", "notes")

    private fun issueJson(n: Int, pr: Boolean = false) = """{"number":$n,"title":"T$n","state":"open","user":{"login":"a"},
        "created_at":"2026-01-01T00:00:00Z","html_url":"https://github.com/x/$n"${if (pr) ""","pull_request":{"url":"u"}""" else ""}}"""

    @Test fun slugParsing() {
        assertEquals(RepoSlug("octo", "notes"), RepoSlug.fromRemoteUrl("https://github.com/octo/notes.git"))
        assertEquals(RepoSlug("octo", "notes"), RepoSlug.fromRemoteUrl("https://user@github.com/octo/notes"))
        assertEquals(RepoSlug("octo", "my.repo"), RepoSlug.fromRemoteUrl("git@github.com:octo/my.repo.git"))
        assertEquals(RepoSlug("octo", "notes"), RepoSlug.fromRemoteUrl("ssh://git@github.com/octo/notes.git"))
        assertNull(RepoSlug.fromRemoteUrl("https://gitlab.com/octo/notes.git"))
        assertNull(RepoSlug.fromRemoteUrl("https://github.com/octo"))
    }

    @Test fun sendsAuthHeadersAndParams() = runTest {
        val c = client { ok("""[{"full_name":"octo/notes","name":"notes","owner":{"login":"octo"},"clone_url":"c","ssh_url":"s","html_url":"h","private":true}]""") }
        val repos = c.repos(page = 2)
        assertEquals("octo/notes", repos.single().fullName)
        assertTrue(repos.single().private)
        val r = requests.single()
        assertEquals("Bearer tok123", r.headers["Authorization"])
        assertEquals("application/vnd.github+json", r.headers["Accept"])
        assertEquals("/user/repos", r.url.encodedPath)
        assertEquals("2", r.url.parameters["page"])
        assertEquals("updated", r.url.parameters["sort"])
    }

    @Test fun issueListExcludesPullRequests() = runTest {
        val c = client { ok("[${issueJson(1)},${issueJson(2, pr = true)},${issueJson(3)}]") }
        assertEquals(listOf(1, 3), c.issues(slug).map { it.number })
        assertEquals("open", requests.single().url.parameters["state"])
    }

    @Test fun searchBuildsQuery() = runTest {
        val c = client { ok("""{"items":[${issueJson(7)}]}""") }
        assertEquals(7, c.searchIssues(slug, "crash", state = "closed").single().number)
        assertEquals("repo:octo/notes is:issue is:closed crash", requests.single().url.parameters["q"])
    }

    @Test fun createIssueCommentMergeAndRerunSendBodies() = runTest {
        val c = client { req ->
            when {
                req.url.encodedPath.endsWith("/merge") -> ok("""{"merged":true,"sha":"abc"}""")
                req.url.encodedPath.endsWith("/comments") -> ok("""{"id":1,"body":"hi","user":{"login":"a"},"created_at":"2026-01-01T00:00:00Z"}""")
                req.url.encodedPath.contains("/actions/") -> respond("", HttpStatusCode.Created)
                else -> ok(issueJson(9))
            }
        }
        c.createIssue(slug, "Bug", "It breaks")
        c.comment(slug, 9, "hi")
        assertTrue(c.merge(slug, 4, MergeMethod.SQUASH).merged)
        c.rerun(slug, 55, failedOnly = true)

        val bodies = requests.map { it.method to it.body.toByteArray().decodeToString() }
        assertEquals(HttpMethod.Post, bodies[0].first)
        assertTrue(bodies[0].second, bodies[0].second.contains("\"title\":\"Bug\"") && bodies[0].second.contains("\"body\":\"It breaks\""))
        assertTrue(bodies[1].second.contains("\"body\":\"hi\""))
        assertEquals(HttpMethod.Put, bodies[2].first)
        assertTrue(bodies[2].second, bodies[2].second.contains("\"merge_method\":\"squash\""))
        assertEquals("/repos/octo/notes/actions/runs/55/rerun-failed-jobs", requests[3].url.encodedPath)
    }

    @Test fun errorsAreReadable() = runTest {
        val cases = mapOf(
            HttpStatusCode.Unauthorized to GitHubAuthException::class.java,
            HttpStatusCode.NotFound to GitHubException::class.java,
        )
        for ((status, type) in cases) {
            requests.clear()
            val e = runCatching { client { respond("""{"message":"x"}""", status, jsonHeaders) }.user() }.exceptionOrNull()
            assertTrue("$status -> $e", type.isInstance(e))
        }
        val validation = runCatching {
            client { respond("""{"message":"Validation Failed"}""", HttpStatusCode.UnprocessableEntity, jsonHeaders) }.createIssue(slug, "", "")
        }.exceptionOrNull() as GitHubException
        assertEquals("Validation Failed", validation.message)
        assertEquals(422, validation.status)

        val reset = System.currentTimeMillis() / 1000 + 600
        val limited = runCatching {
            client {
                respond("{}", HttpStatusCode.Forbidden, headersOf("x-ratelimit-remaining" to listOf("0"), "x-ratelimit-reset" to listOf("$reset")))
            }.user()
        }.exceptionOrNull()!!
        assertTrue(limited.message, limited.message!!.contains("rate limit") && limited.message!!.contains("min"))

        val offline = runCatching { client { throw IOException("no route") }.user() }.exceptionOrNull()
        assertTrue(offline is GitHubNetworkException)
    }

    @Test fun ciStateFromCheckRuns() = runTest {
        suspend fun state(json: String) = client { ok(json) }.ciState(slug, "sha")
        assertEquals(CiState.NONE, state("""{"check_runs":[]}"""))
        assertEquals(CiState.PENDING, state("""{"check_runs":[{"name":"a","status":"completed","conclusion":"success"},{"name":"b","status":"in_progress"}]}"""))
        assertEquals(CiState.FAILURE, state("""{"check_runs":[{"name":"a","status":"completed","conclusion":"failure"}]}"""))
        assertEquals(CiState.SUCCESS, state("""{"check_runs":[{"name":"a","status":"completed","conclusion":"success"},{"name":"b","status":"completed","conclusion":"skipped"}]}"""))
    }

    @Test fun workflowRunsAndJobs() = runTest {
        val c = client { req ->
            if (req.url.encodedPath.endsWith("/jobs")) {
                ok("""{"jobs":[{"id":2,"name":"build","status":"completed","conclusion":"success","steps":[{"name":"Checkout","conclusion":"success","number":1}]}]}""")
            } else {
                ok("""{"workflow_runs":[{"id":1,"name":"CI","head_sha":"abc","status":"completed","conclusion":"failure","run_number":12,"created_at":"2026-01-01T00:00:00Z","html_url":"h"}]}""")
            }
        }
        val run = c.workflowRuns(slug, branch = "main").single()
        assertEquals("failure", run.conclusion)
        assertEquals("main", requests.single().url.parameters["branch"])
        assertEquals("Checkout", c.runJobs(slug, 1).single().steps.single().name)
    }
}
