#!/usr/bin/env python3
# gambatte_rom.py — deterministic 32 KiB Game Boy ROM generator for the
# rommulus-player Gambatte candidate E2E (player_e2e.py).
#
# Authorship / design
# -------------------
# Entirely ORIGINAL, RomMulus-authored bytecode. No Nintendo logo bytes, no
# BIOS/boot-ROM bytes, no third-party ROM content anywhere in the image:
# the 0x0104-0x0133 logo region is the conventional 0xFF unused-ROM fill,
# which is deliberately NOT the Nintendo logo pattern (and is verified
# against both the Nintendo-logo sum and the Sachen-MMC1 scrambled-logo
# sums that the vendored Gambatte cartridge detector consults — see
# third_party/cores/gambatte/src/mem/cartridge.cpp detectSachenMmc1()).
#
# The ROM is crafted against the VENDORED Gambatte core (libretro/
# gambatte-libretro @ 96174369b3c30d9fc57c926fa3379c273dc6a9a5, pinned at
# third_party/cores/gambatte), not against generic hardware folklore. The
# load-bearing facts, derived from that tree:
#
#   1. No-boot-ROM entry point is 0x0100, NOT 0x0000. libretro.cpp's
#      get_bootloader_from_file() returns false unless the
#      "gambatte_gb_bootloader" core option is enabled (default OFF), so
#      Bootloader::load() leaves using_bootloader=false. In that path
#      gambatte.cpp full_init() does NOT touch state.cpu.pc, so the CPU
#      keeps the value setInitState() assigns: state.cpu.pc = 0x100
#      (initstate.cpp). The pc = 0x0000 assignment in gambatte.cpp is
#      inside `if (using_bootloader)` only. The ROM therefore places a
#      `jp 0x0150` at 0x0100 and the program at 0x0150.
#
#   2. 0xFF is NOT an inert fill in this core. cpu.cpp decodes opcode
#      0xFF as `rst_n(0x38)` (case 0xFF, ~line 1991): it PUSHes the return
#      address and jumps to 0x0038. A CPU walking 0xFF fill therefore RSTs
#      to 0x0038; if 0x0038 is also 0xFF fill it loops forever (pushing the
#      stack down every cycle). The ROM places a `jp 0x0150` at 0x0038 so
#      any stray RST 0x38 recovers into the program, and keeps the program
#      self-contained so control never walks into fill.
#
#   3. 8192-byte battery SRAM. cartridge.cpp loadROM() maps header byte
#      0x0147 = 0x03 to MBC1 ("MBC1 ROM+RAM+BATTERY loaded"), and
#      cartridge_libretro.cpp hasBattery(0x03) is true, so
#      retro_get_memory_size(RETRO_MEMORY_SAVE_RAM) returns
#      memptrs rambankdataend - rambankdata = rambanks * 0x2000. Header
#      byte 0x0149 = 0x02 selects rambanks = 1 → exactly 8192 bytes.
#      (0x0149 = 0x00 would also give 8 KiB for this core's MBC2-typed
#      0x09 cart, but 0x03/0x02 is the canonical MBC1 + 8 KiB battery
#      combination and keeps the cart a plain MBC1 with no RTC.)
#
#   4. SRAM enable. Mbc1::romWrite() (cartridge.cpp) treats a write of a
#      value with (data & 0xF) == 0x0A to 0x0000-0x1FFF as the RAM-enable
#      register and maps 0xA000-0xBFFF to the SRAM bank. The ROM writes
#      0x0A to 0x0000 on every boot: the enable flag is mapper state, not
#      part of the SRAM image, so a save restore alone does not re-enable
#      it.
#
#   5. Fresh-SRAM fill. initstate.cpp setInitState() fills SRAM with 0xFF
#      (not 0x00) on the first init after retro_load_game (clearSram),
#      which is how the ROM distinguishes a FRESH cart (SRAM[0] == 0xFF)
#      from a RESTORED one (SRAM[0] == the 0x52 marker written by a
#      previous run): fresh carts get their counters zeroed, restored
#      carts keep them.
#
#   6. One frame = one VBlank edge. gambatte's retro_run() advances the
#      CPU by exactly one video frame (70224 master cycles;
#      VIDEO_REFRESH_RATE = 4194304/70224) and calls the video callback
#      exactly once per retro_run, so the player's reported frame count
#      equals the number of frames the ROM observed. The PPU's LY
#      register (0xFF44) is 0 for exactly one 456-cycle line per frame
#      (ly_counter.cpp: ly_ runs 0..153, wrapping to 0 once per frame),
#      so the 0→nonzero LY transition fires exactly once per frame. The
#      cycle counter does not start at 0 (initstate.cpp sets it to
#      0x102A0 + 0x8D2C for DMG), so the CPU begins mid-frame; the
#      double-wait loop (wait LY==0, then wait LY!=0) makes the first
#      increment land on the first 0→nonzero edge after startup, and
#      because every retro_run() spans exactly one such edge, the ROM's
#      counter advances exactly once per presented frame.
#
# Program (bank 0; entry 0x0100, body 0x0150-0x01C7; all else 0xFF fill)
# ----------------------------------------------------------------------
#   0x0100  C3 50 01    jp 0x0150          ; no-boot-ROM entry (PC starts 0x100)
#   0x0038  C3 50 01    jp 0x0150          ; RST 0x38 handler (0xFF opcode)
#   0x0150  31 FE FF    ld sp, 0xFFFE      ; stack top (also recovers SP)
#   0x0153  3E 0A       ld a, 0x0A
#   0x0155  EA 00 00    ld [0x0000], a     ; MBC1 SRAM enable (every boot)
#   0x0158  FA 00 A0    ld a, [0xA000]     ; read SRAM marker
#   0x015B  FE 52       cp 0x52
#   0x015D  28 0D       jr z, 0x016C       ; restored cart: keep counters
#   0x015F  3E 52       ld a, 0x52
#   0x0161  EA 00 A0    ld [0xA000], a     ; fresh cart: write marker
#   0x0164  3E 00       ld a, 0x00
#   0x0166  EA 01 A0    ld [0xA001], a     ; frame counter = 0
#   0x0169  EA 02 A0    ld [0xA002], a     ; 60-frame counter = 0
#   0x016C  18 1F       jr 0x018D          ; to display setup
#   0x018D  3E 98       ld a, 0x98
#   0x018F  EA 40 FF    ld [0xFF40], a     ; LCDC: display on, 8x8 tiles
#   0x0192  3E E0       ld a, 0xE0
#   0x0194  EA 47 FF    ld [0xFF47], a     ; BGP: light-on-dark
#   0x0197  3E 00       ld a, 0x00
#   0x0199  EA 41 FF    ld [0xFF41], a     ; STAT: no interrupt flags
#   0x019C  FA 44 FF    ld a, [0xFF44]     ; LY
#   0x019F  FE 00       cp 0x00
#   0x01A1  20 F9       jr nz, 0x019C      ; wait for the LY==0 line
#   0x01A3  FA 44 FF    ld a, [0xFF44]
#   0x01A6  FE 00       cp 0x00
#   0x01A8  28 F9       jr z, 0x01A3       ; wait for the frame's first
#                                          ;   active line (LY != 0)
#   0x01AA  FA 01 A0    ld a, [0xA001]
#   0x01AD  3C          inc a
#   0x01AE  EA 01 A0    ld [0xA001], a     ; frame counter++
#   0x01B1  FA 01 A0    ld a, [0xA001]
#   0x01B4  FE 3C       cp 0x3C            ; 60
#   0x01B6  28 02       jr z, 0x01BA       ; wrap
#   0x01B8  18 E2       jr 0x019C          ; next frame
#   0x01BA  3E 00       ld a, 0x00
#   0x01BC  EA 01 A0    ld [0xA001], a     ; frame counter = 0
#   0x01BF  FA 02 A0    ld a, [0xA002]
#   0x01C2  3C          inc a
#   0x01C3  EA 02 A0    ld [0xA002], a     ; 60-frame counter++
#   0x01C6  18 D4       jr 0x019C          ; next frame
#
# The double wait (LY==0, then LY!=0) makes the increment fire exactly
# once per frame regardless of which phase of the frame the CPU is in
# when it reaches the loop, and the loop body is ~20 cycles against a
# 456-cycle LY==0 window and a ~69768-cycle active period, so no edge
# can be missed. Because every retro_run() spans exactly one 0→nonzero
# LY edge and the ROM is in the loop for all of them, after N presented
# frames the SRAM holds
#
#   SRAM[0] = 0x52 (marker), SRAM[1] = N mod 60, SRAM[2] = N // 60,
#   SRAM[3..8191] = 0xFF (untouched fresh fill).
#
# That is the deterministic marker/counter invariant the E2E asserts
# against the player-reported frame count — frame-stable across frame
# boundaries and independent of wall-clock pacing.
#
# Python 3 standard library only (the E2E harness runs on GUI-less CI
# runners with no third-party packages).

