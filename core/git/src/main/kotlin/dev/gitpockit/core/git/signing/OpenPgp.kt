package dev.gitpockit.core.git.signing

import dev.gitpockit.core.git.GitException
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.Features
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Date

/** OpenPGP ("GPG") keys and detached signatures for signed commits, using BouncyCastle. */
object OpenPgp {

    class KeyInfo(
        /** ASCII-armored secret key ring (store encrypted). */
        val armoredSecret: String,
        /** ASCII-armored public key: paste into GitHub → Settings → SSH and GPG keys. */
        val armoredPublic: String,
        /** 40-hex-digit fingerprint of the signing key. */
        val fingerprint: String,
        val userId: String,
    )

    /** Generates an RSA-3072 key (the gpg default) that can sign, for "Name <email>". */
    fun generate(name: String, email: String): KeyInfo {
        val userId = "$name <$email>"
        val gen = RSAKeyPairGenerator().apply {
            init(RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), SecureRandom(), 3072, 80))
        }
        val keyPair = BcPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, gen.generateKeyPair(), Date())
        val subpackets = PGPSignatureSubpacketGenerator().apply {
            setKeyFlags(false, KeyFlags.CERTIFY_OTHER or KeyFlags.SIGN_DATA)
            setPreferredHashAlgorithms(false, intArrayOf(HashAlgorithmTags.SHA512, HashAlgorithmTags.SHA256))
            setFeature(false, Features.FEATURE_MODIFICATION_DETECTION)
        }
        val sha1 = BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1)
        val ring = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            keyPair,
            userId,
            sha1,
            subpackets.generate(),
            null,
            BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.RSA_GENERAL, HashAlgorithmTags.SHA256),
            null, // not passphrase-protected here: the app encrypts it with the Android Keystore
        )
        val secret = ring.generateSecretKeyRing()
        return info(secret, ring.generatePublicKeyRing())
    }

    /** Reads an exported secret key (`gpg --export-secret-keys --armor`) and checks the passphrase. */
    fun inspect(armoredSecret: String, passphrase: String?): KeyInfo {
        val ring = readSecretRing(armoredSecret)
        val key = signingKey(ring)
        unlock(key, passphrase) // throws on a wrong passphrase
        val publicRing = PGPPublicKeyRing(ring.publicKeys.asSequence().toList())
        return info(ring, publicRing)
    }

    /** Creates an armored detached signature over [data]. */
    fun sign(armoredSecret: String, passphrase: String?, data: ByteArray): String {
        val ring = readSecretRing(armoredSecret)
        val key = signingKey(ring)
        val privateKey = unlock(key, passphrase)
        val generator = PGPSignatureGenerator(
            BcPGPContentSignerBuilder(key.publicKey.algorithm, HashAlgorithmTags.SHA256),
            key.publicKey,
        )
        generator.init(PGPSignature.BINARY_DOCUMENT, privateKey)
        generator.setHashedSubpackets(
            PGPSignatureSubpacketGenerator().apply {
                setSignatureCreationTime(false, Date())
                setIssuerFingerprint(false, key.publicKey)
            }.generate(),
        )
        generator.update(data)
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armor -> BCPGOutputStream(armor).use { generator.generate().encode(it) } }
        return out.toString(Charsets.US_ASCII)
    }

    data class Verification(val valid: Boolean, val fingerprint: String?, val keyId: String?, val message: String?)

    /**
     * Verifies [armoredSignature] over [data] against the public keys in [publicKeys]
     * (armored). Without the signer's public key, only the key id can be reported.
     */
    fun verify(armoredSignature: String, data: ByteArray, publicKeys: List<String>): Verification {
        val signature = runCatching {
            val factory = BcPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(armoredSignature.toByteArray())))
            generateSequence { factory.nextObject() }.filterIsInstance<PGPSignatureList>().first()[0]
        }.getOrElse { return Verification(false, null, null, "Malformed OpenPGP signature") }
        val keyId = java.lang.Long.toHexString(signature.keyID).uppercase().padStart(16, '0')
        val key = publicKeys.asSequence()
            .mapNotNull { runCatching { readPublicRing(it) }.getOrNull() }
            .mapNotNull { ring -> ring.getPublicKey(signature.keyID) }
            .firstOrNull() ?: return Verification(false, null, keyId, "Signed by an unknown key ($keyId)")
        signature.init(BcPGPContentVerifierBuilderProvider(), key)
        signature.update(data)
        val ok = runCatching { signature.verify() }.getOrDefault(false)
        return Verification(ok, hex(key.fingerprint), keyId, if (ok) null else "Signature doesn't match")
    }

    private fun info(secret: PGPSecretKeyRing, public: PGPPublicKeyRing): KeyInfo {
        val key = signingKey(secret)
        return KeyInfo(
            armoredSecret = armor(secret.encoded),
            armoredPublic = armor(public.encoded),
            fingerprint = hex(key.publicKey.fingerprint),
            userId = key.publicKey.userIDs.asSequence().firstOrNull()
                ?: secret.publicKey.userIDs.asSequence().firstOrNull().orEmpty(),
        )
    }

    private fun readSecretRing(armored: String): PGPSecretKeyRing = runCatching {
        PGPSecretKeyRingCollection(PGPUtil.getDecoderStream(ByteArrayInputStream(armored.trim().toByteArray())), BcKeyFingerprintCalculator())
            .keyRings.next()
    }.getOrElse { throw GitException("Not an OpenPGP secret key. Export it with: gpg --export-secret-keys --armor <key>", it) }

    private fun readPublicRing(armored: String): PGPPublicKeyRing =
        PGPPublicKeyRing(PGPUtil.getDecoderStream(ByteArrayInputStream(armored.trim().toByteArray())), BcKeyFingerprintCalculator())

    /** The newest key in the ring that's allowed to sign (often a subkey). */
    private fun signingKey(ring: PGPSecretKeyRing): PGPSecretKey =
        ring.secretKeys.asSequence()
            .filter { it.isSigningKey && !it.isPrivateKeyEmpty && canSign(it.publicKey) }
            .maxByOrNull { it.publicKey.creationTime }
            ?: throw GitException("This OpenPGP key has no secret signing key")

    private fun canSign(key: PGPPublicKey): Boolean {
        if (key.isMasterKey) return true
        val flags = key.getSignaturesOfType(PGPSignature.SUBKEY_BINDING).asSequence()
            .mapNotNull { it.hashedSubPackets?.keyFlags }.firstOrNull() ?: return false
        return flags and KeyFlags.SIGN_DATA != 0
    }

    private fun unlock(key: PGPSecretKey, passphrase: String?) = runCatching {
        key.extractPrivateKey(BcPBESecretKeyDecryptorBuilder(BcPGPDigestCalculatorProvider()).build(passphrase.orEmpty().toCharArray()))
    }.getOrElse { throw GitException(if (passphrase.isNullOrEmpty()) "This key is protected by a passphrase" else "Wrong passphrase", it) }

    private fun armor(bytes: ByteArray): String {
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { it.write(bytes) }
        return out.toString(Charsets.US_ASCII)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }
}
