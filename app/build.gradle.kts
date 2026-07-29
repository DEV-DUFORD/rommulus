plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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

android {
    namespace = "com.romm.androidtv"
    compileSdk = 34
    // Pinned per LIBRETRO_REFACTOR.md section 7.1: build approved cores as pinned
    // shared libraries for armeabi-v7a and arm64-v8a. Keep this version pinned and
    // bump it deliberately, not implicitly via SDK manager updates.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.romm.androidtv"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // RomM origin: read from local.properties for local dev only.
        // Debug build gets the configured value; release builds get empty string.
        val rommOrigin = localProp("romm.origin", "")
        buildConfigField("String", "ROMM_ORIGIN", "\"$rommOrigin\"")

        ndk {
            // The physical Google TV Streamer is 32-bit userspace (armeabi-v7a); it is a
            // release gate, not a legacy afterthought (LIBRETRO_REFACTOR.md section 3).
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
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
    // Compose BOM pins all androidx.compose.* versions
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Extended icon set (SportsEsports, Collections, etc.) used by the native browsing UI
    implementation("androidx.compose.material:material-icons-extended")

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

    // Coil for Compose image loading (cover art) — UI_REFACTOR.md section 5
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Jetpack Compose for TV: focus-aware lazy lists/rows and D-pad focus restoration,
    // used by the native browsing UI (UI_REFACTOR.md) instead of hand-rolled focus plumbing.
    implementation("androidx.tv:tv-foundation:1.0.0-alpha10")
    implementation("androidx.tv:tv-material:1.0.0")

    // Unit testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // MockWebServer for network unit tests
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Instrumented UI/Compose testing
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.material3:material3")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
