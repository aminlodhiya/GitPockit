import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.gitpockit"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.gitpockit"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Two editions from the same code. Same application id, so switching is a normal update.
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            isDefault = true
            // No accessibility service: installs without Play Protect's sensitive-permission block.
            buildConfigField("boolean", "APP_TRIGGERS", "false")
        }
        create("full") {
            dimension = "edition"
            versionNameSuffix = "-full"
            // Adds "sync when an app opens/closes" (accessibility service, see src/full/AndroidManifest.xml).
            buildConfigField("boolean", "APP_TRIGGERS", "true")
        }
    }

    // Release signing: from environment variables (CI) or a git-ignored keystore.properties.
    // Without them, release builds are produced unsigned.
    val signing = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
    fun secret(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: signing.getProperty(prop)
    val keystorePath = secret("GITPOCKIT_KEYSTORE", "storeFile")
    val releaseSigning = keystorePath?.let { path ->
        signingConfigs.create("release") {
            storeFile = rootProject.file(path)
            storePassword = secret("GITPOCKIT_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = secret("GITPOCKIT_KEY_ALIAS", "keyAlias")
            keyPassword = secret("GITPOCKIT_KEY_PASSWORD", "keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            releaseSigning?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "plugin.properties",
                // Eclipse/OSGi plugin metadata duplicated by JGit modules; unused on Android.
                "OSGI-INF/**",
                "about.html",
            )
        }
    }

    // F-Droid: don't embed Google's encrypted dependency-info blob.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:git"))
    implementation(project(":core:data"))
    implementation(project(":core:security"))
    implementation(project(":sync"))
    implementation(project(":core:files"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.splashscreen)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.work.runtime)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.sora.editor)
    implementation(libs.sora.textmate)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)
    implementation(libs.markdown.m3)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
}
