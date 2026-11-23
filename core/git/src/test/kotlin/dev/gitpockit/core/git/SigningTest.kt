package dev.gitpockit.core.git

import dev.gitpockit.core.git.jgit.JGitEngine
import dev.gitpockit.core.git.signing.OpenPgp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class SigningTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var engine: JGitEngine
    private lateinit var repo: GitRepository
    private val me = GitAuthor("Pocket Tester", "tester@example.com")

    @Before fun setUp() = runTest {
        engine = JGitEngine(tmp.newFolder("home"))
        repo = engine.init(tmp.newFolder("work"))
        repo.setIdentity(me)
    }

    private fun file(p: String) = File(repo.workTree, p)

    private suspend fun commit(path: String, text: String, msg: String = "edit $path"): CommitInfo {
        file(path).apply { parentFile.mkdirs() }.writeText(text)
        repo.stageAll()
        return repo.commit(msg, me)
    }

    private fun tool(name: String) = listOf("/usr/bin/$name", "/usr/local/bin/$name").any { File(it).canExecute() }

    /** Runs a command and returns (exit code, combined output). */
    private fun run(dir: File, env: Map<String, String> = emptyMap(), vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).apply { environment().putAll(env) }.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    // ---- SSH

    private fun sshTrust(key: SshKeys.Generated) = TrustedKeys(sshFingerprints = setOf(key.fingerprint))

    @Test fun `ssh signed commits verify with our verifier`() = runTest {
        for (type in SshKeys.Type.entries) {
            val key = SshKeys.generate(type, "tester")
            repo.setSigning(SigningKey.Ssh(key.privateKey, null))
            val c = commit("f-$type.txt", "x", "signed with $type")
            val info = repo.signature(c.id, sshTrust(key))
            assertEquals("$type: ${info.message}", SignatureState.VERIFIED, info.state)
            assertEquals("ssh", info.format)
            assertEquals(key.fingerprint, info.fingerprint)
            // Someone else's key: still a valid signature, but not one of ours.
            assertEquals(SignatureState.UNKNOWN_KEY, repo.signature(c.id, TrustedKeys()).state)
        }
    }

    @Test fun `ssh signatures are accepted by real git verify-commit`() = runTest {
        assumeTrue(tool("git") && tool("ssh-keygen"))
        for (type in SshKeys.Type.entries) {
            val key = SshKeys.generate(type, "tester")
            repo.setSigning(SigningKey.Ssh(key.privateKey, null))
            commit("g-$type.txt", "y", "git verify $type")
            val allowed = tmp.newFile("allowed-$type").apply { writeText("${me.email} ${key.publicKey}\n") }
            val (code, out) = run(
                repo.workTree, emptyMap(),
                "git", "-c", "gpg.format=ssh", "-c", "gpg.ssh.allowedSignersFile=${allowed.path}", "verify-commit", "HEAD",
            )
            assertEquals("$type: $out", 0, code)
            assertTrue(out, out.contains("Good \"git\" signature"))
        }
    }

    @Test fun `merge cherry-pick revert rebase and annotated tags are all signed`() = runTest {
        val key = SshKeys.generate(SshKeys.Type.ED25519, "tester")
        val trust = sshTrust(key)
        commit("base.txt", "base", "root")
        repo.setSigning(SigningKey.Ssh(key.privateKey, null))

        repo.createBranch("side", checkout = true)
        val pick = commit("side.txt", "s", "side work")
        repo.checkout("main")
        commit("main.txt", "m", "main work")
        assertEquals(OperationResult.Done, repo.merge("side"))
        assertEquals(SignatureState.VERIFIED, repo.signature(repo.log(1).single().id, trust).state)

        repo.createBranch("picks", checkout = true)
        repo.reset(repo.log().last().id, ResetMode.HARD)
        assertEquals(OperationResult.Done, repo.cherryPick(pick.id))
        assertEquals(SignatureState.VERIFIED, repo.signature(repo.log(1).single().id, trust).state)
        assertEquals(OperationResult.Done, repo.revert(repo.log(1).single().id))
        assertEquals(SignatureState.VERIFIED, repo.signature(repo.log(1).single().id, trust).state)

        repo.checkout("side")
        commit("side2.txt", "s2", "more side work") // diverge again so the rebase replays a commit
        assertEquals(OperationResult.Done, repo.rebase("main"))
        assertEquals(SignatureState.VERIFIED, repo.signature(repo.log(1).single().id, trust).state)

        repo.createTag("v1", "signed tag", null, me)
        val (code, out) = run(repo.workTree, emptyMap(), "git", "cat-file", "-p", "v1")
        if (tool("git")) assertTrue(out, code == 0 && out.contains("BEGIN SSH SIGNATURE"))
    }

    @Test fun `signing off means unsigned and tampering is detected`() = runTest {
        val unsigned = commit("a.txt", "a", "unsigned")
        assertEquals(SignatureState.UNSIGNED, repo.signature(unsigned.id, TrustedKeys()).state)

        val key = SshKeys.generate(SshKeys.Type.ED25519, "tester")
        repo.setSigning(SigningKey.Ssh(key.privateKey, null))
        commit("b.txt", "b", "signed")
        repo.setSigning(null)
        val off = commit("c.txt", "c", "off again")
        assertEquals(SignatureState.UNSIGNED, repo.signature(off.id, TrustedKeys()).state)

        // Re-sign a tampered commit message by editing the raw object: the signature must fail.
        assumeTrue(tool("git"))
        val (_, raw) = run(repo.workTree, emptyMap(), "git", "cat-file", "commit", "HEAD~1")
        val forged = raw.replace("signed", "forged")
        val obj = tmp.newFile("forged.txt").apply { writeText(forged) }
        val (code, id) = run(repo.workTree, emptyMap(), "git", "hash-object", "-t", "commit", "-w", obj.path)
        assertEquals(id, 0, code)
        assertEquals(SignatureState.BAD, repo.signature(id.trim(), sshTrust(key)).state)
    }

    @Test fun `repo config asking for gpg signing does not break commits`() = runTest {
        // A repo cloned from a PC may say commit.gpgsign=true with a key that isn't on the phone.
        File(repo.workTree, ".git/config").appendText("[commit]\n\tgpgsign = true\n[user]\n\tsigningkey = ABCDEF\n")
        val reopened = engine.open(repo.workTree)
        reopened.setIdentity(me)
        reopened.setSigning(null) // what RepoOps does for repos without a signing key
        File(repo.workTree, "z.txt").writeText("z")
        reopened.stageAll()
        reopened.commit("still works", me)
        assertEquals("still works", reopened.log(1).single().title)
    }

    // ---- OpenPGP

    @Test fun `openpgp generate import sign and verify`() = runTest {
        val key = OpenPgp.generate(me.name, me.email)
        assertTrue(key.armoredPublic.contains("BEGIN PGP PUBLIC KEY BLOCK"))
        assertEquals(40, key.fingerprint.length)
        assertEquals("Pocket Tester <tester@example.com>", key.userId)
        assertEquals(key.fingerprint, OpenPgp.inspect(key.armoredSecret, null).fingerprint)

        repo.setSigning(SigningKey.OpenPgp(key.armoredSecret, null))
        val c = commit("p.txt", "p", "pgp signed")
        val ok = repo.signature(c.id, TrustedKeys(pgpPublicKeys = listOf(key.armoredPublic)))
        assertEquals(ok.message, SignatureState.VERIFIED, ok.state)
        assertEquals(key.fingerprint, ok.fingerprint)
        assertEquals(SignatureState.UNKNOWN_KEY, repo.signature(c.id, TrustedKeys()).state)
    }

    @Test fun `openpgp signatures are accepted by real gpg via git verify-commit`() = runTest {
        assumeTrue(tool("git") && tool("gpg"))
        val key = OpenPgp.generate(me.name, me.email)
        repo.setSigning(SigningKey.OpenPgp(key.armoredSecret, null))
        commit("q.txt", "q", "verify with gpg")

        val home = tmp.newFolder("gnupg").apply { setReadable(true, true); setExecutable(true, true) }
        val env = mapOf("GNUPGHOME" to home.path)
        val pub = tmp.newFile("pub.asc").apply { writeText(key.armoredPublic) }
        val (importCode, importOut) = run(repo.workTree, env, "gpg", "--batch", "--import", pub.path)
        assertEquals(importOut, 0, importCode)
        val (code, out) = run(repo.workTree, env, "git", "verify-commit", "HEAD")
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("Good signature"))
    }

    @Test fun `importing a passphrase protected gpg key`() = runTest {
        assumeTrue(tool("gpg"))
        val home = tmp.newFolder("gnupg2")
        val env = mapOf("GNUPGHOME" to home.path)
        val (genCode, genOut) = run(
            tmp.root, env,
            "gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase", "s3cret",
            "--quick-gen-key", "Imported <imp@example.com>", "ed25519", "sign", "never",
        )
        assumeTrue(genOut, genCode == 0)
        val (_, secret) = run(tmp.root, env, "gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase", "s3cret", "--armor", "--export-secret-keys", "imp@example.com")
        val (_, public) = run(tmp.root, env, "gpg", "--armor", "--export", "imp@example.com")

        val wrong = runCatching { OpenPgp.inspect(secret, "nope") }.exceptionOrNull()
        assertTrue(wrong is GitException)
        val info = OpenPgp.inspect(secret, "s3cret")
        assertEquals("Imported <imp@example.com>", info.userId)

        repo.setSigning(SigningKey.OpenPgp(secret, "s3cret"))
        val c = commit("e.txt", "e", "ed25519 gpg")
        assertEquals(SignatureState.VERIFIED, repo.signature(c.id, TrustedKeys(pgpPublicKeys = listOf(public))).state)
        val (code, out) = run(repo.workTree, env, "git", "verify-commit", "HEAD")
        assertEquals(out, 0, code)
    }
}
