package com.romm.androidtv.emulation.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoreManifestTest {

    @Test
    fun `only SameBoy is approved, following its Phase 4 individual license review`() {
        // Every other core in the manifest is still an unreviewed/restricted starting fact
        // from Phase 0 (LIBRETRO_REFACTOR.md section 4.1) — approving one core in Phase 4
        // must not silently approve any other entry.
        assertThat(CoreManifest.approvedEntries().map { it.coreId }).containsExactly("sameboy")
    }

    @Test
    fun `SameBoy's approval records a named reviewer, date, commit, and permissive finding`() {
        val sameboy = CoreManifest.findById("sameboy")

        assertThat(sameboy).isNotNull
        assertThat(sameboy!!.approved).isTrue()
        assertThat(sameboy.reviewedBy).isEqualTo("DEV-DUFORD")
        assertThat(sameboy.reviewedOn).isNotBlank()
        assertThat(sameboy.commitSha).isNotBlank()
        assertThat(sameboy.commercialUseFinding).isEqualTo(CommercialUseFinding.PERMISSIVE_OR_COPYLEFT_OK)
    }

    @Test
    fun `every entry declares a commercial-use finding`() {
        assertThat(CoreManifest.entries).allSatisfy { entry ->
            assertThat(entry.commercialUseFinding).isNotNull()
        }
    }

    @Test
    fun `restrictive cores are recorded as non-commercial restricted`() {
        val restricted = listOf("genesis_plus_gx", "picodrive", "snes9x")

        restricted.forEach { coreId ->
            val entry = CoreManifest.findById(coreId)
            assertThat(entry).isNotNull
            assertThat(entry!!.commercialUseFinding)
                .isEqualTo(CommercialUseFinding.NON_COMMERCIAL_RESTRICTED)
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
    fun `an approved entry cannot have a restricted commercial-use finding`() {
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
            )
        }
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
