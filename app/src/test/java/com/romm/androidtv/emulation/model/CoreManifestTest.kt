package com.romm.androidtv.emulation.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoreManifestTest {

    @Test
    fun `no core is approved before Phase 0 product review`() {
        // Phase 0 only records starting facts and gates; it must not pre-approve
        // any core for production distribution.
        assertThat(CoreManifest.approvedEntries()).isEmpty()
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
}
