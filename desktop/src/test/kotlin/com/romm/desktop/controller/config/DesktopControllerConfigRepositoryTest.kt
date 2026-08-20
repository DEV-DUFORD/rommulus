package com.romm.desktop.controller.config

import com.romm.androidtv.controller.config.BindingAddress
import com.romm.androidtv.controller.config.BindingSlot
import com.romm.androidtv.controller.config.CoreControlId
import com.romm.androidtv.controller.config.PhysicalBinding
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import com.romm.androidtv.storage.records.ControllerBindingRecord
import com.romm.desktop.player.PAD_AXIS_NAMES
import com.romm.desktop.player.PAD_BUTTON_NAMES
import com.romm.desktop.player.RetroPadControlMapping
import com.romm.desktop.storage.sqlite.SqliteControllerBindingStore
import com.romm.desktop.storage.sqlite.SqliteDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tests [DesktopControllerConfigRepository] (set/clear/swap/replace/reset) against a real
 * [SqliteControllerBindingStore], asserting both the merged configs and the persisted record
 * encoding (RetroPad ordinals, so launch serialization works unchanged).
 */
@DisplayName("DesktopControllerConfigRepository — store-backed overrides over shared defaults")
class DesktopControllerConfigRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: SqliteControllerBindingStore
    private lateinit var repo: DesktopControllerConfigRepository

    private val coreId = "snes9x"

    /** SNES A default primary: NeutralKey.BUTTON_A platform code (96). */
    private val snesA = PhysicalBinding.Key(NeutralKey.BUTTON_A.platformCode)
    /** SNES B default primary: NeutralKey.BUTTON_B platform code (97). */
    private val snesB = PhysicalBinding.Key(NeutralKey.BUTTON_B.platformCode)

    @BeforeEach
    fun setUp() {
        val db = SqliteDatabase.open(tempDir.resolve("rommulus.db")).getOrThrow()
        store = SqliteControllerBindingStore(db)
        repo = DesktopControllerConfigRepository(store)
    }

    // ---- load / observe: merged config = profile defaults + stored overrides ----

    @Test
    fun `loadCore with empty store returns the shared profile defaults`() = runTest {
        val config = repo.loadCore(coreId)

        assertThat(config.coreId).isEqualTo(coreId)
        assertThat(config.players.keys).containsExactly(0, 1)
        // SNES A/B default to their matching face buttons; D-Pad Up gets the stick alias.
        assertThat(config.players[0]!!.get(CoreControlId.BUTTON_A, BindingSlot.PRIMARY)).isEqualTo(snesA)
        assertThat(config.players[0]!!.get(CoreControlId.BUTTON_B, BindingSlot.PRIMARY)).isEqualTo(snesB)
        assertThat(config.players[0]!!.get(CoreControlId.D_PAD_UP, BindingSlot.SECONDARY))
            .isEqualTo(PhysicalBinding.AxisDirection(NeutralAxis.Y.platformCode, -1))
    }

    @Test
    fun `loadCore for an unknown core yields an empty config rather than crashing`() = runTest {
        val config = repo.loadCore("not_a_core")
        assertThat(config.coreId).isEqualTo("not_a_core")
        assertThat(config.players).isEmpty()
    }

    @Test
    fun `observeCore emits the merged config and re-emits after a mutation`() = runTest {
        val flow = repo.observeCore(coreId)
        assertThat(flow.first().players[0]!!.get(CoreControlId.BUTTON_A, BindingSlot.PRIMARY)).isEqualTo(snesA)

        repo.setBinding(coreId, 0, CoreControlId.BUTTON_A, PhysicalBinding.Key(NeutralKey.DPAD_UP.platformCode))

        // StateFlow re-emits the freshly merged value to new collectors after refresh.
        assertThat(flow.first().players[0]!!.get(CoreControlId.BUTTON_A, BindingSlot.PRIMARY))
            .isEqualTo(PhysicalBinding.Key(NeutralKey.DPAD_UP.platformCode))
    }

    // ---- set / clear ----

    @Test
    fun `setBinding persists a RetroPad-ordinal record and overrides the default in the merged config`() = runTest {
        repo.setBinding(coreId, 0, CoreControlId.BUTTON_A, PhysicalBinding.Key(NeutralKey.DPAD_UP.platformCode))

        val records = store.loadForCore(coreId)
        assertThat(records).hasSize(1)
        val record = records.single()
        // Encoding: KEY with the PadButton ordinal for dpad_up — identical to what
        // RetroPadControlMapping.toRecords writes, so launch serialization is unchanged.
        assertThat(record.bindingType).isEqualTo(RetroPadControlMapping.TYPE_KEY)
        assertThat(record.inputCode).isEqualTo(PAD_BUTTON_NAMES.indexOf("dpad_up"))
        assertThat(record.polarity).isNull()
        assertThat(record.controlId).isEqualTo("button_a")

        assertThat(repo.loadCore(coreId).players[0]!!.get(CoreControlId.BUTTON_A, BindingSlot.PRIMARY))
            .isEqualTo(PhysicalBinding.Key(NeutralKey.DPAD_UP.platformCode))
    }

    @Test
    fun `clearBinding persists an explicit unmapped override that hides the catalog default`() = runTest {
        repo.clearBinding(coreId, 0, CoreControlId.BUTTON_A, BindingSlot.PRIMARY)

        val record = store.loadForCore(coreId).single()
        assertThat(record.bindingType).isEqualTo(RetroPadControlMapping.TYPE_UNMAPPED)
        assertThat(repo.loadCore(coreId).players[0]!!.get(CoreControlId.BUTTON_A, BindingSlot.PRIMARY)).isNull()
    }

    // ---- swap ----

    @Test
    fun `swapBindings exchanges two effective defaults`() = runTest {
        repo.swapBindings(
            coreId, 0,
            BindingAddress(CoreControlId.BUTTON_A, BindingSlot.PRIMARY),
            BindingAddress(CoreControlId.BUTTON_B, BindingSlot.PRIMARY),
        )

        val player = repo.loadCore(coreId).players[0]!!
        assertThat(player.get(CoreControlId.BUTTON_A, BindingSlot.PRIMARY)).isEqualTo(snesB)
        assertThat(player.get(CoreControlId.BUTTON_B, BindingSlot.PRIMARY)).isEqualTo(snesA)
    }

    @Test
    fun `swapBindings with a null side persists an explicit unmapped override`() = runTest {
        // A's secondary is null by default; swap it with B's primary (Key 97).
        repo.swapBindings(
            coreId, 0,
            BindingAddress(CoreControlId.BUTTON_A, BindingSlot.SECONDARY),
            BindingAddress(CoreControlId.BUTTON_B, BindingSlot.PRIMARY),
        )

        val player = repo.loadCore(coreId).players[0]!!
        assertThat(player.get(CoreControlId.BUTTON_A, BindingSlot.SECONDARY)).isEqualTo(snesB)
        assertThat(player.get(CoreControlId.BUTTON_B, BindingSlot.PRIMARY)).isNull()
        // The null side must be an explicit UNMAPPED row so the catalog default does not reappear.
        val unmapped = store.loadForPlayer(coreId, 0)
            .single { it.controlId == "button_b" && it.bindingSlot == BindingSlot.PRIMARY.index }
        assertThat(unmapped.bindingType).isEqualTo(RetroPadControlMapping.TYPE_UNMAPPED)
    }

    // ---- replace ----

    @Test
    fun `replaceBinding unmaps every other effective slot holding the same physical input`() = runTest {
        // Default: A.primary already holds Key(96). Replacing B.primary with Key(96) must
        // explicitly unmap A.primary (one physical input maps to one target per player).
        repo.replaceBinding(
            coreId, 0,
            BindingAddress(CoreControlId.BUTTON_B, BindingSlot.PRIMARY),
            snesA,
        )

        val player = repo.loadCore(coreId).players[0]!!
        assertThat(player.get(CoreControlId.BUTTON_B, BindingSlot.PRIMARY)).isEqualTo(snesA)
        assertThat(player.get(CoreControlId.BUTTON_A, BindingSlot.PRIMARY)).isNull()
    }

    // ---- resets ----

    @Test
    fun `resetPlayer deletes only that player's overrides and restores defaults`() = runTest {
        repo.setBinding(coreId, 0, CoreControlId.BUTTON_A, PhysicalBinding.Key(NeutralKey.DPAD_UP.platformCode))
        repo.setBinding(coreId, 1, CoreControlId.BUTTON_B, PhysicalBinding.Key(NeutralKey.DPAD_DOWN.platformCode))

        repo.resetPlayer(coreId, 0)

        val config = repo.loadCore(coreId)
        assertThat(config.players[0]!!.get(CoreControlId.BUTTON_A, BindingSlot.PRIMARY)).isEqualTo(snesA)
        assertThat(config.players[1]!!.get(CoreControlId.BUTTON_B, BindingSlot.PRIMARY))
            .isEqualTo(PhysicalBinding.Key(NeutralKey.DPAD_DOWN.platformCode))
    }

    @Test
    fun `clearPlayerMappings explicitly unmaps every control including the pause menu`() = runTest {
        repo.clearPlayerMappings(coreId, 0)

        val player = repo.loadCore(coreId).players[0]!!
        assertThat(player.bindings).isNotEmpty
        for ((controlId, bindings) in player.bindings) {
            assertThat(bindings.primary).`as`("primary for %s", controlId).isNull()
            assertThat(bindings.secondary).`as`("secondary for %s", controlId).isNull()
        }
        // A reset afterwards restores the catalog defaults (unmapped rows are overrides, not deletes).
        repo.resetPlayer(coreId, 0)
        assertThat(repo.loadCore(coreId).players[0]!!.get(CoreControlId.BUTTON_A, BindingSlot.PRIMARY)).isEqualTo(snesA)
    }

    @Test
    fun `resetCore deletes every player's overrides`() = runTest {
        repo.setBinding(coreId, 0, CoreControlId.BUTTON_A, PhysicalBinding.Key(NeutralKey.DPAD_UP.platformCode))
        repo.setBinding(coreId, 1, CoreControlId.BUTTON_B, PhysicalBinding.Key(NeutralKey.DPAD_DOWN.platformCode))

        repo.resetCore(coreId)

        assertThat(store.loadForCore(coreId)).isEmpty()
        val config = repo.loadCore(coreId)
        assertThat(config.players[0]!!.get(CoreControlId.BUTTON_A, BindingSlot.PRIMARY)).isEqualTo(snesA)
        assertThat(config.players[1]!!.get(CoreControlId.BUTTON_B, BindingSlot.PRIMARY)).isEqualTo(snesB)
    }

    // ---- codec: record encoding/decoding (launch serialization compatibility) ----

    @Test
    fun `codec encodes key bindings as PadButton ordinals and round-trips them`() {
        val record = DesktopControllerBindingCodec.encode(
            coreId, 0, CoreControlId.BUTTON_A, snesA, BindingSlot.PRIMARY.index,
        )
        assertThat(record.bindingType).isEqualTo(RetroPadControlMapping.TYPE_KEY)
        assertThat(record.inputCode).isEqualTo(PAD_BUTTON_NAMES.indexOf("south"))

        val decoded = DesktopControllerBindingCodec.decodeOverride(record)
        assertThat(decoded).isEqualTo(DesktopControllerBindingCodec.DecodedOverride.Mapped(snesA))
    }

    @Test
    fun `codec encodes axis directions as PadAxis ordinals with polarity`() {
        val binding = PhysicalBinding.AxisDirection(NeutralAxis.Y.platformCode, -1)
        val record = DesktopControllerBindingCodec.encode(
            coreId, 0, CoreControlId.D_PAD_UP, binding, BindingSlot.SECONDARY.index,
        )
        assertThat(record.bindingType).isEqualTo(RetroPadControlMapping.TYPE_AXIS_DIRECTION)
        assertThat(record.inputCode).isEqualTo(PAD_AXIS_NAMES.indexOf("left_y"))
        assertThat(record.polarity).isEqualTo(-1)

        val decoded = DesktopControllerBindingCodec.decodeOverride(record)
        assertThat(decoded).isEqualTo(DesktopControllerBindingCodec.DecodedOverride.Mapped(binding))
    }

    @Test
    fun `codec expresses digital trigger keys as the matching trigger pad-axis direction`() {
        // Xbox LT/RT arrive as BUTTON_L2/R2 key presses; the player has no trigger buttons,
        // only trigger axes — encode them as the positive direction of that axis.
        val record = DesktopControllerBindingCodec.encode(
            coreId, 0, CoreControlId.BUTTON_A,
            PhysicalBinding.Key(NeutralKey.BUTTON_L2.platformCode), BindingSlot.PRIMARY.index,
        )
        assertThat(record.bindingType).isEqualTo(RetroPadControlMapping.TYPE_AXIS_DIRECTION)
        assertThat(record.inputCode).isEqualTo(PAD_AXIS_NAMES.indexOf("left_trigger"))
        assertThat(record.polarity).isEqualTo(1)

        // The record round-trips stably through decode + re-encode.
        val decoded = DesktopControllerBindingCodec.decode(record)!!
        val reencoded = DesktopControllerBindingCodec.encode(
            coreId, 0, CoreControlId.BUTTON_A, decoded, BindingSlot.PRIMARY.index,
        )
        assertThat(reencoded).isEqualTo(record)
    }

    @Test
    fun `codec decodes Android-origin full-axis rows and rejects unknown types`() {
        val axisRow = ControllerBindingRecord(
            coreId = coreId, playerIndex = 0, controlId = "l2", bindingSlot = 0,
            bindingType = "AXIS", inputCode = NeutralAxis.LTRIGGER.platformCode, polarity = null,
        )
        assertThat(DesktopControllerBindingCodec.decode(axisRow))
            .isEqualTo(PhysicalBinding.Axis(NeutralAxis.LTRIGGER.platformCode))

        val unknown = axisRow.copy(bindingType = "FUTURE_TYPE")
        assertThat(DesktopControllerBindingCodec.decode(unknown)).isNull()
        assertThat(DesktopControllerBindingCodec.decodeOverride(unknown)).isNull()

        val unmapped = DesktopControllerBindingCodec.encodeUnmapped(
            coreId, 0, BindingAddress(CoreControlId.BUTTON_A, BindingSlot.PRIMARY),
        )
        assertThat(DesktopControllerBindingCodec.decodeOverride(unmapped))
            .isEqualTo(DesktopControllerBindingCodec.DecodedOverride.Unmapped)
    }
}