import hashlib

ROM_SIZE = 0x8000            # 32 KiB: two 16 KiB banks (header 0x0148 = 0x00)
SRAM_SIZE = 8192             # 8 KiB battery SRAM (0x0147 = 0x03, 0x0149 = 0x02)
SRAM_MARKER = 0x52           # 'R' — written once on a fresh cart
SRAM_FRAME_COUNTER = 0x0001  # SRAM offset: frames since fresh init, mod 60
SRAM_60FRAME_COUNTER = 0x0002  # SRAM offset: completed 60-frame intervals

ENTRY_ADDR = 0x0100          # no-boot-ROM PC start (initstate.cpp)
RST38_ADDR = 0x0038          # 0xFF opcode target (cpu.cpp case 0xFF)
PROGRAM_ADDR = 0x0150        # program body start (after the 0x014F header)

# Cartridge header (0x0100-0x014F) — original RomMulus values.
TITLE = b"ROMMULUS E2E GB"   # 15 bytes at 0x0134-0x0142
CGB_FLAG = 0xC0              # 0x0143: DMG (bit 7 clear), original DMG bits
MANUFACTURER = b"RM"         # 0x0144-0x0145
CGB_NEW_FLAG = 0x00          # 0x0146
CARTRIDGE_TYPE = 0x03        # 0x0147: MBC1 + RAM + battery (Gambatte: MBC1,
                              #       hasBattery(0x03) == true, no RTC)
