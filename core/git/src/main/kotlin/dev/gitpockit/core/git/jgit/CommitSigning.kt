package dev.gitpockit.core.git.jgit

import dev.gitpockit.core.git.SignatureInfo
import dev.gitpockit.core.git.SignatureState
import dev.gitpockit.core.git.SigningKey
import dev.gitpockit.core.git.TrustedKeys
import dev.gitpockit.core.git.signing.OpenPgp
import dev.gitpockit.core.git.signing.SshSig
import org.eclipse.jgit.api.errors.CanceledException
import org.eclipse.jgit.lib.GpgConfig
import org.eclipse.jgit.lib.GpgSignature
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.Signer
import org.eclipse.jgit.lib.Signers
import org.eclipse.jgit.transport.CredentialsProvider
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Plugs GitPockit's signing into JGit. Every commit JGit creates (normal commits, merges,
 * cherry-picks, reverts, rebases) and annotated tags are signed when the repository's
 * in-memory config says so, using the key registered here under `user.signingKey`.
 */
internal object CommitSigning : Signer {
    private val keys = ConcurrentHashMap<String, SigningKey>()

    init {
        Signers.set(GpgConfig.GpgFormat.OPENPGP, this)
        Signers.set(GpgConfig.GpgFormat.SSH, this)
    }

    /** Registers [key] and returns the token to put in `user.signingKey`. */
    fun register(key: SigningKey): String {
        val material = when (key) {
            is SigningKey.Ssh -> key.privateKey
            is SigningKey.OpenPgp -> key.armoredSecret.toByteArray()
        }
        val token = "gitpockit:" + MessageDigest.getInstance("SHA-256").digest(material).take(12).joinToString("") { "%02x".format(it) }
        keys[token] = key
        return token
    }

    override fun sign(
        repository: Repository,
        config: GpgConfig,
        data: ByteArray,
        committer: PersonIdent,
        signingKey: String?,
        credentialsProvider: CredentialsProvider?,
    ): GpgSignature {
        val key = keyFor(repository, config, signingKey) ?: throw CanceledException("Signing key isn't available")
        val armored = when (key) {
            is SigningKey.Ssh -> SshSig.sign(key.privateKey, key.passphrase, data)
            is SigningKey.OpenPgp -> OpenPgp.sign(key.armoredSecret, key.passphrase, data)
        }
        return GpgSignature(armored.toByteArray(Charsets.US_ASCII))
    }

    override fun canLocateSigningKey(
        repository: Repository,
        config: GpgConfig,
        committer: PersonIdent,
        signingKey: String?,
        credentialsProvider: CredentialsProvider?,
    ): Boolean = keyFor(repository, config, signingKey) != null

    /**
     * Some JGit paths (e.g. rebase) don't pass the key name, so fall back to the configuration.
     * ConcurrentHashMap rejects null lookups, hence the explicit checks.
     */
    private fun keyFor(repository: Repository, config: GpgConfig, signingKey: String?): SigningKey? {
        val token = signingKey ?: config.signingKey ?: repository.config.getString("user", null, "signingKey") ?: return null
        return keys[token]
    }

    /** Turns signing on (with [key]) or off for this repository, in memory only. */
    fun configure(repository: Repository, key: SigningKey?) {
        val cfg = repository.config
        if (key == null) {
            cfg.setBoolean("commit", null, "gpgSign", false)
            cfg.setBoolean("tag", null, "gpgSign", false)
            cfg.unset("user", null, "signingKey")
            return
        }
        cfg.setString("gpg", null, "format", if (key is SigningKey.Ssh) "ssh" else "openpgp")
        cfg.setString("user", null, "signingKey", register(key))
        cfg.setBoolean("commit", null, "gpgSign", true)
        cfg.setBoolean("tag", null, "gpgSign", true)
    }

    /** Checks the signature of a raw commit object (as stored by Git). */
    fun verify(rawCommit: ByteArray, trusted: TrustedKeys): SignatureInfo {
        val (data, signature) = split(rawCommit) ?: return SignatureInfo(SignatureState.UNSIGNED, null, null, null)
        return when {
            signature.contains(SshSig.BEGIN) -> {
                val v = SshSig.verify(signature, data)
                val state = when {
                    !v.valid -> SignatureState.BAD
                    v.fingerprint in trusted.sshFingerprints -> SignatureState.VERIFIED
                    else -> SignatureState.UNKNOWN_KEY
                }
                SignatureInfo(state, "ssh", v.fingerprint, v.message)
            }
            signature.contains("BEGIN PGP SIGNATURE") -> {
                val v = OpenPgp.verify(signature, data, trusted.pgpPublicKeys)
                val state = when {
                    v.valid -> SignatureState.VERIFIED
                    v.fingerprint == null -> SignatureState.UNKNOWN_KEY // we don't have that public key
                    else -> SignatureState.BAD
                }
                SignatureInfo(state, "openpgp", v.fingerprint ?: v.keyId, v.message)
            }
            else -> SignatureInfo(SignatureState.UNKNOWN_KEY, "x509", null, "Unsupported signature format")
        }
    }

    /**
     * Splits a raw commit into (signed data, armored signature): Git signs the commit text with
     * the `gpgsig` header (and its indented continuation lines) removed.
     */
    internal fun split(raw: ByteArray): Pair<ByteArray, String>? {
        val text = String(raw, Charsets.UTF_8)
        val headerEnd = text.indexOf("\n\n").let { if (it < 0) text.length else it }
        val lines = text.substring(0, headerEnd).split('\n')
        val start = lines.indexOfFirst { it.startsWith("gpgsig ") || it.startsWith("gpgsig-sha256 ") }
        if (start < 0) return null
        var end = start + 1
        while (end < lines.size && lines[end].startsWith(" ")) end++
        val signature = (listOf(lines[start].substringAfter(' ')) + lines.subList(start + 1, end).map { it.substring(1) }).joinToString("\n") + "\n"
        val kept = lines.subList(0, start) + lines.subList(end, lines.size)
        val data = ByteArrayOutputStream().apply {
            write(kept.joinToString("\n").toByteArray(Charsets.UTF_8))
            write(text.substring(headerEnd).toByteArray(Charsets.UTF_8))
        }.toByteArray()
        return data to signature
    }
}
