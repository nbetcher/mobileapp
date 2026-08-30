import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.googleServices)
    alias(libs.plugins.firebaseCrashlytics)
}

val properties = Properties().apply {
    try {
        load(rootDir.resolve("local.properties").reader())
    } catch (e: Exception) {
        println("local.properties file not found")
    }
}
val localReleaseBuild = properties["LOCAL_RELEASE_BUILD"]?.toString()?.toBooleanStrictOrNull() ?: false

// Most recent tag reachable from HEAD, so a release branch versions from its own tag.
val gitVersionName = providers.exec {
    isIgnoreExitValue = true
    commandLine("git", "describe", "--tags", "--abbrev=0", "HEAD")
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

// Tag as an increasing int: 1.9.1.3 -> 10901003. Major must stay below 100, the rest below 1000.
val gitVersionCode = gitVersionName.map { name ->
    val parts = name.split('.').map { it.toIntOrNull() ?: -1 }
    if (parts.size > 4 || parts.first() !in 0..99 || parts.any { it !in 0..999 }) {
        throw GradleException("Cannot derive versionCode from tag '$name'")
    }
    listOf(10_000_000, 100_000, 1_000, 1).zip(parts) { scale, part -> scale * part }.sum()
}

// Store version at the release cutoff, injected by the sync pipeline as -PpebbleVersionName.
// Without it versionName falls back to "unknown", which the Rebble cohorts firmware check
// (Cohorts.kt -> cohorts.rebble.io) cannot resolve, eventually forcing the watch into recovery.
// Zero-padded to 4 octets (1.3.0 -> 1.3.0.0).
val injectedVersionName: String? = (findProperty("pebbleVersionName") as String?)
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?.let { v -> (v.split(".") + listOf("0", "0", "0", "0")).take(4).joinToString(".") }

val resolvedVersionName: Provider<String> =
    injectedVersionName?.let { v -> providers.provider { v } } ?: gitVersionName

// Upstream commit we resynced to, for the APK filename. Injected as -PpebbleCommitHash; falls back
// to HEAD for ad-hoc local builds. Kept as a Provider so it resolves at execution time.
val pebbleCommitHash: Provider<String> = (findProperty("pebbleCommitHash") as String?)
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?.let { h -> providers.provider { h } }
    ?: providers.exec {
        isIgnoreExitValue = true
        commandLine("git", "rev-parse", "--short=8", "HEAD")
    }.standardOutput.asText.map { it.trim().ifEmpty { "nogit" } }

android {
    namespace = "coredevices.coreapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

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
                // Crashlytics regenerates a mapping-id resource every build
                // (upToDateWhen=false), forcing aapt + a full R8 rerun even on
                // null builds. Skip it for local release builds.
                configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                    mappingFileUploadEnabled = false
                }
            } else {
                signingConfig = signingConfigs.getByName("release")
            }
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":composeApp"))
    // Components this module's manifest declares, so lint can resolve them.
    implementation(project(":util"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.health.kmp)

    androidTestImplementation(platform(libs.firebase.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.ktor.client.okhttp)
    androidTestImplementation(libs.koin.core)
    androidTestImplementation(libs.koin.android)
    androidTestImplementation(libs.coroutines)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.firebase.auth)
    androidTestImplementation(project(":cactus"))
    androidTestImplementation(project(":experimental"))
    androidTestImplementation(project(":libindex"))
    androidTestImplementation(project(":index-ai"))
    androidTestImplementation(project(":mcp"))
}

// Resolved at execution time — a configuration-time .get() makes every commit invalidate the
// configuration cache.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach {
            it.versionCode.set(gitVersionCode)
            it.versionName.set(resolvedVersionName)
        }
    }
}

// Single canonical artifact per build type: Pebble_<version>-<commit>-<buildType>.apk.
listOf("release", "debug").forEach { bt ->
    // Locals, so the doLast below captures values rather than the script object — a reference to
    // any script-level property makes the task unserializable for the configuration cache.
    val apkDir = layout.buildDirectory.dir("outputs/apk/$bt")
    val versionName = resolvedVersionName
    val commitHash = pebbleCommitHash
    tasks.matching { it.name == "assemble${bt.replaceFirstChar { c -> c.uppercase() }}" }.configureEach {
        doLast {
            val dir = apkDir.get().asFile
            if (!dir.isDirectory) return@doLast
            val target = dir.resolve("Pebble_${versionName.get()}-${commitHash.get()}-$bt.apk")
            val apks = dir.listFiles { f -> f.isFile && f.extension == "apk" }?.toList().orEmpty()
            // A file already at the target name is last build's leftover; delete it before renaming,
            // or the fresh APK gets dropped instead and the previous build is republished.
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
