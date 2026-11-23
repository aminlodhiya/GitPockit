package dev.gitpockit.core.git.jgit.lfs

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.gitpockit.core.git.GitAuthException
import dev.gitpockit.core.git.GitCredentials
import dev.gitpockit.core.git.GitException
import dev.gitpockit.core.git.GitNetworkException
import org.eclipse.jgit.lfs.Lfs
import org.eclipse.jgit.lfs.LfsPointer
import org.eclipse.jgit.lfs.lib.LongObjectId
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Where to talk to the LFS server, and the headers that authenticate us. */
internal data class LfsEndpoint(val url: String, val headers: Map<String, String>)

/**
 * Minimal Git LFS client (Batch API, "basic" transfer), as implemented by GitHub, GitLab, Gitea
 * and others: https://github.com/git-lfs/git-lfs/blob/main/docs/api/batch.md
 */
internal object LfsTransfer {
    private const val MEDIA_TYPE = "application/vnd.git-lfs+json"

    /**
     * Resolves the LFS endpoint for [remote]: `lfs.url` / `remote.<name>.lfsurl` in the repo
     * config or a committed `.lfsconfig`, otherwise derived from the remote URL. For SSH remotes,
     * `git-lfs-authenticate` is run over the SSH key to obtain the URL and auth header.
     */
    fun endpoint(repo: Repository, remote: String, operation: String, credentials: GitCredentials, ssh: SshSessionFactory?): LfsEndpoint {
        val lfsConfig = Config(repo.config).also { cfg ->
            File(repo.workTree, ".lfsconfig").takeIf { it.isFile }?.let { runCatching { cfg.fromText(it.readText()) } }
        }
        val explicit = repo.config.getString("remote", remote, "lfsurl")
            ?: repo.config.getString("lfs", null, "url")
            ?: lfsConfig.getString("remote", remote, "lfsurl")
            ?: lfsConfig.getString("lfs", null, "url")
        val remoteUrl = repo.config.getString("remote", remote, "url") ?: throw GitException("Remote \"$remote\" not found")
        val uri = URIish(remoteUrl)
        val isSsh = uri.scheme == null && uri.host != null || uri.scheme == "ssh"

        if (explicit == null && isSsh && credentials is GitCredentials.SshKey && ssh != null) {
            sshAuthenticate(uri, operation, ssh)?.let { return it }
        }
        val url = explicit ?: run {
            val host = uri.host ?: throw GitException("Can't find the LFS server for $remoteUrl")
            val path = uri.path.trim('/').let { if (it.endsWith(".git")) it else "$it.git" }
            val base = if (isSsh) "https://$host/$path" else "${uri.scheme}://$host${if (uri.port > 0) ":${uri.port}" else ""}/$path"
            "$base/info/lfs"
        }
        return LfsEndpoint(url.trimEnd('/'), basicAuth(credentials))
    }

