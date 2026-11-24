package dev.gitpockit.core.git

import dev.gitpockit.core.git.jgit.JGitEngine
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A folder added as a repository may contain folders that are Git repositories of their own. */
class NestedRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val me = GitAuthor("Me", "me@x")

    private fun files(dir: File) =
        dir.walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()

    private fun folder(name: String) = tmp.newFolder(name).also { dir ->
        File(dir, "notes.md").writeText("hello")
        File(dir, "sub/a.txt").apply { parentFile.mkdirs() }.writeText("a")
    }

    private fun emptyNestedRepo(dir: File) {
        Git.init().setDirectory(File(dir, "plugin")).call().close()
        File(dir, "plugin/p.js").writeText("p")
    }

    private fun committedPaths(dir: File) = Git.open(dir).use { g ->
        val head = g.repository.resolve("HEAD")
        org.eclipse.jgit.treewalk.TreeWalk(g.repository).use { tw ->
            tw.addTree(g.repository.parseCommit(head).tree); tw.isRecursive = true
            buildList { while (tw.next()) add(tw.pathString) }.sorted()
        }
    }

    @Test fun nestedRepoWithoutCommitsIsSkipped() = runTest {
        val dir = folder("unborn")
        emptyNestedRepo(dir)
        val before = files(dir)
        val repo = JGitEngine(tmp.newFolder("home1")).init(dir, "main")
        assertEquals(listOf("plugin"), repo.nestedRepositories())

        repo.stageAll()
        repo.status() // used to throw "Missing unknown 0000000…"
        repo.commit("first", me)

        assertEquals(listOf("notes.md", "sub/a.txt"), committedPaths(dir))
        assertTrue(repo.status().added.isEmpty())
        assertEquals(before, files(dir))
        repo.close()
    }

    @Test fun nestedRepoWithCommitsIsSkipped() = runTest {
        val dir = folder("born")
        Git.init().setDirectory(File(dir, "plugin")).call().use { g ->
            File(dir, "plugin/p.js").writeText("p")
            g.add().addFilepattern(".").call()
            g.commit().setMessage("x").setAuthor("a", "a@x").setCommitter("a", "a@x").setSign(false).call()
        }
        val repo = JGitEngine(tmp.newFolder("home2")).init(dir, "main")
        repo.stage(listOf("."))
        repo.commit("first", me)
        assertEquals(listOf("notes.md", "sub/a.txt"), committedPaths(dir))
        repo.close()
    }

    @Test fun stagingOrDiscardingNestedRepoExplicitlyFailsClearly() = runTest {
        val dir = folder("explicit")
        emptyNestedRepo(dir)
        val repo = JGitEngine(tmp.newFolder("home3")).init(dir, "main")
        val stage = runCatching { repo.stage(listOf("plugin")) }.exceptionOrNull()
        assertTrue(stage is GitException && "plugin/.git" in stage.message.orEmpty())
        assertTrue(runCatching { repo.discardChanges(listOf("plugin")) }.exceptionOrNull() is GitException)
        assertTrue(File(dir, "plugin/p.js").isFile)
        repo.close()
    }

    @Test fun brokenIndexIsRepairedOnOpen() = runTest {
        val dir = folder("broken")
        emptyNestedRepo(dir)
        // What older versions did: record the nested repository with an all-zero id.
        Git.init().setDirectory(dir).call().use { it.add().addFilepattern(".").call() }
        assertTrue(runCatching { Git.open(dir).use { it.status().call() } }.isFailure)
        val before = files(dir)

        val repo = JGitEngine(tmp.newFolder("home4")).open(dir)
        val status = repo.status()
        assertFalse("plugin" in status.added)
        assertTrue("notes.md" in status.added)
        assertEquals(before, files(dir))
        repo.close()
    }

    @Test fun submodulesAreNotTreatedAsNested() = runTest {
        val sub = tmp.newFolder("subsrc")
        Git.init().setDirectory(sub).call().use { g ->
            File(sub, "s.txt").writeText("s")
            g.add().addFilepattern(".").call()
            g.commit().setMessage("s").setAuthor("a", "a@x").setCommitter("a", "a@x").setSign(false).call()
        }
        val dir = folder("withsub")
        Git.init().setDirectory(dir).call().use { g ->
            g.submoduleAdd().setURI(sub.toURI().toString()).setPath("lib").call().close()
        }
        val repo = JGitEngine(tmp.newFolder("home5")).open(dir)
        assertEquals(emptyList<String>(), repo.nestedRepositories())
        repo.close()
    }
}
