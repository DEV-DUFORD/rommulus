#!/usr/bin/env python3
"""Deterministic original BIOS-free Atari 2600 ROM for Stella E2E."""

import hashlib

ROM_SIZE = 0x1000
ENTRY_OFFSET = 0
RESET_VECTOR_OFFSET = 0x0FFC
PROVENANCE_OFFSET = 0x100
PROVENANCE = b"ROMMULUS-STELLA-E2E-ORIGINAL-2026"


def generate_rom():
    """Return an original 4 KiB F8-free cartridge with video and audio output."""
    rom = bytearray([0xFF] * ROM_SIZE)
    # 6502 program at $F000: configure TIA color/audio and repeatedly draw a
    # 262-line NTSC frame (3 VSYNC + 37 VBLANK + 192 visible + 30 overscan).
    # The complete image and bytecode are authored here; no external ROM,
    # firmware, or known game signature is included.
    program = bytes([
        0x78,                    # sei
        0xD8,                    # cld
        0xA2, 0xFF, 0x9A,        # ldx #$ff; txs
        0xA9, 0x2A, 0x85, 0x09,  # lda #$2a; sta COLUBK
        0xA9, 0x04, 0x85, 0x15,  # lda #4; sta AUDC0
        0xA9, 0x08, 0x85, 0x17,  # lda #8; sta AUDF0
        0xA9, 0x0F, 0x85, 0x19,  # lda #15; sta AUDV0
        # $F019: vertical sync, then the VBLANK interval.
        0xA9, 0x02, 0x85, 0x00,  # lda #2; sta VSYNC
        0x85, 0x02, 0x85, 0x02, 0x85, 0x02,  # three WSYNC lines
        0xA9, 0x00, 0x85, 0x00,  # lda #0; sta VSYNC
        0xA9, 0x02, 0x85, 0x01,  # lda #2; sta VBLANK
        0xA2, 0x25,              # ldx #37
        0x85, 0x02, 0xCA, 0xD0, 0xFB,  # WSYNC; dex; bne
        # Visible scanlines: animate the background color every line.
        0xA9, 0x00, 0x85, 0x01,  # lda #0; sta VBLANK
        0xA2, 0xC0,              # ldx #192
        0x85, 0x02, 0xE6, 0x09, 0xCA, 0xD0, 0xF9,  # WSYNC; inc; dex; bne
        # Overscan completes the 262-line frame, then begins the next.
        0xA9, 0x02, 0x85, 0x01,  # lda #2; sta VBLANK
        0xA2, 0x1E,              # ldx #30
        0x85, 0x02, 0xCA, 0xD0, 0xFB,  # WSYNC; dex; bne
        0x4C, 0x15, 0xF0,        # jmp frame start
    ])
    rom[ENTRY_OFFSET:ENTRY_OFFSET + len(program)] = program
    rom[PROVENANCE_OFFSET:PROVENANCE_OFFSET + len(PROVENANCE)] = PROVENANCE
    rom[RESET_VECTOR_OFFSET:RESET_VECTOR_OFFSET + 2] = bytes([0x00, 0xF0])
    rom[0x0FFE:0x1000] = bytes([0x00, 0xF0])
    return bytes(rom)


def rom_sha256():
    return hashlib.sha256(generate_rom()).hexdigest()
