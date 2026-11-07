import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// End-to-end tests that drive IOKey through its real UI on an emulator. The APK under test is whatever
// build is already installed, so this module deliberately has no dependency on :app and keeps compiling
// while the app's source is in flux. Run it through tools/e2e/run.ps1.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.ioservices.iokey.e2e"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.ioservices.iokey.e2e"
        minSdk = 34
        // Below 35 so the host app keeps the plain, non edge-to-edge window most chat apps still have.
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