ROM_SIZE_CODE = 0x00         # 0x0148: 32 KiB
RAM_SIZE_CODE = 0x02         # 0x0149: 8 KiB RAM (Gambatte: rambanks = 1)

# The program, as (offset, bytes) — every byte is RomMulus-authored. The
# entry jump and the RST 0x38 handler both target PROGRAM_ADDR.
_JP_PROGRAM = bytes([0xC3, PROGRAM_ADDR & 0xFF, (PROGRAM_ADDR >> 8) & 0xFF])
_PROGRAM = (
    (PROGRAM_ADDR + 0x00, bytes([0x31, 0xFE, 0xFF])),  # ld sp, 0xFFFE
    (PROGRAM_ADDR + 0x03, bytes([0x3E, 0x0A])),        # ld a, 0x0A
    (PROGRAM_ADDR + 0x05, bytes([0xEA, 0x00, 0x00])),  # ld [0x0000], a (SRAM enable)
    (PROGRAM_ADDR + 0x08, bytes([0xFA, 0x00, 0xA0])),  # ld a, [0xA000]
    (PROGRAM_ADDR + 0x0B, bytes([0xFE, 0x52])),        # cp 0x52
    (PROGRAM_ADDR + 0x0D, bytes([0x28, 0x0D])),        # jr z, +0x0D (skip init)
    (PROGRAM_ADDR + 0x0F, bytes([0x3E, 0x52])),        # ld a, 0x52
    (PROGRAM_ADDR + 0x11, bytes([0xEA, 0x00, 0xA0])),  # ld [0xA000], a (marker)
    (PROGRAM_ADDR + 0x14, bytes([0x3E, 0x00])),        # ld a, 0x00
    (PROGRAM_ADDR + 0x16, bytes([0xEA, 0x01, 0xA0])),  # ld [0xA001], a (counter = 0)
    (PROGRAM_ADDR + 0x19, bytes([0xEA, 0x02, 0xA0])),  # ld [0xA002], a (counter = 0)
    (PROGRAM_ADDR + 0x1C, bytes([0x18, 0x1F])),        # jr +0x1F (display setup)
    (PROGRAM_ADDR + 0x3D, bytes([0x3E, 0x98])),        # ld a, 0x98
    (PROGRAM_ADDR + 0x3F, bytes([0xEA, 0x40, 0xFF])),  # ld [0xFF40], a (LCDC on)
    (PROGRAM_ADDR + 0x42, bytes([0x3E, 0xE0])),        # ld a, 0xE0
    (PROGRAM_ADDR + 0x44, bytes([0xEA, 0x47, 0xFF])),  # ld [0xFF47], a (BGP)
    (PROGRAM_ADDR + 0x47, bytes([0x3E, 0x00])),        # ld a, 0x00
    (PROGRAM_ADDR + 0x49, bytes([0xEA, 0x41, 0xFF])),  # ld [0xFF41], a (STAT)
    (PROGRAM_ADDR + 0x4C, bytes([0xFA, 0x44, 0xFF])),  # ld a, [0xFF44] (LY)
    (PROGRAM_ADDR + 0x4F, bytes([0xFE, 0x00])),        # cp 0x00
    (PROGRAM_ADDR + 0x51, bytes([0x20, 0xF9])),        # jr nz, -7 (wait LY==0)
    (PROGRAM_ADDR + 0x53, bytes([0xFA, 0x44, 0xFF])),  # ld a, [0xFF44]
    (PROGRAM_ADDR + 0x56, bytes([0xFE, 0x00])),        # cp 0x00
    (PROGRAM_ADDR + 0x58, bytes([0x28, 0xF9])),        # jr z, -7 (wait LY!=0)
    (PROGRAM_ADDR + 0x5A, bytes([0xFA, 0x01, 0xA0])),  # ld a, [0xA001]
    (PROGRAM_ADDR + 0x5D, bytes([0x3C])),              # inc a
    (PROGRAM_ADDR + 0x5E, bytes([0xEA, 0x01, 0xA0])),  # ld [0xA001], a (counter++)
    (PROGRAM_ADDR + 0x61, bytes([0xFA, 0x01, 0xA0])),  # ld a, [0xA001]
    (PROGRAM_ADDR + 0x64, bytes([0xFE, 0x3C])),        # cp 0x3C (60)
    (PROGRAM_ADDR + 0x66, bytes([0x28, 0x02])),        # jr z, +2 (wrap)
    (PROGRAM_ADDR + 0x68, bytes([0x18, 0xE2])),        # jr -0x1E (next frame)
    (PROGRAM_ADDR + 0x6A, bytes([0x3E, 0x00])),        # ld a, 0x00
    (PROGRAM_ADDR + 0x6C, bytes([0xEA, 0x01, 0xA0])),  # ld [0xA001], a (counter = 0)
    (PROGRAM_ADDR + 0x6F, bytes([0xFA, 0x02, 0xA0])),  # ld a, [0xA002]
    (PROGRAM_ADDR + 0x72, bytes([0x3C])),              # inc a
    (PROGRAM_ADDR + 0x73, bytes([0xEA, 0x02, 0xA0])),  # ld [0xA002], a (counter++)
    (PROGRAM_ADDR + 0x76, bytes([0x18, 0xD4])),        # jr -0x2C (next frame)
)

