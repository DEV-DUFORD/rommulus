package com.romm.desktop.storage.paths

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.util.UUID

class JnaWindowsKnownFolderResolverTest {

    /**
     * Pins the FOLDERID GUIDs to their authoritative values (host-neutral). The values come
     * from `knownfolders.h` (Windows SDK; identical in MinGW-w64 14.0.0):
     * `FOLDERID_RoamingAppData` = {3EB685DB-65F9-4CF6-A03A-E3EF65729F3D},
     * `FOLDERID_LocalAppData` = {F1B32785-6FBA-4FCF-9D55-7B8E7F157091}. The C++ counterpart
     * (`native/platform/windows/src/windows_platform_paths.cpp`) must carry the same GUIDs.
     */
    @Test
    fun `folder id constants match the documented FOLDERID values`() {
        assertThat(JnaWindowsKnownFolderResolver.FOLDERID_ROAMING_APP_DATA)
            .isEqualTo(UUID.fromString("3EB685DB-65F9-4CF6-A03A-E3EF65729F3D"))
        assertThat(JnaWindowsKnownFolderResolver.FOLDERID_LOCAL_APP_DATA)
            .isEqualTo(UUID.fromString("F1B32785-6FBA-4FCF-9D55-7B8E7F157091"))
    }

    /** Real Known Folder API round-trip — the host-native confirmation on windows-2022. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `resolves the real per-user known folders on windows`() {
        val resolver = JnaWindowsKnownFolderResolver()

        val roaming = resolver.roamingAppData()
        val local = resolver.localAppData()

        assertThat(Files.isDirectory(roaming)).isTrue()
        assertThat(Files.isDirectory(local)).isTrue()
    }
}
