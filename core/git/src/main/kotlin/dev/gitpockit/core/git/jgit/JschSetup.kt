package dev.gitpockit.core.git.jgit

import com.jcraft.jsch.JSch

/**
 * Makes JSch use its BouncyCastle implementations for Ed25519/Ed448 keys and X25519 key exchange.
 *
 * Its default JCA-based classes don't work reliably on Android:
 * - Android's runtime loads the pre-Java-15 base classes from JSch's multi-release jar, which
 *   throw "requires Java15+".
 * - Where a JCA provider such as Conscrypt does supply Ed25519, it returns key classes JSch can't cast.
 *
 * BouncyCastle is bundled with the app, so we use it everywhere for identical behaviour on
 * phones, Robolectric and plain JVM tests.
 */
internal object JschSetup {
    @Volatile private var done = false

    fun ensure() {
        if (done) return
        synchronized(this) {
            if (done) return
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            done = true
        }
    }
}
