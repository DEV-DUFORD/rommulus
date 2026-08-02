package com.romm.androidtv.romm

import com.romm.androidtv.config.FakeSharedPreferences
import com.romm.androidtv.config.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class SegaCdBiosManagerTest {
    private fun firmware(
        id: Long,
        fileName: String,
        sha1: String,
    ) = FirmwareInfo(
        firmwareId = id,
        fileName = fileName,
        sizeBytes = 4,
        sha1Hash = sha1,
        md5Hash = "",
        crcHash = "",
        isVerified = false,
    )

    private class FakeFirmwareRepository(
        var firmware: List<FirmwareInfo>,
        private val root: File,
    ) : FirmwareRepository {
        val cached = mutableMapOf<Long, String>()

        override suspend fun findPlatformId(platformSlug: String) = PlatformIdOutcome.Success(31)

        override suspend fun listAvailable(platformId: Long?) =
            FirmwareCatalogOutcome.Success(firmware)

        override suspend fun findCachedPath(firmwareId: Long): String? = cached[firmwareId]

        override suspend fun ensureStaged(firmware: FirmwareInfo): FirmwareStagingOutcome {
            val file = File(root, "${firmware.firmwareId}-${firmware.fileName}")
            file.parentFile?.mkdirs()
            file.writeText("bios")
            cached[firmware.firmwareId] = file.absolutePath
            return FirmwareStagingOutcome.Success(mapOf(firmware.fileName to file.absolutePath))
        }

        override suspend fun checkAvailability(
            platformId: Long,
            requiredFileNames: List<String>,
        ) = FirmwareAvailability(emptyList(), requiredFileNames, emptyList())

        override suspend fun ensureStaged(
            platformId: Long,
            requiredFileNames: List<String>,
        ) = FirmwareStagingOutcome.Missing(requiredFileNames)
    }

    private fun manager(repository: FakeFirmwareRepository): Pair<SegaCdBiosManager, SettingsRepository> {
        val settings = SettingsRepository(FakeSharedPreferences(), "https://example.com")
        return SegaCdBiosManager(repository, settings) to settings
    }

    @Test
    fun `known USA hash is recognized even when server filename is custom`() = runBlocking {
        val root = Files.createTempDirectory("segacd-bios-test").toFile()
        try {
            val custom = firmware(1, "my-personal-backup.bin", SegaCdBiosManager.Region.USA.sha1)
            val repository = FakeFirmwareRepository(listOf(custom), root)
            val (manager, _) = manager(repository)

            val catalog = manager.fetchCatalog() as SegaCdBiosManager.Catalog.Success

            assertThat(catalog.options.single().region).isEqualTo(SegaCdBiosManager.Region.USA)
            assertThat(manager.checkAvailability()).isEqualTo(SegaCdBiosManager.Availability.Ready)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unknown hash requires explicit selection`() = runBlocking {
        val root = Files.createTempDirectory("segacd-bios-test").toFile()
        try {
            val unknown = firmware(2, "odd-dump.bin", "deadbeef")
            val repository = FakeFirmwareRepository(listOf(unknown), root)
            val (manager, _) = manager(repository)

            assertThat(manager.checkAvailability())
                .isEqualTo(SegaCdBiosManager.Availability.NeedsManualSelection)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `manual selection trusts and caches an unknown hash`() = runBlocking {
        val root = Files.createTempDirectory("segacd-bios-test").toFile()
        try {
            val unknown = firmware(3, "odd-dump.bin", "deadbeef")
            val repository = FakeFirmwareRepository(listOf(unknown), root)
            val (manager, settings) = manager(repository)

            assertThat(manager.select(unknown)).isInstanceOf(FirmwareStagingOutcome.Success::class.java)

            assertThat(settings.segaCdBiosSelection()?.firmwareId).isEqualTo(3)
            assertThat(manager.checkAvailability()).isEqualTo(SegaCdBiosManager.Availability.Ready)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `launch defaults to USA hash and stages all canonical names`() = runBlocking {
        val root = Files.createTempDirectory("segacd-bios-test").toFile()
        try {
            val europe = firmware(4, "europe.bin", SegaCdBiosManager.Region.EUROPE.sha1)
            val usa = firmware(5, "renamed-usa.bin", SegaCdBiosManager.Region.USA.sha1)
            val repository = FakeFirmwareRepository(listOf(europe, usa), root)
            val (manager, settings) = manager(repository)
            val systemDir = File(root, "system")

            val outcome = manager.prepareForLaunch(systemDir)

            assertThat(outcome).isInstanceOf(FirmwareStagingOutcome.Success::class.java)
            assertThat(settings.segaCdBiosSelection()?.firmwareId).isEqualTo(usa.firmwareId)
            SegaCdBiosManager.CANONICAL_FILE_NAMES.forEach {
                assertThat(File(systemDir, it)).exists().hasContent("bios")
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
