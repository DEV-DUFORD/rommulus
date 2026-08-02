package com.romm.androidtv.romm

import com.romm.androidtv.config.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SegaCdBiosManager(
    private val firmwareRepository: FirmwareRepository,
    private val settingsRepository: SettingsRepository,
) {
    enum class Region(val displayName: String, val canonicalFileName: String, val sha1: String) {
        USA("USA (NTSC-U)", "bios_CD_U.bin", "f4f315adcef9b8feb0364c21ab7f0eaf5457f3ed"),
        EUROPE("Europe (PAL)", "bios_CD_E.bin", "f891e0ea651e2232af0c5c4cb46a0cae2ee8f356"),
        JAPAN("Japan (NTSC-J)", "bios_CD_J.bin", "4846f448160059a7da0215a5df12ca160f26dd69"),
    }

    data class BiosOption(
        val firmware: FirmwareInfo,
        val region: Region?,
    )

    sealed interface Availability {
        object Ready : Availability
        object Missing : Availability
        object NeedsManualSelection : Availability
        object AuthExpired : Availability
        data class Error(val message: String) : Availability
    }

    sealed interface Catalog {
        data class Success(
            val options: List<BiosOption>,
            val selectedFirmwareId: Long?,
        ) : Catalog
        object AuthExpired : Catalog
        data class Error(val message: String) : Catalog
    }

    suspend fun checkAvailability(): Availability {
        val selected = settingsRepository.segaCdBiosSelection()
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
        val platformId = when (val outcome = firmwareRepository.findPlatformId("segacd")) {
            is PlatformIdOutcome.Success -> outcome.platformId
                ?: return Catalog.Error("SEGA_CD_PLATFORM_NOT_FOUND")
            PlatformIdOutcome.AuthExpired -> return Catalog.AuthExpired
            is PlatformIdOutcome.Error -> return Catalog.Error(outcome.message)
        }
        return when (val outcome = firmwareRepository.listAvailable(platformId)) {
            is FirmwareCatalogOutcome.Success -> Catalog.Success(
                options = outcome.firmware
                    .map { firmware -> BiosOption(firmware, regionForSha1(firmware.sha1Hash)) }
                    .sortedWith(
                        compareBy<BiosOption> { it.region == null }
                            .thenBy { it.region?.ordinal ?: Int.MAX_VALUE }
                            .thenBy { it.firmware.fileName.lowercase() },
                    ),
                selectedFirmwareId = settingsRepository.segaCdBiosSelection()?.firmwareId,
            )
            FirmwareCatalogOutcome.AuthExpired -> Catalog.AuthExpired
            is FirmwareCatalogOutcome.Error -> Catalog.Error(outcome.message)
        }
    }

    suspend fun select(firmware: FirmwareInfo): FirmwareStagingOutcome {
        val outcome = firmwareRepository.ensureStaged(firmware)
        if (outcome is FirmwareStagingOutcome.Success) {
            settingsRepository.setSegaCdBiosSelection(firmware.firmwareId, firmware.fileName)
        }
        return outcome
    }

    suspend fun prepareForLaunch(systemDirectory: File): FirmwareStagingOutcome = withContext(Dispatchers.IO) {
        val selected = settingsRepository.segaCdBiosSelection()
        val cachedPath = selected?.let { firmwareRepository.findCachedPath(it.firmwareId) }

        val sourcePath = if (cachedPath != null) {
            cachedPath
        } else {
            val options = when (val outcome = fetchCatalog()) {
                is Catalog.Success -> outcome.options
                Catalog.AuthExpired -> return@withContext FirmwareStagingOutcome.AuthExpired
                is Catalog.Error -> return@withContext FirmwareStagingOutcome.NetworkError(outcome.message)
            }
            val firmware = options.firstOrNull { it.firmware.firmwareId == selected?.firmwareId }?.firmware
                ?: options.firstOrNull { it.region == Region.USA }?.firmware
                ?: options.firstOrNull { it.region != null }?.firmware
                ?: return@withContext FirmwareStagingOutcome.Missing(CANONICAL_FILE_NAMES)
            when (val outcome = select(firmware)) {
                is FirmwareStagingOutcome.Success -> outcome.stagedPaths.getValue(firmware.fileName)
                else -> return@withContext outcome
            }
        }

        val source = File(sourcePath)
        if (!source.isFile) {
            return@withContext FirmwareStagingOutcome.CorruptedDownload(
                selected?.fileName ?: USA_FILE_NAME,
                "cached file is missing",
            )
        }
        systemDirectory.mkdirs()
        for (fileName in CANONICAL_FILE_NAMES) {
            val target = File(systemDirectory, fileName)
            val temp = File(systemDirectory, ".tmp-${UUID.randomUUID()}-$fileName")
            try {
                source.inputStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.exists() && !target.delete()) {
                    temp.delete()
                    return@withContext FirmwareStagingOutcome.NetworkError("Could not replace staged BIOS")
                }
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return@withContext FirmwareStagingOutcome.NetworkError("Could not stage BIOS")
                }
            } catch (e: java.io.IOException) {
                temp.delete()
                return@withContext FirmwareStagingOutcome.NetworkError(e.message ?: "Could not stage BIOS")
            }
        }
        FirmwareStagingOutcome.Success(CANONICAL_FILE_NAMES.associateWith { File(systemDirectory, it).absolutePath })
    }

    companion object {
        const val USA_FILE_NAME = "bios_CD_U.bin"
        val CANONICAL_FILE_NAMES = Region.entries.map { it.canonicalFileName }

        fun regionForSha1(sha1: String): Region? =
            Region.entries.find { it.sha1.equals(sha1, ignoreCase = true) }
    }
}
