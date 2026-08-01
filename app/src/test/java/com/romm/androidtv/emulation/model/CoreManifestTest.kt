package com.romm.androidtv.emulation.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoreManifestTest {

    @Test
    fun `gambatte, Genesis Plus GX, Snes9x, fceumm, mgba, and stella are approved, following their individual license reviews`() {
        // SameBoy was replaced by gambatte on 2026-08-01 (unapproved, retained pending retire-vs-retain).
        // PicoDrive and Mupen64Plus remain unreviewed/restricted starting facts from Phase 0
        // (LIBRETRO_REFACTOR.md section 4.1) — approving these six cores must not silently
        // approve any other entry.
        assertThat(CoreManifest.approvedEntries().map { it.coreId })
            .containsExactlyInAnyOrder("gambatte", "genesis_plus_gx", "snes9x", "fceumm", "mgba", "stella", "beetle_pce_fast", "mednafen_ngp")
    }

    @Test
    fun `SameBoy is unapproved after being replaced by gambatte on 2026-08-01`() {
        val sameboy = CoreManifest.findById("sameboy")

        assertThat(sameboy).isNotNull
        assertThat(sameboy!!.approved).isFalse()
        assertThat(sameboy.reviewedBy).isEqualTo("DEV-DUFORD")
        assertThat(sameboy.reviewedOn).isNotBlank()
        assertThat(sameboy.commitSha).isNotBlank()
        assertThat(sameboy.commercialUseFinding).isEqualTo(CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK)
    }

    @Test
    fun `Genesis Plus GX's approval records a named reviewer, commit, restricted finding, and the owner's risk acceptance`() {
        val gpgx = CoreManifest.findById("genesis_plus_gx")

        assertThat(gpgx).isNotNull
        assertThat(gpgx!!.approved).isTrue()
        assertThat(gpgx.reviewedBy).isEqualTo("DEV-DUFORD")
        assertThat(gpgx.reviewedOn).isNotBlank()
        assertThat(gpgx.commitSha).isNotBlank()
        assertThat(gpgx.commercialUseFinding).isEqualTo(CommercialUseFinding.NON_COMMERCIAL_RESTRICTED)
        assertThat(gpgx.ownerRiskAcceptedBy).isNotBlank()
        assertThat(gpgx.ownerRiskAcceptedOn).isNotBlank()
        assertThat(gpgx.supportedAbis).containsExactlyInAnyOrder("armeabi-v7a", "arm64-v8a")
        assertThat(gpgx.binaryChecksums).containsOnlyKeys("armeabi-v7a", "arm64-v8a")
        assertThat(gpgx.binaryChecksums.values).allSatisfy { checksum ->
            assertThat(checksum).hasSize(64) // SHA-256 hex digest
        }
    }

    @Test
    fun `Snes9x's approval records a named reviewer, commit, restricted finding, and the owner's risk acceptance`() {
        val snes9x = CoreManifest.findById("snes9x")

        assertThat(snes9x).isNotNull
        assertThat(snes9x!!.approved).isTrue()
        assertThat(snes9x.reviewedBy).isEqualTo("DEV-DUFORD")
        assertThat(snes9x.reviewedOn).isNotBlank()
        assertThat(snes9x.commitSha).isNotBlank()
        assertThat(snes9x.commercialUseFinding).isEqualTo(CommercialUseFinding.NON_COMMERCIAL_RESTRICTED)
        assertThat(snes9x.ownerRiskAcceptedBy).isNotBlank()
        assertThat(snes9x.ownerRiskAcceptedOn).isNotBlank()
        assertThat(snes9x.supportedAbis).containsExactlyInAnyOrder("armeabi-v7a", "arm64-v8a")
        assertThat(snes9x.binaryChecksums).containsOnlyKeys("armeabi-v7a", "arm64-v8a")
        assertThat(snes9x.binaryChecksums.values).allSatisfy { checksum ->
            assertThat(checksum).hasSize(64) // SHA-256 hex digest
        }
    }

    @Test
    fun `fceumm's approval records a named reviewer, commit, and permissive finding`() {
        val fceumm = CoreManifest.findById("fceumm")

        assertThat(fceumm).isNotNull
        assertThat(fceumm!!.approved).isTrue()
        assertThat(fceumm.reviewedBy).isEqualTo("DEV-DUFORD")
        assertThat(fceumm.reviewedOn).isNotBlank()
        assertThat(fceumm.commitSha).isNotBlank()
        assertThat(fceumm.commercialUseFinding).isEqualTo(CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK)
        assertThat(fceumm.ownerRiskAcceptedBy).isBlank()
        assertThat(fceumm.ownerRiskAcceptedOn).isBlank()
        assertThat(fceumm.supportedAbis).containsExactlyInAnyOrder("armeabi-v7a", "arm64-v8a")
        assertThat(fceumm.binaryChecksums).containsOnlyKeys("armeabi-v7a", "arm64-v8a")
        assertThat(fceumm.binaryChecksums.values).allSatisfy { checksum ->
            assertThat(checksum).hasSize(64) // SHA-256 hex digest
        }
    }

    @Test
    fun `mgba's approval records a named reviewer, commit, and permissive finding`() {
        val mgba = CoreManifest.findById("mgba")

        assertThat(mgba).isNotNull
        assertThat(mgba!!.approved).isTrue()
        assertThat(mgba.reviewedBy).isEqualTo("DEV-DUFORD")
        assertThat(mgba.reviewedOn).isNotBlank()
        assertThat(mgba.commitSha).isNotBlank()
        assertThat(mgba.commercialUseFinding).isEqualTo(CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK)
        assertThat(mgba.ownerRiskAcceptedBy).isBlank()
        assertThat(mgba.ownerRiskAcceptedOn).isBlank()
        assertThat(mgba.supportedAbis).containsExactlyInAnyOrder("armeabi-v7a", "arm64-v8a")
        assertThat(mgba.binaryChecksums).containsOnlyKeys("armeabi-v7a", "arm64-v8a")
        assertThat(mgba.binaryChecksums.values).allSatisfy { checksum ->
            assertThat(checksum).hasSize(64) // SHA-256 hex digest
        }
        assertThat(mgba.supportedSystems).containsExactly("gba")
    }

    @Test
    fun `stella's approval records a named reviewer, commit, and permissive finding`() {
        val stella = CoreManifest.findById("stella")

        assertThat(stella).isNotNull
        assertThat(stella!!.approved).isTrue()
        assertThat(stella.reviewedBy).isEqualTo("DEV-DUFORD")
        assertThat(stella.reviewedOn).isNotBlank()
        assertThat(stella.commitSha).isNotBlank()
        assertThat(stella.commercialUseFinding).isEqualTo(CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK)
        assertThat(stella.ownerRiskAcceptedBy).isBlank()
        assertThat(stella.ownerRiskAcceptedOn).isBlank()
        assertThat(stella.supportedAbis).containsExactlyInAnyOrder("armeabi-v7a", "arm64-v8a")
        assertThat(stella.binaryChecksums).containsOnlyKeys("armeabi-v7a", "arm64-v8a")
        assertThat(stella.binaryChecksums.values).allSatisfy { checksum ->
            assertThat(checksum).hasSize(64) // SHA-256 hex digest
        }
        assertThat(stella.supportedSystems).containsExactly("atari2600")
        assertThat(stella.releaseTag).isEqualTo("7.0")
    }

    @Test
    fun `gambatte's approval records a named reviewer, commit, and permissive finding`() {
        val gambatte = CoreManifest.findById("gambatte")

        assertThat(gambatte).isNotNull
        assertThat(gambatte!!.approved).isTrue()
        assertThat(gambatte.reviewedBy).isEqualTo("DEV-DUFORD")
        assertThat(gambatte.reviewedOn).isNotBlank()
        assertThat(gambatte.commitSha).isNotBlank()
        assertThat(gambatte.commercialUseFinding).isEqualTo(CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK)
        assertThat(gambatte.ownerRiskAcceptedBy).isBlank()
        assertThat(gambatte.ownerRiskAcceptedOn).isBlank()
        assertThat(gambatte.supportedAbis).containsExactlyInAnyOrder("armeabi-v7a", "arm64-v8a")
        assertThat(gambatte.binaryChecksums).containsOnlyKeys("armeabi-v7a", "arm64-v8a")
        assertThat(gambatte.binaryChecksums.values).allSatisfy { checksum ->
            assertThat(checksum).hasSize(64) // SHA-256 hex digest
        }
        assertThat(gambatte.supportedSystems).containsExactly("gb", "gbc")
    }

    @Test
    fun `beetle_pce_fast's approval records a named reviewer, commit, and permissive finding`() {
        val beetle = CoreManifest.findById("beetle_pce_fast")

        assertThat(beetle).isNotNull
        assertThat(beetle!!.approved).isTrue()
        assertThat(beetle.reviewedBy).isEqualTo("PROJECT-OWNER")
        assertThat(beetle.reviewedOn).isNotBlank()
        assertThat(beetle.commitSha).isNotBlank()
        assertThat(beetle.commercialUseFinding).isEqualTo(CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK)
        assertThat(beetle.ownerRiskAcceptedBy).isBlank()
        assertThat(beetle.ownerRiskAcceptedOn).isBlank()
        assertThat(beetle.supportedAbis).containsExactlyInAnyOrder("armeabi-v7a", "arm64-v8a")
        assertThat(beetle.binaryChecksums).containsOnlyKeys("armeabi-v7a", "arm64-v8a")
        assertThat(beetle.binaryChecksums.values).allSatisfy { checksum ->
            assertThat(checksum).hasSize(64) // SHA-256 hex digest
        }
        assertThat(beetle.supportedSystems).containsExactly("tg16")
        assertThat(beetle.supportedExtensions).containsExactly(".pce")
        assertThat(beetle.requiredFirmware).isEmpty()
    }

    @Test
    fun `mednafen_ngp's approval records a named reviewer, commit, and permissive finding`() {
        val ngp = CoreManifest.findById("mednafen_ngp")

        assertThat(ngp).isNotNull
        assertThat(ngp!!.approved).isTrue()
        assertThat(ngp.reviewedBy).isEqualTo("PROJECT-OWNER")
        assertThat(ngp.reviewedOn).isNotBlank()
        assertThat(ngp.commitSha).isNotBlank()
        assertThat(ngp.commercialUseFinding).isEqualTo(CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK)
        assertThat(ngp.ownerRiskAcceptedBy).isBlank()
        assertThat(ngp.ownerRiskAcceptedOn).isBlank()
        assertThat(ngp.supportedAbis).containsExactlyInAnyOrder("armeabi-v7a", "arm64-v8a")
        assertThat(ngp.binaryChecksums).containsOnlyKeys("armeabi-v7a", "arm64-v8a")
        assertThat(ngp.binaryChecksums.values).allSatisfy { checksum ->
            assertThat(checksum).hasSize(64) // SHA-256 hex digest
        }
        assertThat(ngp.supportedSystems).containsExactly("neo-geo-pocket", "neo-geo-pocket-color")
        assertThat(ngp.supportedExtensions).containsExactly(".ngp", ".ngc", ".ngpc", ".npc")
        assertThat(ngp.requiredFirmware).isEmpty()
    }

    @Test
    fun `every entry declares a commercial-use finding`() {
        assertThat(CoreManifest.entries).allSatisfy { entry ->
            assertThat(entry.commercialUseFinding).isNotNull()
        }
    }

    @Test
    fun `remaining restrictive cores are recorded as non-commercial restricted and unapproved`() {
        val restricted = listOf("picodrive")

        restricted.forEach { coreId ->
            val entry = CoreManifest.findById(coreId)
            assertThat(entry).isNotNull
            assertThat(entry!!.commercialUseFinding)
                .isEqualTo(CommercialUseFinding.NON_COMMERCIAL_RESTRICTED)
            assertThat(entry.approved).isFalse()
        }
    }

    @Test
    fun `findById returns null for an unknown core`() {
        assertThat(CoreManifest.findById("does-not-exist")).isNull()
    }

    @Test
    fun `an approved entry must record a reviewer and date`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            CoreLicenseFinding(
                coreName = "Test Core",
                coreId = "test_core",
                upstreamRepository = "https://example.com/test-core",
                commitSha = "abc123",
                licenseSummary = "MIT",
                commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
                supportedSystems = listOf("gb"),
                supportedExtensions = listOf(".gb"),
                approved = true,
                // reviewedBy / reviewedOn intentionally omitted
            )
        }
    }

    @Test
    fun `an approved entry cannot have a restricted commercial-use finding without an owner risk acceptance`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            CoreLicenseFinding(
                coreName = "Test Core",
                coreId = "test_core",
                upstreamRepository = "https://example.com/test-core",
                commitSha = "abc123",
                licenseSummary = "Non-commercial",
                commercialUseFinding = CommercialUseFinding.NON_COMMERCIAL_RESTRICTED,
                supportedSystems = listOf("gb"),
                supportedExtensions = listOf(".gb"),
                reviewedBy = "reviewer",
                reviewedOn = "2026-07-27",
                approved = true,
                // ownerRiskAcceptedBy / ownerRiskAcceptedOn intentionally omitted
            )
        }
    }

    @Test
    fun `a restricted entry cannot record an owner risk acceptance without being approved-eligible`() {
        // ownerRiskAcceptedBy/On may only be set alongside a NON_COMMERCIAL_RESTRICTED finding —
        // recording one against a permissive/copyleft-ok finding would be a meaningless, misleading
        // record (there is no risk to accept), so the constructor rejects it outright.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            CoreLicenseFinding(
                coreName = "Test Core",
                coreId = "test_core",
                upstreamRepository = "https://example.com/test-core",
                commitSha = "abc123",
                licenseSummary = "MIT",
                commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
                supportedSystems = listOf("gb"),
                supportedExtensions = listOf(".gb"),
                ownerRiskAcceptedBy = "PROJECT-OWNER",
                ownerRiskAcceptedOn = "2026-07-31",
                approved = false,
            )
        }
    }

    @Test
    fun `a restricted entry with a recorded owner risk acceptance can be approved`() {
        val entry = CoreLicenseFinding(
            coreName = "Test Core",
            coreId = "test_core",
            upstreamRepository = "https://example.com/test-core",
            commitSha = "abc123",
            licenseSummary = "Non-commercial",
            commercialUseFinding = CommercialUseFinding.NON_COMMERCIAL_RESTRICTED,
            supportedSystems = listOf("gb"),
            supportedExtensions = listOf(".gb"),
            reviewedBy = "reviewer",
            reviewedOn = "2026-07-27",
            ownerRiskAcceptedBy = "PROJECT-OWNER",
            ownerRiskAcceptedOn = "2026-07-31",
            approved = true,
        )

        assertThat(entry.approved).isTrue()
    }

    @Test
    fun `a fully reviewed and permitted entry can be approved`() {
        val entry = CoreLicenseFinding(
            coreName = "Test Core",
            coreId = "test_core",
            upstreamRepository = "https://example.com/test-core",
            commitSha = "abc123",
            licenseSummary = "MIT",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            supportedSystems = listOf("gb"),
            supportedExtensions = listOf(".gb"),
            reviewedBy = "reviewer",
            reviewedOn = "2026-07-27",
            approved = true,
        )

        assertThat(entry.approved).isTrue()
    }

    @Test
    fun `coreBuildRevision uses exact commit SHA over release tag`() {
        val sameboy = CoreManifest.findById("sameboy")!!

        // The authoritative build revision is the exact commit SHA — not a release label.
        // MainActivity resolves coreBuildRevision as: commitSha ?: releaseTag (commit preferred).
        assertThat(sameboy.commitSha).isEqualTo("8230189896a8bb6598574d302ba0ad3658f98ab4")
        assertThat(sameboy.releaseTag).isEqualTo("v1.0.3-libretro")

        // When both are present, commitSha is the exact build identity (preferred by MainActivity).
        val coreBuildRevision = sameboy.commitSha.takeIf { it.isNotBlank() } ?: sameboy.releaseTag.takeIf { it.isNotBlank() }
        assertThat(coreBuildRevision).isEqualTo("8230189896a8bb6598574d302ba0ad3658f98ab4")
    }

    @Test
    fun `coreBuildRevision falls back to releaseTag when commitSha is blank`() {
        val entry = CoreLicenseFinding(
            coreName = "Untagged Core",
            coreId = "untagged_core",
            upstreamRepository = "https://example.com/untagged",
            commitSha = "", // Intentionally blank — untagged, no commit recorded.
            releaseTag = "v2.0.0-libretro",
            licenseSummary = "MIT",
            commercialUseFinding = CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK,
            supportedSystems = listOf("gb"),
            supportedExtensions = listOf(".gb"),
            approved = false,
        )

        val coreBuildRevision = entry.commitSha.takeIf { it.isNotBlank() } ?: entry.releaseTag.takeIf { it.isNotBlank() }
        assertThat(coreBuildRevision).isEqualTo("v2.0.0-libretro")
    }
}
