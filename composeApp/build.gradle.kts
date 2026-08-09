
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.firebaseCrashlytics)
    alias(libs.plugins.androidVersion)
    alias(libs.plugins.nativeCocoaPods)
    alias(libs.plugins.kotlinx.atomicfu)
}

val properties = Properties().apply {
    try {
        load(rootDir.resolve("local.properties").reader())
    } catch (e: Exception) {
        println("local.properties file not found")
    }
}
val localReleaseBuild = properties["LOCAL_RELEASE_BUILD"]?.toString()?.toBooleanStrictOrNull() ?: false
// We name the release/debug APK ourselves (below); don't let nanogiants keep its own copy.
versioning.keepOriginalBundleFile = false

// Real app version, injected by the resync as the store version at the release cutoff
// (`-PpebbleVersionName=1.5.0.2`). The nanogiants plugin derives versionName from git tags, but neither
// our fork nor Core Devices' public repo carries release tags, so `getVersionName()` throws and falls
// back to "unknown" — which the Rebble cohorts firmware check (Cohorts.kt sends mobileVersion /
// pebbleAppVersion to cohorts.rebble.io) can't resolve, eventually forcing the watch into recovery.
// Zero-pad missing octets to 4 (e.g. 1.3.0 -> 1.3.0.0).
val injectedVersionName: String? = (findProperty("pebbleVersionName") as String?)
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?.let { v -> (v.split(".") + listOf("0", "0", "0", "0")).take(4).joinToString(".") }

// The version actually applied (injected store version, else nanogiants' tag value, else "unknown").
val resolvedVersionName: String =
    injectedVersionName ?: runCatching { versioning.getVersionName() }.getOrDefault("unknown")

// Commit we resynced up to (the release cutoff), 8-char short — for the APK filename. Injected by the
// resync as -PpebbleCommitHash; falls back to the current HEAD short sha for ad-hoc local builds.
val pebbleCommitHash: String = (findProperty("pebbleCommitHash") as String?)?.trim()?.takeIf { it.isNotEmpty() }
    ?: runCatching {
        project.providers.exec { commandLine("git", "rev-parse", "--short=8", "HEAD") }
            .standardOutput.asText.get().trim()
    }.getOrDefault("nogit")

// Canonical single artifact: Pebble_<version>-<commit>-<buildType>.apk. Runs after packaging (hence
// after nanogiants), renames whatever APK AGP produced, and deletes any other APK so the output dir
// holds exactly one, correctly-named file.
listOf("release", "debug").forEach { bt ->
    tasks.matching { it.name == "assemble${bt.replaceFirstChar { c -> c.uppercase() }}" }.configureEach {
        doLast {
            val dir = layout.buildDirectory.dir("outputs/apk/$bt").get().asFile
            if (!dir.isDirectory) return@doLast
            val target = dir.resolve("Pebble_$resolvedVersionName-$pebbleCommitHash-$bt.apk")
            val apks = dir.listFiles { f -> f.isFile && f.extension == "apk" }?.toList().orEmpty()
            // Anything that is NOT the target name is what AGP/nanogiants just packaged; a file
            // already AT the target name is last build's leftover (we renamed AGP's output away, so
            // the packaging task re-emits it every run). Drop the stale target FIRST — deleting the
            // fresh APK instead would silently republish the previous build on every rebuild.
            val fresh = apks.filter { it.name != target.name }
            if (fresh.isNotEmpty()) {
                target.delete()
                val keep = fresh.maxByOrNull { it.lastModified() }!!
                fresh.forEach { if (it != keep) it.delete() }
                keep.renameTo(target)
            }
        }
    }
}

val headSha by lazy {
    project.providers.exec {
        commandLine("git", "describe", "--always", "--dirty")
    }.standardOutput.asText.get().trim()
}

dependencies {
    debugImplementation(compose.uiTooling)
}


