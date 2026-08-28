plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // OkHttp used by RommServerAddress for URL parsing (same version as :app).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Moshi used by LaunchSessionJournal for JSON serialization (same version as :app).
    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    // Coroutines core used by the ControllerConfigRepository port (Flow) — same version as :app.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.23")
    testImplementation("org.assertj:assertj-core:3.25.3")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
