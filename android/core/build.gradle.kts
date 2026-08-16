import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The engine is a pure-Kotlin/JVM library: everything above the VpnService
// API boundary is a function of bytes, so the entire packet path — raw IP
// packet in, raw IP packet out — runs under `gradle :core:test` on any
// machine, with no Android SDK and no emulator. Keep it that way: no
// android.* imports, and coroutines as the only dependency.
dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Bytecode level, not toolchain: the module then builds on any JDK 17 or
// newer without downloading one.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
