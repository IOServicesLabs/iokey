plugins {
    // AGP 8.13 / Kotlin 2.3 are the LEAP SDK's floor (ai.liquid.leap:leap-sdk).
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    // KSP is versioned independently of Kotlin now (plain "2.3.x").
    id("com.google.devtools.ksp") version "2.3.10" apply false
}
