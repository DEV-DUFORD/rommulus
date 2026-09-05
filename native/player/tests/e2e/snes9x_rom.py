#!/usr/bin/env python3
"""Original deterministic LoROM cartridge for Snes9x qualification."""

import hashlib

ROM_SIZE = 0x8000
HEADER_OFFSET = 0x7FC0
SRAM_SIZE = 0x800
SRAM_FILL = 0x60
SRAM_MARKER = 0x52
SRAM_MARKER_OFFSET = 0
SRAM_COUNTER_OFFSET = 1
PROVENANCE = b"ROMMULUS E2E SNES9X - original deterministic LoROM qualification ROM"
PROVENANCE_OFFSET = 0x200


def _program():
    # Reset enters emulation mode. Switch to native mode before enabling NMI,
    # then count one VBlank NMI per emulated frame in battery-backed SRAM.
    reset = bytes((
        0x78,                    # sei
        0x18, 0xFB,              # clc; xce (native mode)
        0xC2, 0x30,              # rep #$30 (16-bit index/accumulator)
        0xA2, 0xFF, 0x1F, 0x9A,  # ldx #$1fff; txs
        0xE2, 0x20,              # sep #$20 (8-bit accumulator)
        0xAF, 0x00, 0x00, 0x70,  # lda.l $700000
        0xC9, SRAM_MARKER,       # cmp #marker
        0xF0, 0x0C,              # beq setup
        0xA9, SRAM_MARKER, 0x8F, 0x00, 0x00, 0x70,
        0xA9, 0x00, 0x8F, 0x01, 0x00, 0x70,
        0xA9, 0x0F, 0x8D, 0x00, 0x21,  # brightness on
        0xA9, 0x80, 0x8D, 0x00, 0x42,  # enable VBlank NMI
        0xCB, 0x80, 0xFD,        # wai; bra wait
    ))
    nmi_offset = len(reset)
    nmi = bytes((
        0x48,                    # pha
        0xAF, 0x01, 0x00, 0x70,  # lda.l $700001
        0x1A,                    # inc a
        0x8F, 0x01, 0x00, 0x70,  # sta.l $700001
        0x68, 0x40,              # pla; rti
    ))
    return reset + nmi, nmi_offset


PROGRAM, NMI_OFFSET = _program()


def _set_vector(rom, vector_offset, address):
    rom[vector_offset:vector_offset + 2] = address.to_bytes(2, "little")


def _set_checksum(rom):
    complement_offset = HEADER_OFFSET + 0x1C
    checksum_offset = HEADER_OFFSET + 0x1E
    rom[complement_offset:checksum_offset + 2] = b"\x00" * 4
    checksum = sum(rom) & 0xFFFF
    rom[complement_offset:complement_offset + 2] = (checksum ^ 0xFFFF).to_bytes(2, "little")
    rom[checksum_offset:checksum_offset + 2] = checksum.to_bytes(2, "little")


def generate_rom():
    rom = bytearray(b"\xff" * ROM_SIZE)
    rom[:len(PROGRAM)] = PROGRAM
    rom[PROVENANCE_OFFSET:PROVENANCE_OFFSET + len(PROVENANCE)] = PROVENANCE
    rom[HEADER_OFFSET:HEADER_OFFSET + 21] = b"ROMMULUS SNES9X E2E  "
    rom[HEADER_OFFSET + 0x15] = 0x20  # LoROM, slow
    rom[HEADER_OFFSET + 0x16] = 0x02  # ROM + SRAM + battery
    rom[HEADER_OFFSET + 0x17] = 5     # 32 KiB ROM
    rom[HEADER_OFFSET + 0x18] = 1     # 2 KiB SRAM
    rom[HEADER_OFFSET + 0x19] = 1     # NTSC
    _set_vector(rom, 0x7FEA, 0x8000 + NMI_OFFSET)  # native NMI
    _set_vector(rom, 0x7FFC, 0x8000)                # emulation reset
    _set_checksum(rom)
    _validate(bytes(rom))
    return bytes(rom)


def expected_sram_image(run_frames):
    if isinstance(run_frames, int):
        run_frames = (run_frames,)
    if any(not isinstance(frames, int) or isinstance(frames, bool) or frames < 0
           for frames in run_frames):
        raise ValueError("each reported frame count must be an int >= 0")
    image = bytearray([SRAM_FILL] * SRAM_SIZE)
    image[SRAM_MARKER_OFFSET] = SRAM_MARKER
    image[SRAM_COUNTER_OFFSET] = sum(run_frames) & 0xff
    return bytes(image)


def _validate(rom):
    if len(rom) != ROM_SIZE:
        raise ValueError("unexpected ROM size")
    if rom[:len(PROGRAM)] != PROGRAM:
        raise ValueError("program bytes drifted")
    if rom[HEADER_OFFSET + 0x15:HEADER_OFFSET + 0x1A] != bytes((0x20, 0x02, 5, 1, 1)):
        raise ValueError("LoROM battery SRAM header drifted")
    if rom[0x7FEA:0x7FEC] != (0x8000 + NMI_OFFSET).to_bytes(2, "little"):
        raise ValueError("native NMI vector drifted")
    if rom[0x7FFC:0x7FFE] != (0x8000).to_bytes(2, "little"):
        raise ValueError("reset vector drifted")
    complement = int.from_bytes(rom[HEADER_OFFSET + 0x1C:HEADER_OFFSET + 0x1E], "little")
    checksum = int.from_bytes(rom[HEADER_OFFSET + 0x1E:HEADER_OFFSET + 0x20], "little")
    if (checksum ^ complement) != 0xFFFF:
        raise ValueError("checksum complement drifted")


def rom_sha256():
    return hashlib.sha256(generate_rom()).hexdigest()


if __name__ == "__main__":
    print("rom bytes: %d" % ROM_SIZE)
    print("sha256:    %s" % rom_sha256())
