package dev.gitpockit.core.data.model

import java.io.File

data class Container(val id: Long, val name: String)

enum class CredentialType {
    HTTPS,
    SSH,
    /** OpenPGP key used only for signing commits. */
    GPG,
}

/** A saved credential. Secrets are never exposed here; see CredentialRepository.resolve. */
data class Credential(
    val id: Long,
    val label: String,
    val type: CredentialType,
    val username: String,
    val publicKey: String?,
    val fingerprint: String?,
    /** "github" for a GitHub account sign-in. */
    val provider: String? = null,
) {
    val isGitHubAccount: Boolean get() = provider == PROVIDER_GITHUB

    companion object {
        const val PROVIDER_GITHUB = "github"
    }
}

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
    val sync: SyncSettings = SyncSettings(),
    /** SSH or GPG credential used to sign commits; null = unsigned. */
    val signingCredentialId: Long? = null,
    /** Local commits are waiting for the network to be pushed. */
    val pendingSync: Boolean = false,
)
