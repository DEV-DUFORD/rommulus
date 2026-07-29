// Top-level build file.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    // Room's annotation processor (Phase 5: local save-replica + pending-operation databases,
    // LIBRETRO_REFACTOR.md section 11.1/11.4).
    id("com.google.devtools.ksp") version "1.9.23-1.0.20" apply false
}
