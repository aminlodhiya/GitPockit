plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.jgit)
    implementation(libs.jgit.ssh.jsch) {
        exclude(group = "com.jcraft", module = "jsch")
    }
    implementation(libs.jsch)
    // Git LFS: local clean/smudge and object store (network handled by our own client).
    implementation(libs.jgit.lfs)
    // Ed25519 for JSch on Android (no JCA EdDSA there); also used later for commit signing.
    implementation(libs.bouncycastle.prov)
    // OpenPGP (GPG) commit signing.
    implementation(libs.bouncycastle.pg)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
