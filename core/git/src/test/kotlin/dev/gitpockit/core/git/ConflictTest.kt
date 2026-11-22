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

class ConflictTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var engine: JGitEngine
    private lateinit var remote: File
    private val me = GitAuthor("Me", "me@x")

    @Before fun setUp() {
        engine = JGitEngine(tmp.newFolder("home"))
        remote = tmp.newFolder("remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
    }

    private suspend fun clone(name: String) = engine.clone(CloneOptions(remote.toURI().toString(), File(tmp.root, name)))

    private suspend fun GitRepository.edit(path: String, text: String?) {
        val f = File(workTree, path)
        if (text == null) f.delete() else f.apply { parentFile.mkdirs() }.writeText(text)
        stageAll()
        commit("edit $path", me)
    }

    /** Leaves clone "b" mid-merge with doc.md conflicting (local "bob", remote "alice"). */
    private suspend fun conflicted(): GitRepository {
        clone("a").use { it.edit("doc.md", "base\n"); it.push() }
        val b = clone("b")
        clone("a2").use { it.edit("doc.md", "alice\n"); it.push() }
        b.edit("doc.md", "bob\n")
        assertTrue(b.pull() is PullResult.Conflicts)
        return b
    }

    @Test fun `conflict versions expose base ours and theirs`() = runTest {
        conflicted().use { b ->
            assertTrue(b.isMerging())
            val v = b.conflictVersions("doc.md")
            assertEquals("base\n", v.base!!.decodeToString())
            assertEquals("bob\n", v.ours!!.decodeToString())
            assertEquals("alice\n", v.theirs!!.decodeToString())
        }
    }

    @Test fun `resolving and committing finishes the merge and can be pushed`() = runTest {
        conflicted().use { b ->
            b.resolveConflict("doc.md", "bob and alice\n".toByteArray())
            assertTrue(b.status().conflicting.isEmpty())
            b.commit("Merge", me)
            assertFalse(b.isMerging())
            assertEquals(2, b.log(maxCount = 1).single().parentIds.size)
            assertTrue(b.push().isSuccess)
        }
    }

    @Test fun `resolving by deleting removes the file`() = runTest {
        conflicted().use { b ->
            b.resolveConflict("doc.md", null)
            b.commit("Merge", me)
            assertFalse(File(b.workTree, "doc.md").exists())
            assertTrue(b.status().isClean)
        }
    }

    @Test fun `modify-delete conflict has a missing side`() = runTest {
        clone("a").use { it.edit("doc.md", "base\n"); it.push() }
        clone("b").use { b ->
            clone("a2").use { it.edit("doc.md", null); it.push() }
            b.edit("doc.md", "changed\n")
            assertTrue(b.pull() is PullResult.Conflicts)
            val v = b.conflictVersions("doc.md")
            assertEquals("changed\n", v.ours!!.decodeToString())
            assertNull(v.theirs)
        }
    }

    @Test fun `abort merge restores local commit`() = runTest {
        conflicted().use { b ->
            b.abortMerge()
            assertFalse(b.isMerging())
            assertTrue(b.status().isClean)
            assertEquals("bob\n", File(b.workTree, "doc.md").readText())
        }
    }
}
