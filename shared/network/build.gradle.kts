plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared:domain"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Phase 8 Wave 2: :desktop compiles the player launch request/result protocol against Moshi
    // (plans/LINUX_X64.md §12.2/§12.3), so these must be on consumers' compile classpaths.
    // They were already on every consumer's runtime classpath; only visibility changes.
    api("com.squareup.moshi:moshi:1.15.1")
    api("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.25.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
