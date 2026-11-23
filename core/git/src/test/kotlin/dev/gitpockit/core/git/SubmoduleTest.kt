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

class SubmoduleTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var engine: JGitEngine
    private lateinit var libRemote: File
    private lateinit var appRemote: File
    private val me = GitAuthor("Me", "me@example.com")

    @Before fun setUp() = runTest {
        engine = JGitEngine(tmp.newFolder("home"))
        libRemote = bare("lib.git")
        appRemote = bare("app.git")
        // The library has one commit.
        cloneInto(libRemote, "lib-work").use { lib -> commit(lib, "lib.txt", "v1", "lib v1"); assertTrue(lib.push().isSuccess) }
        // The app adds the library as a submodule.
        cloneInto(appRemote, "app-work").use { app ->
            commit(app, "README.md", "app", "app root")
            app.addSubmodule("libs/lib", libRemote.toURI().toString())
            app.stageAll()
            app.commit("Add lib submodule", me)
            assertTrue(app.push().isSuccess)
        }
    }

    private fun bare(name: String) = tmp.newFolder(name).also {
        Git.init().setBare(true).setInitialBranch("main").setDirectory(it).call().close()
    }

    private suspend fun cloneInto(remote: File, name: String, submodules: Boolean = false) =
        engine.clone(CloneOptions(remote.toURI().toString(), File(tmp.root, name), cloneSubmodules = submodules)).also { it.setIdentity(me) }

    private suspend fun commit(repo: GitRepository, path: String, text: String, msg: String) {
        File(repo.workTree, path).apply { parentFile.mkdirs() }.writeText(text)
        repo.stageAll()
        repo.commit(msg, me)
    }

    @Test fun `clone with submodules checks them out`() = runTest {
        cloneInto(appRemote, "with", submodules = true).use { app ->
            assertEquals("v1", File(app.workTree, "libs/lib/lib.txt").readText())
            val sm = app.submodules().single()
            assertEquals("libs/lib", sm.path)
            assertEquals(SubmoduleState.UP_TO_DATE, sm.state)
            assertTrue(sm.url!!.contains("lib.git"))
            // The submodule is a repository of its own (".git" is a file pointing into the parent).
            val dir = File(app.workTree, "libs/lib")
            assertTrue(File(dir, ".git").isFile)
            assertTrue(engine.isRepository(dir))
            engine.open(dir).use { lib -> assertEquals("lib v1", lib.log(1).single().title) }
        }
    }

    @Test fun `plain clone shows uninitialized submodule and update downloads it`() = runTest {
        cloneInto(appRemote, "plain").use { app ->
            assertEquals(SubmoduleState.UNINITIALIZED, app.submodules().single().state)
            assertFalse(File(app.workTree, "libs/lib/lib.txt").exists())
            assertEquals(listOf("libs/lib"), app.updateSubmodules())
            assertEquals(SubmoduleState.UP_TO_DATE, app.submodules().single().state)
            assertEquals("v1", File(app.workTree, "libs/lib/lib.txt").readText())
        }
    }

    @Test fun `parent moving the submodule forward is applied by update`() = runTest {
        cloneInto(libRemote, "lib-work2").use { lib -> commit(lib, "lib.txt", "v2", "lib v2"); lib.push() }
        // Another clone of the app bumps the submodule to v2 and pushes.
        cloneInto(appRemote, "bumper", submodules = true).use { app ->
            engine.open(File(app.workTree, "libs/lib")).use { lib ->
                // Submodules are checked out without a branch; pulling explains what to do.
                val e = runCatching { lib.pull() }.exceptionOrNull()
                assertTrue(e?.message.orEmpty(), e is GitException && e.message!!.contains("Check out a branch"))
                lib.checkout("main")
                lib.pull()
                assertEquals("v2", File(lib.workTree, "lib.txt").readText())
            }
            assertEquals(SubmoduleState.DIFFERENT_COMMIT, app.submodules().single().state)
            app.stageAll()
            app.commit("Bump lib to v2", me)
            assertTrue(app.push().isSuccess)
        }
        // Our copy pulls the bump: the submodule is now behind until updated.
        cloneInto(appRemote, "follower", submodules = true).use { app ->
            assertEquals("v2", File(app.workTree, "libs/lib/lib.txt").readText()) // fresh clone gets v2 directly
        }
        val old = cloneInto(appRemote, "old", submodules = true)
        old.use { app ->
            app.reset(app.log().first { it.title == "Add lib submodule" }.id, ResetMode.HARD)
            app.updateSubmodules()
            assertEquals("v1", File(app.workTree, "libs/lib/lib.txt").readText())
            app.pull()
            assertEquals(SubmoduleState.DIFFERENT_COMMIT, app.submodules().single().state)
            app.updateSubmodules()
            assertEquals(SubmoduleState.UP_TO_DATE, app.submodules().single().state)
            assertEquals("v2", File(app.workTree, "libs/lib/lib.txt").readText())
        }
    }

    @Test fun `sync rewrites urls from gitmodules`() = runTest {
        cloneInto(appRemote, "sync", submodules = true).use { app ->
            app.syncSubmodules() // must not fail on an already consistent repo
            assertEquals(1, app.submodules().size)
        }
    }
}
