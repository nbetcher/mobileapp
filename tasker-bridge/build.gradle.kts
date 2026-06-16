import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :tasker-bridge — in-app automation bridge (HLDD-001).
// Android-only KMP library; namespace is deliberately Tasker-neutral (ADR-008) so the module
// is upstreamable verbatim as a generic "automation" API.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlin.time.ExperimentalTime")
            }
        }
        commonMain {
            dependencies {
                implementation(project(":libpebble3"))
                implementation(libs.koin.core)
                implementation(libs.kermit)
                implementation(libs.serialization)
                implementation(libs.coroutines)
            }
        }
        androidUnitTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.test)
                // Relaxed mocks let the handler/collector tests simulate a connected watch (LibPebble)
                // without a physical device — the only piece that genuinely needs hardware.
                implementation("io.mockk:mockk:1.13.13")
            }
        }
    }
}