kotlin {
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Make xcode invoke gradle from the right place
    tasks.register("fixXcodeProject") {
        doLast {
            val xcodeProjectFile = project.file("../iosApp/Pods/Pods.xcodeproj/project.pbxproj")
            if (xcodeProjectFile.exists()) {
                var content = xcodeProjectFile.readText()
                content = content.replace("gradlew\\\" -p \\\"\$REPO_ROOT\\\"", "gradlew\\\" -p \\\"${rootProject.projectDir}\\\"")
                xcodeProjectFile.writeText(content)
            } else {
                logger.warn("Xcode project file not found, skipping fix: ${xcodeProjectFile.path}")
            }
        }
    }
    tasks.named("podInstall") {
        finalizedBy("fixXcodeProject")
    }

    cocoapods {
        version = "1.0"
        summary = "Core App"
        homepage = "https://github.com/coredevices/CoreApp"
        license = "proprietary"
        ios.deploymentTarget = "15.6"
        podfile = project.file("../iosApp/Podfile")

        pod("GoogleSignIn", "8.0.0")
        pod("FirebaseCore")
        pod("FirebaseAuth") {
            linkOnly = true
            extraOpts += listOf("-compiler-option", "-fmodules")
        }
        pod("FirebaseFirestore") {
            linkOnly = true
        }
        pod("FirebaseStorage") {
            linkOnly = true
        }
        pod("FirebaseCrashlytics") {
            linkOnly = true
        }
        pod("FirebaseMessaging") {
            linkOnly = true
        }

        framework {
            baseName = "ComposeApp"
            linkerOpts("-framework", "Accelerate")
            val osName = when (target.name) {
                "iosArm64" -> "iphoneos"
                "iosX64", "iosSimulatorArm64" -> "iphonesimulator"
                else -> error("Unknown target ${target.name}")
            }
            val dir = project.file("../libpebble3/build/libpebble-swift/$osName")
            val xcodeExists = providers.exec {
                isIgnoreExitValue = true
                commandLine("which", "xcode-select")
            }.result.get().exitValue == 0
            if (xcodeExists) {
                val xcodeDir = providers.exec {
                    commandLine("xcode-select", "-p")
                }.standardOutput.asText.get().trim()
                linkerOpts(
                    "-framework", "LibPebbleSwift", "-F"+dir.absolutePath,
                    "-weak_framework", "CoreML",
                    "-L$xcodeDir/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/iphoneos"
                )
            }
        }
    }

    buildList {
        if (System.getenv("CI_RELEASE") != "true") {
            add(iosSimulatorArm64())
        } else {
            logger.warn("Skipping configuration of iOS simulator targets for CI release build")
        }
        add(iosArm64())
    }.forEach {
        it.binaries.all {
            freeCompilerArgs += listOf(
                "-Xdisable-phases=DevirtualizationAnalysis,DCEPhase"
            )
        }
    }
    
    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlinx.serialization.ExperimentalSerializationApi")
                optIn("kotlinx.cinterop.ExperimentalForeignApi")
                optIn("androidx.compose.material3.ExperimentalMaterial3Api")
                optIn("kotlinx.cinterop.BetaInteropApi")
                optIn("kotlin.time.ExperimentalTime")
            }
        }
        androidMain.dependencies {
            implementation(libs.firebase.crashlytics.ndk)
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.credentials)
            implementation(libs.gms.auth)
            implementation(libs.identity.google)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.coroutines.android)
            implementation(libs.androidx.work)
            implementation(libs.play.update)
            implementation(libs.play.update.ktx)
            implementation(libs.coil.gif)
            implementation(libs.coredevices.haversine)
            implementation(project(":tasker-bridge"))
        }
        androidInstrumentedTest.dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.rules)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.ktor.client.okhttp)
            implementation(project(":experimental"))
            implementation(project(":util"))
            implementation(project(":index-ai"))
            implementation(project(":mcp"))
        }
        androidUnitTest.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.crashkios)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        commonMain.dependencies {
            implementation(libs.kotlinx.io.okio)
            implementation(libs.kermit)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.ui)
            implementation(libs.backhandler)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.serialization)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.coil)
            implementation(libs.coil.svg)

            implementation(libs.firebase.auth)
            implementation(libs.firebase.firestore)
            implementation(libs.firebase.crashlytics)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.client.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.coroutines)
            implementation(project(":pebble"))
            implementation(project(":util"))
            implementation(project(":experimental"))
            implementation(libs.kmpnotifier)
            implementation(libs.kmpio)
            implementation(project(":libpebble3"))
            implementation(project(":libindex"))
            implementation(project(":index-ai"))
            api(project(":mcp"))
            implementation(libs.health.kmp)
        }
    }
    sourceSets.androidInstrumentedTest.dependencies {
        implementation(kotlin("test"))
    }
}

compose.resources {
    packageOfResClass = "coreapp.composeapp.generated.resources"
}

android {
    namespace = "coredevices.coreapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    buildFeatures {
        buildConfig = true
        compose = true
    }

    if (!localReleaseBuild) {
        signingConfigs {
            create("release") {
                storeFile = file("../keystore.jks")
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEYSTORE_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "coredevices.coreapp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // This uses the number of commits in the git history, so it will always increase on main
        versionCode = versioning.getVersionCode()
        versionName = resolvedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            if (localReleaseBuild) {
                signingConfig = signingConfigs.getByName("debug")
            } else {
                signingConfig = signingConfigs.getByName("release")
            }
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}