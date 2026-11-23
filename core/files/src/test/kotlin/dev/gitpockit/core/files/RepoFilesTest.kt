package dev.gitpockit.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class RepoFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var files: RepoFiles

    @Before fun setUp() {
        root = tmp.newFolder("repo")
        File(root, ".git/info").mkdirs()
        File(root, ".git/config").writeText("[core]\n")
        File(root, "notes").mkdirs()
        File(root, "notes/b.md").writeText("# B")
        File(root, "A.txt").writeText("a")
        File(root, "img.png").writeBytes(byteArrayOf(1, 2))
        files = RepoFiles(root)
    }

    @Test fun listsFoldersFirstAndHidesGit() {
        assertEquals(listOf("notes", "A.txt", "img.png"), files.list().map { it.name })
        val kinds = files.list().associate { it.name to it.kind }
        assertEquals(FileKind.IMAGE, kinds["img.png"])
        assertEquals(listOf("notes/b.md"), files.list("notes").map { it.path })
        assertEquals(FileKind.MARKDOWN, files.list("notes").single().kind)
    }

    @Test fun refusesPathsOutsideTheRepoAndGitInternals() {
        assertThrows(FileAccessException::class.java) { files.resolve("../outside.txt") }
        assertThrows(FileAccessException::class.java) { files.resolve("notes/../../x") }
        assertThrows(FileAccessException::class.java) { files.readText(".git/config") }
        assertThrows(FileAccessException::class.java) { files.writeText(".git/HEAD", "x") }
        assertThrows(FileAccessException::class.java) { files.createFile("", ".git") }
        assertThrows(FileAccessException::class.java) { files.createFile("", "a/b") }
        // The exclude file is the one allowed exception, and only when asked for explicitly.
        files.writeText(RepoFiles.EXCLUDE_FILE, "*.log\n", allowGitInternals = true)
        assertEquals("*.log\n", File(root, ".git/info/exclude").readText())
    }

    @Test fun refusesSymlinksPointingOutside() {
        val outside = tmp.newFolder("outside")
        File(outside, "secret.txt").writeText("s")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertThrows(FileAccessException::class.java) { files.readText("link/secret.txt") }
    }

    @Test fun createRenameMoveDelete() {
        assertEquals("notes/new.md", files.createFile("notes", "new.md", "hi"))
        assertThrows(FileAccessException::class.java) { files.createFile("notes", "new.md") }
        assertEquals("notes/sub", files.createFolder("notes", "sub"))
        assertEquals("notes/renamed.md", files.rename("notes/new.md", "renamed.md"))
        assertEquals("notes/sub/renamed.md", files.move("notes/renamed.md", "notes/sub"))
        assertEquals("hi", files.readText("notes/sub/renamed.md"))
        assertThrows(FileAccessException::class.java) { files.move("notes", "notes/sub") }
        files.delete("notes/sub")
        assertFalse(File(root, "notes/sub").exists())
        assertThrows(FileAccessException::class.java) { files.delete("") }
    }

    @Test fun textReadingRejectsBinaryAndHugeFiles() {
        File(root, "data.bin").writeBytes(byteArrayOf(65, 0, 66))
        assertThrows(FileAccessException::class.java) { files.readText("data.bin") }
        File(root, "big.txt").writeText("x".repeat(100))
        assertThrows(FileAccessException::class.java) { files.readText("big.txt", maxBytes = 10) }
        assertEquals("", files.readText("does-not-exist.txt"))
    }

    @Test fun writeIsAtomicAndKeepsNoTempFiles() {
        files.writeText("notes/b.md", "# Updated\n")
        assertEquals("# Updated\n", File(root, "notes/b.md").readText())
        assertTrue(File(root, "notes").list()!!.none { it.contains("gitpockit-tmp") })
    }

    @Test fun ignoreRulesAreAppendedOnce() {
        files.addIgnoreRule("*.log", deviceOnly = false)
        files.addIgnoreRule("*.log", deviceOnly = false)
        files.addIgnoreRule("build/", deviceOnly = false)
        assertEquals("*.log\nbuild/\n", File(root, ".gitignore").readText())

        File(root, ".git/info/exclude").writeText("# comment") // no trailing newline
        files.addIgnoreRule("secret.txt", deviceOnly = true)
        assertEquals("# comment\nsecret.txt\n", File(root, ".git/info/exclude").readText())
    }

    @Test fun kindDetection() {
        assertEquals(FileKind.MARKDOWN, RepoFiles.kindOf("README.MD"))
        assertEquals(FileKind.IMAGE, RepoFiles.kindOf("logo.svg"))
        assertEquals(FileKind.BINARY, RepoFiles.kindOf("doc.pdf"))
        assertEquals(FileKind.TEXT, RepoFiles.kindOf("Makefile"))
        assertEquals(FileKind.TEXT, RepoFiles.kindOf("main.kt"))
    }
}
