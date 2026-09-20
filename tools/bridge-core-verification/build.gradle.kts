plugins {
    id("com.android.library") version "8.13.2"
    kotlin("android") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
}

// Read CURRENT sources directly. No copies, trimming, or replacement production classes.
// This deliberately excludes LibPebble, Koin wiring, collectors, service and host UI.
val bridge = file("../../tasker-bridge")
val coreFiles = listOf(
    "coredevices/coreapp/automation/trust/**",
    "coredevices/coreapp/automation/events/EventDispatcher.kt",
    "coredevices/coreapp/automation/events/ListenerHub.kt",
    "coredevices/coreapp/automation/command/CommandExecutor.kt",
    "coredevices/coreapp/automation/command/CommandHandler.kt",
    "coredevices/coreapp/automation/command/RateLimiter.kt",
    "coredevices/coreapp/automation/service/ClientSessions.kt",
)
android {
    namespace = "coredevices.coreapp.automation"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            aidl.srcDir("${bridge}/src/androidMain/aidl")
        }
        getByName("test") {
            resources.srcDir("${bridge}/src/androidUnitTest/resources")
        }
    }
}
kotlin {
    sourceSets {
        getByName("main").kotlin.apply {
            srcDirs("${bridge}/src/commonMain/kotlin", "${bridge}/src/androidMain/kotlin")
            include("coredevices/coreapp/automation/Contract.kt", "coredevices/coreapp/automation/ClientRecord.kt")
            include("coredevices/coreapp/automation/AutomationSettings.kt")
            include("coredevices/coreapp/automation/events/EventEnvelope.kt")
            include("coredevices/coreapp/automation/events/EventAccessPolicy.kt")
            include("coredevices/coreapp/automation/command/CommandArgs.kt", "coredevices/coreapp/automation/command/CommandCatalog.kt")
            include("coredevices/coreapp/automation/command/AppMessageArgs.kt")
            include(coreFiles)
        }
        getByName("test").kotlin.apply {
            srcDir("${bridge}/src/androidUnitTest/kotlin")
            // Actual LibPebbleCommandHandler requires the real libpebble3 dependency graph.
            exclude("**/LibPebbleCommandHandlerTest.kt")
            exclude("**/CommandActuationTest.kt", "**/CollectorRegressionTest.kt")
            exclude("**/BridgeServiceContractTest.kt")
            exclude("**/ClientTetherTest.kt")
        }
    }
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll("kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime")
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("co.touchlab:kermit:2.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.3.10")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
}
