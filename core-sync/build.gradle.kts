import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM module. NO Android or SMB dependencies — this is what makes the
// sync engine exhaustively unit-testable on a plain JVM (requirement #5: prove
// data-loss-freedom via automated tests).
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okio)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Compile with whatever JDK runs Gradle (Android Studio's bundled JBR 21 is fine),
// but emit Java 17 bytecode so the Android :app module can depend on this module.
// We deliberately avoid jvmToolchain(17) so no separate JDK download is needed.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
