package dev.gitpockit.core.git

import dev.gitpockit.core.git.jgit.JGitEngine
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiffAndHistoryTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var engine: JGitEngine
    private lateinit var repo: GitRepository
    private val me = GitAuthor("Me", "me@example.com")

    @Before fun setUp() = runTest {
        engine = JGitEngine(tmp.newFolder("appdata"))
        repo = engine.init(tmp.newFolder("work"))
    }

    private fun write(path: String, text: String) = File(repo.workTree, path).apply { parentFile.mkdirs() }.writeText(text)

    private suspend fun commitAll(msg: String): CommitInfo { repo.stageAll(); return repo.commit(msg, me) }

    @Test fun `commit detail lists added modified deleted and renamed files`() = runTest {
        write("a.txt", "one\ntwo\nthree\n")
        write("b.txt", "bye\n")
        write("c.txt", "a fairly long line so rename detection has content to match\n".repeat(5))
        val root = commitAll("root")
        assertEquals(setOf("a.txt", "b.txt", "c.txt"), repo.commitDetail(root.id).changes.map { it.path }.toSet())

        write("a.txt", "one\nTWO\nthree\n")
        File(repo.workTree, "b.txt").delete()
        File(repo.workTree, "c.txt").renameTo(File(repo.workTree, "d.txt"))
        write("new.txt", "hello\n")
        val second = commitAll("second")

        val byPath = repo.commitDetail(second.id).changes.associateBy { it.path }
        assertEquals(ChangeType.MODIFIED, byPath.getValue("a.txt").type)
        assertEquals(ChangeType.DELETED, byPath.getValue("b.txt").type)
        assertEquals(ChangeType.ADDED, byPath.getValue("new.txt").type)
        assertEquals(ChangeType.RENAMED, byPath.getValue("d.txt").type)
        assertEquals("c.txt", byPath.getValue("d.txt").oldPath)
    }

    @Test fun `commit file diff has line numbers and counts`() = runTest {
        write("a.txt", "one\ntwo\nthree\n")
        commitAll("root")
        write("a.txt", "one\nTWO\nthree\nfour\n")
        val c = commitAll("edit")

        val diff = repo.commitFileDiff(c.id, "a.txt")
        assertFalse(diff.isBinary)
        assertEquals(2, diff.additions)
        assertEquals(1, diff.deletions)
        val lines = diff.hunks.single().lines
        val removed = lines.single { it.type == DiffLineType.REMOVED }
        assertEquals("two", removed.text)
        assertEquals(2, removed.oldLine)
        assertNull(removed.newLine)
        val added = lines.filter { it.type == DiffLineType.ADDED }
        assertEquals(listOf("TWO" to 2, "four" to 4), added.map { it.text to it.newLine })
    }

    @Test fun `binary files are flagged`() = runTest {
        File(repo.workTree, "img.bin").writeBytes(byteArrayOf(0, 1, 2, 0, 3))
        val c = commitAll("bin")
        assertTrue(repo.commitFileDiff(c.id, "img.bin").isBinary)
    }

    @Test fun `working diffs separate staged from unstaged`() = runTest {
        write("a.txt", "1\n")
        commitAll("root")
        write("a.txt", "2\n")
        repo.stage(listOf("a.txt"))
        write("a.txt", "3\n")

        val staged = repo.workingFileDiff("a.txt", WorkingDiffMode.STAGED)
        assertEquals(listOf("1", "2"), staged.hunks.single().lines.map { it.text })
        val unstaged = repo.workingFileDiff("a.txt", WorkingDiffMode.UNSTAGED)
        assertEquals(listOf("2", "3"), unstaged.hunks.single().lines.map { it.text })
    }

    @Test fun `untracked file diff shows whole file as added`() = runTest {
        write("a.txt", "1\n")
        commitAll("root")
        write("new.md", "x\ny\n")
        val d = repo.workingFileDiff("new.md", WorkingDiffMode.UNSTAGED)
        assertEquals(ChangeType.ADDED, d.type)
        assertEquals(2, d.additions)
    }

    @Test fun `staged diff works before the first commit`() = runTest {
        write("a.txt", "1\n")
        repo.stage(listOf("a.txt"))
        assertEquals(ChangeType.ADDED, repo.workingFileDiff("a.txt", WorkingDiffMode.STAGED).type)
    }

    @Test fun `discard restores tracked files and deletes untracked ones`() = runTest {
        write("a.txt", "orig\n")
        write("gone.txt", "keep me\n")
        commitAll("root")
        write("a.txt", "changed\n")
        repo.stage(listOf("a.txt"))
        File(repo.workTree, "gone.txt").delete()
        write("junk.txt", "junk")
        write("staged-new.txt", "s")
        repo.stage(listOf("staged-new.txt"))

        repo.discardChanges(listOf("a.txt", "gone.txt", "junk.txt", "staged-new.txt"))

        assertEquals("orig\n", File(repo.workTree, "a.txt").readText())
        assertEquals("keep me\n", File(repo.workTree, "gone.txt").readText())
        assertFalse(File(repo.workTree, "junk.txt").exists())
        assertFalse(File(repo.workTree, "staged-new.txt").exists())
        assertTrue(repo.status().isClean)
    }

    @Test fun `log pages with skip`() = runTest {
        repeat(5) { i -> write("f.txt", "$i"); commitAll("c$i") }
        assertEquals(listOf("c4", "c3"), repo.log(maxCount = 2).map { it.title })
        assertEquals(listOf("c2", "c1"), repo.log(maxCount = 2, skip = 2).map { it.title })
        assertEquals(listOf("c0"), repo.log(maxCount = 2, skip = 4).map { it.title })
    }

    @Test fun `rename remote moves tracking refs and upstream config`() = runTest {
        val remoteDir = tmp.newFolder("remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remoteDir).call().close()
        val clone = engine.clone(CloneOptions(remoteDir.toURI().toString(), File(tmp.root, "c")))
        clone.use { c ->
            File(c.workTree, "f").writeText("1")
            c.stageAll(); c.commit("init", me)
            assertTrue(c.push().isSuccess)
            c.fetch()

            c.renameRemote("origin", "upstream")

            assertEquals(listOf("upstream"), c.remotes().map { it.name })
            val remoteBranches = c.branches().filter { it.isRemote }.map { it.name }
            assertTrue(remoteBranches.toString(), "upstream/main" in remoteBranches)
            assertFalse(remoteBranches.any { it.startsWith("origin/") })
            assertEquals(0 to 0, c.aheadBehind())
            assertEquals(PullResult.UpToDate, c.pull(remote = "upstream"))
        }
    }

    @Test fun `ssh keys generate and round-trip`() {
        for (type in SshKeys.Type.entries) {
            val key = SshKeys.generate(type, "test@gitpockit")
            assertTrue(key.publicKey, key.publicKey.startsWith(if (type == SshKeys.Type.ED25519) "ssh-ed25519 " else "ssh-rsa "))
            assertTrue(key.publicKey.endsWith(" test@gitpockit"))
            assertTrue(key.fingerprint.startsWith("SHA256:"))
            val inspected = SshKeys.inspect(key.privateKey, null, "test@gitpockit")
            assertEquals(key.publicKey, inspected.publicKey)
            assertEquals(key.fingerprint, inspected.fingerprint)
        }
    }

    @Test fun `garbage ssh key is rejected`() {
        val e = runCatching { SshKeys.inspect("not a key".toByteArray(), null) }.exceptionOrNull()
        assertTrue(e is GitException)
    }
}
