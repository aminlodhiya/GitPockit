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
    indices = [Index("localPath", unique = true), Index("containerId"), Index("credentialId")],
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
)
