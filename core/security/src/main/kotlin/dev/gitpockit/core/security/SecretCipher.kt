package dev.gitpockit.core.security

/** Encrypts small secrets (tokens, passwords, private keys) for storage at rest. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}
