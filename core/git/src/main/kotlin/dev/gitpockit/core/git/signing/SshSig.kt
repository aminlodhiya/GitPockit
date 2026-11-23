package dev.gitpockit.core.git.signing

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import dev.gitpockit.core.git.GitException
import dev.gitpockit.core.git.SshKeys
import dev.gitpockit.core.git.jgit.JschSetup
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * SSH signatures in OpenSSH's SSHSIG format (PROTOCOL.sshsig), as used by `git commit -S` with
 * `gpg.format=ssh` and verified by GitHub/GitLab ("Verified" when the key is added as a signing key).
 */
object SshSig {
    private const val MAGIC = "SSHSIG"
    private const val VERSION = 1
    private const val HASH = "sha512"
    const val BEGIN = "-----BEGIN SSH SIGNATURE-----"
    private const val END = "-----END SSH SIGNATURE-----"

    init {
        JschSetup.ensure()
    }

    /** Signs [data] with an OpenSSH/PEM private key; returns the armored signature. */
    fun sign(privateKey: ByteArray, passphrase: String?, data: ByteArray, namespace: String = "git"): String {
        val kp = load(privateKey, passphrase)
        try {
            val alg = when (kp.keyTypeString) {
                "ssh-rsa" -> "rsa-sha2-512" // SHA-1 RSA signatures are rejected by modern verifiers
                else -> kp.keyTypeString
            }
            val signature = kp.getSignature(signedData(namespace, data), alg)
                ?: throw GitException("This SSH key type can't sign")
            val blob = ByteArrayOutputStream().apply {
                write(MAGIC.toByteArray())
                writeInt(VERSION)
                writeString(kp.publicKeyBlob)
                writeString(namespace.toByteArray())
                writeString(ByteArray(0)) // reserved
                writeString(HASH.toByteArray())
                writeString(signature)
            }.toByteArray()
            val b64 = Base64.getEncoder().encodeToString(blob).chunked(70).joinToString("\n")
            return "$BEGIN\n$b64\n$END\n"
        } finally {
            kp.dispose()
        }
    }

    data class Verification(val valid: Boolean, val fingerprint: String?, val keyType: String?, val message: String?)

    /** Checks the signature cryptographically; [Verification.fingerprint] identifies the signing key. */
    fun verify(armored: String, data: ByteArray, namespace: String = "git"): Verification {
        val blob = runCatching {
            Base64.getMimeDecoder().decode(armored.substringAfter(BEGIN).substringBefore(END).trim())
        }.getOrElse { return Verification(false, null, null, "Malformed SSH signature") }
        val buf = ByteBuffer.wrap(blob)
        val magic = ByteArray(6).also { buf.get(it) }
        if (String(magic) != MAGIC || buf.int != VERSION) return Verification(false, null, null, "Not an SSH signature")
        val pubBlob = buf.readString()
        val ns = String(buf.readString())
        buf.readString() // reserved
        val hash = String(buf.readString())
        val sigBlob = buf.readString()
        val fingerprint = SshKeys.fingerprint(pubBlob)
        if (ns != namespace) return Verification(false, fingerprint, null, "Signature is for \"$ns\", not \"$namespace\"")
        val digest = when (hash) {
            "sha512" -> "SHA-512"
            "sha256" -> "SHA-256"
            else -> return Verification(false, fingerprint, null, "Unsupported hash $hash")
        }
        val message = signedData(namespace, data, hash, digest)
        val pub = ByteBuffer.wrap(pubBlob)
        val keyType = String(pub.readString())
        val sig = ByteBuffer.wrap(sigBlob)
        val sigType = String(sig.readString())
        val raw = sig.readString()
        val ok = runCatching {
            when (keyType) {
                "ssh-ed25519" -> Ed25519Signer().run {
                    init(false, Ed25519PublicKeyParameters(pub.readString(), 0))
                    update(message, 0, message.size)
                    verifySignature(raw)
                }
                "ssh-rsa" -> {
                    val e = BigInteger(1, pub.readString())
                    val n = BigInteger(1, pub.readString())
                    val algorithm = when (sigType) {
                        "rsa-sha2-512" -> "SHA512withRSA"
                        "rsa-sha2-256" -> "SHA256withRSA"
                        else -> return Verification(false, fingerprint, keyType, "Insecure RSA signature type $sigType")
                    }
                    Signature.getInstance(algorithm).run {
                        initVerify(KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e)))
                        update(message)
                        verify(raw)
                    }
                }
                else -> return Verification(false, fingerprint, keyType, "Can't check $keyType signatures yet")
            }
        }.getOrDefault(false)
        return Verification(ok, fingerprint, keyType, if (ok) null else "Signature doesn't match")
    }

    private fun load(privateKey: ByteArray, passphrase: String?): KeyPair {
        val kp = runCatching { KeyPair.load(JSch(), privateKey, null) }
            .getOrElse { throw GitException("Unsupported or malformed SSH key", it) }
        if (kp.isEncrypted && !kp.decrypt(passphrase.orEmpty())) {
            kp.dispose()
            throw GitException("Wrong passphrase for the signing key")
        }
        return kp
    }

    private fun signedData(namespace: String, data: ByteArray, hash: String = HASH, digest: String = "SHA-512"): ByteArray =
        ByteArrayOutputStream().apply {
            write(MAGIC.toByteArray())
            writeString(namespace.toByteArray())
            writeString(ByteArray(0))
            writeString(hash.toByteArray())
            writeString(MessageDigest.getInstance(digest).digest(data))
        }.toByteArray()

    private fun ByteArrayOutputStream.writeInt(v: Int) = write(ByteBuffer.allocate(4).putInt(v).array())

    private fun ByteArrayOutputStream.writeString(b: ByteArray) {
        writeInt(b.size)
        write(b)
    }

    private fun ByteBuffer.readString(): ByteArray = ByteArray(int).also { get(it) }
}
