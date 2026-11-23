package dev.gitpockit.core.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.gitpockit.core.data.db.GitPockitDatabase
import dev.gitpockit.core.data.model.AppTrigger
import dev.gitpockit.core.data.model.ConflictStrategy
import dev.gitpockit.core.data.model.SyncMode
import dev.gitpockit.core.data.model.SyncResult
import dev.gitpockit.core.data.model.SyncSettings
import dev.gitpockit.core.data.model.SyncTrigger
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.git.jgit.JGitEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import org.junit.runner.RunWith
import java.io.File
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncDataTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: GitPockitDatabase
    private lateinit var engine: JGitEngine
    private lateinit var repos: RepoRepository
    private var now = 1_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GitPockitDatabase::class.java)
            .allowMainThreadQueries().build()
        engine = JGitEngine(tmp.newFolder("home"))
        repos = RepoRepository(db, engine) { now }
    }

    @After fun tearDown() = db.close()

    private suspend fun newRepo(name: String) = tmp.newFolder(name).let { dir -> engine.init(dir).close(); repos.register(dir) }

    /**
     * Builds a real version-1 database from the committed schema (schemas/…/1.json), then opens it
     * with the current code. Room runs the 1→2 auto-migration and validates the resulting schema,
     * exactly as on an upgrading user's phone.
     */
    @Test fun migrationFrom1To2KeepsReposAndAddsDefaults() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val schema = JSONObject(File("schemas/${GitPockitDatabase::class.java.name}/1.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath("migrate.db").apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v1 ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                v1.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx ->
                    for (j in 0 until idx.length()) v1.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) v1.execSQL(setup.getString(i))
            v1.execSQL("INSERT INTO repos (id, name, localPath, remoteName, createdAt) VALUES (7, 'notes', '/x/notes', 'origin', 5)")
            v1.version = 1
        }

        val migrated = Room.databaseBuilder(context, GitPockitDatabase::class.java, file.path).allowMainThreadQueries().build()
        try {
            val repo = RepoRepository(migrated, engine).get(7)!!
            assertEquals("notes", repo.name)
            assertEquals(SyncSettings(), repo.sync)
            assertFalse(repo.pendingSync)
        } finally {
            migrated.close()
        }
    }

    @Test fun syncSettingsRoundTrip() = runTest {
        val repo = newRepo("a")
        assertEquals(SyncSettings(), repo.sync)
        val settings = SyncSettings(
            SyncMode.PULL_ONLY, ConflictStrategy.KEEP_BOTH, "x {date}", 30,
            onFileChange = true, wifiOnly = true, dailyTimes = listOf(540, 1290), pullRebase = true, lfsDownload = false, updateSubmodules = false,
        )
        repos.updateSyncSettings(repo.id, settings)
        assertEquals(settings, repos.get(repo.id)!!.sync)

        // Times are cleaned up: sorted, unique, within a day.
        repos.updateSyncSettings(repo.id, settings.copy(dailyTimes = listOf(1290, 540, 540, 5000, -1)))
        assertEquals(listOf(540, 1290), repos.get(repo.id)!!.sync.dailyTimes)

        repos.recordSync(repo.id, "offline", pending = true)
        val after = repos.get(repo.id)!!
        assertTrue(after.pendingSync)
        assertEquals("offline", after.lastError)
    }

    @Test fun syncLogIsNewestFirstAndPruned() = runTest {
        val repo = newRepo("a")
        repeat(205) { i ->
            now = 10_000L + i
            repos.addLog(repo.id, SyncTrigger.SCHEDULE, startedAt = now, result = SyncResult.UP_TO_DATE, message = "#$i")
        }
        val log = repos.observeLog(repo.id).first()
        assertEquals(200, log.size)
        assertEquals("#204", log.first().message)
        assertEquals(SyncTrigger.SCHEDULE, log.first().trigger)
    }

    @Test fun appTriggersUpsertAndDeleteWhenBothOff() = runTest {
        val a = newRepo("a")
        repos.setAppTrigger(AppTrigger("md.obsidian", a.id, onOpen = true, onClose = true))
        repos.setAppTrigger(AppTrigger("md.obsidian", a.id, onOpen = false, onClose = true))
        assertEquals(listOf(AppTrigger("md.obsidian", a.id, false, true)), repos.allAppTriggers())
        repos.setAppTrigger(AppTrigger("md.obsidian", a.id, onOpen = false, onClose = false))
        assertTrue(repos.allAppTriggers().isEmpty())

        // Removing the repo removes its triggers and log.
        repos.setAppTrigger(AppTrigger("x", a.id, true, false))
        repos.addLog(a.id, SyncTrigger.MANUAL, now, SyncResult.SYNCED, null)
        repos.remove(a.id, deleteFiles = false)
        assertTrue(repos.allAppTriggers().isEmpty())
        assertFalse(repos.observeLog(a.id).first().isNotEmpty())
    }
}
