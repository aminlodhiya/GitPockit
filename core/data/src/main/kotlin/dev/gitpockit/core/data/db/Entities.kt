package dev.gitpockit.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "containers")
data class ContainerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int = 0,
)

@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    /** "HTTPS" or "SSH". */
    val type: String,
    val username: String,
    /** Encrypted token/password (HTTPS) or private key (SSH). */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val secret: ByteArray,
    /** Encrypted SSH key passphrase, if any. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val passphrase: ByteArray? = null,
    val publicKey: String? = null,
    val fingerprint: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "repos",
    indices = [Index("localPath", unique = true), Index("containerId"), Index("credentialId"), Index("signingCredentialId")],
    foreignKeys = [
        ForeignKey(
            entity = ContainerEntity::class,
            parentColumns = ["id"],
            childColumns = ["containerId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = CredentialEntity::class,
            parentColumns = ["id"],
            childColumns = ["credentialId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = CredentialEntity::class,
            parentColumns = ["id"],
            childColumns = ["signingCredentialId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class RepoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val localPath: String,
    val containerId: Long? = null,
    val credentialId: Long? = null,
    val remoteName: String = "origin",
    /** Per-repo commit author; null means use the global author from settings. */
    val authorName: String? = null,
    val authorEmail: String? = null,
    val createdAt: Long,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    // --- Sync settings (v2) ---
    /** FULL = commit, pull and push; PULL_ONLY = never commit or push. */
    @ColumnInfo(defaultValue = "FULL") val syncMode: String = "FULL",
    /** ASK, KEEP_LOCAL, KEEP_REMOTE or KEEP_BOTH. */
    @ColumnInfo(defaultValue = "ASK") val conflictStrategy: String = "ASK",
    /** Auto-commit message template; null = default. */
    val commitTemplate: String? = null,
    /** Background sync interval in minutes; 0 = off. */
    @ColumnInfo(defaultValue = "0") val syncIntervalMinutes: Int = 0,
    @ColumnInfo(defaultValue = "0") val syncOnChange: Boolean = false,
    @ColumnInfo(defaultValue = "0") val wifiOnly: Boolean = false,
    /** Local work is waiting to be pushed (e.g. the last sync was offline). */
    @ColumnInfo(defaultValue = "0") val pendingSync: Boolean = false,
    /** Daily sync times as minutes after midnight, comma separated, e.g. "540,1290" (v3). */
    @ColumnInfo(defaultValue = "") val syncTimes: String = "",
    /** Pull with rebase instead of merge, for a linear history (v4). */
    @ColumnInfo(defaultValue = "0") val pullRebase: Boolean = false,
    /** Credential (SSH or GPG key) used to sign commits (v5). */
    val signingCredentialId: Long? = null,
    /** Download Git LFS files automatically on pull (v6). */
    @ColumnInfo(defaultValue = "1") val lfsDownload: Boolean = true,
    /** Update submodules after pulling (v6). */
    @ColumnInfo(defaultValue = "1") val updateSubmodules: Boolean = true,
)

@Entity(
    tableName = "sync_log",
    indices = [Index("repoId")],
    foreignKeys = [ForeignKey(entity = RepoEntity::class, parentColumns = ["id"], childColumns = ["repoId"], onDelete = ForeignKey.CASCADE)],
)
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val repoId: Long,
    val trigger: String,
    val startedAt: Long,
    val durationMs: Long,
    val result: String,
    val message: String? = null,
)

@Entity(
    tableName = "app_triggers",
    primaryKeys = ["packageName", "repoId"],
    indices = [Index("repoId")],
    foreignKeys = [ForeignKey(entity = RepoEntity::class, parentColumns = ["id"], childColumns = ["repoId"], onDelete = ForeignKey.CASCADE)],
)
data class AppTriggerEntity(
    val packageName: String,
    val repoId: Long,
    val onOpen: Boolean,
    val onClose: Boolean,
)
