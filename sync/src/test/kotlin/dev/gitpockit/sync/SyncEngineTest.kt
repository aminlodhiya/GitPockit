package dev.gitpockit.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.gitpockit.core.data.db.GitPockitDatabase
import dev.gitpockit.core.data.model.ConflictStrategy
import dev.gitpockit.core.data.model.SyncMode
import dev.gitpockit.core.data.model.SyncResult
import dev.gitpockit.core.data.model.SyncSettings
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.core.data.repository.CredentialRepository
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.git.CloneOptions
import dev.gitpockit.core.git.GitAuthor
import dev.gitpockit.core.git.GitRepository
import dev.gitpockit.core.git.jgit.JGitEngine
import dev.gitpockit.core.security.SecretCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime

private class FakeNetwork(var online: Boolean = true, var unmetered: Boolean = true) : NetworkStatus {
    override fun isOnline() = online
    override fun isUnmetered() = unmetered
}

private object NoCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain
    override fun decrypt(blob: ByteArray) = blob
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: GitPockitDatabase
    private lateinit var engine: JGitEngine
    private lateinit var repos: RepoRepository
    private lateinit var sync: SyncEngine
    private lateinit var remote: File
    private val network = FakeNetwork()
    private val other = GitAuthor("Laptop", "laptop@x")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GitPockitDatabase::class.java)
            .allowMainThreadQueries().build()
        engine = JGitEngine(tmp.newFolder("home"))
        repos = RepoRepository(db, engine)
        val ops = RepoOps(engine, repos, CredentialRepository(db.credentialDao(), NoCipher)) { GitAuthor("Phone", "phone@x") }
        sync = SyncEngine(ops, repos, network, deviceName = { "Pixel 8" }, now = { LocalDateTime.of(2026, 11, 22, 15, 30) })
        remote = tmp.newFolder("remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
        // The "laptop" seeds the remote with one file.
        laptop { it.write("notes.md", "base\n") }
    }

    @After fun tearDown() = db.close()

    /** Runs [block] in a fresh clone (another device), committing and pushing what it writes. */
    private fun laptop(block: suspend (GitRepository) -> Unit) = kotlinx.coroutines.runBlocking {
        val dir = tmp.newFolder()
        dir.delete()
        engine.clone(CloneOptions(remote.toURI().toString(), dir)).use { git ->
            block(git)
            git.stageAll()
            if (!git.status().isClean || git.log(1).isEmpty()) git.commit("laptop edit", other)
            assertTrue(git.push().isSuccess)
        }
    }

    private suspend fun GitRepository.write(path: String, text: String) {
        File(workTree, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private suspend fun phone(settings: SyncSettings = SyncSettings()): Long {
        val dir = File(tmp.root, "phone")
        engine.clone(CloneOptions(remote.toURI().toString(), dir)).close()
        val repo = repos.register(dir)
        repos.updateSyncSettings(repo.id, settings)
        return repo.id
    }

    private fun phoneFile(path: String) = File(tmp.root, "phone/$path")

    private fun remoteLog(): List<String> = Git.open(remote).use { g -> g.log().call().map { it.shortMessage } }

    @Test fun nothingToDoIsUpToDate() = runTest {
        val id = phone()
        val out = sync.sync(id, SyncTrigger.MANUAL)
        assertEquals(SyncResult.UP_TO_DATE, out.result)
        assertEquals(SyncResult.UP_TO_DATE, repos.observeLog(id).first().single().result)
    }

    @Test fun localEditsAreCommittedWithTemplateAndPushed() = runTest {
        val id = phone()
        phoneFile("notes.md").writeText("phone edit\n")
        phoneFile("ideas/new.md").apply { parentFile.mkdirs() }.writeText("idea\n")

        val out = sync.sync(id, SyncTrigger.FILE_CHANGE)

        assertEquals(SyncResult.SYNCED, out.result)
        assertTrue(out.committed && out.pushed)
        assertEquals("Sync from Pixel 8: new.md, notes.md", remoteLog().first())
        assertFalse(repos.get(id)!!.pendingSync)
    }

    @Test fun customTemplatePlaceholders() = runTest {
        val id = phone(SyncSettings(commitTemplate = "{device} {date} {time} {count}"))
        phoneFile("notes.md").writeText("x\n")
        sync.sync(id, SyncTrigger.SCHEDULE)
        assertEquals("Pixel 8 2026-11-22 15:30 1", remoteLog().first())
    }

    @Test fun remoteChangesArePulled() = runTest {
        val id = phone()
        laptop { it.write("notes.md", "from laptop\n") }
        val out = sync.sync(id, SyncTrigger.APP_OPEN)
        assertEquals(SyncResult.SYNCED, out.result)
        assertTrue(out.pulled)
        assertEquals("from laptop\n", phoneFile("notes.md").readText())
    }

    @Test fun offlineCommitsLocallyAndMarksPending() = runTest {
        val id = phone()
        phoneFile("notes.md").writeText("offline edit\n")
        network.online = false

        val out = sync.sync(id, SyncTrigger.APP_CLOSE)

        assertEquals(SyncResult.OFFLINE, out.result)
        assertTrue(out.committed && out.pendingNetwork)
        assertTrue(repos.get(id)!!.pendingSync)
        assertEquals(1, remoteLog().size) // nothing pushed yet

        network.online = true
        val retry = sync.sync(id, SyncTrigger.RETRY)
        assertEquals(SyncResult.SYNCED, retry.result)
        assertTrue(retry.pushed)
        assertFalse(repos.get(id)!!.pendingSync)
        assertEquals(2, remoteLog().size)
    }

    @Test fun wifiOnlyWaitsOnMobileDataButManualSyncStillRuns() = runTest {
        val id = phone(SyncSettings(wifiOnly = true))
        phoneFile("notes.md").writeText("x\n")
        network.unmetered = false
        assertEquals(SyncResult.OFFLINE, sync.sync(id, SyncTrigger.SCHEDULE).result)
        assertEquals(SyncResult.SYNCED, sync.sync(id, SyncTrigger.MANUAL).result)
    }

    @Test fun pullOnlyNeverCommitsOrPushes() = runTest {
        val id = phone(SyncSettings(mode = SyncMode.PULL_ONLY))
        laptop { it.write("other.md", "remote\n") }
        phoneFile("local.md").writeText("untracked local\n")

        val out = sync.sync(id, SyncTrigger.SCHEDULE)

        assertTrue(out.pulled)
        assertFalse(out.committed || out.pushed)
        assertTrue(phoneFile("other.md").exists())
        assertEquals(2, remoteLog().size)
    }

    private suspend fun conflictingEdit(strategy: ConflictStrategy): Long {
        val id = phone(SyncSettings(conflictStrategy = strategy))
        laptop { it.write("notes.md", "laptop\n") }
        phoneFile("notes.md").writeText("phone\n")
        return id
    }

    @Test fun conflictsWithAskStopForTheUser() = runTest {
        val id = conflictingEdit(ConflictStrategy.ASK)
        val out = sync.sync(id, SyncTrigger.SCHEDULE)
        assertEquals(SyncResult.CONFLICTS, out.result)
        assertEquals(setOf("notes.md"), out.conflicts)
        assertTrue(repos.get(id)!!.lastError != null)

        // While mid-merge, further syncs don't touch the repo.
        val again = sync.sync(id, SyncTrigger.SCHEDULE)
        assertEquals(SyncResult.CONFLICTS, again.result)
    }

    @Test fun keepLocalWins() = runTest {
        val id = conflictingEdit(ConflictStrategy.KEEP_LOCAL)
        val out = sync.sync(id, SyncTrigger.SCHEDULE)
        assertEquals(SyncResult.SYNCED, out.result)
        assertEquals("phone\n", phoneFile("notes.md").readText())
        assertTrue(out.pushed)
    }

    @Test fun keepRemoteWins() = runTest {
        val id = conflictingEdit(ConflictStrategy.KEEP_REMOTE)
        assertEquals(SyncResult.SYNCED, sync.sync(id, SyncTrigger.SCHEDULE).result)
        assertEquals("laptop\n", phoneFile("notes.md").readText())
    }

    @Test fun keepBothSavesLocalCopy() = runTest {
        val id = conflictingEdit(ConflictStrategy.KEEP_BOTH)
        val out = sync.sync(id, SyncTrigger.SCHEDULE)
        assertEquals(SyncResult.SYNCED, out.result)
        assertEquals("laptop\n", phoneFile("notes.md").readText())
        assertEquals("phone\n", phoneFile("notes (conflict Pixel 8 2026-11-22 1530).md").readText())
        assertTrue(out.pushed)
    }

    // ---- pull with rebase

    private fun phoneLog(): List<org.eclipse.jgit.revwalk.RevCommit> = Git.open(File(tmp.root, "phone")).use { it.log().call().toList() }

    @Test fun mergeCommitsCarryTheAppAuthor() = runTest {
        val id = phone()
        laptop { it.write("other.md", "laptop\n") }
        phoneFile("mine.md").writeText("phone\n")
        assertEquals(SyncResult.SYNCED, sync.sync(id, SyncTrigger.MANUAL).result)
        val merge = phoneLog().first()
        assertEquals(2, merge.parentCount)
        assertEquals("Phone", merge.committerIdent.name) // not the Android system user
    }

    @Test fun rebasePullKeepsHistoryLinear() = runTest {
        val id = phone(SyncSettings(pullRebase = true))
        laptop { it.write("other.md", "laptop\n") }
        phoneFile("mine.md").writeText("phone\n")
        val out = sync.sync(id, SyncTrigger.MANUAL)
        assertEquals(SyncResult.SYNCED, out.result)
        assertTrue(out.pushed)
        assertTrue(phoneLog().all { it.parentCount <= 1 })
        assertEquals(3, remoteLog().size)
    }

    private suspend fun rebaseConflict(strategy: ConflictStrategy): Long {
        val id = phone(SyncSettings(pullRebase = true, conflictStrategy = strategy))
        laptop { it.write("notes.md", "laptop\n") }
        phoneFile("notes.md").writeText("phone\n")
        return id
    }

    @Test fun rebaseKeepLocalKeepsMyText() = runTest {
        val id = rebaseConflict(ConflictStrategy.KEEP_LOCAL)
        assertEquals(SyncResult.SYNCED, sync.sync(id, SyncTrigger.SCHEDULE).result)
        assertEquals("phone\n", phoneFile("notes.md").readText())
        assertTrue(phoneLog().all { it.parentCount <= 1 })
    }

    @Test fun rebaseKeepRemoteDropsMyNowEmptyCommit() = runTest {
        val id = rebaseConflict(ConflictStrategy.KEEP_REMOTE)
        val out = sync.sync(id, SyncTrigger.SCHEDULE)
        assertEquals(SyncResult.SYNCED, out.result)
        assertEquals("laptop\n", phoneFile("notes.md").readText())
        assertEquals(remoteLog().size, phoneLog().size)
    }

    @Test fun rebaseKeepBothSavesMyCopy() = runTest {
        val id = rebaseConflict(ConflictStrategy.KEEP_BOTH)
        assertEquals(SyncResult.SYNCED, sync.sync(id, SyncTrigger.SCHEDULE).result)
        assertEquals("laptop\n", phoneFile("notes.md").readText())
        assertEquals("phone\n", phoneFile("notes (conflict Pixel 8 2026-11-22 1530).md").readText())
    }

    @Test fun rebaseAskLeavesRebaseForTheUser() = runTest {
        val id = rebaseConflict(ConflictStrategy.ASK)
        val out = sync.sync(id, SyncTrigger.SCHEDULE)
        assertEquals(SyncResult.CONFLICTS, out.result)
        val again = sync.sync(id, SyncTrigger.SCHEDULE)
        assertEquals(SyncResult.CONFLICTS, again.result)
        assertTrue(again.message!!, again.message!!.contains("rebase"))
    }

    @Test fun conflictCopyNames() = runTest {
        assertEquals("a/b/todo (conflict Pixel 8 2026-11-22 1530).md", sync.conflictCopyName("a/b/todo.md"))
        assertEquals("Makefile (conflict Pixel 8 2026-11-22 1530)", sync.conflictCopyName("Makefile"))
        assertEquals(".env (conflict Pixel 8 2026-11-22 1530)", sync.conflictCopyName(".env"))
    }

    @Test fun missingFolderIsReportedAsFailure() = runTest {
        val id = phone()
        File(tmp.root, "phone").deleteRecursively()
        val out = sync.sync(id, SyncTrigger.SCHEDULE)
        assertEquals(SyncResult.FAILED, out.result)
        assertEquals(SyncResult.FAILED, repos.observeLog(id).first().first().result)
    }

    @Test fun unreachableRemoteIsTreatedAsOffline() = runTest {
        val id = phone()
        phoneFile("notes.md").writeText("x\n")
        remote.renameTo(File(tmp.root, "moved.git"))
        val out = sync.sync(id, SyncTrigger.SCHEDULE)
        assertTrue(out.committed)
        assertTrue(out.result == SyncResult.OFFLINE || out.result == SyncResult.FAILED)
    }
}
