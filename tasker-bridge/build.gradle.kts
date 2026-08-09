import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :tasker-bridge — in-app automation bridge (HLDD-001).
// Android-only; namespace is deliberately Tasker-neutral (ADR-008) so the module is upstreamable
// verbatim as a generic "automation" API.
//
// Plain Android library rather than KMP: AGP 9 forbids com.android.library alongside
// kotlin.multiplatform, and the KMP replacement (com.android.kotlin.multiplatform.library) exposes
// no AIDL build feature, which this module's Binder data plane requires. Same trade-off
// :cactus-native makes for the NDK. Source layout stays androidMain/ so the module keeps its KMP
// shape for upstreaming.
plugins {
    // AGP 9 compiles Kotlin itself; the kotlin.android plugin is not applied (and is rejected).
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "coredevices.coreapp.automation"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/androidMain/AndroidManifest.xml")
            // commonMain holds the platform-neutral contract (envelopes, catalogs, ClientRecord);
            // it stays a separate directory so the module keeps its KMP shape for upstreaming.
            kotlin.srcDirs("src/commonMain/kotlin", "src/androidMain/kotlin")
            aidl.srcDirs("src/androidMain/aidl")
        }
        getByName("test") {
            kotlin.srcDirs("src/androidUnitTest/kotlin")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll("kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime")
    }
}

dependencies {
    implementation(project(":libpebble3"))
    implementation(libs.koin.core)
    implementation(libs.kermit)
    implementation(libs.serialization)
    implementation(libs.coroutines)

    // kotlin-test-junit, not plain kotlin-test: the KMP variant resolution that binds a framework
    // to kotlin.test.Test isn't there for a plain Android unit-test source set.
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.coroutines.test)
    // Relaxed mocks let the handler/collector tests simulate a connected watch (LibPebble) without
    // a physical device — the only piece that genuinely needs hardware.
    testImplementation("io.mockk:mockk:1.13.13")
}
