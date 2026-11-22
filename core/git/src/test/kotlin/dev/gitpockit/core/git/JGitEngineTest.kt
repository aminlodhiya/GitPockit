package dev.gitpockit.core.git

import dev.gitpockit.core.git.jgit.JGitEngine
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JGitEngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var engine: JGitEngine
    private lateinit var remote: File
    private val alice = GitAuthor("Alice", "alice@example.com")
    private val bob = GitAuthor("Bob", "bob@example.com")

    @Before fun setUp() {
        engine = JGitEngine(tmp.newFolder("appdata"))
        remote = tmp.newFolder("remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
    }

    private suspend fun cloneAs(name: String): GitRepository =
        engine.clone(CloneOptions(url = remote.toURI().toString(), directory = File(tmp.root, name)))

    private suspend fun GitRepository.writeCommit(file: String, content: String, author: GitAuthor, msg: String = "edit $file") {
        File(workTree, file).apply { parentFile.mkdirs() }.writeText(content)
        stageAll()
        commit(msg, author)
    }

    @Test fun `init creates a repository on the requested branch`() = runTest {
        val dir = tmp.newFolder("fresh")
        engine.init(dir, "trunk").use { repo ->
            assertTrue(engine.isRepository(dir))
            repo.writeCommit("a.txt", "hi", alice)
            assertEquals("trunk", repo.currentBranch())
            assertEquals(1, repo.log().size)
        }
    }

    @Test fun `clone commit push then pull in second clone`() = runTest {
        cloneAs("a").use { a ->
            a.writeCommit("notes/hello.md", "# Hello", alice, "Add hello")
            assertTrue(a.push().isSuccess)
        }
        cloneAs("b").use { b ->
            assertEquals("Add hello", b.log().first().title)
            assertEquals("# Hello", File(b.workTree, "notes/hello.md").readText())
        }
    }

    @Test fun `cloning an empty remote tracks origin so the first sync works`() = runTest {
        cloneAs("a").use { a ->
            assertEquals("main", a.currentBranch())
            assertEquals(listOf("origin"), a.remotes().map { it.name })
            assertEquals(PullResult.UpToDate, a.pull())
            a.writeCommit("first.md", "1", alice)
            assertTrue(a.push().isSuccess)
            assertEquals(0 to 0, a.aheadBehind())
        }
        cloneAs("b").use { b -> assertEquals(1, b.log().size) }
    }

    @Test fun `status reports untracked modified and staged deletions`() = runTest {
        cloneAs("a").use { a ->
            a.writeCommit("keep.txt", "1", alice)
            a.writeCommit("gone.txt", "1", alice)
            File(a.workTree, "keep.txt").writeText("2")
            File(a.workTree, "gone.txt").delete()
            File(a.workTree, "new.txt").writeText("n")

            val before = a.status()
            assertEquals(setOf("keep.txt"), before.modified)
            assertEquals(setOf("gone.txt"), before.missing)
            assertEquals(setOf("new.txt"), before.untracked)

            a.stage(listOf("."))
            val after = a.status()
            assertEquals(setOf("gone.txt"), after.removed)
            assertEquals(setOf("new.txt"), after.added)
            assertEquals(setOf("keep.txt"), after.changed)
            assertTrue(after.hasStagedChanges)

            a.unstage(listOf("new.txt"))
            assertEquals(setOf("new.txt"), a.status().untracked)
        }
    }

    @Test fun `pull fast-forwards and reports ahead-behind`() = runTest {
        cloneAs("a").use { a -> a.writeCommit("f.txt", "1", alice); a.push() }
        cloneAs("b").use { b ->
            cloneAs("a2").use { a ->
                a.writeCommit("f.txt", "2", alice)
                a.push()
            }
            b.fetch()
            assertEquals(0 to 1, b.aheadBehind())
            assertEquals(PullResult.FastForward, b.pull())
            assertEquals("2", File(b.workTree, "f.txt").readText())
            assertEquals(PullResult.UpToDate, b.pull())
        }
    }

    @Test fun `concurrent edits to the same line report conflicts`() = runTest {
        cloneAs("a").use { a -> a.writeCommit("doc.md", "base\n", alice); a.push() }
        cloneAs("b").use { b ->
            cloneAs("a2").use { a -> a.writeCommit("doc.md", "alice\n", alice); a.push() }
            b.writeCommit("doc.md", "bob\n", bob)
            val rejected = b.push()
            assertFalse("non-fast-forward push must be rejected", rejected.isSuccess)
            val result = b.pull()
            assertEquals(PullResult.Conflicts(setOf("doc.md")), result)
            assertEquals(setOf("doc.md"), b.status().conflicting)
        }
    }

    @Test fun `non-conflicting edits merge cleanly`() = runTest {
        cloneAs("a").use { a -> a.writeCommit("x.txt", "x", alice); a.push() }
        cloneAs("b").use { b ->
            cloneAs("a2").use { a -> a.writeCommit("y.txt", "y", alice); a.push() }
            b.writeCommit("z.txt", "z", bob)
            assertEquals(PullResult.Merged, b.pull())
            assertTrue(b.push().isSuccess)
            assertTrue(File(b.workTree, "y.txt").exists())
        }
    }

    @Test fun `rebase pull keeps history linear`() = runTest {
        cloneAs("a").use { a -> a.writeCommit("x.txt", "x", alice); a.push() }
        cloneAs("b").use { b ->
            cloneAs("a2").use { a -> a.writeCommit("y.txt", "y", alice); a.push() }
            b.writeCommit("z.txt", "z", bob)
            assertEquals(PullResult.Merged, b.pull(rebase = true))
            assertTrue(b.log().all { it.parentIds.size <= 1 })
        }
    }

    @Test fun `branch create checkout rename delete`() = runTest {
        cloneAs("a").use { a ->
            a.writeCommit("f", "1", alice)
            a.push()
            a.createBranch("feature", checkout = true)
            assertEquals("feature", a.currentBranch())
            a.checkout("main")
            a.renameBranch("feature", "feat")
            assertTrue(a.branches(includeRemote = false).any { it.name == "feat" })
            a.deleteBranch("feat")
            assertFalse(a.branches(includeRemote = false).any { it.name == "feat" })
            assertTrue(a.branches().any { it.isRemote && it.name == "origin/main" })
        }
    }

    @Test fun `checkout of remote-only branch creates tracking branch`() = runTest {
        cloneAs("a").use { a ->
            a.writeCommit("f", "1", alice)
            a.push()
            a.createBranch("topic", checkout = true)
            a.writeCommit("t", "t", alice)
            a.push()
        }
        cloneAs("b").use { b ->
            // clone only checks out the default branch
            b.checkout("topic")
            assertEquals("topic", b.currentBranch())
            assertTrue(File(b.workTree, "t").exists())
        }
    }

    @Test fun `init with remote and upstream can push to empty remote`() = runTest {
        val dir = tmp.newFolder("local")
        engine.init(dir, "main").use { repo ->
            repo.addRemote("origin", remote.toURI().toString())
            repo.setUpstream("main", "origin")
            repo.writeCommit("a.md", "a", alice)
            assertEquals(PullResult.UpToDate, repo.pull())
            assertTrue(repo.push().isSuccess)
            assertEquals(0 to 0, repo.aheadBehind())
        }
    }

    @Test fun `unmerged branch delete needs force`() = runTest {
        cloneAs("a").use { a ->
            a.writeCommit("f", "1", alice)
            a.createBranch("wip", checkout = true)
            a.writeCommit("g", "2", alice)
            a.checkout("main")
            val e = runCatching { a.deleteBranch("wip") }.exceptionOrNull()
            assertTrue(e.toString(), e is GitBranchNotMergedException)
            a.deleteBranch("wip", force = true)
            assertFalse(a.branches(includeRemote = false).any { it.name == "wip" })
        }
    }

    @Test fun `checkout with conflicting local changes explains the problem`() = runTest {
        cloneAs("a").use { a ->
            a.writeCommit("f", "main", alice)
            a.createBranch("other", checkout = true)
            a.writeCommit("f", "other", alice)
            a.checkout("main")
            File(a.workTree, "f").writeText("dirty")
            val e = runCatching { a.checkout("other") }.exceptionOrNull()
            assertTrue(e.toString(), e is GitLocalChangesException)
            assertEquals("dirty", File(a.workTree, "f").readText())
        }
    }

    @Test fun `remote add set-url remove`() = runTest {
        cloneAs("a").use { a ->
            a.addRemote("backup", "https://example.com/x.git")
            a.setRemoteUrl("backup", "https://example.com/y.git")
            assertEquals(listOf("https://example.com/y.git"), a.remotes().first { it.name == "backup" }.fetchUrls)
            a.removeRemote("backup")
            assertEquals(listOf("origin"), a.remotes().map { it.name })
        }
    }

    @Test fun `failed clone cleans up directory and maps error`() = runTest {
        val target = File(tmp.root, "bad")
        val error = runCatching {
            engine.clone(CloneOptions(url = File(tmp.root, "missing.git").toURI().toString(), directory = target))
        }.exceptionOrNull()
        assertTrue(error is GitException)
        assertFalse(target.exists())
    }

    @Test fun `opening a non repository fails`() = runTest {
        val error = runCatching { engine.open(tmp.newFolder("plain")) }.exceptionOrNull()
        assertTrue(error is GitException)
    }
}
