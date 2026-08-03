package com.romm.androidtv.controller.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ControllerBindingCodec — PhysicalBinding <-> entity round trips and validation")
class ControllerBindingCodecTest {

    private val coreId = "snes9x"
    private val playerIndex = 1
    private val controlId = CoreControlId.BUTTON_A

    @Nested
    @DisplayName("encode")
    inner class EncodeTests {
        @Test
        fun `Key encodes as TYPE_KEY with keyCode and null polarity`() {
            val entity = ControllerBindingCodec.encode(coreId, playerIndex, controlId, PhysicalBinding.Key(23))

            assertThat(entity.coreId).isEqualTo(coreId)
            assertThat(entity.playerIndex).isEqualTo(playerIndex)
            assertThat(entity.controlId).isEqualTo(controlId.id)
            assertThat(entity.bindingType).isEqualTo(ControllerBindingCodec.TYPE_KEY)
            assertThat(entity.inputCode).isEqualTo(23)
            assertThat(entity.polarity).isNull()
            assertThat(entity.schemaVersion).isEqualTo(ControllerBindingCodec.SCHEMA_VERSION)
        }

        @Test
        fun `Axis encodes as TYPE_AXIS with axis and null polarity`() {
            val entity = ControllerBindingCodec.encode(coreId, playerIndex, controlId, PhysicalBinding.Axis(8))

            assertThat(entity.bindingType).isEqualTo(ControllerBindingCodec.TYPE_AXIS)
            assertThat(entity.inputCode).isEqualTo(8)
            assertThat(entity.polarity).isNull()
        }

        @Test
        fun `AxisDirection encodes as TYPE_AXIS_DIRECTION with axis and polarity`() {
            val entity = ControllerBindingCodec.encode(
                coreId, playerIndex, controlId, PhysicalBinding.AxisDirection(8, -1),
            )

            assertThat(entity.bindingType).isEqualTo(ControllerBindingCodec.TYPE_AXIS_DIRECTION)
            assertThat(entity.inputCode).isEqualTo(8)
            assertThat(entity.polarity).isEqualTo(-1)
        }
    }

    @Nested
    @DisplayName("decode")
    inner class DecodeTests {
        @Test
        fun `round-trips a Key binding`() {
            val original = PhysicalBinding.Key(23)
            val decoded = ControllerBindingCodec.decode(
                ControllerBindingCodec.encode(coreId, playerIndex, controlId, original),
            )
            assertThat(decoded).isEqualTo(original)
        }

        @Test
        fun `round-trips an Axis binding`() {
            val original = PhysicalBinding.Axis(8)
            val decoded = ControllerBindingCodec.decode(
                ControllerBindingCodec.encode(coreId, playerIndex, controlId, original),
            )
            assertThat(decoded).isEqualTo(original)
        }

        @Test
        fun `round-trips an AxisDirection binding`() {
            val original = PhysicalBinding.AxisDirection(8, 1)
            val decoded = ControllerBindingCodec.decode(
                ControllerBindingCodec.encode(coreId, playerIndex, controlId, original),
            )
            assertThat(decoded).isEqualTo(original)
        }

        @Test
        fun `polarity is preserved on AxisDirection`() {
            val entity = ControllerBindingCodec.encode(
                coreId, playerIndex, controlId, PhysicalBinding.AxisDirection(8, -1),
            )
            assertThat(ControllerBindingCodec.decode(entity))
                .isEqualTo(PhysicalBinding.AxisDirection(8, -1))
        }

        @Test
        fun `unknown bindingType decodes to null`() {
            val entity = ControllerBindingEntity(
                coreId = coreId,
                playerIndex = playerIndex,
                controlId = controlId.id,
                bindingType = "FUTURE_TYPE",
                inputCode = 1,
                polarity = null,
                schemaVersion = 99,
            )
            assertThat(ControllerBindingCodec.decode(entity)).isNull()
        }
    }

    @Nested
    @DisplayName("entity init validation")
    inner class EntityValidationTests {
        private fun entity(
            bindingType: String = ControllerBindingCodec.TYPE_KEY,
            polarity: Int? = null,
        ) = ControllerBindingEntity(
            coreId = coreId,
            playerIndex = playerIndex,
            controlId = controlId.id,
            bindingType = bindingType,
            inputCode = 1,
            polarity = polarity,
            schemaVersion = ControllerBindingCodec.SCHEMA_VERSION,
        )

        @Test
        fun `unknown bindingType is tolerated at construction`() {
            // Unknown/future bindingType rows are retained (never crash) and decode to null,
            // so an older/newer app version can read rows it no longer understands.
            val unknown = entity(bindingType = "BOGUS")
            assertThat(ControllerBindingCodec.decode(unknown)).isNull()
        }

        @Test
        fun `blank coreId throws`() {
            assertThatThrownBy { entity().copy(coreId = " ") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `negative playerIndex throws`() {
            assertThatThrownBy { entity().copy(playerIndex = -1) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `polarity on a non AXIS_DIRECTION throws`() {
            assertThatThrownBy { entity(bindingType = ControllerBindingCodec.TYPE_KEY, polarity = 1) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { entity(bindingType = ControllerBindingCodec.TYPE_AXIS, polarity = -1) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `polarity not -1 or +1 throws for AXIS_DIRECTION`() {
            assertThatThrownBy {
                entity(bindingType = ControllerBindingCodec.TYPE_AXIS_DIRECTION, polarity = 0)
            }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy {
                entity(bindingType = ControllerBindingCodec.TYPE_AXIS_DIRECTION, polarity = 2)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `valid AxisDirection with polarity -1 or +1 constructs`() {
            assertThat(
                entity(bindingType = ControllerBindingCodec.TYPE_AXIS_DIRECTION, polarity = -1).polarity,
            ).isEqualTo(-1)
            assertThat(
                entity(bindingType = ControllerBindingCodec.TYPE_AXIS_DIRECTION, polarity = 1).polarity,
            ).isEqualTo(1)
        }
    }
}
