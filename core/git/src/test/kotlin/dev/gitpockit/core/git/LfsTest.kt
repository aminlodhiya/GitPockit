package dev.gitpockit.core.git

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.gitpockit.core.git.jgit.JGitEngine
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** A tiny Git LFS server (Batch API + basic transfer) requiring HTTP basic auth. */
private class FakeLfsServer(private val user: String, private val password: String) {
    val objects = ConcurrentHashMap<String, ByteArray>()
    var uploads = 0
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}/lfs"

    init {
        server.createContext("/lfs/objects/batch") { ex ->
            if (!authorized(ex)) return@createContext ex.reply(401, "{}")
            val req = JsonParser.parseString(ex.requestBody.readBytes().decodeToString()).asJsonObject
            val op = req.get("operation").asString
            val out = JsonArray()
            for (el in req.getAsJsonArray("objects")) {
                val oid = el.asJsonObject.get("oid").asString
                val size = el.asJsonObject.get("size").asLong
                val o = JsonObject().apply { addProperty("oid", oid); addProperty("size", size) }
                val href = "http://127.0.0.1:${server.address.port}/obj/$oid"
                when {
                    op == "download" && objects.containsKey(oid) -> o.add("actions", action("download", href))
                    op == "download" -> o.add("error", JsonObject().apply { addProperty("code", 404); addProperty("message", "Object does not exist") })
                    op == "upload" && !objects.containsKey(oid) -> o.add("actions", action("upload", href))
                }
                out.add(o)
            }
            ex.reply(200, JsonObject().apply { addProperty("transfer", "basic"); add("objects", out) }.toString())
        }
        server.createContext("/obj/") { ex ->
            val oid = ex.requestURI.path.substringAfterLast('/')
            if (ex.requestHeaders.getFirst("X-Transfer") != "token-123") return@createContext ex.reply(403, "")
            when (ex.requestMethod) {
                "PUT" -> { objects[oid] = ex.requestBody.readBytes(); uploads++; ex.reply(200, "") }
                else -> objects[oid]?.let { ex.replyBytes(200, it) } ?: ex.reply(404, "")
            }
        }
        server.start()
    }

    private fun action(name: String, href: String) = JsonObject().apply {
        add(name, JsonObject().apply { addProperty("href", href); add("header", JsonObject().apply { addProperty("X-Transfer", "token-123") }) })
    }

    private fun authorized(ex: HttpExchange) =
        ex.requestHeaders.getFirst("Authorization") == "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    private fun HttpExchange.reply(code: Int, body: String) = replyBytes(code, body.toByteArray())

    private fun HttpExchange.replyBytes(code: Int, body: ByteArray) {
        sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) responseBody.use { it.write(body) } else close()
    }

    fun stop() = server.stop(0)
}

class LfsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var engine: JGitEngine
    private lateinit var remote: File
    private lateinit var server: FakeLfsServer
    private val me = GitAuthor("Me", "me@example.com")
    private val creds = GitCredentials.UserPassword("alice", "secret")
    private val big = Random(42).nextBytes(200_000)

    @Before fun setUp() = runTest {
        engine = JGitEngine(tmp.newFolder("home"))
        server = FakeLfsServer("alice", "secret")
        remote = tmp.newFolder("remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
        // Publisher: tracks *.bin with LFS and points .lfsconfig at the test server.
        clone("publisher", download = false).use { pub ->
            File(pub.workTree, ".lfsconfig").writeText("[lfs]\n\turl = ${server.url}\n")
            pub.lfsTrack("*.bin")
            File(pub.workTree, "assets").mkdirs()
            File(pub.workTree, "assets/big.bin").writeBytes(big)
            File(pub.workTree, "readme.md").writeText("hello")
            pub.stageAll()
            pub.commit("Add big file with LFS", me)
            assertTrue(pub.push(credentials = creds).isSuccess)
        }
    }

    @After fun tearDown() = server.stop()

    private suspend fun clone(name: String, download: Boolean = true, credentials: GitCredentials = creds) =
        engine.clone(CloneOptions(remote.toURI().toString(), File(tmp.root, name), credentials = credentials, downloadLfs = download))
            .also { it.setIdentity(me) }

    @Test fun `push uploads large files and commits only a pointer`() = runTest {
        assertEquals(1, server.objects.size)
        assertArrayEquals(big, server.objects.values.single())
        // The Git object for big.bin is a small pointer, not 200 KB.
        Git.open(remote).use { g ->
            val blob = g.repository.resolve("main:assets/big.bin")
            val bytes = g.repository.open(blob).bytes
            assertTrue(bytes.decodeToString(), bytes.decodeToString().startsWith("version https://git-lfs.github.com/spec/v1"))
        }
    }

    @Test fun `clone downloads real content and status is clean`() = runTest {
        clone("downloader").use { repo ->
            assertArrayEquals(big, File(repo.workTree, "assets/big.bin").readBytes())
            assertTrue(repo.status().toString(), repo.status().isClean)
            assertTrue(repo.usesLfs())
            assertEquals(listOf(LfsFile("assets/big.bin", repo.lfsFiles().single().oid, big.size.toLong(), true)), repo.lfsFiles())
        }
    }

    @Test fun `skipping download leaves clean pointers that can be fetched later`() = runTest {
        clone("lazy", download = false).use { repo ->
            val pointer = File(repo.workTree, "assets/big.bin").readText()
            assertTrue(pointer, pointer.startsWith("version https://git-lfs"))
            assertTrue("pointer files must not look modified: ${repo.status()}", repo.status().isClean)
            assertFalse(repo.lfsFiles().single().downloaded)

            assertEquals(1, repo.lfsDownload(credentials = creds))
            assertArrayEquals(big, File(repo.workTree, "assets/big.bin").readBytes())
            assertTrue(repo.lfsFiles().single().downloaded)
            assertTrue(repo.status().isClean)
        }
    }

    @Test fun `wrong credentials give an authentication error`() = runTest {
        clone("noauth", download = false).use { repo ->
            val e = runCatching { repo.lfsDownload(credentials = GitCredentials.UserPassword("alice", "wrong")) }.exceptionOrNull()
            assertTrue(e.toString(), e is GitAuthException)
        }
    }

    @Test fun `pull brings new large files down automatically`() = runTest {
        clone("follower").use { follower ->
            val newer = Random(7).nextBytes(50_000)
            clone("pusher").use { pusher ->
                File(pusher.workTree, "assets/second.bin").writeBytes(newer)
                pusher.stageAll()
                pusher.commit("Second big file", me)
                assertTrue(pusher.push(credentials = creds).isSuccess)
            }
            assertEquals(2, server.objects.size)
            follower.pull(credentials = creds)
            assertArrayEquals(newer, File(follower.workTree, "assets/second.bin").readBytes())
            assertTrue(follower.status().isClean)
        }
    }

    @Test fun `editing a large file and pushing uploads only the new version`() = runTest {
        clone("editor").use { repo ->
            val edited = Random(9).nextBytes(10_000)
            File(repo.workTree, "assets/big.bin").writeBytes(edited)
            assertEquals(setOf("assets/big.bin"), repo.status().modified)
            repo.stageAll()
            repo.commit("Edit big", me)
            val before = server.uploads
            assertTrue(repo.push(credentials = creds).isSuccess)
            assertEquals(before + 1, server.uploads)
            assertTrue(server.objects.values.any { it.contentEquals(edited) })
        }
    }
}
