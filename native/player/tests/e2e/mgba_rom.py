#!/usr/bin/env python3
"""Original deterministic GBA cartridge for mGBA qualification."""

import hashlib

ROM_SIZE = 0x1000
ENTRY_OFFSET = 0xC0
SRAM_SIZE = 0x8000
SRAM_BASE = 0x0E000000
DISPSTAT = 0x04000004
SRAM_MARKER = 0x52
SRAM_MARKER_OFFSET = 0
SRAM_COUNTER_OFFSET = 1
PROVENANCE = b"ROMMULUS E2E MGBA - original deterministic ARM qualification ROM"
PROVENANCE_OFFSET = 0x200


def _word(value):
    return value.to_bytes(4, "little")


def _branch(from_offset, to_offset, condition=0xE):
    displacement = (to_offset - (from_offset + 8)) // 4
    if (to_offset - (from_offset + 8)) % 4 or not -(1 << 23) <= displacement < (1 << 23):
        raise ValueError("ARM branch target is out of range")
    return _word((condition << 28) | 0x0A000000 | (displacement & 0xFFFFFF))


def _program():
    # ARM state starts at ROM offset zero. The cartridge uses DISPSTAT's
    # VBlank bit, rising then falling once per display frame, so the SRAM
    # counter is tied to emulation rather than instruction throughput.
    code = bytearray()
    code += _branch(0, ENTRY_OFFSET)
    code += b"\x00" * (ENTRY_OFFSET - len(code))

    start = len(code)
    code += _word(0xE59F004C)  # ldr r0, [pc, #76] -> SRAM base
    code += _word(0xE59F104C)  # ldr r1, [pc, #76] -> DISPSTAT
    code += _word(0xE5D02000)  # ldrb r2, [r0]
    code += _word(0xE3520052)  # cmp r2, #0x52
    initialized = ENTRY_OFFSET + 0x24
    code += _branch(len(code), initialized, condition=0x0)
    code += _word(0xE3A02052)  # mov r2, #0x52
    code += _word(0xE5C02000)  # strb r2, [r0]
    code += _word(0xE3A02000)  # mov r2, #0
    code += _word(0xE5C02001)  # strb r2, [r0, #1]
    if len(code) != initialized:
        raise ValueError("initialization branch target drifted")
    code += _word(0xE2800001)  # add r0, r0, #1 (counter byte)
    wait_vblank = len(code)
    code += _word(0xE1D120B0)  # ldrh r2, [r1]
    code += _word(0xE3120001)  # tst r2, #1
    code += _branch(len(code), wait_vblank, condition=0x0)
    code += _word(0xE5D02000)  # ldrb r2, [r0]
    code += _word(0xE2822001)  # add r2, r2, #1
    code += _word(0xE5C02000)  # strb r2, [r0]
    wait_active = len(code)
    code += _word(0xE1D120B0)  # ldrh r2, [r1]
    code += _word(0xE3120001)  # tst r2, #1
    code += _branch(len(code), wait_active, condition=0x1)
    code += _branch(len(code), wait_vblank)
    code += _word(SRAM_BASE)
    code += _word(DISPSTAT)
    return bytes(code)


PROGRAM = _program()


def generate_rom():
    rom = bytearray(b"\xff" * ROM_SIZE)
    rom[:len(PROGRAM)] = PROGRAM
    rom[0xA0:0xAC] = b"ROMMULUSMGBA"
    rom[0xAC:0xB0] = b"RMGB"
    rom[0xB0:0xB2] = b"01"
    rom[0xB2] = 0x96
    rom[PROVENANCE_OFFSET:PROVENANCE_OFFSET + len(PROVENANCE)] = PROVENANCE
    _validate(bytes(rom))
    return bytes(rom)


def expected_sram_image(run_frames):
    if isinstance(run_frames, int):
        run_frames = (run_frames,)
    if any(not isinstance(frames, int) or isinstance(frames, bool) or frames < 0
           for frames in run_frames):
        raise ValueError("each reported frame count must be an int >= 0")
    image = bytearray(b"\xff" * SRAM_SIZE)
    image[SRAM_MARKER_OFFSET] = SRAM_MARKER
    image[SRAM_COUNTER_OFFSET] = sum(run_frames) & 0xff
    return bytes(image)


def _validate(rom):
    if len(rom) != ROM_SIZE:
        raise ValueError("unexpected ROM size")
    if rom[:4] != _branch(0, ENTRY_OFFSET):
        raise ValueError("entry branch drifted")
    if rom[ENTRY_OFFSET:ENTRY_OFFSET + len(PROGRAM) - ENTRY_OFFSET] != PROGRAM[ENTRY_OFFSET:]:
        raise ValueError("ARM program bytes drifted")


def rom_sha256():
    return hashlib.sha256(generate_rom()).hexdigest()


if __name__ == "__main__":
    print("rom bytes: %d" % ROM_SIZE)
    print("sha256:    %s" % rom_sha256())
