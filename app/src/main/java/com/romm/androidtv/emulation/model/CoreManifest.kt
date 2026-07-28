package com.romm.androidtv.emulation.model

/**
 * Whether a core's license permits unrestricted commercial distribution
 * (including free store distribution, per LIBRETRO_REFACTOR.md section 4.2).
 *
 * A `PERMISSIVE_OR_COPYLEFT_OK` finding still requires satisfying that license's
 * obligations (e.g. GPL source offer, attribution notices) at release time; it
 * is not a statement that no obligations apply.
 */
enum class CommercialUseFinding {
    /** Permissive (MIT/BSD/Apache-style) or copyleft (GPL/LGPL) with obligations the project accepts. */
    PERMISSIVE_OR_COPYLEFT_OK,

    /** The reviewed source/redistribution terms restrict commercial or store distribution. */
    NON_COMMERCIAL_RESTRICTED,

    /** No license review has been recorded yet. Never treat this as approval. */
    UNREVIEWED,
}

/**
 * Review and approval state for one core. See LIBRETRO_REFACTOR.md section 4.1.
 *
 * An entry with [approved] == true still requires [reviewedBy] and
 * [reviewedOn] to be non-blank; the loader must reject any entry that claims
 * approval without a recorded reviewer and date.
 */
data class CoreLicenseFinding(
    /** Upstream project or organization name, e.g. "SameBoy". */
    val coreName: String,
    /** Canonical Libretro core identifier used in `EJS_core` / RomM metadata, e.g. "sameboy". */
    val coreId: String,
    /** Upstream repository URL. */
    val upstreamRepository: String,
    /** Exact commit SHA reviewed and built. */
    val commitSha: String,
    /** Release tag, if the reviewed commit corresponds to one. Empty if untagged. */
    val releaseTag: String = "",
    /** Short summary of the license findings, including vendored subcomponents. */
    val licenseSummary: String,
    val commercialUseFinding: CommercialUseFinding,
    /** True only for obligations already satisfied in this repository (e.g. NOTICE file checked in). */
    val sourceOfferSatisfied: Boolean = false,
    val attributionSatisfied: Boolean = false,
    /** Systems this core supports, e.g. ["gb", "gbc"]. */
    val supportedSystems: List<String>,
    /** File extensions this core accepts, e.g. [".gb", ".gbc"]. */
    val supportedExtensions: List<String>,
    /** Required firmware/BIOS file names, if any. */
    val requiredFirmware: List<String> = emptyList(),
    /** ABIs this core is built for, e.g. ["armeabi-v7a", "arm64-v8a"]. */
    val supportedAbis: List<String> = emptyList(),
    /** Build command used to produce the shared library. Empty until Phase 2+ builds it. */
    val buildCommand: String = "",
    /** SHA-256 of the resulting shared library, per ABI. Empty until the core is actually built. */
    val binaryChecksums: Map<String, String> = emptyMap(),
    val reviewedBy: String = "",
    val reviewedOn: String = "",
    /**
     * Whether this core is cleared to ship in a production build. Must be false unless
     * [reviewedBy] and [reviewedOn] are populated and [commercialUseFinding] is
     * [CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK].
     */
    val approved: Boolean = false,
) {
    init {
        require(coreName.isNotBlank()) { "coreName must not be blank" }
        require(coreId.isNotBlank()) { "coreId must not be blank" }
        require(supportedSystems.isNotEmpty()) { "supportedSystems must not be empty" }
        if (approved) {
            require(commitSha.isNotBlank()) { "approved core '$coreId' must record commitSha" }
            require(reviewedBy.isNotBlank()) { "approved core '$coreId' must record reviewedBy" }
            require(reviewedOn.isNotBlank()) { "approved core '$coreId' must record reviewedOn" }
            require(commercialUseFinding == CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK) {
                "approved core '$coreId' must have a permitted commercial-use finding"
            }
        }
    }
}

/**
 * In-repo registry of reviewed cores. Phase 0 ships this schema with zero
 * approved entries: no production core may be added until Phase 0's product
 * decisions are recorded and each core has an individual license review
 * (LIBRETRO_REFACTOR.md sections 4.1-4.3). Do not flip [CoreLicenseFinding.approved]
 * to true as a side effect of unrelated work.
 */
