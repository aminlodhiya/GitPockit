package dev.gitpockit.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.gitpockit.core.data.db.GitPockitDatabase
import dev.gitpockit.core.data.model.CredentialType
import dev.gitpockit.core.data.repository.CredentialRepository
import dev.gitpockit.core.data.repository.RepoRepository
import dev.gitpockit.core.git.GitAuthor
import dev.gitpockit.core.git.GitCredentials
import dev.gitpockit.core.git.GitException
import dev.gitpockit.core.git.SshKeys
import dev.gitpockit.core.git.jgit.JGitEngine
import dev.gitpockit.core.security.SecretCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reversible stand-in for the Keystore cipher (the real one needs a device). */
private class FakeCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = byteArrayOf(9) + plain.map { (it.toInt() xor 0x5A).toByte() }
    override fun decrypt(blob: ByteArray) = blob.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RepositoriesTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: GitPockitDatabase
    private lateinit var engine: JGitEngine
    private lateinit var repos: RepoRepository
    private lateinit var creds: CredentialRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GitPockitDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        engine = JGitEngine(tmp.newFolder("home"))
        repos = RepoRepository(db, engine)
        creds = CredentialRepository(db.credentialDao(), FakeCipher())
    }

    @After fun tearDown() = db.close()

    @Test fun httpsCredentialIsEncryptedAndResolves() = runTest {
        val id = creds.addHttps("GitHub", "alice", "ghp_secret")
        val stored = db.credentialDao().get(id)!!
        assertFalse(String(stored.secret).contains("ghp_secret"))
        assertEquals(GitCredentials.UserPassword("alice", "ghp_secret"), creds.resolve(id))
        assertEquals(CredentialType.HTTPS, creds.credentials.first().single().type)
        assertEquals(GitCredentials.None, creds.resolve(null))
    }

    @Test fun generatedSshKeyResolvesToLoadableKey() = runTest {
        val cred = creds.generateSsh("Phone", SshKeys.Type.ED25519)
        assertTrue(cred.publicKey!!.startsWith("ssh-ed25519 "))
        val resolved = creds.resolve(cred.id) as GitCredentials.SshKey
        assertEquals(cred.fingerprint, SshKeys.inspect(resolved.privateKey, null).fingerprint)
    }

    @Test fun importedSshKeyWithPassphraseIsValidated() = runTest {
        val key = SshKeys.generate(SshKeys.Type.RSA)
        val id = creds.importSsh("Laptop", String(key.privateKey).trimEnd() + "\r\n", null)
        val resolved = creds.resolve(id) as GitCredentials.SshKey
        assertArrayEquals(key.privateKey, resolved.privateKey)

        val bad = runCatching { creds.importSsh("x", "garbage", null) }.exceptionOrNull()
        assertTrue(bad is GitException)
    }

    @Test fun gpgKeysGenerateImportAndResolveForSigning() = runTest {
        val generated = creds.generateGpg("Signing", "Alice", "alice@example.com")
        assertEquals(CredentialType.GPG, generated.type)
        assertTrue(generated.publicKey!!.contains("BEGIN PGP PUBLIC KEY BLOCK"))
        assertEquals(GitCredentials.None, creds.resolve(generated.id)) // never used for network access
        val key = creds.signingKey(generated.id) as dev.gitpockit.core.git.SigningKey.OpenPgp
        val reimported = creds.importGpg("Copy", key.armoredSecret, null)
        assertEquals(generated.fingerprint, creds.credentials.first().first { it.id == reimported }.fingerprint)

        val ssh = creds.generateSsh("ssh", SshKeys.Type.ED25519)
        assertTrue(creds.signingKey(ssh.id) is dev.gitpockit.core.git.SigningKey.Ssh)
        assertEquals(null, creds.signingKey(creds.addHttps("t", "u", "p")))
        val trusted = creds.trustedKeys()
        assertEquals(setOf(ssh.fingerprint), trusted.sshFingerprints)
        assertEquals(2, trusted.pgpPublicKeys.size)

        val bad = runCatching { creds.importGpg("x", "not a key", null) }.exceptionOrNull()
        assertTrue(bad is GitException)
    }

    @Test fun registerListMoveAndRemoveRepos() = runTest {
        val dir = tmp.newFolder("notes")
        engine.init(dir).close()
        val credId = creds.addHttps("t", "u", "p")
        val container = repos.createContainer("Personal")

        val repo = repos.register(dir, credentialId = credId, containerId = container)
        assertEquals("notes", repo.name)
        assertEquals(container, repo.containerId)

        val dup = runCatching { repos.register(dir) }.exceptionOrNull()
        assertTrue(dup is GitException)
        val notRepo = runCatching { repos.register(tmp.newFolder("plain")) }.exceptionOrNull()
        assertTrue(notRepo is GitException)

        // Deleting a credential or container detaches it instead of deleting the repo.
        creds.delete(credId)
        repos.deleteContainer(container)
        val after = repos.get(repo.id)!!
        assertNull(after.credentialId)
        assertNull(after.containerId)

        val signer = creds.generateSsh("sign", SshKeys.Type.ED25519)
        repos.setSigningCredential(repo.id, signer.id)
        assertEquals(signer.id, repos.get(repo.id)!!.signingCredentialId)
        creds.delete(signer.id)
        assertNull(repos.get(repo.id)!!.signingCredentialId) // deleting the key turns signing off

        repos.setAuthor(repo.id, GitAuthor("A", "a@x"))
        assertEquals("A", repos.get(repo.id)!!.authorName)

        repos.remove(repo.id, deleteFiles = false)
        assertTrue(dir.exists())
        assertTrue(repos.repos.first().isEmpty())
    }

    @Test fun removeWithFilesDeletesFolder() = runTest {
        val dir = tmp.newFolder("scratch")
        engine.init(dir).close()
        val repo = repos.register(dir)
        repos.remove(repo.id, deleteFiles = true)
        assertFalse(dir.exists())
    }

    @Test fun containersKeepCreationOrder() = runTest {
        repos.createContainer("Work")
        repos.createContainer("Archive")
        assertEquals(listOf("Work", "Archive"), repos.containers.first().map { it.name })
    }
}
