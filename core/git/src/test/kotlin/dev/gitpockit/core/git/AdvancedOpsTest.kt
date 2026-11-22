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

class AdvancedOpsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var engine: JGitEngine
    private lateinit var repo: GitRepository
    private val me = GitAuthor("Me", "me@example.com")

    @Before fun setUp() = runTest {
        engine = JGitEngine(tmp.newFolder("home"))
        repo = engine.init(tmp.newFolder("work"))
        repo.setIdentity(me)
        commit("f.txt", "base\n", "root")
    }

    private fun file(path: String) = File(repo.workTree, path)

    private suspend fun commit(path: String, text: String, msg: String = "edit $path"): CommitInfo {
        file(path).apply { parentFile.mkdirs() }.writeText(text)
        repo.stageAll()
        return repo.commit(msg, me)
    }

    /** main: base→main-change ; feature: base→feature-change, both editing f.txt. */
    private suspend fun divergedOnSameLine() {
        repo.createBranch("feature", checkout = true)
        commit("f.txt", "feature\n", "feature edit")
        repo.checkout("main")
        commit("f.txt", "main\n", "main edit")
    }

    // ---- merge

    @Test fun `merge fast-forwards and creates merge commits with our identity`() = runTest {
        repo.createBranch("ff", checkout = true)
        commit("a.txt", "a")
        repo.checkout("main")
        assertEquals(OperationResult.FastForward, repo.merge("ff"))

        repo.createBranch("side", checkout = true)
        commit("b.txt", "b")
        repo.checkout("main")
        commit("c.txt", "c")
        assertEquals(OperationResult.Done, repo.merge("side"))
        val head = repo.log(1).single()
        assertEquals(2, head.parentIds.size)
        assertEquals("Me", head.authorName) // not the system user
        assertEquals(OperationResult.UpToDate, repo.merge("side"))
    }

    @Test fun `merge conflict can be resolved and continued`() = runTest {
        divergedOnSameLine()
        val r = repo.merge("feature")
        assertEquals(OperationResult.Conflicts(setOf("f.txt")), r)
        assertEquals(GitOperation.MERGE, repo.operation())
        val v = repo.conflictVersions("f.txt")
        assertEquals("main\n", v.yours(GitOperation.MERGE)!!.decodeToString())
        assertEquals("feature\n", v.incoming(GitOperation.MERGE)!!.decodeToString())

        // Continuing with unresolved files reports them again.
        assertEquals(OperationResult.Conflicts(setOf("f.txt")), repo.continueOperation("merge", me))
        repo.resolveConflict("f.txt", "both\n".toByteArray())
        assertEquals(OperationResult.Done, repo.continueOperation("Merge feature", me))
        assertEquals(GitOperation.NONE, repo.operation())
        assertEquals(2, repo.log(1).single().parentIds.size)
    }

    @Test fun `abort merge restores state`() = runTest {
        divergedOnSameLine()
        repo.merge("feature")
        repo.abortOperation()
        assertEquals(GitOperation.NONE, repo.operation())
        assertEquals("main\n", file("f.txt").readText())
    }

    @Test fun `merge refuses to overwrite local changes`() = runTest {
        divergedOnSameLine()
        file("f.txt").writeText("dirty\n")
        val e = runCatching { repo.merge("feature") }.exceptionOrNull()
        assertTrue(e.toString(), e is GitLocalChangesException)
        assertEquals("dirty\n", file("f.txt").readText())
    }

    // ---- rebase

    @Test fun `rebase replays commits linearly`() = runTest {
        repo.createBranch("feature", checkout = true)
        commit("a.txt", "a", "feature a")
        repo.checkout("main")
        commit("b.txt", "b", "main b")
        repo.checkout("feature")

        assertEquals(OperationResult.Done, repo.rebase("main"))
        assertEquals(listOf("feature a", "main b", "root"), repo.log().map { it.title })
        assertTrue(repo.log().all { it.parentIds.size <= 1 })
    }

    @Test fun `rebase conflicts swap ours and theirs and can continue`() = runTest {
        divergedOnSameLine()
        repo.checkout("feature")
        val r = repo.rebase("main")
        assertEquals(OperationResult.Conflicts(setOf("f.txt")), r)
        assertEquals(GitOperation.REBASE, repo.operation())

        val v = repo.conflictVersions("f.txt")
        // "Yours" is the feature commit being replayed, even though Git calls it "theirs" here.
        assertEquals("feature\n", v.yours(GitOperation.REBASE)!!.decodeToString())
        assertEquals("main\n", v.incoming(GitOperation.REBASE)!!.decodeToString())

        repo.resolveConflict("f.txt", "resolved\n".toByteArray())
        assertEquals(OperationResult.Done, repo.continueOperation("ignored", me))
        assertEquals(GitOperation.NONE, repo.operation())
        assertEquals(listOf("feature edit", "main edit", "root"), repo.log().map { it.title })
        assertEquals("resolved\n", file("f.txt").readText())
    }

    @Test fun `abort rebase returns to original branch tip`() = runTest {
        divergedOnSameLine()
        repo.checkout("feature")
        val before = repo.log(1).single().id
        repo.rebase("main")
        repo.abortOperation()
        assertEquals(GitOperation.NONE, repo.operation())
        assertEquals("feature", repo.currentBranch())
        assertEquals(before, repo.log(1).single().id)
    }

    // ---- cherry-pick & revert

    @Test fun `cherry-pick copies a commit`() = runTest {
        repo.createBranch("feature", checkout = true)
        val c = commit("pick.txt", "picked", "add pick")
        repo.checkout("main")
        assertEquals(OperationResult.Done, repo.cherryPick(c.id))
        assertEquals("add pick", repo.log(1).single().title)
        assertTrue(file("pick.txt").exists())
    }

    @Test fun `cherry-pick conflict then continue`() = runTest {
        repo.createBranch("feature", checkout = true)
        val c = commit("f.txt", "feature\n", "feature edit")
        repo.checkout("main")
        commit("f.txt", "main\n")
        assertEquals(OperationResult.Conflicts(setOf("f.txt")), repo.cherryPick(c.id))
        assertEquals(GitOperation.CHERRY_PICK, repo.operation())
        repo.resolveConflict("f.txt", "x\n".toByteArray())
        assertEquals(OperationResult.Done, repo.continueOperation("feature edit", me))
        assertEquals(GitOperation.NONE, repo.operation())
    }

    @Test fun `revert undoes a commit`() = runTest {
        val c = commit("r.txt", "temp")
        assertEquals(OperationResult.Done, repo.revert(c.id))
        assertFalse(file("r.txt").exists())
        assertTrue(repo.log(1).single().title.startsWith("Revert"))
    }

    // ---- reset & detached checkout

    @Test fun `reset modes`() = runTest {
        val root = repo.log().last()
        commit("a.txt", "a")

        repo.reset(root.id, ResetMode.SOFT)
        assertEquals(setOf("a.txt"), repo.status().added)

        repo.commit("again", me)
        repo.reset(root.id, ResetMode.MIXED)
        assertEquals(setOf("a.txt"), repo.status().untracked)

        repo.stageAll(); repo.commit("again", me)
        repo.reset(root.id, ResetMode.HARD)
        assertFalse(file("a.txt").exists())
        assertTrue(repo.status().isClean)
    }

    @Test fun `detached checkout of a commit`() = runTest {
        val root = repo.log().last()
        commit("a.txt", "a")
        repo.checkoutDetached(root.id)
        assertEquals(root.id, repo.currentBranch()) // JGit reports the commit id when detached
        assertFalse(file("a.txt").exists())
    }

    // ---- stash

    @Test fun `stash save list apply pop drop`() = runTest {
        file("f.txt").writeText("wip\n")
        file("new.txt").writeText("untracked")
        assertTrue(repo.stashSave("work in progress", includeUntracked = true, author = me))
        assertTrue(repo.status().isClean)
        assertFalse(repo.stashSave(null, true, me)) // nothing left to stash

        val list = repo.stashes()
        assertEquals(1, list.size)
        assertTrue(list.single().message, list.single().message.contains("work in progress"))

        assertEquals(OperationResult.Done, repo.stashApply(0, pop = false))
        assertEquals("wip\n", file("f.txt").readText())
        assertTrue(file("new.txt").exists())
        assertEquals(1, repo.stashes().size) // apply keeps it

        repo.discardChanges(listOf("f.txt", "new.txt"))
        assertEquals(OperationResult.Done, repo.stashApply(0, pop = true))
        assertTrue(repo.stashes().isEmpty())

        repo.stashSave("second", false, me)
        repo.stashDrop(0)
        assertTrue(repo.stashes().isEmpty())
    }

    @Test fun `stash pop with conflicts keeps the stash`() = runTest {
        file("f.txt").writeText("stashed\n")
        repo.stashSave(null, false, me)
        commit("f.txt", "committed meanwhile\n")
        val r = repo.stashApply(0, pop = true)
        assertTrue(r.toString(), r is OperationResult.Conflicts || r == OperationResult.Done)
        if (r is OperationResult.Conflicts) assertEquals(1, repo.stashes().size)
    }

    // ---- tags

    @Test fun `lightweight and annotated tags`() = runTest {
        val root = repo.log().last()
        repo.createTag("v1", null, root.id, me)
        repo.createTag("v2", "Release 2", null, me)

        val tags = repo.tags().associateBy { it.name }
        assertEquals(root.id, tags.getValue("v1").commitId)
        assertFalse(tags.getValue("v1").annotated)
        assertNull(tags.getValue("v1").message)
        assertTrue(tags.getValue("v2").annotated)
        assertEquals("Release 2", tags.getValue("v2").message)
        assertEquals("Me", tags.getValue("v2").taggerName)
        assertEquals(repo.log(1).single().id, tags.getValue("v2").commitId)

        repo.deleteTag("v1")
        assertEquals(listOf("v2"), repo.tags().map { it.name })
    }

    @Test fun `push and delete remote tag`() = runTest {
        val remote = tmp.newFolder("remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
        repo.addRemote("origin", remote.toURI().toString())
        repo.setUpstream("main", "origin")
        repo.push()
        repo.createTag("v1", "one", null, me)

        assertTrue(repo.pushTag("v1", "origin", GitCredentials.None).isSuccess)
        Git.open(remote).use { assertEquals(listOf("refs/tags/v1"), it.tagList().call().map { r -> r.name }) }

        assertTrue(repo.pushTag("v1", "origin", GitCredentials.None, delete = true).isSuccess)
        Git.open(remote).use { assertTrue(it.tagList().call().isEmpty()) }
    }

    // ---- reflog

    @Test fun `reflog records history including resets`() = runTest {
        val root = repo.log().last()
        val lost = commit("a.txt", "a", "will be lost")
        repo.reset(root.id, ResetMode.HARD)
        val entries = repo.reflog()
        assertTrue(entries.any { it.newId == lost.id })
        assertEquals(root.id, entries.first().newId)
        // The "lost" commit can be recovered from the reflog.
        repo.reset(lost.id, ResetMode.HARD)
        assertTrue(file("a.txt").exists())
    }
}