object CoreManifest {
    /**
     * Known findings recorded during Phase 0 planning. These are starting facts
     * from LIBRETRO_REFACTOR.md section 4.1, not approvals: every entry here is
     * unreviewed/unapproved until a dedicated reviewer signs off with a commit SHA,
     * build command, and checksum.
     */
    val entries: List<CoreLicenseFinding> = listOf(
        CoreLicenseFinding(
            coreName = "SameBoy",
            coreId = "sameboy",
            upstreamRepository = "https://github.com/LIJI32/SameBoy",
            commitSha = "8230189896a8bb6598574d302ba0ad3658f98ab4",
            releaseTag = "v1.0.3-libretro",
            licenseSummary = "Expat/MIT license covers \"all files and directories in this " +
                "repository, except the iOS and HexFiend directories\" (upstream LICENSE, " +
                "verbatim). This project only ever vendors Core/ (the GB/GBC emulation engine " +
                "— flat, first-party .c/.h files, no vendored third-party libraries found) and " +
                "libretro/ (the libretro API wrapper, including upstream's own jni/Android.mk + " +
                "Application.mk NDK build config); neither references iOS/ or HexFiend/. The " +
                "iOS directory's extra restriction (\"written permission required for App Store " +
                "distribution\") is textually scoped only to files under iOS/ and does not apply " +
                "here. BootROMs/ (SameBoy's own from-scratch reimplemented boot ROM replacements, " +
                "shipped as .asm source, not Nintendo's copyrighted binaries) is NOT excluded from " +
                "the Expat license and requires no external firmware. Fully compatible with a " +
                "GPLv3 application (see docs/PHASE0_DECISIONS.md); MIT imposes no source-offer " +
                "obligation, only preservation of the copyright notice and license text in the " +
                "app's bundled NOTICE/licenses.",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            sourceOfferSatisfied = true,
            attributionSatisfied = false,
            supportedSystems = listOf("gb", "gbc"),
            supportedExtensions = listOf(".gb", ".gbc"),
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a"),
            buildCommand = "JAVA_HOME=\"/opt/homebrew/opt/openjdk@17\" ./gradlew assembleRelease " +
                "(NDK r27.2.12479018, CMake 3.22.1; builds the `sameboy_core` CMake target in " +
                "app/src/main/cpp/CMakeLists.txt, compiling third_party/cores/sameboy/{Core,libretro}/* " +
                "with the exact preprocessor flags upstream's own libretro/Makefile.common + " +
                "libretro/jni/Android.mk use, -std=c99, and upstream's own libretro/link.T version " +
                "script; see third_party/cores/sameboy/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "251cfde8cbe2e4be5d6dad300efb6feb2e90b5bc74c536b5709c4b0b42fb5738",
                "arm64-v8a" to "f4fda64892a3febdafabc326e616791395c087f7f40ef468f636f76eb37cf944",
            ),
            reviewedBy = "DEV-DUFORD",
            reviewedOn = "2026-07-28",
            approved = true,
        ),
        CoreLicenseFinding(
            coreName = "Genesis Plus GX",
            coreId = "genesis_plus_gx",
            upstreamRepository = "https://github.com/libretro/Genesis-Plus-GX",
            commitSha = "",
            licenseSummary = "Reviewed redistribution terms include a no-sale restriction. If the " +
                "core code itself is GPL, that part is compatible with a GPLv3 app; the no-sale " +
                "term is an additional restriction GPLv3 section 10 does not permit combining " +
                "into one covered work without further review (see docs/PHASE0_DECISIONS.md).",
            commercialUseFinding = CommercialUseFinding.NON_COMMERCIAL_RESTRICTED,
            supportedSystems = listOf("genesis", "megadrive", "sms", "gamegear"),
            supportedExtensions = listOf(".md", ".gen", ".bin", ".sms", ".gg"),
            approved = false,
        ),
        CoreLicenseFinding(
            coreName = "PicoDrive",
            coreId = "picodrive",
            upstreamRepository = "https://github.com/libretro/picodrive",
            commitSha = "",
            licenseSummary = "Restrictive/non-commercial terms found in reviewed source. An " +
                "additional restriction under GPLv3 section 10; see docs/PHASE0_DECISIONS.md " +
                "for the compatibility risk before combining with a GPLv3 application.",
            commercialUseFinding = CommercialUseFinding.NON_COMMERCIAL_RESTRICTED,
            supportedSystems = listOf("genesis", "megadrive", "32x", "segacd"),
            supportedExtensions = listOf(".md", ".gen", ".bin"),
            approved = false,
        ),
        CoreLicenseFinding(
            coreName = "Snes9x",
            coreId = "snes9x",
            upstreamRepository = "https://github.com/libretro/snes9x",
            commitSha = "",
            licenseSummary = "Custom non-commercial license. An additional restriction under " +
                "GPLv3 section 10; see docs/PHASE0_DECISIONS.md for the compatibility risk " +
                "before combining with a GPLv3 application.",
            commercialUseFinding = CommercialUseFinding.NON_COMMERCIAL_RESTRICTED,
            supportedSystems = listOf("snes", "sfc"),
            supportedExtensions = listOf(".sfc", ".smc"),
            approved = false,
        ),
        CoreLicenseFinding(
            coreName = "Mupen64Plus",
            coreId = "mupen64plus_next",
            upstreamRepository = "https://github.com/libretro/mupen64plus-libretro-nx",
            commitSha = "",
            licenseSummary = "GPL and component-specific licensing; requires component-by-component " +
                "review. GPL components are naturally compatible with a GPLv3 application.",
            commercialUseFinding = CommercialUseFinding.UNREVIEWED,
            supportedSystems = listOf("n64"),
            supportedExtensions = listOf(".n64", ".z64", ".v64"),
            approved = false,
        ),
    )

    /** Cores cleared for use in a production build. Empty until Phase 0 approvals happen. */
    fun approvedEntries(): List<CoreLicenseFinding> = entries.filter { it.approved }

    fun findById(coreId: String): CoreLicenseFinding? = entries.find { it.coreId == coreId }
}