    private fun basicAuth(credentials: GitCredentials): Map<String, String> = when (credentials) {
        is GitCredentials.UserPassword ->
            mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("${credentials.username}:${credentials.password}".toByteArray()))
        else -> emptyMap()
    }

    /** Runs `git-lfs-authenticate <path> <operation>` on the SSH server (GitHub/GitLab support it). */
    private fun sshAuthenticate(uri: URIish, operation: String, ssh: SshSessionFactory): LfsEndpoint? = runCatching {
        val session = ssh.getSession(uri, null, FS.DETECTED, 30_000)
        try {
            val process = session.exec("git-lfs-authenticate ${uri.path.trimStart('/')} $operation", 30_000)
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor(30, TimeUnit.SECONDS)
            val json = JsonParser.parseString(out).asJsonObject
            val headers = json.getAsJsonObject("header")?.entrySet()?.associate { it.key to it.value.asString }.orEmpty()
            LfsEndpoint(json.get("href").asString.trimEnd('/'), headers)
        } finally {
            ssh.releaseSession(session)
        }
    }.getOrNull()

    data class Action(val href: String, val headers: Map<String, String>)

    data class BatchItem(val oid: String, val size: Long, val action: Action?, val verify: Action?, val error: String?)

    fun batch(endpoint: LfsEndpoint, operation: String, pointers: Collection<LfsPointer>): List<BatchItem> {
        val body = JsonObject().apply {
            addProperty("operation", operation)
            add("transfers", JsonArray().apply { add("basic") })
            add(
                "objects",
                JsonArray().apply {
                    pointers.forEach { p -> add(JsonObject().apply { addProperty("oid", p.oid.name()); addProperty("size", p.size) }) }
                },
            )
        }.toString().toByteArray()
        val conn = open("${endpoint.url}/objects/batch", "POST", endpoint.headers + mapOf("Accept" to MEDIA_TYPE, "Content-Type" to MEDIA_TYPE))
        conn.doOutput = true
        conn.outputStream.use { it.write(body) }
        val text = readResponse(conn, "LFS batch request")
        return JsonParser.parseString(text).asJsonObject.getAsJsonArray("objects").map { el ->
            val o = el.asJsonObject
            val actions = o.getAsJsonObject("actions")
            BatchItem(
                oid = o.get("oid").asString,
                size = o.get("size").asLong,
                action = actions?.getAsJsonObject(operation)?.toAction(),
                verify = actions?.getAsJsonObject("verify")?.toAction(),
                error = o.getAsJsonObject("error")?.get("message")?.asString,
            )
        }
    }

    /** Downloads into the repo's LFS store, verifying the SHA-256 and size. */
    fun download(repo: Repository, item: BatchItem) {
        val action = item.action ?: throw GitException(item.error ?: "The LFS server has no download for ${item.oid.take(12)}")
        val lfs = Lfs(repo)
        val target = lfs.getMediaFile(LongObjectId.fromString(item.oid))
        Files.createDirectories(lfs.lfsTmpDir) // missing in a fresh clone
        val tmp = lfs.createTmpFile()
        try {
            val conn = open(action.href, "GET", action.headers)
            check(conn, "LFS download")
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            conn.inputStream.use { input ->
                Files.newOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        size += n
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != item.oid || size != item.size) throw GitException("Downloaded LFS file is corrupt (${item.oid.take(12)})")
            Files.createDirectories(target.parent)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun upload(repo: Repository, item: BatchItem) {
        val action = item.action ?: return // the server already has it
        val file = Lfs(repo).getMediaFile(LongObjectId.fromString(item.oid))
        val conn = open(action.href, "PUT", action.headers + mapOf("Content-Type" to "application/octet-stream"))
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(item.size)
        conn.outputStream.use { out -> Files.newInputStream(file).use { it.copyTo(out) } }
        check(conn, "LFS upload")
        item.verify?.let { v ->
            val verify = open(v.href, "POST", v.headers + mapOf("Accept" to MEDIA_TYPE, "Content-Type" to MEDIA_TYPE))
            verify.doOutput = true
            verify.outputStream.use { it.write("""{"oid":"${item.oid}","size":${item.size}}""".toByteArray()) }
            check(verify, "LFS verify")
        }
    }

    private fun JsonObject.toAction() = Action(
        href = get("href").asString,
        headers = getAsJsonObject("header")?.entrySet()?.associate { it.key to it.value.asString }.orEmpty(),
    )

    private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 120_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
    } catch (e: IOException) {
        throw GitNetworkException("Can't reach the LFS server: ${e.message}", e)
    }

    private fun check(conn: HttpURLConnection, what: String) {
        val code = try {
            conn.responseCode
        } catch (e: IOException) {
            throw GitNetworkException("$what failed: ${e.message}", e)
        }
        when {
            code == 401 || code == 403 -> throw GitAuthException("$what: not authorized. Check the repository's credential.")
            code !in 200..299 -> throw GitException("$what failed (HTTP $code)")
        }
    }

    private fun readResponse(conn: HttpURLConnection, what: String): String {
        check(conn, what)
        return conn.inputStream.bufferedReader().readText()
    }
}
