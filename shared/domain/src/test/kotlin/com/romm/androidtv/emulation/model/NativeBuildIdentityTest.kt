package com.romm.androidtv.emulation.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("NativeBuildIdentities — shared platform/build identity constants")
class NativeBuildIdentityTest {

    @Test
    fun `production build identities are the fixed linux and windows x86_64 slugs`() {
        assertThat(NativeBuildIdentities.LINUX_X86_64).isEqualTo("linux-x86_64")
        assertThat(NativeBuildIdentities.WINDOWS_X86_64).isEqualTo("windows-x86_64")
        assertThat(NativeBuildIdentities.PRODUCTION)
            .containsExactlyInAnyOrder(NativeBuildIdentities.LINUX_X86_64, NativeBuildIdentities.WINDOWS_X86_64)
        // The two identities must stay distinct first-class build identities, not aliases.
        assertThat(NativeBuildIdentities.LINUX_X86_64).isNotEqualTo(NativeBuildIdentities.WINDOWS_X86_64)
    }

    @Test
    fun `no production core advertises windows-x86_64 support`() {
        // Phase 0 guardrail (plans/WINDOWS_IMPL.md §3.1/§6.2): windows-x86_64 is a first-class
        // build identity, but no core may claim it until its Windows gate passes. Enabling a
        // core for Windows is a deliberate, gated decision — never a manifest side effect.
        val offenders = CoreManifest.approvedEntries()
            .filter { NativeBuildIdentities.WINDOWS_X86_64 in it.supportedAbis }
            .map { it.coreId }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `every manifest ABI is a known Android ABI or production build identity`() {
        val known = (ANDROID_CORE_ABIS + NativeBuildIdentities.PRODUCTION).toSet()
        CoreManifest.entries.forEach { entry ->
            val unknown = entry.supportedAbis.filterNot { abi -> abi in known }
            // Fails per core with the offending ABI list in the assertion message.
            assertThat(unknown).isEmpty()
        }
    }

    @Test
    fun `every approved linux desktop core still advertises the shared linux identity constant`() {
        // Regression guard: the manifest must reference the same slug the desktop launch code
        // filters on (com.romm.desktop.platform.LinuxNativeArtifactLayout.buildIdentity).
        val linuxCores = CoreManifest.approvedEntries()
            .filter { NativeBuildIdentities.LINUX_X86_64 in it.supportedAbis }
        assertThat(linuxCores.map { it.coreId }).contains("gambatte", "dolphin", "lrps2", "test_core")
    }
}
