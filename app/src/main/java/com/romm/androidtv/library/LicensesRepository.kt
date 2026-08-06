package com.romm.androidtv.library

import android.content.Context

/**
 * Loads the aggregated open-source notices shown by the Settings "View Licenses" screen.
 *
 * Two sources are merged:
 * - Google's `oss-licenses-plugin`: raw resources [com.romm.androidtv.R.raw.third_party_licenses]
 *   and [com.romm.androidtv.R.raw.third_party_license_metadata], which cover every
 *   Gradle/transitive dependency of the app.
 * - The checked-in vendored-core asset [LicenseNotices.VENDORED_ASSET]
 *   (`assets/licenses/libretro.txt`), covering the native libretro cores, `libretro.h`,
 *   and Oboe that the plugin cannot see because they are not Maven dependencies.
 */
object LicensesRepository {

    fun load(context: Context): List<LicenseNotice> {
        val gradleNotices = LicenseNotices.parsePluginMetadata(
            metadata = context.resources
                .openRawResource(com.romm.androidtv.R.raw.third_party_license_metadata)
                .bufferedReader()
                .use { it.readText() },
            licensesText = context.resources
                .openRawResource(com.romm.androidtv.R.raw.third_party_licenses)
                .bufferedReader()
                .use { it.readText() },
        )
        val vendoredNotices = context.assets
            .open(LicenseNotices.VENDORED_ASSET)
            .bufferedReader()
            .use { LicenseNotices.parseVendored(it.readText()) }
        return gradleNotices + vendoredNotices
    }
}