package dev.gitpockit.core.data.repository

import dev.gitpockit.core.data.db.CredentialDao
import dev.gitpockit.core.data.db.CredentialEntity
import dev.gitpockit.core.data.model.Credential
import dev.gitpockit.core.data.model.CredentialType
import dev.gitpockit.core.git.GitCredentials
import dev.gitpockit.core.git.GitException
import dev.gitpockit.core.git.SigningKey
import dev.gitpockit.core.git.SshKeys
import dev.gitpockit.core.git.TrustedKeys
import dev.gitpockit.core.git.signing.OpenPgp
import dev.gitpockit.core.security.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Saved HTTPS tokens and SSH keys. Secrets are encrypted with [cipher] before they touch the database. */
class CredentialRepository(
    private val dao: CredentialDao,
    private val cipher: SecretCipher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val credentials: Flow<List<Credential>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    /**
     * Saves a GitHub account (OAuth or personal access token). It's an HTTPS credential too, so the
     * same token clones and pushes. Signing in again as the same user replaces the old token.
     */
    suspend fun addGitHubAccount(login: String, token: String): Long {
        dao.all().filter { it.provider == Credential.PROVIDER_GITHUB && it.username.equals(login, ignoreCase = true) }
            .forEach { dao.delete(it.id) }
        val secret = withContext(Dispatchers.Default) { cipher.encrypt(token.toByteArray()) }
        return dao.insert(
            CredentialEntity(
                label = "GitHub: $login",
                type = CredentialType.HTTPS.name,
                username = login,
                secret = secret,
                createdAt = clock(),
                provider = Credential.PROVIDER_GITHUB,
            ),
        )
    }

    /** API token of a credential: GitHub accounts and HTTPS tokens qualify. */
    suspend fun token(id: Long): String? {
        val entity = dao.get(id) ?: return null
        if (entity.type != CredentialType.HTTPS.name) return null
        return withContext(Dispatchers.Default) { runCatching { String(cipher.decrypt(entity.secret)) }.getOrNull() }
    }

    suspend fun addHttps(label: String, username: String, token: String): Long {
        require(token.isNotBlank()) { "Token must not be empty" }
        val secret = withContext(Dispatchers.Default) { cipher.encrypt(token.toByteArray()) }
        return dao.insert(
            CredentialEntity(
                label = label.trim().ifBlank { username.ifBlank { "HTTPS token" } },
                type = CredentialType.HTTPS.name,
                username = username.trim(),
                secret = secret,
                createdAt = clock(),
            ),
        )
    }

    /** Imports an existing private key. Throws [GitException] if it can't be read or the passphrase is wrong. */
    suspend fun importSsh(label: String, privateKey: String, passphrase: String?): Long =
        withContext(Dispatchers.Default) {
            val keyBytes = normalizeKey(privateKey)
            val info = SshKeys.inspect(keyBytes, passphrase?.ifEmpty { null }, comment = label)
            insertSsh(label, keyBytes, passphrase?.ifEmpty { null }, info.publicKey, info.fingerprint)
        }

    /** Generates a new key pair on the device; the private key never leaves it. */
    suspend fun generateSsh(label: String, type: SshKeys.Type): Credential =
        withContext(Dispatchers.Default) {
            val key = SshKeys.generate(type, comment = label.ifBlank { "gitpockit" })
            val id = insertSsh(label, key.privateKey, null, key.publicKey, key.fingerprint)
            checkNotNull(dao.get(id)).toModel()
        }

    /** Generates an OpenPGP key for signing commits as "[name] <[email]>". */
    suspend fun generateGpg(label: String, name: String, email: String): Credential =
        withContext(Dispatchers.Default) {
            val key = OpenPgp.generate(name, email)
            checkNotNull(dao.get(insertGpg(label, key, null))).toModel()
        }

    /** Imports `gpg --export-secret-keys --armor` output. Throws [GitException] on a bad key or passphrase. */
    suspend fun importGpg(label: String, armoredSecret: String, passphrase: String?): Long =
        withContext(Dispatchers.Default) {
            val key = OpenPgp.inspect(armoredSecret.trim(), passphrase?.ifEmpty { null })
            insertGpg(label, key, passphrase?.ifEmpty { null })
        }

    private suspend fun insertGpg(label: String, key: OpenPgp.KeyInfo, passphrase: String?): Long = dao.insert(
        CredentialEntity(
            label = label.trim().ifBlank { key.userId.ifBlank { "GPG key" } },
            type = CredentialType.GPG.name,
            username = key.userId,
            secret = cipher.encrypt(key.armoredSecret.toByteArray()),
            passphrase = passphrase?.let { cipher.encrypt(it.toByteArray()) },
            publicKey = key.armoredPublic,
            fingerprint = key.fingerprint,
            createdAt = clock(),
        ),
    )

    /** Decrypted signing key for an SSH or GPG credential, or null (none / HTTPS / missing). */
    suspend fun signingKey(id: Long?): SigningKey? {
        if (id == null) return null
        val entity = dao.get(id) ?: return null
        return withContext(Dispatchers.Default) {
            val secret = runCatching { cipher.decrypt(entity.secret) }.getOrElse {
                throw GitException("Signing key \"${entity.label}\" can't be decrypted. Please add it again.", it)
            }
            val passphrase = entity.passphrase?.let { String(cipher.decrypt(it)) }
            when (CredentialType.valueOf(entity.type)) {
                CredentialType.SSH -> SigningKey.Ssh(secret, passphrase)
                CredentialType.GPG -> SigningKey.OpenPgp(String(secret), passphrase)
                CredentialType.HTTPS -> null
            }
        }
    }

    /** The user's own public keys, to show their commits as verified. */
    suspend fun trustedKeys(): TrustedKeys {
        val all = dao.all()
        return TrustedKeys(
            sshFingerprints = all.filter { it.type == CredentialType.SSH.name }.mapNotNull { it.fingerprint }.toSet(),
            pgpPublicKeys = all.filter { it.type == CredentialType.GPG.name }.mapNotNull { it.publicKey },
        )
    }

    suspend fun rename(id: Long, label: String) = dao.rename(id, label.trim())

    suspend fun delete(id: Long) = dao.delete(id)

    /** Decrypts the credential for a network operation. `null` id means anonymous access. */
    suspend fun resolve(id: Long?): GitCredentials {
        if (id == null) return GitCredentials.None
        val entity = dao.get(id) ?: return GitCredentials.None
        return withContext(Dispatchers.Default) {
            val secret = runCatching { cipher.decrypt(entity.secret) }.getOrElse {
                throw GitException("Saved credential \"${entity.label}\" can't be decrypted. Please add it again.", it)
            }
            when (CredentialType.valueOf(entity.type)) {
                CredentialType.HTTPS -> GitCredentials.UserPassword(entity.username, String(secret))
                CredentialType.SSH -> GitCredentials.SshKey(
                    privateKey = secret,
                    passphrase = entity.passphrase?.let { String(cipher.decrypt(it)) },
                    username = entity.username,
                )
                CredentialType.GPG -> GitCredentials.None // signing only, not for network access
            }
        }
    }

    private suspend fun insertSsh(label: String, key: ByteArray, passphrase: String?, publicKey: String, fingerprint: String): Long =
        dao.insert(
            CredentialEntity(
                label = label.trim().ifBlank { "SSH key" },
                type = CredentialType.SSH.name,
                username = "git",
                secret = cipher.encrypt(key),
                passphrase = passphrase?.let { cipher.encrypt(it.toByteArray()) },
                publicKey = publicKey,
                fingerprint = fingerprint,
                createdAt = clock(),
            ),
        )

    private fun normalizeKey(text: String): ByteArray {
        // Pasted keys often lose their trailing newline or gain Windows line endings.
        val cleaned = text.trim().replace("\r\n", "\n")
        if (cleaned.isEmpty()) throw GitException("Paste a private key")
        return (cleaned + "\n").toByteArray()
    }

    private fun CredentialEntity.toModel() = Credential(
        id = id,
        label = label,
        type = CredentialType.valueOf(type),
        username = username,
        publicKey = publicKey,
        fingerprint = fingerprint,
        provider = provider,
    )
}