# Gambatte cartridge-detector magic sums (cartridge.cpp): the generated
# image must match NEITHER the plain Nintendo-logo sum at 0x0104 (5446)
# NOR the Sachen scrambled-logo sums at 0x184 (5542 / 7484), so the core
# classifies the cart through the normal header switch.
_NINTENDO_LOGO_SUM = 5446
_SACHEN_LOGO_SUM_A = 5542
_SACHEN_LOGO_SUM_B = 7484


def _sachen_scramble(addr):
    """cartridge.cpp sachenScramble(): A0<->A6, A1<->A4 bit swap."""
    return (addr & ~0x53) | ((addr >> 6) & 1) | ((addr >> 3) & 2) \
        | ((addr << 3) & 0x10) | ((addr << 6) & 0x40)


def _header_checksum(rom):
    """The actual Game Boy header checksum (stored at 0x014D).

    The DMG boot ROM algorithm: the running checksum starts at 0 and, for
    each byte of 0x0134-0x014C, is updated with
    checksum = (checksum - byte - 1) & 0xFF.
    """
    c = 0
    for i in range(0x0134, 0x014D):
        c = (c - rom[i] - 1) & 0xFF
    return c


def _global_checksum(rom):
    """The 16-bit global checksum (stored big-endian at 0x014E-0x014F).

    The sum of every ROM byte EXCEPT the two checksum bytes themselves
    (0x014E and 0x014F), taken modulo 2^16.
    """
    return (sum(rom[:0x014E]) + sum(rom[0x0150:])) & 0xFFFF


def generate_rom():
    """Build the 32 KiB ROM image (fully deterministic; pure function)."""
    rom = bytearray(b"\xFF" * ROM_SIZE)

    # Entry point (the no-boot-ROM PC starts at 0x0100) and the RST 0x38
    # handler (the 0xFF opcode target) both jump into the program.
    rom[ENTRY_ADDR:ENTRY_ADDR + 3] = _JP_PROGRAM
    rom[RST38_ADDR:RST38_ADDR + 3] = _JP_PROGRAM

    for offset, code in _PROGRAM:
        rom[offset:offset + len(code)] = code

    # Header 0x0134-0x0143: title + CGB flag (0x0143 is the title's 16th
    # byte). 0x0104-0x0133 stays 0xFF: no Nintendo logo.
    rom[0x0134:0x0134 + len(TITLE)] = TITLE
    rom[0x0143] = CGB_FLAG
    rom[0x0144:0x0144 + len(MANUFACTURER)] = MANUFACTURER
    rom[0x0146] = CGB_NEW_FLAG
    rom[0x0147] = CARTRIDGE_TYPE
    rom[0x0148] = ROM_SIZE_CODE
    rom[0x0149] = RAM_SIZE_CODE

    # 0x014D: header checksum — the actual Game Boy algorithm (the DMG
    # boot ROM subtraction over 0x0134-0x014C). Must be written before the
    # global checksum, which folds this byte in.
    rom[0x014D] = _header_checksum(rom)

    # 0x014E-0x014F: 16-bit global checksum — the sum of every ROM byte
    # except the two checksum bytes, stored big-endian (high byte first).
    gsum = _global_checksum(rom)
    rom[0x014E] = (gsum >> 8) & 0xFF
    rom[0x014F] = gsum & 0xFF

    rom = bytes(rom)
    _validate(rom)
    return rom


