package dev.gitpockit.core.git

import dev.gitpockit.core.git.jgit.JGitEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class HistoryToolsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var engine: JGitEngine
    private lateinit var repo: GitRepository
    private val alice = GitAuthor("Alice", "alice@example.com")
    private val bob = GitAuthor("Bob", "bob@example.com")

    @Before fun setUp() = runTest {
        engine = JGitEngine(tmp.newFolder("home"))
        repo = engine.init(tmp.newFolder("work"))
        repo.setIdentity(alice)
    }

    private fun file(p: String) = File(repo.workTree, p)

    private suspend fun commit(path: String, text: String, msg: String, who: GitAuthor = alice): CommitInfo {
        file(path).apply { parentFile.mkdirs() }.writeText(text)
        repo.stageAll()
        return repo.commit(msg, who)
    }

    // ---- graph

    private fun c(id: String, vararg parents: String) = CommitInfo(id, id, id, "a", "a", Instant.EPOCH, parents.toList())

    @Test fun `linear history stays in one lane`() {
        val rows = CommitGraph.layout(listOf(c("c", "b"), c("b", "a"), c("a")))
        assertEquals(listOf(0, 0, 0), rows.map { it.lane })
        assertTrue(rows.all { it.width == 1 })
        assertFalse(rows.last().segments.any { !it.top }) // root: nothing below
    }

    @Test fun `branch and merge use two lanes`() {
        // m merges x (side) into b (main):  m -> b -> a ; m -> x -> a
        val rows = CommitGraph.layout(listOf(c("m", "b", "x"), c("x", "a"), c("b", "a"), c("a")))
        val byId = rows.associateBy { it.commitId }
        assertEquals(0, byId.getValue("m").lane)
        assertEquals(1, byId.getValue("x").lane)
        assertEquals(0, byId.getValue("b").lane)
        assertEquals(0, byId.getValue("a").lane)
        assertTrue(byId.getValue("m").segments.any { !it.top && it.from == 0 && it.to == 1 }) // merge line
        // Lane 1 ends when x's parent a is already expected in lane 0: it merges back in a's row.
        assertTrue(byId.getValue("a").segments.any { it.top && it.from == 1 && it.to == 0 })
        assertEquals(2, byId.getValue("x").width)
    }

    @Test fun `two branch tips get distinct colours`() {
        val rows = CommitGraph.layout(listOf(c("t1", "a"), c("t2", "a"), c("a")))
        assertTrue(rows[0].color != rows[1].color)
    }

    @Test fun `graph log is topological and can include all branches`() = runTest {
        commit("f", "1", "root")
        repo.createBranch("side", checkout = true)
        commit("s", "s", "side work")
        repo.checkout("main")
        commit("m", "m", "main work")

        assertEquals(listOf("main work", "root"), repo.graphLog().map { it.title })
        val all = repo.graphLog(allRefs = true).map { it.title }
        assertEquals(3, all.size)
        assertEquals("root", all.last())

        val labels = repo.refLabels()
        val head = repo.log(1).single().id
        assertTrue(labels.getValue(head).any { it.name == "main" && it.kind == RefKind.LOCAL_BRANCH })
        repo.createTag("v1", null, null, alice)
        assertTrue(repo.refLabels().getValue(head).any { it.name == "v1" && it.kind == RefKind.TAG })
    }

    // ---- search, file history, blame

    @Test fun `search by message author and hash`() = runTest {
        commit("a", "1", "Add login screen")
        val b = commit("b", "2", "Fix crash on startup", bob)
        assertEquals(listOf("Add login screen"), repo.searchCommits("LOGIN").map { it.title })
        assertEquals(listOf("Fix crash on startup"), repo.searchCommits("bob@").map { it.title })
        assertEquals(listOf(b.id), repo.searchCommits(b.id.take(8)).map { it.id })
        assertTrue(repo.searchCommits("nothing matches").isEmpty())
    }

    @Test fun `file history follows renames`() = runTest {
        commit("notes/old.md", "line one\nline two\nline three\nline four\n", "create")
        commit("other.txt", "x", "unrelated")
        file("notes/old.md").renameTo(file("notes/new.md"))
        repo.stageAll(); repo.commit("rename", alice)
        commit("notes/new.md", "line one\nline two\nline three\nline four\nline five\n", "extend")

        val history = repo.fileHistory("notes/new.md")
        assertEquals(listOf("extend", "rename", "create"), history.map { it.commit.title })
        assertEquals(listOf("notes/new.md", "notes/new.md", "notes/old.md"), history.map { it.path })
        // The diff of the oldest entry works with the path it had back then.
        assertEquals(ChangeType.ADDED, repo.commitFileDiff(history.last().commit.id, history.last().path).type)
    }

    @Test fun `blame attributes lines to authors`() = runTest {
        commit("f.txt", "alpha\nbeta\n", "alice writes", alice)
        commit("f.txt", "alpha\nBETA\ngamma\n", "bob edits", bob)
        val lines = repo.blame("f.txt")
        assertEquals(listOf("alpha", "BETA", "gamma"), lines.map { it.text })
        assertEquals(listOf("Alice", "Bob", "Bob"), lines.map { it.authorName })
        assertEquals("bob edits", lines[1].summary)
    }

    // ---- compare

    @Test fun `compare branches lists commits and files`() = runTest {
        commit("f", "1", "root")
        repo.createBranch("feature", checkout = true)
        commit("new.txt", "n", "feature one")
        commit("f", "2", "feature two")
        repo.checkout("main")

        val cmp = repo.compare("main", "feature")
        assertEquals(listOf("feature two", "feature one"), cmp.commits.map { it.title })
        assertEquals(mapOf("new.txt" to ChangeType.ADDED, "f" to ChangeType.MODIFIED), cmp.changes.associate { it.path to it.type })
        val d = repo.compareFileDiff("main", "feature", "f")
        assertEquals(1, d.additions)
        assertTrue(repo.compare("feature", "main").commits.isEmpty())
    }

    // ---- line staging

    @Test fun `stage selected lines only`() = runTest {
        commit("f.txt", "one\ntwo\nthree\nfour\n", "base")
        file("f.txt").writeText("ONE\ntwo\nthree\nFOUR\nfive\n")
        val diff = repo.workingFileDiff("f.txt", WorkingDiffMode.UNSTAGED)
        // Pick only the change to line 1: remove "one", add "ONE".
        val removed = diff.hunks.flatMap { it.lines }.first { it.type == DiffLineType.REMOVED && it.text == "one" }
        val added = diff.hunks.flatMap { it.lines }.first { it.type == DiffLineType.ADDED && it.text == "ONE" }
        repo.stageLines("f.txt", LineSelection(setOf(removed.oldLine!!), setOf(added.newLine!!)))

        val staged = repo.workingFileDiff("f.txt", WorkingDiffMode.STAGED)
        assertEquals(listOf("one", "ONE"), staged.hunks.flatMap { h -> h.lines.filter { it.type != DiffLineType.CONTEXT } }.map { it.text })
        // The file itself is untouched, and the rest stays unstaged.
        assertEquals("ONE\ntwo\nthree\nFOUR\nfive\n", file("f.txt").readText())
        val unstaged = repo.workingFileDiff("f.txt", WorkingDiffMode.UNSTAGED)
        assertEquals(setOf("four", "FOUR", "five"), unstaged.hunks.flatMap { h -> h.lines.filter { it.type != DiffLineType.CONTEXT } }.map { it.text }.toSet())
    }

    @Test fun `unstage selected lines keeps the rest staged`() = runTest {
        commit("f.txt", "a\nb\nc\n", "base")
        file("f.txt").writeText("A\nb\nC\n")
        repo.stage(listOf("f.txt"))
        val staged = repo.workingFileDiff("f.txt", WorkingDiffMode.STAGED)
        val lines = staged.hunks.flatMap { it.lines }
        val sel = LineSelection(
            setOf(lines.first { it.text == "c" }.oldLine!!),
            setOf(lines.first { it.text == "C" }.newLine!!),
        )
        repo.unstageLines("f.txt", sel)
        val after = repo.workingFileDiff("f.txt", WorkingDiffMode.STAGED)
        assertEquals(listOf("a", "A"), after.hunks.flatMap { h -> h.lines.filter { it.type != DiffLineType.CONTEXT } }.map { it.text })
        assertEquals("A\nb\nC\n", file("f.txt").readText())
    }

    @Test fun `partial staging of a new file and line endings are preserved`() = runTest {
        commit("keep", "k", "base")
        file("new.txt").writeText("first\r\nsecond\r\nthird")
        val diff = repo.workingFileDiff("new.txt", WorkingDiffMode.UNSTAGED)
        val first = diff.hunks.flatMap { it.lines }.first { it.text.startsWith("first") }
        repo.stageLines("new.txt", LineSelection(newLines = setOf(first.newLine!!)))
        assertEquals(setOf("new.txt"), repo.status().added)
        val staged = repo.workingFileDiff("new.txt", WorkingDiffMode.STAGED)
        assertEquals(listOf("first"), staged.hunks.flatMap { it.lines }.map { it.text }) // '\r' hidden in display

        // Selecting the rest reproduces the file byte-for-byte (CRLF and missing final newline).
        val rest = repo.workingFileDiff("new.txt", WorkingDiffMode.UNSTAGED).hunks.flatMap { it.lines }
        repo.stageLines(
            "new.txt",
            LineSelection(
                rest.filter { it.type == DiffLineType.REMOVED }.mapNotNull { it.oldLine }.toSet(),
                rest.filter { it.type == DiffLineType.ADDED }.mapNotNull { it.newLine }.toSet(),
            ),
        )
        assertTrue(repo.workingFileDiff("new.txt", WorkingDiffMode.UNSTAGED).hunks.isEmpty())
        repo.commit("add new", alice)
        assertTrue(repo.status().isClean)
    }

    @Test fun `files with lone carriage returns are treated as binary`() = runTest {
        // JGit classifies a lone '\r' as binary content, so such files never get a line diff
        // (and line staging can't pick the wrong lines in them).
        commit("cr.txt", "a\rstill line one\nb\n", "base")
        file("cr.txt").writeText("a\rstill line one\nB\n")
        assertTrue(repo.workingFileDiff("cr.txt", WorkingDiffMode.UNSTAGED).isBinary)
    }
}
