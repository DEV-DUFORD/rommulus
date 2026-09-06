plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    // Auto-generates third-party notices for all Gradle/transitive deps (Settings "View Licenses").
    id("com.google.android.gms.oss-licenses-plugin")
}

/** Read a property from local.properties (root project). */
fun localProp(name: String, fallback: String = ""): String {
    val rootFile = File(rootProject.projectDir, "local.properties")
    if (!rootFile.exists()) return fallback
    for (line in rootFile.readLines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("$name=")) {
            return trimmed.substringAfter("=").trim()
        }
    }
    return fallback
}

val releaseSigningEnvironment = mapOf(
    "storeFile" to System.getenv("RELEASE_STORE_FILE"),
    "storePassword" to System.getenv("RELEASE_STORE_PASSWORD"),
    "keyAlias" to System.getenv("RELEASE_KEY_ALIAS"),
    "keyPassword" to System.getenv("RELEASE_KEY_PASSWORD"),
)
val hasReleaseSigning = releaseSigningEnvironment.values.all { !it.isNullOrBlank() }
require(hasReleaseSigning || releaseSigningEnvironment.values.none { !it.isNullOrBlank() }) {
    "Release signing requires RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, " +
        "RELEASE_KEY_ALIAS, and RELEASE_KEY_PASSWORD"
}

android {
    namespace = "com.romm.androidtv"
    compileSdk = 36
    // Pinned per LIBRETRO_REFACTOR.md section 7.1: build approved cores as pinned
    // shared libraries for armeabi-v7a and arm64-v8a. Keep this version pinned and
    // bump it deliberately, not implicitly via SDK manager updates.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.devduford.tv.rommulus"
        minSdk = 26
        targetSdk = 36
        versionCode = 3002
        versionName = "0.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // RomM origin: read from local.properties for local dev only.
        // Debug build gets the configured value; release builds get empty string.
        val rommOrigin = localProp("romm.origin", "")
        buildConfigField("String", "ROMM_ORIGIN", "\"$rommOrigin\"")

        // Controller-config artwork highlighting: opt-in via env var, defaulted off.
        // Checks the real environment first, then local.properties, so CI/dev shells
        // can toggle it without editing tracked files.
        val controllerHighlightingEnabled =
            (System.getenv("CONTROLLER_HIGHLIGHTING_ENABLED")
                ?: localProp("controller.highlighting.enabled", "false"))
                .toBoolean()
        buildConfigField("boolean", "CONTROLLER_HIGHLIGHTING_ENABLED", "$controllerHighlightingEnabled")

        ndk {
            // The physical Google TV Streamer is 32-bit userspace (armeabi-v7a); it is a
            // release gate, not a legacy afterthought (LIBRETRO_REFACTOR.md section 3).
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += ""
                // Native code always builds optimized (RelWithDebInfo: -O2 -g), regardless of
                // the Gradle debug/release build type. Emulator cores (SameBoy) are CPU-bound
                // interpreters where an unoptimized (-O0) build runs at a small fraction of
                // real-time speed on this project's target hardware — confirmed on-device: the
                // Phase 4 debug build ran a Game Boy title at ~20fps (vs. the ~59.7fps a Game
                // Boy actually runs at) with continuous audio underruns, purely because AGP's
                // default CMAKE_BUILD_TYPE for a debuggable variant is "Debug" (no optimization).
                // Keeping -g preserves native debuggability/symbols for the debug variant; only
                // the missing optimization was the problem.
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=RelWithDebInfo")
                // Opt-in (env-gated, not on by default) ccache wiring for the NDK/CMake native
                // build. Speeds up repeated CI native rebuilds — the self-hosted runner's
                // checkout step wipes .cxx/ every run, but ccache's own cache dir lives outside
                // the workspace and survives. Off by default so local dev machines without
                // ccache installed aren't affected.
                if (System.getenv("ROMMULUS_NDK_CCACHE") == "1") {
                    arguments += listOf(
                        "-DCMAKE_C_COMPILER_LAUNCHER=ccache",
                        "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache",
                    )
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseSigningEnvironment.getValue("storeFile")!!)
                storePassword = releaseSigningEnvironment.getValue("storePassword")
                keyAlias = releaseSigningEnvironment.getValue("keyAlias")
                keyPassword = releaseSigningEnvironment.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isDebuggable = false
            // Override ROMM_ORIGIN to empty in release — never leak dev origin.
            buildConfigField("String", "ROMM_ORIGIN", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.13"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Shared domain (Linux port Phase 1)
    implementation(project(":shared:domain"))
    implementation(project(":shared:network"))
    implementation(project(":shared:storage-api"))
    // Shared presenters (Linux port Phase 4)
    implementation(project(":shared:presentation"))

    // Compose BOM pins all androidx.compose.* versions
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Extended icon set (SportsEsports, Collections, etc.) used by the native browsing UI
    implementation("androidx.compose.material:material-icons-extended")

    // Jetpack WindowManager for window metrics and folding features (phone/tablet support)
    implementation("androidx.window:window:1.3.0")

    // AndroidX Leanback (TV support library) for leanback feature declaration
    implementation("androidx.leanback:leanback:1.0.0")

    // AndroidX WebKit for document-start script injection (WebViewCompat)
    implementation("androidx.webkit:webkit:1.12.1")

    // Activity Compose
    implementation("androidx.activity:activity-compose:1.9.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Core Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.23")

    // OkHttp for native API calls (heartbeat, login)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Moshi for robust JSON parsing (replaces hand-parsers)
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.google.zxing:core:3.5.3")

    // 7z archive extraction (LIBRETRO_REFACTOR.md section 10: RomM single-file ROM entries are
    // commonly .7z, needing extraction before raw bytes can reach a libretro core). Both are
    // ordinary, permissively-licensed app dependencies (Apache-2.0 / public domain) — not
    // libretro cores — so the CoreManifest review gate (section 4) doesn't apply to them.
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12") // LZMA2 codec support used by commons-compress's 7z reader

    // Coroutines + lifecycle
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    // ViewModel integration for Compose (viewModel() composable) — UI_REFACTOR.md
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Room: local save-replica records and the pending-operation (upload-queue) database
    // (LIBRETRO_REFACTOR.md section 11.1/11.4, Phase 5).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager: durable, retryable post-play save upload, run from the main process and
    // surviving process death (LIBRETRO_REFACTOR.md section 11.4, Phase 5).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Coil for Compose image loading (cover art) — UI_REFACTOR.md section 5
    implementation("io.coil-kt:coil-compose:2.6.0")
    // Decodes RomM's bundled platform icons, which are SVGs (/assets/platforms/{slug}.svg).
    implementation("io.coil-kt:coil-svg:2.6.0")

    // Material components optimized for TV.
    implementation("androidx.tv:tv-material:1.0.0")

    // Unit testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // MockWebServer for network unit tests
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Provides a controllable Dispatchers.Main for ViewModel unit tests (no Robolectric in this
    // repo) — required by SettingsViewModel tests that exercise viewModelScope.launch.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // Instrumented UI/Compose testing
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.material3:material3")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
    // WorkManager test harness requires real Android Context; moved from testImplementation
    // per p5-workmanager plan (no Robolectric in this repo).
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    // Provides a controllable Dispatchers.Main for ViewModel instrumented tests.
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