def _validate(rom):
    """Self-checks so a generator regression fails at generation time."""
    if len(rom) != ROM_SIZE:
        raise ValueError("ROM is %d bytes, want %d" % (len(rom), ROM_SIZE))
    # Entry point (no-boot-ROM PC start) must jump into the program.
    if rom[ENTRY_ADDR:ENTRY_ADDR + 3] != _JP_PROGRAM:
        raise ValueError("entry point at 0x%04X is not 'jp 0x%04X'"
                         % (ENTRY_ADDR, PROGRAM_ADDR))
    # The RST 0x38 handler (0xFF opcode target) must recover into the
    # program so a stray RST cannot loop on 0xFF fill.
    if rom[RST38_ADDR:RST38_ADDR + 3] != _JP_PROGRAM:
        raise ValueError("RST 0x38 handler at 0x%04X is not 'jp 0x%04X'"
                         % (RST38_ADDR, PROGRAM_ADDR))
    # No Nintendo logo: the plain logo sum must not match, and the
    # scrambled (Sachen) logo sum must not match either variant.
    plain_sum = sum(rom[0x0104:0x0134])
    if plain_sum == _NINTENDO_LOGO_SUM:
        raise ValueError("logo region matches the Nintendo-logo sum")
    scrambled_sum = sum(rom[_sachen_scramble(0x184 + i)] for i in range(0x30))
    if scrambled_sum in (_SACHEN_LOGO_SUM_A, _SACHEN_LOGO_SUM_B):
        raise ValueError("logo region matches a Sachen scrambled-logo sum")
    # Header checksum (0x014D) must verify with the actual Game Boy
    # algorithm (the DMG boot ROM subtraction over 0x0134-0x014C).
    if _header_checksum(rom) != rom[0x014D]:
        raise ValueError("header checksum at 0x014D is invalid")
    # Global checksum (0x014E-0x014F) must equal the 16-bit sum of the
    # whole ROM excluding the two checksum bytes, big-endian (high byte
    # first).
    gsum = _global_checksum(rom)
    if (rom[0x014E], rom[0x014F]) != ((gsum >> 8) & 0xFF, gsum & 0xFF):
        raise ValueError("global checksum at 0x014E-0x014F is invalid")
    # The cartridge combination the vendored Gambatte exposes as 8 KiB
    # battery SRAM.
    if rom[0x0147] != CARTRIDGE_TYPE or rom[0x0149] != RAM_SIZE_CODE:
        raise ValueError("cartridge type / RAM size header drifted")


def rom_sha256():
    """SHA256 hex digest of the generated ROM (pinned in unit tests)."""
    return hashlib.sha256(generate_rom()).hexdigest()


def expected_sram_image(total_frames):
    """The exact 8192-byte SRAM checkpoint after `total_frames` frames.

    total_frames is the cumulative frame count across relaunches (the
    restored counters keep counting), so a fresh N-frame run passes N and
    a restored run passing F1 then F2 frames passes F1 + F2. Bytes 3..8191
    are the untouched 0xFF fresh fill — the ROM never writes them.
    """
    if total_frames < 0:
        raise ValueError("total_frames must be >= 0")
    image = bytearray(b"\xFF" * SRAM_SIZE)
    image[0x0000] = SRAM_MARKER
    image[SRAM_FRAME_COUNTER] = total_frames % 60
    image[SRAM_60FRAME_COUNTER] = total_frames // 60
    return bytes(image)


if __name__ == "__main__":
    import sys
    rom = generate_rom()
    print("rom bytes: %d" % len(rom))
    print("sha256:    %s" % hashlib.sha256(rom).hexdigest())
    sys.stdout.flush()
