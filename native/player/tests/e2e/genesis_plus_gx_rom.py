#!/usr/bin/env python3
"""Original deterministic Genesis cartridge for Genesis Plus GX qualification."""

import hashlib

ROM_SIZE = 0x4000
ENTRY_ADDRESS = 0x200
SRAM_SIZE = 0x10000
SRAM_MARKER = 0x52
SRAM_MARKER_OFFSET = 0
SRAM_COUNTER_OFFSET = 1
PROVENANCE = b"ROMMULUS E2E GENESIS - original deterministic 68000 qualification ROM"
PROVENANCE_OFFSET = 0x300
HEADER_OFFSET = 0x100
SRAM_START = 0x200000
VDP_CONTROL_PORT = 0xC00004


def _long(value):
    return value.to_bytes(4, "big")


def _word(value):
    return value.to_bytes(2, "big")


def _program():
    # The VDP status register's bit 3 is set during vertical blank. Waiting
    # for it to rise and then clear prevents multiple counter updates in one
    # VBlank interval. Enable display first: Genesis Plus GX correctly holds
    # VBlank asserted while display is disabled. The core produces its
    # ordinary RGB565 frame each run; this fixture only supplies a stable
    # persistence oracle.
    code = bytearray()
    code += b"\x33\xfc\x81\x44" + _long(VDP_CONTROL_PORT)          # VDP R1: display on
    code += b"\x0c\x39" + _word(SRAM_MARKER) + _long(SRAM_START)  # cmpi.b
    code += b"\x67\x10"                                           # beq initialized
    code += b"\x13\xfc" + _word(SRAM_MARKER) + _long(SRAM_START)  # move.b #$52
    code += b"\x13\xfc\x00\x00" + _long(SRAM_START + 1)           # counter = 0
    wait_vblank = ENTRY_ADDRESS + len(code)
    code += b"\x30\x39" + _long(VDP_CONTROL_PORT)                 # move.w VDP,d0
    code += b"\x08\x00\x00\x03"                                   # btst #3,d0
    code += b"\x67\xf4"                                           # beq wait_vblank
    code += b"\x52\x39" + _long(SRAM_START + 1)                   # addq.b #1,SRAM[1]
    wait_not_vblank = ENTRY_ADDRESS + len(code)
    code += b"\x30\x39" + _long(VDP_CONTROL_PORT)
    code += b"\x08\x00\x00\x03"
    code += b"\x66\xf4"                                           # bne wait_not_vblank
    displacement = wait_vblank - (ENTRY_ADDRESS + len(code) + 2)
    if not -128 <= displacement <= 127:
        raise ValueError("VBlank loop branch exceeded short range")
    code += bytes((0x60, displacement & 0xFF))                    # bra wait_vblank
    return bytes(code)


PROGRAM = _program()


def generate_rom():
    rom = bytearray(b"\xff" * ROM_SIZE)
    rom[4:8] = _long(ENTRY_ADDRESS)  # reset vector
    rom[ENTRY_ADDRESS:ENTRY_ADDRESS + len(PROGRAM)] = PROGRAM
    rom[PROVENANCE_OFFSET:PROVENANCE_OFFSET + len(PROVENANCE)] = PROVENANCE

    rom[HEADER_OFFSET:HEADER_OFFSET + 16] = b"SEGA GENESIS    "
    rom[0x120:0x150] = b"ROMMULUS E2E GENESIS".ljust(48, b" ")
    rom[0x150:0x180] = b"ROMMULUS E2E GENESIS".ljust(48, b" ")
    rom[0x180:0x18e] = b"GM 00000000-00"
    rom[0x190:0x1a0] = b"J               "
    rom[0x1a0:0x1a4] = _long(0)
    rom[0x1a4:0x1a8] = _long(ROM_SIZE - 1)
    # "RA", backup SRAM, both byte lanes, type SRAM; the configured range
    # is within the core's 64 KiB maximum and starts at the usual cartridge
    # SRAM mapping. The ROMM extension then exposes a stable 64 KiB image.
    rom[0x1b0:0x1b4] = b"RA\xf8\x20"
    rom[0x1b4:0x1b8] = _long(SRAM_START)
    rom[0x1b8:0x1bc] = _long(SRAM_START + SRAM_SIZE - 1)
    rom[0x1f0:0x200] = b"JUE             "

    checksum = sum(rom[0x200::2]) + sum(rom[0x201::2])
    rom[0x18e:0x190] = _word(checksum & 0xffff)
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


def expected_sram_image_for_reported_frames(run_frames):
    """Map player frame reports to completed Genesis VBlank-loop iterations.

    The player records a frame after the core's `retro_run()` returns; our
    fixture increments while that call executes. Hosted evidence therefore
    has exactly one trailing presented frame per fresh core process.
    """
    if isinstance(run_frames, int):
        run_frames = (run_frames,)
    if any(not isinstance(frames, int) or isinstance(frames, bool) or frames < 1
           for frames in run_frames):
        raise ValueError("each reported frame count must be an int >= 1")
    return expected_sram_image(tuple(frames - 1 for frames in run_frames))


def _validate(rom):
    if len(rom) != ROM_SIZE:
        raise ValueError("unexpected ROM size")
    if rom[4:8] != _long(ENTRY_ADDRESS):
        raise ValueError("reset vector does not point to entry")
    if rom[ENTRY_ADDRESS:ENTRY_ADDRESS + len(PROGRAM)] != PROGRAM:
        raise ValueError("68000 program bytes drifted")
    if rom[0x1b0:0x1b4] != b"RA\xf8\x20":
        raise ValueError("SRAM header missing")
    if rom[0x1b4:0x1bc] != _long(SRAM_START) + _long(SRAM_START + SRAM_SIZE - 1):
        raise ValueError("SRAM range drifted")


def rom_sha256():
    return hashlib.sha256(generate_rom()).hexdigest()


if __name__ == "__main__":
    print("rom bytes: %d" % ROM_SIZE)
    print("sha256:    %s" % rom_sha256())
