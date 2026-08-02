package com.romm.androidtv.romm

import com.romm.androidtv.config.FakeSharedPreferences
import com.romm.androidtv.config.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class PsxBiosManagerTest {
    private fun firmware(
        id: Long,
        fileName: String,
        sha1: String,
    ) = FirmwareInfo(
        firmwareId = id,
        fileName = fileName,
        sizeBytes = BIOS_BYTES.toLong(),
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
        var requestedSlug: String? = null

        override suspend fun findPlatformId(platformSlug: String): PlatformIdOutcome {
            requestedSlug = platformSlug
            return PlatformIdOutcome.Success(27)
        }

        override suspend fun listAvailable(platformId: Long?) =
            FirmwareCatalogOutcome.Success(firmware)

        override suspend fun findCachedPath(firmwareId: Long): String? = cached[firmwareId]

        override suspend fun ensureStaged(firmware: FirmwareInfo): FirmwareStagingOutcome {
            val file = File(root, "${firmware.firmwareId}-${firmware.fileName}")
            file.parentFile?.mkdirs()
            file.writeBytes(ByteArray(BIOS_BYTES) { 0x5a })
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

    private fun manager(repository: FakeFirmwareRepository): Pair<PsxBiosManager, SettingsRepository> {
        val settings = SettingsRepository(FakeSharedPreferences(), "https://example.com")
        return PsxBiosManager(repository, settings) to settings
    }

    @Test
    fun `renamed known USA BIOS is recognized by SHA-1 and PSX platform is resolved by slug`() = runBlocking {
        withTempRoot { root ->
            val renamed = firmware(1, "my-backup.bin", PsxBiosManager.Region.USA.sha1)
            val repository = FakeFirmwareRepository(listOf(renamed), root)
            val (manager, _) = manager(repository)

            val catalog = manager.fetchCatalog() as PsxBiosManager.Catalog.Success

            assertThat(repository.requestedSlug).isEqualTo("psx")
            assertThat(catalog.options.single().region).isEqualTo(PsxBiosManager.Region.USA)
            assertThat(manager.checkAvailability()).isEqualTo(PsxBiosManager.Availability.Ready)
        }
    }

    @Test
    fun `no server firmware reports missing`() = runBlocking {
        withTempRoot { root ->
            val (manager, _) = manager(FakeFirmwareRepository(emptyList(), root))

            assertThat(manager.checkAvailability()).isEqualTo(PsxBiosManager.Availability.Missing)
        }
    }

    @Test
    fun `unknown hash requires explicit selection and becomes ready after verified staging`() = runBlocking {
        withTempRoot { root ->
            val unknown = firmware(2, "unknown.bin", "deadbeef")
            val repository = FakeFirmwareRepository(listOf(unknown), root)
            val (manager, settings) = manager(repository)

            assertThat(manager.checkAvailability()).isEqualTo(PsxBiosManager.Availability.NeedsManualSelection)
            assertThat(manager.select(unknown)).isInstanceOf(FirmwareStagingOutcome.Success::class.java)
            assertThat(settings.psxBiosSelection()?.firmwareId).isEqualTo(2)
            assertThat(manager.checkAvailability()).isEqualTo(PsxBiosManager.Availability.Ready)
        }
    }

    @Test
    fun `first launch prefers USA and stages every upstream canonical BIOS name`() = runBlocking {
        withTempRoot { root ->
            val europe = firmware(3, "eu.bin", PsxBiosManager.Region.EUROPE.sha1)
            val usa = firmware(4, "renamed-us.bin", PsxBiosManager.Region.USA.sha1)
            val repository = FakeFirmwareRepository(listOf(europe, usa), root)
            val (manager, settings) = manager(repository)
            val systemDirectory = File(root, "system")

            val outcome = manager.prepareForLaunch(systemDirectory)

            assertThat(outcome).isInstanceOf(FirmwareStagingOutcome.Success::class.java)
            assertThat(settings.psxBiosSelection()?.firmwareId).isEqualTo(usa.firmwareId)
            PsxBiosManager.CANONICAL_FILE_NAMES.forEach { fileName ->
                assertThat(File(systemDirectory, fileName)).exists().hasSize(BIOS_BYTES.toLong())
            }
        }
    }

    private suspend fun withTempRoot(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("psx-bios-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private const val BIOS_BYTES = 512 * 1024
    }
}
