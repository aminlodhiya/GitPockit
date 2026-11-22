package dev.gitpockit.core.data.model

import java.io.File

data class Container(val id: Long, val name: String)

enum class CredentialType { HTTPS, SSH }

/** A saved credential. Secrets are never exposed here; see CredentialRepository.resolve. */
data class Credential(
    val id: Long,
    val label: String,
    val type: CredentialType,
    val username: String,
    val publicKey: String?,
    val fingerprint: String?,
)

data class Repo(
    val id: Long,
    val name: String,
    val directory: File,
    val containerId: Long?,
    val credentialId: Long?,
    val remoteName: String,
    val authorName: String?,
    val authorEmail: String?,
    val lastSyncAt: Long?,
    val lastError: String?,
)
