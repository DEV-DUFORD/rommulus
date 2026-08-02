package com.romm.androidtv.romm

import com.romm.androidtv.cache.ArchiveExtractionOutcome
import com.romm.androidtv.cache.ZipArchiveExtractor
import com.romm.androidtv.config.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class PsxBiosManager(
    private val firmwareRepository: FirmwareRepository,
    private val settingsRepository: SettingsRepository,
) {
    enum class Region(val displayName: String, val sha1: String) {
        USA("USA (SCPH-5501)", "0555c6fae8906f3f09baf5988f00e55f88e9f30b"),
        EUROPE("Europe (SCPH-5502)", "f6bc2d1f5eb6593de7d089c425ac681d6fffd3f0"),
        JAPAN("Japan (SCPH-5500)", "b05def971d8ec59f346f2d9ac21fb742e3eb6917"),
    }

    data class BiosOption(
        val firmware: FirmwareInfo,
        val region: Region?,
    )

    sealed interface Availability {
        data object Ready : Availability
        data object Missing : Availability
        data object NeedsManualSelection : Availability
        data object AuthExpired : Availability
        data class Error(val message: String) : Availability
    }

    sealed interface Catalog {
        data class Success(
            val options: List<BiosOption>,
            val selectedFirmwareId: Long?,
        ) : Catalog
        data object AuthExpired : Catalog
        data class Error(val message: String) : Catalog
    }

    suspend fun checkAvailability(): Availability {
        val selected = settingsRepository.psxBiosSelection()
        if (selected != null && firmwareRepository.findCachedPath(selected.firmwareId) != null) {
            return Availability.Ready
        }
        return when (val catalog = fetchCatalog()) {
            is Catalog.Success -> when {
                catalog.options.isEmpty() -> Availability.Missing
                catalog.selectedFirmwareId != null &&
                    catalog.options.any { it.firmware.firmwareId == catalog.selectedFirmwareId } -> Availability.Ready
                catalog.options.any { it.region != null } -> Availability.Ready
                else -> Availability.NeedsManualSelection
            }
            Catalog.AuthExpired -> Availability.AuthExpired
            is Catalog.Error -> Availability.Error(catalog.message)
        }
    }

    suspend fun fetchCatalog(): Catalog {
        val platformId = when (val outcome = firmwareRepository.findPlatformId(PLATFORM_SLUG)) {
            is PlatformIdOutcome.Success -> outcome.platformId
                ?: return Catalog.Error("PSX_PLATFORM_NOT_FOUND")
            PlatformIdOutcome.AuthExpired -> return Catalog.AuthExpired
            is PlatformIdOutcome.Error -> return Catalog.Error(outcome.message)
        }
        return when (val outcome = firmwareRepository.listAvailable(platformId)) {
            is FirmwareCatalogOutcome.Success -> Catalog.Success(
                options = outcome.firmware
                    .filterNot { it.sizeBytes <= 0 }
                    .map { BiosOption(it, regionForSha1(it.sha1Hash)) }
                    .sortedWith(
                        compareBy<BiosOption> { it.region == null }
                            .thenBy { it.region?.ordinal ?: Int.MAX_VALUE }
                            .thenBy { it.firmware.fileName.lowercase() },
                    ),
                selectedFirmwareId = settingsRepository.psxBiosSelection()?.firmwareId,
            )
            FirmwareCatalogOutcome.AuthExpired -> Catalog.AuthExpired
            is FirmwareCatalogOutcome.Error -> Catalog.Error(outcome.message)
        }
    }

    suspend fun select(firmware: FirmwareInfo): FirmwareStagingOutcome = withContext(Dispatchers.IO) {
        when (val outcome = firmwareRepository.ensureStaged(firmware)) {
            is FirmwareStagingOutcome.Success -> {
                val cachedPath = outcome.stagedPaths.getValue(firmware.fileName)
                when (val payload = resolvePayload(File(cachedPath), firmware.firmwareId)) {
                    is PayloadOutcome.Success -> {
                        payload.temporaryDirectory?.deleteRecursively()
                        settingsRepository.setPsxBiosSelection(firmware.firmwareId, firmware.fileName)
                        outcome
                    }
                    is PayloadOutcome.Error -> FirmwareStagingOutcome.CorruptedDownload(
                        firmware.fileName,
                        payload.reason,
                    )
                }
            }
            else -> outcome
        }
    }

    suspend fun prepareForLaunch(systemDirectory: File): FirmwareStagingOutcome = withContext(Dispatchers.IO) {
        val selected = settingsRepository.psxBiosSelection()
        var firmware = selected?.let { selection ->
            val catalog = fetchCatalog() as? Catalog.Success
            catalog?.options?.firstOrNull { it.firmware.firmwareId == selection.firmwareId }?.firmware
        }

        var cachedPath = selected?.let { firmwareRepository.findCachedPath(it.firmwareId) }
        if (cachedPath == null) {
            val options = when (val outcome = fetchCatalog()) {
                is Catalog.Success -> outcome.options
                Catalog.AuthExpired -> return@withContext FirmwareStagingOutcome.AuthExpired
                is Catalog.Error -> return@withContext FirmwareStagingOutcome.NetworkError(outcome.message)
            }
            firmware = options.firstOrNull { it.firmware.firmwareId == selected?.firmwareId }?.firmware
                ?: options.firstOrNull { it.region == Region.USA }?.firmware
                ?: options.firstOrNull { it.region != null }?.firmware
                ?: return@withContext FirmwareStagingOutcome.Missing(CANONICAL_FILE_NAMES)
            when (val outcome = select(firmware)) {
                is FirmwareStagingOutcome.Success -> {
                    cachedPath = outcome.stagedPaths.getValue(firmware.fileName)
                }
                else -> return@withContext outcome
            }
        }

        val payload = when (val outcome = resolvePayload(File(requireNotNull(cachedPath)), firmware?.firmwareId ?: selected!!.firmwareId)) {
            is PayloadOutcome.Success -> outcome
            is PayloadOutcome.Error -> return@withContext FirmwareStagingOutcome.CorruptedDownload(
                firmware?.fileName ?: selected?.fileName ?: USA_FILE_NAME,
                outcome.reason,
            )
        }

        try {
            systemDirectory.mkdirs()
            for (fileName in CANONICAL_FILE_NAMES) {
                atomicCopy(payload.file, File(systemDirectory, fileName))
            }
        } catch (e: IOException) {
            return@withContext FirmwareStagingOutcome.NetworkError(e.message ?: "Could not stage BIOS")
        } finally {
            payload.temporaryDirectory?.deleteRecursively()
        }
        FirmwareStagingOutcome.Success(
            CANONICAL_FILE_NAMES.associateWith { File(systemDirectory, it).absolutePath },
        )
    }

    private fun resolvePayload(source: File, firmwareId: Long): PayloadOutcome {
        if (!source.isFile) return PayloadOutcome.Error("cached file is missing")
        if (!source.extension.equals("zip", ignoreCase = true)) {
            return validatePayload(source, temporaryDirectory = null)
        }

        val directory = File(source.parentFile, ".psx-bios-$firmwareId-${UUID.randomUUID()}")
        if (!directory.mkdirs()) return PayloadOutcome.Error("could not create BIOS extraction directory")
        return when (val outcome = ZipArchiveExtractor.extractSingleEntry(source, directory)) {
            is ArchiveExtractionOutcome.Success -> validatePayload(outcome.file, directory)
            is ArchiveExtractionOutcome.MultipleEntries -> {
                directory.deleteRecursively()
                PayloadOutcome.Error("BIOS archive contains multiple files")
            }
            is ArchiveExtractionOutcome.Rejected -> {
                directory.deleteRecursively()
                PayloadOutcome.Error(outcome.reason)
            }
        }
    }

    private fun validatePayload(file: File, temporaryDirectory: File?): PayloadOutcome =
        if (file.length() == BIOS_SIZE_BYTES || file.length() == PSIO_SIZE_BYTES) {
            PayloadOutcome.Success(file, temporaryDirectory)
        } else {
            temporaryDirectory?.deleteRecursively()
            PayloadOutcome.Error("BIOS payload must be 512 KiB or 4 MiB")
        }

    private fun atomicCopy(source: File, target: File) {
        val temp = File(target.parentFile, ".tmp-${UUID.randomUUID()}-${target.name}")
        try {
            source.inputStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.exists() && !target.delete()) throw IOException("Could not replace staged BIOS")
            if (!temp.renameTo(target)) throw IOException("Could not stage BIOS")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private sealed interface PayloadOutcome {
        data class Success(val file: File, val temporaryDirectory: File?) : PayloadOutcome
        data class Error(val reason: String) : PayloadOutcome
    }

    companion object {
        const val PLATFORM_SLUG = "psx"
        const val USA_FILE_NAME = "scph5501.bin"
        val CANONICAL_FILE_NAMES = listOf(
            "scph5500.bin",
            USA_FILE_NAME,
            "scph5502.bin",
            "psxonpsp660.bin",
            "scph101.bin",
            "scph7001.bin",
            "scph1001.bin",
        )
        private const val BIOS_SIZE_BYTES = 512L * 1024
        private const val PSIO_SIZE_BYTES = 4L * 1024 * 1024

        fun regionForSha1(sha1: String): Region? =
            Region.entries.find { it.sha1.equals(sha1, ignoreCase = true) }
    }
}
