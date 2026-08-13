// Top-level build file.
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    // Room's annotation processor (Phase 5: local save-replica + pending-operation databases,
    // LIBRETRO_REFACTOR.md section 11.1/11.4).
    id("com.google.devtools.ksp") version "1.9.23-1.0.20" apply false
    // Google OSS Licenses: auto-generates third-party notices for Gradle deps and provides
    // the LicensesActivity used by the Settings "View Licenses" screen (Play Store compliance).
    id("com.google.android.gms.oss-licenses-plugin") version "0.13.0" apply false
}
