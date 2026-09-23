import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * Release signing material, resolved from outside the repository.
 *
 * The release key decides whether an APK can install as an UPDATE over an
 * existing install. Anyone holding it can publish something that silently
 * replaces this app on a user's phone, so it is never committed:
 *
 *   local builds — keystore.properties in the project root (gitignored)
 *   CI           — IOKEY_* environment variables from GitHub Secrets
 *   neither      — falls back to debug signing, so a fresh clone and every
 *                  pull-request build still produce a working APK
 *
 * See BUILD.md for how to create keystore.properties and how the CI secrets
 * are set up.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingSetting(key: String, env: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val releaseStorePath = signingSetting("storeFile", "IOKEY_STORE_FILE")
val releaseStoreFile = releaseStorePath?.let { path ->
    rootProject.file(path).takeIf { it.exists() } ?: File(path).takeIf { it.exists() }
}
val canSignRelease = releaseStoreFile != null &&
    signingSetting("storePassword", "IOKEY_STORE_PASSWORD") != null &&
    signingSetting("keyAlias", "IOKEY_KEY_ALIAS") != null &&
    signingSetting("keyPassword", "IOKEY_KEY_PASSWORD") != null

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.localboard.keyboard"
    compileSdk = 36

    defaultConfig {
        // Reverse-DNS of the domain the project actually owns (ioservices.io).
        // This is the app's permanent identity on Google Play: it cannot be
        // changed after the first release, and it is what decides whether an
        // APK updates an install or lands beside it as a separate app.
        //
        // NOTE the deliberate asymmetry with `namespace` above, which stays
        // com.localboard.keyboard. namespace only decides where R and
        // BuildConfig are generated and is invisible outside the build;
        // renaming it would mean rewriting every R import for no user-visible
        // gain. Component names in the manifest are fully qualified, so they
        // keep resolving against the unchanged Kotlin package.
        applicationId = "io.ioservices.iokey"
        // LEAP SDK floor is API 31 (Android 12).
        minSdk = 31
        // Play requires 35 to publish an update and 36 from Aug 2026, so 36 is
        // the only value that is not already an expiry date. Targeting 35+ also
        // opts this app into the 16 KB page-size requirement — every bundled
        // .so must have LOAD segments aligned to 0x4000, which is why
        // tools/build_native.sh passes -Wl,-z,max-page-size=16384 and why
        // sqlcipher is pinned at 4.6.1+ rather than 4.5.4.
        targetSdk = 36
        // versionName is what people read; versionCode is what Android
        // compares. It must only ever increase — a device refuses an install
        // whose versionCode is lower than what it already has, which would
        // force an uninstall and take the user's learned dictionary with it.
        // The pre-1.0 builds reached 52, so 1.0.0 maps to 10000 under
        // major*10000 + minor*100 + patch: monotonic, and readable at a glance.
        versionCode = 10100
        versionName = "1.1.0"

        ndk {
            // Modern phones only; keeps APK size and build time sane.
            // The prebuilt liblocalboard_llm.so ships in src/main/jniLibs —
            // NO NDK required to build this project. To rebuild the native
            // lib yourself, run tools/build_native.sh (needs Android NDK r26+).
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingSetting("storePassword", "IOKEY_STORE_PASSWORD")
                keyAlias = signingSetting("keyAlias", "IOKEY_KEY_ALIAS")
                keyPassword = signingSetting("keyPassword", "IOKEY_KEY_PASSWORD")
            }
        }
    }

    /**
     * Two distributions, because Google Play will not accept everything this
     * keyboard can do.
     *
     *  full — the complete app, sideloaded. Includes the
     *         AccessibilityService that reads on-screen conversation text for
     *         AI context, and the one-time SMS writing-style import. Pro
     *         features are unlocked outright: there is nothing to sell here.
     *
     *  play — the Play Store build. Both of those are REMOVED, not disabled:
     *         the permission and the service are declared in the full source
     *         set only, so the Play manifest never mentions them and a
     *         reviewer never has to take our word for it.
     *
     * Why they cannot ship together:
     *   READ_SMS is a Play "restricted permission" granted for a short list of
     *   use cases; style import is not one of them.
     *   AccessibilityService used for anything other than accessibility is a
     *   policy violation Google actively removes apps for, and "read the
     *   screen to feed a language model" is exactly the pattern they police.
     *
     * Everything else — glide typing, autocorrect, on-device LLM drafting,
     * offline voice, themes, privacy controls — is identical in both.
     */
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            // Separate package id so it can sit alongside a Play install.
            applicationIdSuffix = ".full"
            versionNameSuffix = "-full"
            buildConfigField("boolean", "IS_PLAY_BUILD", "false")
        }
        create("play") {
            dimension = "distribution"
            // The canonical id: this is what the store listing owns.
            buildConfigField("boolean", "IS_PLAY_BUILD", "true")
        }
    }

    buildTypes {
        release {
            // Minify/shrink deliberately OFF: identical behavior to the debug
            // build, just non-debuggable with a stable signature. R8 full-mode
            // risks stripping Room/JNI/SQLCipher glue and roughly doubles
            // build time for zero benefit at this size.
            isMinifyEnabled = false
            isShrinkResources = false
            // Skip the symbol-strip pass: the LEAP native libs arrive
            // pre-stripped, and spawning ~30 `strip` processes over them
            // OOM-killed the build daemon on the 1.6.5 attempt.
            ndk { debugSymbolLevel = "none" }
            // No release key available (fresh clone, or a pull-request build
            // that cannot see repository secrets): sign with the debug key so
            // the build still succeeds and produces an installable APK. It
            // just will not update an existing release install.
            signingConfig = if (canSignRelease) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Room + SQLCipher for encrypted local storage (Room 2.8.x: KSP2-native,
    // required by the Kotlin 2.3 toolchain LEAP forces us onto).
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Liquid AI LEAP runtime: LFM-family GGUFs run here (vendor chat
    // template + manifest-tuned sampling ship with the runtime). Bundles
    // its own arm64 native libs.
    implementation("ai.liquid.leap:leap-sdk:0.10.9")
    // 4.5.4's libsqlcipher.so is 4 KB-aligned, which Play rejects for anything
    // targeting API 35+. 16 KB alignment landed in 4.6.1. The on-disk format is
    // unchanged across 4.x (cipher_compatibility = 4), so an existing encrypted
    // database still opens — nobody loses a learned dictionary to this bump.
    implementation("net.zetetic:sqlcipher-android:4.17.0")

    // Nightly on-charger distillation of the private typing log
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Play Billing — the Play distribution ONLY. Keeping it out of the full
    // build means the sideload APK carries no store client at all, rather
    // than one that is present but inert.
    "playImplementation"("com.android.billingclient:billing-ktx:7.1.1")
}
