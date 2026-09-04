plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("com.google.zxing:core:3.5.3")
    // Linux Secret Service transport (freedesktop.org Secret Service over the session bus).
    // Pure-Java D-Bus protocol; the JNR transport is required because dbus-java-core ships no
    // concrete transport implementation. jnr-unixsocket uses JNR FFI (loads a native shim only at
    // runtime on Linux; compiles and is inert on macOS).
    implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
    implementation("com.github.hypfvieh:dbus-java-transport-jnr-unixsocket:5.2.0")
    // dbus-java-core depends on slf4j-api; a NOP binding keeps the JVM unit tests quiet without a
    // logging backend (and without a Secret Service daemon).
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    // Desktop SQLite (schema v1, independent of Android Room v4) behind explicit stores —
    // plans/LINUX_X64.md §10.2. Pure-JDBC driver; no Room annotations anywhere on desktop.
    implementation("org.xerial:sqlite-jdbc:3.41.2.2")
    implementation("net.java.jinput:jinput:2.0.10")
    // Windows Credential Manager binding (plans/WINDOWS_IMPL.md §4.3): pinned, maintained
    // JNA/JNA Platform versions for the narrow credential seam
    // (com.romm.desktop.storage.secret.windows). The seam loads advapi32 lazily on first use, so
    // the dependency is inert on Linux/macOS; JNA Platform is used only for WinBase.FILETIME /
    // SECURITY_ATTRIBUTES types in the CREDENTIAL structure.
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    // JInput's main jar contains ONLY the platform plugin classes — zero native libraries
    // (verified: jinput-2.0.10.jar has no .so/.dll/.jnilib entries). The natives ship in the
    // `natives-all` classifier (jinput-2.0.10-natives-all.jar: libjinput-linux64.so,
    // libjinput-osx.jnilib, jinput-raw_64.dll, jinput-dx8_64.dll, jinput-wintab.dll at the
    // jar root). JInput 2.0.10 does NOT auto-extract classpath natives (no NativeLibLoader
    // in the jar); every platform plugin's loadLibrary() first checks the
    // `net.java.games.input.librarypath` system property and, when set, System.load()s
    // `<property>/<mapLibraryName(lib)>`. JInputControllerSource.ensureJinputNatives()
    // extracts the natives from this jar to a temp dir and sets that property before the
    // first ControllerEnvironment access.
    implementation("net.java.jinput:jinput:2.0.10:natives-all")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.23")
    // Virtual-time clock for the capture coordinator's 15 s timeout (same version as :app).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    implementation(project(":shared:storage-api"))
    implementation(project(":shared:domain"))
    implementation(project(":shared:network"))
    implementation(project(":shared:presentation"))
    // OkHttp (used by shared:network — re-exported here because desktop also fetches artwork).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.assertj:assertj-core:3.25.3")
}

compose.desktop {
    application {
        mainClass = "com.romm.desktop.MainKt"
        nativeDistributions {
            // TODO(phase 14): packaging per plan section 16 (tar.zst, then Flatpak).
            packageName = "rommulus"
            packageVersion = "1.0.0"
            modules("java.sql", "jdk.security.auth")
        }
    }
}

tasks.register<Copy>("copyRuntimeClasspath") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("runtime-libs"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Plumb the D-Bus session address into the test JVM so the Linux Secret Service conformance
    // test can enable. Single source of truth: the DBUS_SESSION_BUS_ADDRESS env var (set by
    // dbus-run-session in CI) — the same variable the backend's forSessionBus() reads. When unset
    // (macOS, plain unit runs) the property is "" and the conformance test stays disabled.
    systemProperty("rommulus.secretServiceBus", System.getenv("DBUS_SESSION_BUS_ADDRESS") ?: "")
    // Mock mode (available|locked|unavailable) from the mock's env var, so the conformance test
    // can gate which assertions apply per CI run.
    systemProperty("rommulus.secretServiceMode", System.getenv("ROM_SECRET_MODE") ?: "")
    // Real Windows Credential Manager round-trip gate (plans/WINDOWS_IMPL.md §4.3): set
    // ROMM_WINDOWS_CREDENTIAL_INTEGRATION to 1 or true (case-insensitive) on the windows-2022
    // runner to enable; inert elsewhere. The gated test's @EnabledIfSystemProperty matches
    // exactly "true", so only those explicit enable values are normalized to "true" here (the
    // workflow sets "1"); every other value ("0", "false", a typo, blank) maps to "" and leaves
    // the gate disabled — the property never carries a raw value that could match by accident.
    val windowsCredentialIntegration = System.getenv("ROMM_WINDOWS_CREDENTIAL_INTEGRATION") ?: ""
    systemProperty(
        "rommulus.windowsCredentialIntegration",
        if (windowsCredentialIntegration.equals("1", ignoreCase = true) ||
            windowsCredentialIntegration.equals("true", ignoreCase = true)
        ) "true" else "",
    )
}
