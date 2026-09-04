package com.romm.desktop.storage.secret.windows

import com.sun.jna.Native
import com.sun.jna.Structure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

/**
 * Host-neutral layout and constant assertions for the JNA [Credential] mapping of the Win32
 * `CREDENTIALW` structure (Windows SDK `wincred.h`).
 *
 * The offsets asserted below are the authoritative x86_64 values of the SDK layout; on any LP64
 * host (x86_64, aarch64) the layout is identical (4-byte DWORDs, 8-byte pointers, 8-byte
 * FILETIME), so the offset test gates on pointer size rather than a specific CPU. The assertions
 * pin the *values* (offsets, total size, constants) against the documented Microsoft layout —
 * independent of JNA's field-order bookkeeping — so any field-order, type, or constant
 * regression fails here without needing a Windows host.
 */
class JnaWindowsCredentialApiTest {

    // JNA keeps the layout accessors protected; both sit on the classpath (unnamed module), so
    // reflective access is stable. A probe subclass is NOT an option: JNA validates that the
    // concrete Structure class is the one declaring the fields.
    private val fieldOffsetMethod: Method =
        Structure::class.java.getDeclaredMethod("fieldOffset", String::class.java)
            .apply { isAccessible = true }

    private val fieldOrderMethod: Method =
        Structure::class.java.getDeclaredMethod("getFieldOrder").apply { isAccessible = true }

    private fun offset(structure: Structure, fieldName: String): Int =
        fieldOffsetMethod.invoke(structure, fieldName) as Int

    @Suppress("UNCHECKED_CAST")
    private fun fieldOrder(structure: Structure): List<String> =
        fieldOrderMethod.invoke(structure) as List<String>

    @Test
    fun `field order matches the CREDENTIALW declaration order`() {
        assertThat(fieldOrder(Credential())).containsExactly(
            "flags", "type", "targetName", "comment", "lastWritten", "credentialBlobSize",
            "credentialBlob", "persist", "attributeCount", "attributes", "targetAlias", "userName",
        )
    }

    @Test
    fun `lp64 offsets and total size match the authoritative CREDENTIALW layout`() {
        assumeTrue(Native.POINTER_SIZE == 8, "authoritative offsets are pinned for LP64 hosts")
        val probe = Credential()
        assertThat(offset(probe, "flags")).isEqualTo(0)
        assertThat(offset(probe, "type")).isEqualTo(4)
        assertThat(offset(probe, "targetName")).isEqualTo(8)
        assertThat(offset(probe, "comment")).isEqualTo(16)
        assertThat(offset(probe, "lastWritten")).isEqualTo(24)
        assertThat(offset(probe, "credentialBlobSize")).isEqualTo(32)
        assertThat(offset(probe, "credentialBlob")).isEqualTo(40)
        assertThat(offset(probe, "persist")).isEqualTo(48)
        assertThat(offset(probe, "attributeCount")).isEqualTo(52)
        assertThat(offset(probe, "attributes")).isEqualTo(56)
        assertThat(offset(probe, "targetAlias")).isEqualTo(64)
        assertThat(offset(probe, "userName")).isEqualTo(72)
        assertThat(probe.size()).isEqualTo(80)
    }

    @Test
    fun `credential type and persist constants match the Windows SDK values`() {
        // wincred.h: #define CRED_TYPE_GENERIC 1
        //            #define CRED_PERSIST_LOCAL_MACHINE 2  (CRED_PERSIST_SESSION is 1)
        //            #define CRED_MAX_CREDENTIAL_BLOB_SIZE 2560
        assertThat(JnaWindowsCredentialApi.CRED_TYPE_GENERIC).isEqualTo(1)
        assertThat(JnaWindowsCredentialApi.CRED_PERSIST_LOCAL_MACHINE).isEqualTo(2)
        assertThat(JnaWindowsCredentialApi.CRED_MAX_CREDENTIAL_BLOB_SIZE).isEqualTo(2560)
    }
}
