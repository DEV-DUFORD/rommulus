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

    // Coroutines + lifecycle
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

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
