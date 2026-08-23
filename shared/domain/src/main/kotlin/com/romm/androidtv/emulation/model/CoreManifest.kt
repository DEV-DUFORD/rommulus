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

/** Android ABIs shipped by the mobile/TV application. */
val ANDROID_CORE_ABIS: Set<String> = setOf("armeabi-v7a", "arm64-v8a")

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
     * Who accepted the product-level licensing risk for a [CommercialUseFinding.NON_COMMERCIAL_RESTRICTED]
     * core, and when. Must stay blank for any core whose [commercialUseFinding] is
     * [CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK] — this is a distinct, narrower decision
     * from an ordinary license review, not a substitute for one. See the 2026-07-31 "Owner
     * licensing-risk decision" in `HANDOFF.md` and `docs/PHASE0_DECISIONS.md`: it authorizes
     * proceeding with a specific restrictively-licensed core in this free, ad-free, open-source
     * app; it does not assert the restriction is actually GPLv3-compatible, and it must be
     * revisited before any monetized/ads/paid build.
     */
    val ownerRiskAcceptedBy: String = "",
    val ownerRiskAcceptedOn: String = "",
    /**
     * Whether this core is cleared to ship in a production build. Must be false unless
     * [reviewedBy] and [reviewedOn] are populated, and either [commercialUseFinding] is
     * [CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK], or it is
     * [CommercialUseFinding.NON_COMMERCIAL_RESTRICTED] with [ownerRiskAcceptedBy] and
     * [ownerRiskAcceptedOn] both recorded (an explicit, dated owner risk acceptance —
     * never a silent default).
     */
    val approved: Boolean = false,
) {
    init {
        require(coreName.isNotBlank()) { "coreName must not be blank" }
        require(coreId.isNotBlank()) { "coreId must not be blank" }
        require(supportedSystems.isNotEmpty()) { "supportedSystems must not be empty" }
        val ownerRiskFieldsSet = ownerRiskAcceptedBy.isNotBlank() || ownerRiskAcceptedOn.isNotBlank()
        require(
            !ownerRiskFieldsSet || commercialUseFinding == CommercialUseFinding.NON_COMMERCIAL_RESTRICTED
        ) {
            "core '$coreId' must not record an owner risk acceptance unless its commercial-use " +
                "finding is NON_COMMERCIAL_RESTRICTED; a permissive/copyleft-ok or unreviewed " +
                "finding has no risk to accept"
        }
        if (approved) {
            require(commitSha.isNotBlank()) { "approved core '$coreId' must record commitSha" }
            require(reviewedBy.isNotBlank()) { "approved core '$coreId' must record reviewedBy" }
            require(reviewedOn.isNotBlank()) { "approved core '$coreId' must record reviewedOn" }
            val riskAccepted = ownerRiskAcceptedBy.isNotBlank() && ownerRiskAcceptedOn.isNotBlank()
            require(
                commercialUseFinding == CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK || riskAccepted
            ) {
                "approved core '$coreId' must have a permitted commercial-use finding, or a " +
                    "recorded owner risk acceptance (ownerRiskAcceptedBy/ownerRiskAcceptedOn) if " +
                    "commercialUseFinding is NON_COMMERCIAL_RESTRICTED"
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
                "with upstream's libretro preprocessor flags plus the documented 48 kHz Android " +
                "audio integration override, -std=c99, and upstream's own libretro/link.T version " +
                "script; see third_party/cores/sameboy/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "2b047a180d920fd8f3460308b2ac05e9e7ad5627f3310a94e22731db1727be99",
                "arm64-v8a" to "b99b4b4486de632ee6c76c7d6d0649eac57d6f04da8826117e726a270ae141b2",
            ),
            reviewedBy = "DEV-DUFORD",
            reviewedOn = "2026-07-28",
            // Replaced by gambatte on 2026-08-01; retained unapproved pending owner
            // confirmation of retire-vs-retain (app enforces one approved core per system).
            approved = false,
        ),
        CoreLicenseFinding(
            coreName = "Genesis Plus GX",
            coreId = "genesis_plus_gx",
            upstreamRepository = "https://github.com/libretro/Genesis-Plus-GX",
            commitSha = "ca93fec870378f3bff65931bcd828d5e756cce75",
            releaseTag = "", // Upstream carries no release tags; commitSha is the exact pin.
            licenseSummary = "Custom BSD-style redistribution license (Charles MacDonald 1998-2003, " +
                "Eke-Eke 2007-2026, portions Nicola Salmoria/MAME team) covering the core code " +
                "itself, adding a no-sale/no-commercial-use condition and a modified-source-offer " +
                "condition beyond a plain BSD license (upstream LICENSE.txt, verbatim). This is an " +
                "additional restriction under GPLv3 section 10 (see docs/PHASE0_DECISIONS.md); " +
                "this core relies on the owner's dated risk-acceptance recorded below and in " +
                "HANDOFF.md's 2026-07-31 Phase 7 entry, not on a GPLv3-compatibility finding. " +
                "Vendored subcomponents carry their own separate, permissive/copyleft-compatible " +
                "licenses that add no further restriction: Nuked OPN2 (ym3438.c, LGPL-2.1-or-later), " +
                "Tremor (core/sound/tremor, BSD-style/Xiph), minimp3 (core/sound/minimp3, CC0 1.0), " +
                "zlib 1.2.11 (zlib license), and the vendored libretro-common helper sources (each " +
                "individually MIT-licensed per its own file header). Sega CD CHD support reuses the " +
                "core's own pinned dependency stack: libchdr (zlib license), LZMA SDK (public " +
                "domain), and zstd (BSD-2-Clause). Only files used by the " +
                "Android build are compiled; Gamecube/Wii/PSP2/UWP build files remain excluded. " +
                "See third_party/cores/genesis_plus_gx/VENDORING.md for the complete file-by-file " +
                "vendoring rationale and exclusions.",
            commercialUseFinding = CommercialUseFinding.NON_COMMERCIAL_RESTRICTED,
            sourceOfferSatisfied = true,
            attributionSatisfied = false,
            supportedSystems = listOf("genesis", "megadrive", "sms", "gamegear", "segacd"),
            supportedExtensions = listOf(".md", ".gen", ".bin", ".sms", ".gg", ".cue", ".iso", ".chd"),
            // Sega CD (segacd) needs the regional BIOS files; Genesis/Mega Drive/SMS/Game Gear do not.
            requiredFirmware = listOf(
                "bios_CD_U.bin", "bios_CD_E.bin", "bios_CD_J.bin",
            ),
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "linux-x86_64"),
            buildCommand = "JAVA_HOME=\"/opt/homebrew/opt/openjdk@17\" ./gradlew assembleRelease " +
                "(NDK r27.2.12479018, CMake 3.22.1; builds the `genesis_plus_gx_core` CMake target " +
                "in app/src/main/cpp/CMakeLists.txt, compiling " +
                "third_party/cores/genesis_plus_gx/{core,libretro}/* with upstream's own " +
                "libretro/jni/Android.mk COREFLAGS including USE_LIBCHDR, upstream's own " +
                "pinned libchdr/LZMA/zstd dependency sources from the same upstream commit, " +
                "libretro/link.T version script, and -D_ARM_ASSEM_ only for armeabi-v7a; see " +
                "third_party/cores/genesis_plus_gx/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "cae8ad226dcb8d89953fa8ae0e12c26632fff6edd286072a83f5b2eb77445ac6",
                "arm64-v8a" to "c12fed0455c94f57b5d5370d72e41add6bda6063fcd2d783cfa449f2f7a3433b",
            ),
            reviewedBy = "DEV-DUFORD",
            reviewedOn = "2026-07-31",
            ownerRiskAcceptedBy = "PROJECT-OWNER",
            ownerRiskAcceptedOn = "2026-07-31",
            approved = true,
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
            upstreamRepository = "https://github.com/snes9xgit/snes9x",
            commitSha = "921f9f7b83660eb44ad263022a57a4a029057c37",
            releaseTag = "1.63",
            licenseSummary = "The project's own non-commercial redistribution license (many named " +
                "contributors 1996-2023; the libretro port itself additionally copyrighted " +
                "2011-2017 by Hans-Kristian Arntzen and Daniel De Matteis \"under no " +
                "circumstances will commercial rights be given\") covering the core and all " +
                "enhancement-chip emulation, explicitly \"freeware for PERSONAL USE only\" " +
                "(upstream LICENSE, verbatim). This is an additional restriction under GPLv3 " +
                "section 10 (see docs/PHASE0_DECISIONS.md); this core relies on the owner's " +
                "dated risk-acceptance recorded below and in HANDOFF.md's Phase 7 entries, not " +
                "on a GPLv3-compatibility finding. One vendored subcomponent carries its own " +
                "separate, copyleft-compatible license that adds no further restriction: " +
                "snes_ntsc (core/filter/snes_ntsc.c, LGPL-2.1). Only the files this core's " +
                "libretro/Android build actually compiles are vendored (no GTK+/Win32/macOS/Qt " +
                "desktop GUI, no Vulkan renderer or its vendored third-party libraries, no " +
                "netplay, no JMA/ZIP ROM-archive readers, no interactive debugger). See " +
                "third_party/cores/snes9x/VENDORING.md for the complete file-by-file vendoring " +
                "rationale and exclusions.",
            commercialUseFinding = CommercialUseFinding.NON_COMMERCIAL_RESTRICTED,
            sourceOfferSatisfied = true,
            attributionSatisfied = false,
            supportedSystems = listOf("snes", "sfc"),
            supportedExtensions = listOf(".sfc", ".smc"),
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "linux-x86_64"),
            buildCommand = "JAVA_HOME=\"/opt/homebrew/opt/openjdk@17\" ./gradlew assembleRelease " +
                "(NDK r27.2.12479018, CMake 3.22.1; builds the `snes9x_core` CMake target in " +
                "app/src/main/cpp/CMakeLists.txt, compiling third_party/cores/snes9x/{core," +
                "libretro}/* with upstream's own libretro/jni/Android.mk COREFLAGS, -std=c++14, " +
                "and upstream's own libretro/link.T version script; see " +
                "third_party/cores/snes9x/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "a03aa51d30713ea6374541f7f08329892097928c50c6af785928ba57dcd1760e",
                "arm64-v8a" to "cd3bda8613969b9263e9a899d98f727e8895ef0bddeb88695611a11196391fef",
            ),
            reviewedBy = "DEV-DUFORD",
            reviewedOn = "2026-07-31",
            ownerRiskAcceptedBy = "PROJECT-OWNER",
            ownerRiskAcceptedOn = "2026-07-31",
            approved = true,
        ),
        CoreLicenseFinding(
            coreName = "FCEUmm",
            coreId = "fceumm",
            upstreamRepository = "https://github.com/libretro/libretro-fceumm",
            commitSha = "b5e3566515c27dc66c9c20572171673126532e06",
            releaseTag = "", // Upstream carries no release tags; commitSha is the exact pin.
            licenseSummary = "GPL-2.0-or-later (root Copying file, and every source header: " +
                "'either version 2 of the License, or (at your option) any later version'). " +
                "No non-commercial/no-sale/'personal use only' restriction anywhere in the " +
                "core. Vendored subcomponents carry their own separate, permissive/copyleft- " +
                "compatible licenses that add no further restriction: the vendored " +
                "libretro-common subtree (src/drivers/libretro/libretro-common/) is MIT; " +
                "Blargg's NTSC filter (src/ntsc/nes_ntsc.c, src/ntsc/license.txt) is " +
                "LGPL-2.1-or-later; the YM2413 emulator (src/boards/emu2413.c) is covered by " +
                "the core's own GPL-2.0-or-later. Upstream's bundled zlib is deliberately " +
                "NOT compiled (HAVE_ZLIB is undefined; the build uses libretro-common's " +
                "clean-room DEFLATE codec instead). Only the files this core's Android " +
                "libretro build actually compiles are vendored (no top-level Makefiles, no " +
                ".github/ CI, no intl/ scripts, no docs). Scope is cartridge-only: " +
                "nes/famicom .nes and .unf; FDS (.fds, disksys.rom firmware) is excluded. " +
                "See third_party/cores/fceumm/VENDORING.md for the complete file-by-file " +
                "vendoring rationale and exclusions.",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            sourceOfferSatisfied = true,
            attributionSatisfied = false,
            supportedSystems = listOf("nes"),
            supportedExtensions = listOf(".nes", ".unf"),
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "linux-x86_64"),
            buildCommand = "JAVA_HOME=\"/opt/homebrew/opt/openjdk@17\" ./gradlew assembleRelease " +
                "(NDK r27.2.12479018, CMake 3.22.1; builds the `fceumm_core` CMake target in " +
                "app/src/main/cpp/CMakeLists.txt, compiling third_party/cores/fceumm/src/**/* " +
                "with upstream's own libretro build defines (__LIBRETRO__, HAVE_NTSC_FILTER, " +
                "HAVE_HDPACK, FRONTEND_SUPPORTS_RGB565, PSS_STYLE=1, GIT_VERSION=\"b5e3566\") " +
                "and upstream's own src/drivers/libretro/link.T version script; see " +
                "third_party/cores/fceumm/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "ef64ee0e5ad2ab7550d853b6f1e286ad83c48c0f2e5762b338a706ec1b4f884a",
                "arm64-v8a" to "3a89042bd09d51d0ae5bf21e8dca4f442a1cc2c1ba0def63d07b47fd451b5a5f",
            ),
            reviewedBy = "DEV-DUFORD",
            reviewedOn = "2026-07-31",
            approved = true,
        ),
        CoreLicenseFinding(
            coreName = "mGBA",
            coreId = "mgba",
            upstreamRepository = "https://github.com/libretro/mgba",
            commitSha = "32de792178a3662cd0402c8568fccfaad4a764a1",
            releaseTag = "", // Upstream carries no usable release tags (only ancient 0.1.0/0.1.1 pre-releases).
            licenseSummary = "The core is licensed under MPL-2.0 (file-level weak copyleft, no non-commercial restriction). Vendored subcomponents in the compiled subset: inih (BSD-3-Clause), crc32 by Gary S. Brown (Public Domain), MurmurHash3 by Austin Appleby (Public Domain). Two data-only files (hle-bios.c, gbk-table.c) carry no per-file license header and are covered by the project-level MPL-2.0 LICENSE. No GPL-incompatible or non-commercial-restricted components found in the compiled subset. GBA content launches without external BIOS via built-in HLE implementation; GB/GBC boot without BIOS (no hardware BIOS exists for those platforms).",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            supportedSystems = listOf("gba"),
            supportedExtensions = listOf(".gba", ".agb"),
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "linux-x86_64"),
            buildCommand = "JAVA_HOME=\"/opt/homebrew/opt/openjdk@17\" ./gradlew assembleRelease " +
                "(NDK r27.2.12479018, CMake 3.22.1; builds the `mgba_core` CMake target in " +
                "app/src/main/cpp/CMakeLists.txt, compiling third_party/cores/mgba/*.{c,h} with " +
                "upstream's own libretro preprocessor flags (-DLIBRETRO, _GNU_SOURCE, " +
                "LIBRETRO_MGBA, HAS_MGBA=1) and upstream's own linker version script; see " +
                "third_party/cores/mgba/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "bf9a821990caf8a1ca51b5a6912947eb2f4b2bd35a49f10b9ac84f5c11febb9d",
                "arm64-v8a" to "945b570253d26ed16b997d56fda94ce7aa43794b6cabbf25b5225e11b18f7d88",
            ),
            reviewedBy = "DEV-DUFORD",
            reviewedOn = "2026-07-31",
            approved = true,
        ),
        CoreLicenseFinding(
            coreName = "Stella",
            coreId = "stella",
            upstreamRepository = "https://github.com/stella-emu/stella",
            commitSha = "d55b1aec0d067a4c901a6dcdf81cb8f579685659",
            releaseTag = "7.0",
            licenseSummary = "GPL-2.0-only (root License.txt, version 2 without 'or later' clause). No non-commercial/no-sale restriction anywhere in the core. Vendored subcomponents: nlohmann/json (MIT), NanoJPEG (MIT). GPL-2.0-only is NOT GPL-3-compatible; this core ships as a separately-licensed dynamically-loaded .so behind the plugin-boundary model.",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            sourceOfferSatisfied = true,
            attributionSatisfied = false,
            supportedSystems = listOf("atari2600"),
            supportedExtensions = listOf(".a26", ".bin"),
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "linux-x86_64"),
            buildCommand = "JAVA_HOME=\"/opt/homebrew/opt/openjdk@17\" ./gradlew assembleRelease " +
                "(NDK r27.2.12479018, CMake 3.22.1; builds the `stella_core` CMake target in " +
                "app/src/main/cpp/CMakeLists.txt, compiling third_party/cores/stella/**/* " +
                "with upstream's own libretro build defines (ANDROID, __LIB_RETRO__, " +
                "HAVE_STRINGS_H, SOUND_SUPPORT, GIT_VERSION=\"d55b1ae\"), -std=c++20, " +
                "-fexceptions, and upstream's own link.T version script; see " +
                "third_party/cores/stella/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "29a5d2bdff2f532b0948b83f9e295ae9de74c263bcebd85ff1113991b47acc94",
                "arm64-v8a" to "54c37d1015a47741da7048ac69a33548566691a94712ff5a7214bcde9c31bab6",
            ),
            reviewedBy = "DEV-DUFORD",
            reviewedOn = "2026-08-01",
            approved = true,
        ),
        CoreLicenseFinding(
            coreName = "gambatte",
            coreId = "gambatte",
            upstreamRepository = "https://github.com/libretro/gambatte-libretro",
            commitSha = "96174369b3c30d9fc57c926fa3379c273dc6a9a5",
            releaseTag = "", // Upstream carries no release tags; commitSha is the exact pin.
            licenseSummary = "GPL-2.0-only (root COPYING, Sindre Aamås, 'either version 2 of the License'). No non-commercial/no-sale restriction anywhere in the core. Mixed licensing with GPLv3 cc_resampler.c compiled unconditionally per Makefile.common SOURCES_C; FSF v2/v3 incompatibility noted but resolved under the project's separately-.so dynamically-loaded posture (each core loaded as an independently licensed shared object). Owner legal clearance recorded 2026-08-01 in third_party/cores/gambatte/VENDORING.md. Vendored subcomponents: blipper.c (MIT), libretro-common subtree (MIT). Only the files this core's Android libretro build actually compiles are vendored (no network code, no intl scripts, no CI/docs). Scope is cartridge-only: gb/gbc .gb/.gbc; .dmg advertised by upstream but not used by this app. See third_party/cores/gambatte/VENDORING.md for the complete file-by-file vendoring rationale and exclusions.",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            sourceOfferSatisfied = true,
            attributionSatisfied = false,
            supportedSystems = listOf("gb", "gbc"),
            supportedExtensions = listOf(".gb", ".gbc"),
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "linux-x86_64"),
            buildCommand = "JAVA_HOME=\"/opt/homebrew/opt/openjdk@17\" ./gradlew assembleRelease " +
                "(NDK r27.2.12479018, CMake 3.22.1; builds the `gambatte_core` CMake target in " +
                "app/src/main/cpp/CMakeLists.txt, compiling third_party/cores/gambatte/{libretro," +
                "src,libretro-common}/* with upstream's own libretro/jni/Android.mk COREFLAGS " +
                "(INLINE=inline, HAVE_STDINT_H, HAVE_INTTYPES_H, __LIBRETRO__, VIDEO_RGB565, " +
                "CC_RESAMPLER_NO_HIGHPASS), -Wno-c++11-narrowing, -O2 -DNDEBUG, -std=c++11, " +
                "and upstream's own link.T version script; see third_party/cores/gambatte/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "c9f9b61b8522fbf73a2121ac8768f4d1f4241333bef1c4eab090f6e8253ddcf4",
                "arm64-v8a" to "b1bc8d892f12c3adccf7c01c8552ed13bbd4de2624b796b14453cd894fb4159e",
            ),
            reviewedBy = "DEV-DUFORD",
            reviewedOn = "2026-08-01",
            approved = true,
        ),
        CoreLicenseFinding(
            coreName = "Beetle PCE Fast",
            coreId = "beetle_pce_fast",
            upstreamRepository = "https://github.com/libretro/beetle-pce-fast-libretro",
            commitSha = "b211204c7026dff6e86e79b00185512e2421fff8",
            releaseTag = "", // Upstream carries no release tags; commitSha is the exact pin.
            licenseSummary = "GPL-2.0-or-later (root COPYING). No non-commercial/no-sale restriction anywhere in the core. Vendored subcomponents: libretro-common subtree (MIT). Only the files this core's Android libretro build actually compiles are vendored (no desktop GUI, no network code, no CI/docs). Scope is cartridge-only: tg16 .pce; Super System Card (.sgx) and CD-ROM (.ccd/.cue) excluded. See third_party/cores/beetle_pce_fast/VENDORING.md for the complete file-by-file vendoring rationale and exclusions.",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            sourceOfferSatisfied = true,
            attributionSatisfied = false,
            supportedSystems = listOf("tg16"),
            supportedExtensions = listOf(".pce"),
            requiredFirmware = emptyList(),
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "linux-x86_64"),
            buildCommand = "JAVA_HOME=\"/opt/homebrew/opt/openjdk@17\" ./gradlew assembleRelease " +
                "(NDK r27.2.12479018, CMake 3.22.1; builds the `beetle_pce_fast_core` CMake target in " +
                "app/src/main/cpp/CMakeLists.txt, compiling third_party/cores/beetle_pce_fast/{libretro.c," +
                "mednafen,libretro-common}/* with upstream's own libretro/jni/Android.mk COREFLAGS " +
                "(FRONTEND_SUPPORTS_RGB565, MEDNAFEN_VERSION, __LIBRETRO__, _LOW_ACCURACY_, INLINE=inline, " +
                "WANT_PCE_FAST_EMU, NEED_CD, NEED_TREMOR) and upstream's own link.T version script; " +
                "see third_party/cores/beetle_pce_fast/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "b9e8e6d4675fb90fde61cd7c807de7f5cf3111d722da017e41ca07858d9415c0",
                "arm64-v8a" to "d53bfbb0ddbdc62b08fa4e9afa3dc685877fe663c76615e07e231cf6bc8db9dd",
            ),
            reviewedBy = "PROJECT-OWNER",
            reviewedOn = "2026-07-31",
            approved = true,
        ),
        CoreLicenseFinding(
            coreName = "Beetle NeoPop",
            coreId = "mednafen_ngp",
            upstreamRepository = "https://github.com/libretro/beetle-ngp-libretro",
            commitSha = "a50d5ac288a81f2104ddf43195a4efdd15c72227",
            releaseTag = "", // Upstream carries no release tags; commitSha is the exact pin.
            licenseSummary = "GPL-2.0-or-later (root COPYING and source headers carry the 'or later' clause, making this core GPL-3-compatible). No non-commercial/no-sale restriction anywhere in the core. Original Neopop emulator code Copyright (c) 2001-2002 by neopop_uk. Vendored subcomponents: Blip_Buffer.cpp (LGPL-2.1-or-later, Shay Green), Stereo_Buffer.cpp (GPL-2.0-or-later), z80-fuse CPU emulator (GPL-2.0-or-later, Philip Kendall), libretro-common subtree (MIT). Only the files this core's Android libretro build actually compiles are vendored (no desktop GUI, no network code, no CI/docs). Scope is cartridge-only: neo-geo-pocket and neo-geo-pocket-color; content launches without external BIOS via built-in HLE implementation. See third_party/cores/mednafen_ngp/VENDORING.md for the complete file-by-file vendoring rationale and exclusions.",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            sourceOfferSatisfied = true,
            attributionSatisfied = false,
            supportedSystems = listOf("neo-geo-pocket", "neo-geo-pocket-color"),
            supportedExtensions = listOf(".ngp", ".ngc", ".ngpc", ".npc"),
            requiredFirmware = emptyList(),
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "linux-x86_64"),
            buildCommand = "JAVA_HOME=\"/opt/homebrew/opt/openjdk@17\" ./gradlew assembleRelease " +
                "(NDK r27.2.12479018, CMake 3.22.1; builds the `mednafen_ngp_core` CMake target in " +
                "app/src/main/cpp/CMakeLists.txt, compiling third_party/cores/mednafen_ngp/{libretro.c," +
                "mednafen,libretro-common}/* with upstream's own libretro/jni/Android.mk COREFLAGS " +
                "(FRONTEND_SUPPORTS_RGB565, MEDNAFEN_VERSION_NUMERIC, WANT_16BPP, __LIBRETRO__, " +
                "WANT_NGP_EMU, LOAD_FROM_MEMORY, INLINE=inline, GIT_VERSION=\"a50d5ac\"), -fexceptions, " +
                "and upstream's own link.T version script; see third_party/cores/mednafen_ngp/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "9cdd4f0bd6fc74de04e4e293dfb595c420e63f7479cc29473242cdc7918aa6f6",
                "arm64-v8a" to "2b7dd03031850e447decb3772f0e30df4b8d24651331a3813b3ffaef16fbc512",
            ),
            reviewedBy = "PROJECT-OWNER",
            reviewedOn = "2026-08-01",
            approved = true,
        ),
        CoreLicenseFinding(
            coreName = "Beetle WonderSwan",
            coreId = "mednafen_wswan",
            upstreamRepository = "https://github.com/libretro/beetle-wswan-libretro",
            commitSha = "4b01295838ea89e3f1355bbe4cb5cf98aa6108cd",
            releaseTag = "", // Upstream carries no release tags; commitSha is the exact pin.
            licenseSummary = "GPL-2.0-or-later (root COPYING and source headers carry the 'or later' clause, making this core GPL-3-compatible). No non-commercial/no-sale restriction anywhere in the core. Vendored subcomponents: mednafen/wswan/v30mz.c (permissive custom license — Cygne project, Bryan McPhail; commercial use allowed with attribution), mednafen/sound/Blip_Buffer.c (LGPL-2.1-or-later, Shay Green), libretro-common subtree (MIT). Only the files this core's Android libretro build actually compiles are vendored (no desktop GUI, no network code, no CI/docs). Scope is BIOS-free cartridge-only: wonderswan and wonderswan-color; content launches directly from frontend memory buffer without external boot ROM. See third_party/cores/mednafen_wswan/VENDORING.md for the complete file-by-file vendoring rationale and exclusions.",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            sourceOfferSatisfied = true,
            attributionSatisfied = false,
            supportedSystems = listOf("wonderswan", "wonderswan-color"),
            supportedExtensions = listOf(".ws", ".wsc", ".pc2"),
            requiredFirmware = emptyList(),
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "linux-x86_64"),
            buildCommand = "JAVA_HOME=\"/opt/homebrew/opt/openjdk@17\" ./gradlew assembleRelease " +
                "(NDK r27.2.12479018, CMake 3.22.1; builds the `mednafen_wswan_core` CMake target in " +
                "app/src/main/cpp/CMakeLists.txt, compiling third_party/cores/mednafen_wswan/{libretro.c," +
                "mednafen,libretro-common}/* with upstream's own libretro/jni/Android.mk COREFLAGS " +
                "(FRONTEND_SUPPORTS_RGB565, MEDNAFEN_VERSION_NUMERIC, WANT_16BPP, __LIBRETRO__, " +
                "WANT_WSWAN_EMU, LOAD_FROM_MEMORY, INLINE=inline), -fexceptions, " +
                "and upstream's own link.T version script; see third_party/cores/mednafen_wswan/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "3a009628d9f21896442f214d6489c0e5ea620b85a23368a5b11094001718745c",
                "arm64-v8a" to "995233d8d0b9354a01dbc9e37b5121659bfb7e06f9e1a66e5607ef0314d6452f",
            ),
            reviewedBy = "PROJECT-OWNER",
            reviewedOn = "2026-08-01",
            approved = true,
        ),
        CoreLicenseFinding(
            coreName = "Handy",
            coreId = "handy",
            upstreamRepository = "https://github.com/libretro/libretro-handy",
            commitSha = "bc55d462f0b2d6b073ea93dc552ebd73cec60fd1",
            releaseTag = "", // Upstream has no release tags; commitSha is the exact pin.
            licenseSummary = "GPL-2.0-or-later effective (core emulator code is zlib/libpng-style permissive per lynx/license.txt — commercial use explicitly permitted), blip/Blip_Buffer.cpp LGPL-2.1-or-later (Shay Green), blip/Stereo_Buffer.cpp/.h GPL-2.0-or-later, libretro-common subtree MIT; effective license GPL-2.0-or-later (GPL-3-compatible); no non-commercial/no-sale restriction anywhere; BIOS-free — lynxboot.img is optional (HLE fallback, used only if found with CRC 0xD973C9D); scope is Atari Lynx cartridge only (lynx); extensions .lnx/.lyx/.o; only the files this core's Android libretro build compiles are vendored (no desktop code, no Makefiles/CI/docs); see third_party/cores/handy/VENDORING.md for the complete file-by-file vendoring rationale.",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            sourceOfferSatisfied = true,
            attributionSatisfied = false,
            supportedSystems = listOf("lynx"),
            supportedExtensions = listOf(".lnx", ".lyx", ".o"),
            requiredFirmware = emptyList(),
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "linux-x86_64"),
            buildCommand = "JAVA_HOME=\"/opt/homebrew/opt/openjdk@17\" ./gradlew assembleRelease " +
                "(NDK r27.2.12479018, CMake 3.22.1; builds the `handy_core` CMake target in " +
                "app/src/main/cpp/CMakeLists.txt, compiling third_party/cores/handy/{libretro," +
                "lynx,blip,libretro-common}/* with upstream's own libretro/jni/Android.mk COREFLAGS " +
                "(ANDROID, __LIBRETRO__, HAVE_STRINGS_H, HAVE_STDINT_H, WANT_CRC32, " +
                "GIT_VERSION=\"bc55d46\"), -std=gnu++11, " +
                "and upstream's own libretro/link.T version script; see third_party/cores/handy/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "5382a30ef80671b5b949b8c0e36699966b92d4c29e1c4351760a897ddd9f70cc",
                "arm64-v8a" to "ab7a28fbed62be8483af91aa92bd1207e99b1ba95cb2d53716644bdbea460cd9",
            ),
            reviewedBy = "PROJECT-OWNER",
            reviewedOn = "2026-08-01",
            approved = true,
        ),
        CoreLicenseFinding(
            coreName = "ProSystem",
            coreId = "prosystem",
            upstreamRepository = "https://github.com/libretro/prosystem-libretro",
            commitSha = "363b6dfbd3e240762e022c2b4897b4fe55722be3",
            releaseTag = "", // Upstream has no release tags; commitSha is the exact pin.
            licenseSummary = "GPL-2.0-or-later effective (core emulator code), bupboop audio library zlib, libretro-common subtree MIT; effective license GPL-2.0-or-later (GPL-3-compatible); no non-commercial/no-sale restriction anywhere; BIOS-free — optional HLE fallback; scope is Atari 7800 only; extensions .a78/.bin/.cdf; only the files this core's Android libretro build compiles are vendored (Makefiles and link.T preserved as build record; CI, docs, and desktop-only files excluded)",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            sourceOfferSatisfied = true,
            attributionSatisfied = false,
            supportedSystems = listOf("atari7800"),
            supportedExtensions = listOf(".a78", ".bin", ".cdf"),
            requiredFirmware = emptyList(),
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "linux-x86_64"),
            buildCommand = "JAVA_HOME=\"/opt/homebrew/opt/openjdk@17\" ./gradlew assembleRelease " +
                "(NDK r27.2.12479018, CMake 3.22.1; builds the `prosystem_core` CMake target in " +
                "app/src/main/cpp/CMakeLists.txt, compiling third_party/cores/prosystem/{core,bupboop," +
                "libretro-common}/* C sources with defines ANDROID __LIBRETRO__ " +
                "GIT_VERSION=\\\"363b6df\\\", -fsigned-char, " +
                "and upstream's own libretro/link.T version script; see third_party/cores/prosystem/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "718a17cfbcf96f923eb2e03cd6cb12ad61b426a6dfeb87f1305900508cc3baf5",
                "arm64-v8a" to "894cdbd1f779d5e764db88065a6fe2c40c379ca5294fbabae13bac61d4d1bb1b",
            ),
            reviewedBy = "PROJECT-OWNER",
            reviewedOn = "2026-08-02",
            approved = true,
        ),
        CoreLicenseFinding(
            coreName = "PCSX-ReARMed",
            coreId = "pcsx_rearmed",
            upstreamRepository = "https://github.com/libretro/pcsx_rearmed",
            commitSha = "da2cb8ecd17fd0932ab6d94774c0522beebce6e3",
            releaseTag = "",
            licenseSummary = "GPL-2.0-or-later effective: the PCSX engine, libretro frontend, " +
                "ari64 dynarec, and NEON renderer compiled here all grant GPL version 2 or later; " +
                "commercial redistribution is permitted subject to GPL corresponding-source and " +
                "notice obligations. Dual GPL-2.0-or-later/LGPL-2.1-or-later glue files are used " +
                "under GPL. Compiled dependencies are libretro-common (MIT), libchdr " +
                "(BSD-3-Clause), LZMA SDK 25.01 (public domain), and the zstd 1.5.7 decoder " +
                "(BSD-style option selected). The Linux build additionally uses Lightrec " +
                "(LGPL-2.1-or-later) and GNU Lightning (LGPL-3.0-or-later); the combined Linux " +
                "core uses PCSX-ReARMed's GPL later-version option. No non-commercial/no-sale " +
                "restriction was found. " +
                "The Android ARM/ARM64 closure and Linux x86_64 Lightrec closure are vendored; see " +
                "third_party/cores/pcsx_rearmed/VENDORING.md. Memory-card slot 1 is forced to " +
                "Libretro's 128 KiB RETRO_MEMORY_SAVE_RAM and therefore uses per-ROM save sync; " +
                "slot 2 is disabled because upstream exposes only unsynchronized serial/shared " +
                "file modes for it.",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            sourceOfferSatisfied = true,
            attributionSatisfied = false,
            supportedSystems = listOf("psx"),
            supportedExtensions = listOf(
                ".bin", ".cue", ".img", ".mdf", ".pbp", ".toc",
                ".cbn", ".m3u", ".chd", ".iso", ".exe",
            ),
            requiredFirmware = listOf(
                "scph5500.bin", "scph5501.bin", "scph5502.bin",
                "psxonpsp660.bin", "scph101.bin", "scph7001.bin", "scph1001.bin",
            ),
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "linux-x86_64"),
            buildCommand = "JAVA_HOME=\"/opt/homebrew/opt/openjdk@17\" ./gradlew assembleRelease " +
                "(NDK r27.2.12479018, CMake 3.22.1; builds `pcsx_rearmed_core` with upstream's " +
                "Android.mk source closure and flags: ARM/ARM64 ari64 dynarec, NEON GPU, " +
                "NDRC_THREAD, async CD/GPU/SPU, libretro VFS, libchdr/LZMA/zstd CHD support, " +
                "upstream export/linker scripts, and 16 KiB maximum ELF page size; see " +
                "third_party/cores/pcsx_rearmed/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "26002854a47547d4c638d8ca0a63ced9a71c3e1c9acf724f98d8fa77bcd18051",
                "arm64-v8a" to "2004b2c61a04e3c2be97ba056750b45cd5aa7d4094e63d1d2f8fc5bc3fb8a144",
            ),
            reviewedBy = "DEV-DUFORD",
            reviewedOn = "2026-08-02",
            approved = true,
        ),
        CoreLicenseFinding(
            coreName = "Mupen64Plus",
            coreId = "mupen64plus_next",
            upstreamRepository = "https://github.com/libretro/mupen64plus-libretro-nx",
            commitSha = "98c1b0d877542b01314b3b04272282ba223b65b3",
            releaseTag = "", // Upstream carries no release tags; commitSha is the exact pin.
            licenseSummary = "GPL-2.0-or-later effective: mupen64plus-core, GLideN64, rsp-hle, and " +
                "the Angrylion renderer are GPL-2.0; the rsp-cxd4 plugin is CC0 1.0, the paraLLEl " +
                "RSP is dual MIT/LGPL-3.0 (built under the MIT option), parallel-rdp is MIT, " +
                "libretro-common is MIT, xxHash is CC0, and the bundled libpng/zlib carry their " +
                "own permissive licenses — all add no further restriction on top of the effective " +
                "GPL-2.0-or-later, which is compatible with a GPLv3 application. No " +
                "non-commercial/no-sale restriction found anywhere in the compiled closure. The " +
                "core runs PIF HLE, so no external firmware/BIOS is required. Only the files this " +
                "core's Android and Linux libretro builds compile are vendored; see " +
                "third_party/cores/mupen64plus_next/VENDORING.md.",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            sourceOfferSatisfied = true,
            attributionSatisfied = false,
            supportedSystems = listOf("n64"),
            supportedExtensions = listOf(".n64", ".z64", ".v64", ".bin", ".u1"),
            requiredFirmware = emptyList(), // No BIOS required — mupen64plus_next runs PIF HLE; IPL3 is read from the ROM at offset 0x40
            supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "linux-x86_64"),
            buildCommand = "JAVA_HOME=\"/opt/homebrew/opt/openjdk@17\" ./gradlew assembleRelease " +
                "(NDK r27.2.12479018, CMake 3.22.1; builds `mupen64plus_next_core` with upstream's " +
                "Android.mk/Makefile.common source closure and flags: ARM/ARM64 dynarec " +
                "(NEW_DYNAREC=3/4), paraLLEl RSP/RDP, LLE, Angrylion renderer, NEON on " +
                "armeabi-v7a, GLES3 override, C++17, libretro VFS, upstream link.T version " +
                "script, and 16 KiB maximum ELF page size; see " +
                "third_party/cores/mupen64plus_next/VENDORING.md)",
            binaryChecksums = mapOf(
                "armeabi-v7a" to "c00c2cd1f2eafc2d950ebaf273f2c962f4fd3daf8becaf4736efa8c2549a2de7",
                "arm64-v8a" to "de7395a403e38ad37cd066ac22fc2eb08c9d1a11e6566bbe30c88de3d971b519",
            ),
            reviewedBy = "DEV-DUFORD",
            reviewedOn = "2026-08-02",
            approved = true,
        ),
        CoreLicenseFinding(
            coreName = "Dolphin",
            coreId = "dolphin",
            upstreamRepository = "https://github.com/libretro/dolphin",
            commitSha = "841bacadb5d5c3f9acba0dc652d306ecd77a7bbf",
            releaseTag = "",
            licenseSummary = "GPL-2.0-or-later for Dolphin's original source, with the complete " +
                "repository declaring its aggregate dependency closure GPLv3-compatible. The " +
                "Linux-only Libretro build disables the standalone UI, analytics, online " +
                "updater, RetroAchievements, Vulkan, and platform input/audio backends. Exact " +
                "source and dependency pins are retained through the recursive Dolphin git " +
                "submodule; see third_party/cores/dolphin/COPYING and LICENSES/.",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            sourceOfferSatisfied = true,
            attributionSatisfied = true,
            supportedSystems = listOf("ngc", "gc"),
            supportedExtensions = listOf(
                ".elf", ".dol", ".gcm", ".iso", ".tgc", ".wbfs",
                ".ciso", ".gcz", ".wia", ".rvz", ".m3u",
            ),
            requiredFirmware = emptyList(),
            supportedAbis = listOf("linux-x86_64"),
            buildCommand = "git submodule update --init --recursive && " +
                "cmake -S native/player -B build/player && cmake --build build/player " +
                "(target dolphin_core via native/cmake/cores/dolphin-linux.cmake; GCC C++23 " +
                "Dolphin LIBRETRO=ON, OpenGL ES hardware rendering, Vulkan disabled)",
            reviewedBy = "DEV-DUFORD",
            reviewedOn = "2026-08-23",
            approved = true,
        ),
        CoreLicenseFinding(
            coreName = "RomM Synthetic Test Core",
            coreId = "test_core",
            upstreamRepository = "https://github.com/romm-android-tv/rommulus",
            // Synthetic project-owned core: no upstream commit exists, but approved
            // entries must record a non-blank commitSha, so a zero placeholder is used.
            commitSha = "0000000000000000000000000000000000000000",
            // releaseTag is the revision pin emitted for this core in the derived
            // ROMM_PLAYER_ALLOWED_CORES value ("test_core=1").
            releaseTag = "1",
            licenseSummary = "Synthetic test core, project-owned",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            sourceOfferSatisfied = true,
            attributionSatisfied = true,
            // No real platform: the data class requires a non-empty list, so a synthetic
            // slug is recorded instead of emptyList().
            supportedSystems = listOf("synthetic"),
            supportedExtensions = emptyList(),
            supportedAbis = listOf("linux-x86_64"),
            reviewedBy = "romm-android-tv",
            reviewedOn = "2026-08-17",
            approved = true,
        ),
    )

    /** Cores cleared for use in a production build. Empty until Phase 0 approvals happen. */
    fun approvedEntries(): List<CoreLicenseFinding> = entries.filter { it.approved }

    fun findById(coreId: String): CoreLicenseFinding? = entries.find { it.coreId == coreId }
}
