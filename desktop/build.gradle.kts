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
    // Linux Secret Service transport (freedesktop.org Secret Service over the session bus).
    // Pure-Java D-Bus protocol; the JNR transport is required because dbus-java-core ships no
    // concrete transport implementation. jnr-unixsocket uses JNR FFI (loads a native shim only at
    // runtime on Linux; compiles and is inert on macOS).
    implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
    implementation("com.github.hypfvieh:dbus-java-transport-jnr-unixsocket:5.2.0")
    // dbus-java-core depends on slf4j-api; a NOP binding keeps the JVM unit tests quiet without a
    // logging backend (and without a Secret Service daemon).
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.23")
    implementation(project(":shared:storage-api"))
    testImplementation("org.assertj:assertj-core:3.25.3")
}

compose.desktop {
    application {
        mainClass = "com.romm.desktop.MainKt"
        nativeDistributions {
            // TODO(phase 14): packaging per plan section 16 (tar.zst, then Flatpak).
            packageName = "rommulus"
            packageVersion = "1.0.0"
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Plumb the D-Bus session address into the test JVM so the Linux Secret Service conformance
    // test can enable. Single source of truth: the DBUS_SESSION_BUS_ADDRESS env var (set by
    // dbus-run-session in CI) — the same variable the backend's forSessionBus() reads. When unset
    // (macOS, plain unit runs) the property is "" and the conformance test stays disabled.
    systemProperty("rommulus.secretServiceBus", System.getenv("DBUS_SESSION_BUS_ADDRESS") ?: "")
    // Mock mode (available|locked|unavailable) from the mock's env var, so the conformance test
    // can gate which assertions apply per CI run.
    systemProperty("rommulus.secretServiceMode", System.getenv("ROM_SECRET_MODE") ?: "")
}
