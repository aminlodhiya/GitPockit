package dev.gitpockit.core.git

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import dev.gitpockit.core.git.jgit.JschSetup
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/** SSH key generation and inspection. Private keys are returned unencrypted; callers encrypt them at rest. */
object SshKeys {

    init {
        JschSetup.ensure()
    }

    enum class Type { ED25519, RSA }

    class Generated(
        val privateKey: ByteArray,
        /** OpenSSH one-line public key, e.g. `ssh-ed25519 AAAA… comment`. Paste this into GitHub/GitLab. */
        val publicKey: String,
        /** `SHA256:…` fingerprint, as shown by GitHub. */
        val fingerprint: String,
    )

    fun generate(type: Type = Type.ED25519, comment: String = "gitpockit"): Generated {
        val jsch = JSch()
        val kp = when (type) {
            Type.ED25519 -> KeyPair.genKeyPair(jsch, KeyPair.ED25519)
            Type.RSA -> KeyPair.genKeyPair(jsch, KeyPair.RSA, 4096)
        }
        try {
            // OpenSSH v1 format (what modern ssh-keygen writes); PEM can't hold Ed25519 keys.
            val priv = ByteArrayOutputStream().also { kp.writeOpenSSHv1PrivateKey(it, null) }.toByteArray()
            val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }.toString(Charsets.UTF_8).trim()
            return Generated(priv, pub, fingerprint(kp.publicKeyBlob))
        } finally {
            kp.dispose()
        }
    }

    /**
     * Loads [privateKey] and returns its public key, or throws [GitException]
     * if the key is unreadable or the passphrase is wrong.
     */
    fun inspect(privateKey: ByteArray, passphrase: String?, comment: String = "gitpockit"): Generated {
        val jsch = JSch()
        val kp = try {
            KeyPair.load(jsch, privateKey, null)
        } catch (e: Exception) {
            throw GitException("Unsupported or malformed private key", e)
        }
        try {
            if (kp.isEncrypted) {
                if (passphrase.isNullOrEmpty()) throw GitException("This key is protected by a passphrase")
                if (!kp.decrypt(passphrase)) throw GitException("Wrong passphrase")
            }
            val blob = kp.publicKeyBlob ?: throw GitException("Could not read the public part of this key")
            val pub = ByteArrayOutputStream().also { kp.writePublicKey(it, comment) }.toString(Charsets.UTF_8).trim()
            return Generated(privateKey, pub, fingerprint(blob))
        } finally {
            kp.dispose()
        }
    }

    fun fingerprint(publicKeyBlob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }
}
