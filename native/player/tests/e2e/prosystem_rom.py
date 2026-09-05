#!/usr/bin/env python3
# prosystem_rom.py — deterministic original Atari 7800 ROM generator for the
# rommulus-player ProSystem candidate E2E (player_e2e.py).
#
# Authorship / design
# -------------------
# Entirely ORIGINAL, RomMulus-authored 6502C bytecode and image bytes. No
# copyrighted ROM content, no Atari BIOS/BIOS-ROM bytes, no third-party bytes:
# the image is exactly 16 KiB (0x4000) of raw cartridge data — the plain
# .a78 form ProSystem's cartridge_Load() accepts without an "ATARI7800"
# header. Every byte of the program, fills, and vectors below is generated
# from this file's commit-fixed constants.
#
# The ROM is crafted against the VENDORED ProSystem core (libretro/
# prosystem-libretro @ 363b6dfbd3e240762e022c2b4897b4fe55722be3, pinned at
# third_party/cores/prosystem), not against generic hardware folklore. The
# load-bearing facts, derived from that tree:
#
#   1. No header → CARTRIDGE_TYPE_NORMAL (0, the zero-initialized default).
#      cartridge_Load() only reclassifies on an "ATARI7800" magic at bytes
#      1..9 or a ">>" CC2 marker at bytes 1..2 (neither is present here);
#      size must exceed 128 (16384 does). For NORMAL carts cartridge_Store()
#      runs memory_WriteROM(65536 - 16384, 16384, buffer): the whole image is
#      mapped at CPU $C000-$FFFF (file offset N = CPU address $C000 + N).
#
#   2. Boot path: prosystem_Reset() → sally_Reset() (PC=0) → memory_Reset()
#      → cartridge_Store() → sally_ExecuteRES(), which loads PC from
#      memory_ram[$FFFC]/memory_ram[$FFFD] — i.e., the IN-CART reset vector
#      at file offsets 0x3FFC/0x3FFD (little-endian). It must hold $C000,
#      the CPU address of file offset 0 where the program starts. IRQs are
#      disabled at that point (P = I|R|Z), so control never leaves the
#      program; no NMI/IRQ vectors are needed.
#
#   3. NO save RAM, by construction of this pin: retro_get_memory_size()
#      (core/libretro.c) implements RETRO_MEMORY_SYSTEM_RAM only and returns
#      0 for RETRO_MEMORY_SAVE_RAM — ProSystem exposes no battery/RAM region
#      for NORMAL carts. The E2E therefore asserts a rigorous NO-PERSISTENT-
#      SAVE gate (saveSize null, saveHash null, checkpointWritten false, no
#      .srm artifact on disk) instead of pretending SRAM exists; there is no
#      adoption chain because no run ever produces a candidate save.
#
#   4. Register map used by this emulator build (Equates.h): WSYNC $0024;
#      TIA audio AUDC0/AUDF0/AUDV0 = $0015/$0017/$0019 (2600-style: AUDC=0 →
#      poly4 tone, frequency = AUDF+1, volume = AUDV*4); color palette
#      BACKGRND..P0C3 = $0020-$0023 (maria_GetColor indexes memory_ram from
#      $0020); Maria display control CTRL=$003C (display enabled iff
#      (CTRL & 0x60) == 0x40; read mode = CTRL & 3), DPPH/DPPL =
#      $002C/$0030. All of these live in the writable RAM region
#      ($0000-$3FFF); writes to them land in memory_ram, which is exactly
#      what Maria reads (maria_ReadByte for non-SOUPER carts).
#
#   5. Display program format (maria_StoreLineRAM): DPP points at a header —
#      [flags: bit7 NMI, bits3:0 per-row offset], [dp high], [dp low] — and
#      the display list is a sequence of entries read from dp; each entry's
#      "mode" byte is at dp+1. Format B (mode & 0x5f != 0 and mode & 31 == 0,
#      i.e. bit 6 set): [PP low], [MODE], [PP high], [(palette<<5) | width
#      field], [HPOS]; width field 0 → 32 cells; dp advances 5 bytes per
#      entry. Each pattern byte expands to four 2-bit color cells (non-wide
#      mode), so a full 160-cell (320-pixel) row needs five 32-cell entries
#      over 40 pattern bytes. With the header offset field at 0 the header
#      advances 3 bytes after EVERY StoreLineRAM, so the ROM chains 242
#      identical 3-byte headers ($1420 + 3k, k = 0..241) covering all 242
#      StoreLineRAM calls per frame (scanlines 16..257); after the last call
#      DPP advances one header past the chain into zeroed RAM ($16F6) and its
#      three bytes are read as header fields — all zeros (no NMI, offset
#      reset to 0) — but nothing is consumed from there: the bottom scanline
#      (258) performs no StoreLineRAM at all, and the next frame reloads DPP
#      from the DPPL/DPPH registers. The display area is y 16..258 of 262
#      scanlines; visible rows 26..248 are written to the surface the player
#      presents (320x223). Because the header chain (726 bytes) exceeds the
#      8-bit index range of this core's STA abs,X, it is stored as a ROM-
#      resident template (file offsets 0x200..0x4D5) and copied into RAM in
#      three passes (256 + 256 + 214 bytes).
#
#   6. One presented frame = one retro_run() = one ExecuteFrame of 262
#      scanlines; the ROM's main loop is a tight WSYNC pair, so exactly one
#      WSYNC lands per scanline and execution is frame-locked and
#      deterministic. The player counts presented frames in its video-refresh
#      trampoline, so reported frames == retro_run() calls.
#
#   7. OPCODE TABLE OF THIS CORE (Sally.c): the vendored ProSystem's opcode
#      dispatch deviates from a stock 6502 in exactly the spots this program
#      touches, and every opcode below was verified against that table:
#        0x78 = SEI          (NOT 0x06 — that is ASL zero-page here)
#        0x9D = STA abs,X    (0x8E is STX abs here; stock 0x9E does not exist)
#        0xBD = LDA abs,X
#        0xD0 = BNE          (stock BCC slot; used for its BNE semantics)
#        0xF0 = BEQ, 0xC9 = CMP #imm, 0xE0 = CPX #imm
#        0x8A/0xAA = TXA/TAX SWAPPED vs stock — deliberately NOT used.
#        0xA6 = LDX zero-page (NOT LDA zp,X) — plain LDA zero-page is 0xA5.
#      Zero-page addressing reaches only $0000-$00FF: the HP accumulator at
#      $013D must therefore use absolute loads (0xAD), never A5/A6.
#      Counters therefore use CPX/CMP + BNE (the loop counters take only the
#      values on their fixed paths, so "!= end value" is exact), and no
#      X<->A transfer opcodes are needed at all.
#
# Real software video and audio behavior (a property of this construction,
# verified locally against the core — NOT something the E2E gate inspects):
# on every boot the ROM writes a 4-color palette ($0020-$0023), fills 40
# deterministic pattern bytes at $1000-$1027 (value i = (0x5A + 7*i) mod 256
# → all four 2-bit color indices occur), builds the display program and
# header chain, enables the Maria display (CTRL = 0x40), and configures TIA
# audio channel 0 (AUDC=0 poly4 tone, frequency 25, volume 32) — so every
# presented frame carries real rendered video and real mixed audio through
# retro_set_video_refresh / retro_set_audio_sample_batch for the whole bounded
# E2E window. The E2E gate itself asserts only lifecycle (clean completed
# exit), bounded presented-frame counts, result schema, and the
# no-persistent-save invariants above — it does not inspect pixel or audio
# sample content.
#
# Program (file offset 0 = CPU $C000; body ends at +0xB3)
# ----------------------------------------------------------------------
#   +0x00  78            sei                    ; IRQs off (never enabled)
#   +0x01  A2 1F         ldx #$1F               ; X = SP value
#   +0x03  9A            txs                    ; SP = 0x1F
#   ; palette: COLUBK / P0C1 / P0C2 / P0C3
#   +0x04  A9 00         lda #0
#   +0x06  8D 20 00      sta $0020
#   +0x09  A9 18         lda #$18
#   +0x0B  8D 21 00      sta $0021
#   +0x0E  A9 6C         lda #$6C
#   +0x10  8D 22 00      sta $0022
#   +0x13  A9 F0         lda #$F0
#   +0x15  8D 23 00      sta $0023
#   ; pattern fill $1000-$1027: byte i = (0x5A + 7*i) mod 256, i = 0..39
#   +0x18  A2 00         ldx #0                 ; pattern index
#   +0x1A  A9 5A         lda #$5A               ; value accumulator
# pat:
#   +0x1C  9D 00 10      sta $1000,x            ; (0x9D = STA abs,X in this core)
#   +0x1F  E8            inx
#   +0x20  18            clc
#   +0x21  69 07         adc #7
#   +0x23  E0 28         cpx #$28               ; index == 40?
#   +0x25  D0 F5         bne pat
#   ; display program $1400-$141A: five format-B entries (32 cells each,
#   ; PP = $1000+8k, HPOS = 32k) + terminator at $141A. X = entry byte
#   ; offset; hpos accumulator in RAM HP=$013D — absolute addressing only
#   ; (zero page cannot reach $013D); PP low byte = hpos/4.
#   +0x27  A2 00         ldx #0                 ; entry byte offset
#   +0x29  A9 00         lda #0
#   +0x2B  8D 3D 01      sta $013D              ; HP = 0 (hpos)
# dp_entry:
#   +0x2E  AD 3D 01      lda $013D
#   +0x31  4A            lsr a                  ; A = hpos/4 = PP low (8k)
#   +0x32  4A            lsr a
#   +0x33  9D 00 14      sta $1400,x            ; PP low
#   +0x36  E8            inx
#   +0x37  A9 40         lda #$40
#   +0x39  9D 00 14      sta $1400,x            ; MODE = format B (bit 6)
#   +0x3C  E8            inx
#   +0x3D  A9 10         lda #$10
#   +0x3F  9D 00 14      sta $1400,x            ; PP high = 0x10
#   +0x42  E8            inx
#   +0x43  A9 00         lda #0
#   +0x45  9D 00 14      sta $1400,x            ; palette<<5 | width field 0 → 32 cells
#   +0x48  E8            inx
#   +0x49  AD 3D 01      lda $013D              ; HPOS = hpos
#   +0x4C  9D 00 14      sta $1400,x
#   +0x4F  E8            inx                    ; entry stride = 5
#   +0x50  AD 3D 01      lda $013D
#   +0x53  18            clc
#   +0x54  69 20         adc #32                ; hpos += 32
#   +0x56  8D 3D 01      sta $013D
#   +0x59  AD 3D 01      lda $013D
#   +0x5C  C9 A0         cmp #$A0               ; five entries (hpos hits 160)
#   +0x5E  D0 CE         bne dp_entry
#   +0x60  E8            inx                    ; x = 26
#   +0x61  A9 00         lda #0
#   +0x63  9D 00 14      sta $1400,x            ; terminator at $141A (mode & 0x5f == 0)
#   ; header chain template copy: ROM $C200..$C4D5 ([0x00,0x14,0x00] x 242)
#   ; → RAM $1420..$16F5 in three passes (256 + 256 + 214 bytes).
#   +0x66  A2 00         ldx #0                 ; p1: $1420..$151F ← ROM $C200
# p1:
#   +0x68  BD 00 C2      lda $C200,x
#   +0x6B  9D 20 14      sta $1420,x
#   +0x6E  E8            inx
#   +0x6F  F0 02         beq p1_done            ; X wrapped to 0 after 256
#   +0x71  D0 F5         bne p1
# p1_done:
#   +0x73  A2 00         ldx #0                 ; p2: $1520..$161F ← ROM $C300
# p2:
#   +0x75  BD 00 C3      lda $C300,x
#   +0x78  9D 20 15      sta $1520,x
#   +0x7B  E8            inx
#   +0x7C  F0 02         beq p2_done
#   +0x7E  D0 F5         bne p2
# p2_done:
#   +0x80  A2 00         ldx #0                 ; p3: $1620..$16F5 ← ROM $C400
# p3:
#   +0x82  BD 00 C4      lda $C400,x
#   +0x85  9D 20 16      sta $1620,x
#   +0x88  E8            inx
#   +0x89  E0 D6         cpx #$D6               ; index == 214?
#   +0x8B  D0 F5         bne p3
#   ; TIA audio channel 0: poly4 tone, frequency 25, volume 32
#   +0x8D  A9 00         lda #0
#   +0x8F  8D 15 00      sta $0015              ; AUDC0 = 0 (no gate, poly4)
#   +0x92  A9 18         lda #$18
#   +0x94  8D 17 00      sta $0017              ; AUDF0 = 24 → frequency 25
#   +0x97  A9 08         lda #8
#   +0x99  8D 19 00      sta $0019              ; AUDV0 = 8 → volume 32
#   ; Maria display: enable (bit 6), read mode 0; DPP → header chain at $1420
#   +0x9C  A9 40         lda #$40
#   +0x9E  8D 3C 00      sta $003C              ; CTRL: display on, rmode 0
#   +0xA1  A9 14         lda #$14
#   +0xA3  8D 2C 00      sta $002C              ; DPPH = 0x14
#   +0xA6  A9 20         lda #$20
#   +0xA8  8D 30 00      sta $0030              ; DPPL = 0x20
# loop:
#   +0xAB  A9 00         lda #0
#   +0xAD  8D 24 00      sta $0024              ; WSYNC (one per scanline)
#   +0xB0  4C AB C0      jmp $C0AB              ; next scanline
#
# The in-cart reset vector at file offsets 0x3FFC/0x3FFD holds the
# little-endian address of $C000 (bytes 0x00, 0xC0). That is the entire boot
# contract: sally_ExecuteRES loads PC from there on every power-on/reset.
#
# Python 3 standard library only (the E2E harness runs on GUI-less CI
# runners with no third-party packages).

import hashlib

ROM_SIZE = 16384             # 16 KiB raw .a78 image (no header)
ENTRY_OFFSET = 0x00          # program entry inside the image (CPU $C000)
ENTRY_ADDR = 0xC000          # CPU address of file offset 0 for a NORMAL cart
RESET_VECTOR_OFFSET = 0x3FFC  # in-cart reset vector (CPU $FFFC/$FFFD, little-endian)
RESET_VECTOR_TARGET = 0xC000

# Register addresses (Equates.h of the vendored core).
WSYNC = 0x0024
AUDC0 = 0x0015
AUDF0 = 0x0017
AUDV0 = 0x0019
COLUBK = 0x0020              # palette base (maria_GetColor indexes from here)
DPPH = 0x002C
DPPL = 0x0030
CTRL = 0x003C

# RAM data regions written by the program at every boot.
PATTERN_ADDR = 0x1000        # 40 pattern bytes ($1000-$1027)
PATTERN_COUNT = 40
DISPLAY_PROG_ADDR = 0x1400   # display program ($1400-$141A)
HEADER_CHAIN_ADDR = 0x1420   # 242 x 3-byte display headers ($1420..$16F5)
# 242 StoreLineRAM calls per frame (scanlines 16..257); the trailing load
# after the last call reads RAM zeros and is never consumed.
HEADER_COUNT = 242

# ROM-resident template for the header chain (file offset 0x200): the 726-byte
# image [0x00, 0x14, 0x00] x 242 that the program copies into RAM in three
# passes (this core's STA abs,X indexes with an 8-bit X).
TEMPLATE_OFFSET = 0x200
TEMPLATE_BYTES = bytes([0x00, 0x14, 0x00]) * HEADER_COUNT   # 726 bytes

# Provenance marker in the never-executed ROM region (file offset 0x100).
PROVENANCE_OFFSET = 0x100
PROVENANCE = b"ROMMULUS E2E A78 - original deterministic ProSystem qualification ROM"

# The program, as (offset, bytes) — every byte is RomMulus-authored. Offsets
# are relative to file offset 0 (CPU $C000); the assembled image is verified
# below against this exact table.
_PROGRAM = (
    (0x00, bytes([0x78])),                  # sei (0x78 in this core's table)
    (0x01, bytes([0xA2, 0x1F])),            # ldx #$1F
    (0x03, bytes([0x9A])),                  # txs
    # palette: COLUBK / P0C1 / P0C2 / P0C3
    (0x04, bytes([0xA9, 0x00])),            # lda #0
    (0x06, bytes([0x8D, 0x20, 0x00])),      # sta $0020 (COLUBK)
    (0x09, bytes([0xA9, 0x18])),            # lda #$18
    (0x0B, bytes([0x8D, 0x21, 0x00])),      # sta $0021 (P0C1)
    (0x0E, bytes([0xA9, 0x6C])),            # lda #$6C
    (0x10, bytes([0x8D, 0x22, 0x00])),      # sta $0022 (P0C2)
    (0x13, bytes([0xA9, 0xF0])),            # lda #$F0
    (0x15, bytes([0x8D, 0x23, 0x00])),      # sta $0023 (P0C3)
    # pattern fill $1000-$1027: byte i = (0x5A + 7*i) mod 256, i = 0..39
    (0x18, bytes([0xA2, 0x00])),            # ldx #0 (pattern index)
    (0x1A, bytes([0xA9, 0x5A])),            # lda #$5A (value accumulator)
    # pat:
    (0x1C, bytes([0x9D, 0x00, 0x10])),      # sta $1000,x (0x9D = STA abs,X here)
    (0x1F, bytes([0xE8])),                  # inx
    (0x20, bytes([0x18])),                  # clc
    (0x21, bytes([0x69, 0x07])),            # adc #7
    (0x23, bytes([0xE0, 0x28])),            # cpx #$28 (index == 40?)
    (0x25, bytes([0xD0, 0xF5])),            # bne pat
    # display program $1400-$141A: five format-B entries (32 cells each,
    # PP = $1000+8k, HPOS = 32k) + terminator at $141A. X = entry byte
    # offset; hpos accumulator in RAM HP=$013D — ABSOLUTE addressing only
    # (zero page cannot reach $013D); PP low byte = hpos/4.
    (0x27, bytes([0xA2, 0x00])),            # ldx #0 (entry byte offset)
    (0x29, bytes([0xA9, 0x00])),            # lda #0
    (0x2B, bytes([0x8D, 0x3D, 0x01])),      # sta $013D (HP = 0)
    # dp_entry:
    (0x2E, bytes([0xAD, 0x3D, 0x01])),      # lda $013D
    (0x31, bytes([0x4A])),                  # lsr a (A = hpos/4 = PP low)
    (0x32, bytes([0x4A])),                  # lsr a
    (0x33, bytes([0x9D, 0x00, 0x14])),      # sta $1400,x (PP low)
    (0x36, bytes([0xE8])),                  # inx
    (0x37, bytes([0xA9, 0x40])),            # lda #$40
    (0x39, bytes([0x9D, 0x00, 0x14])),      # sta $1400,x (MODE = format B)
    (0x3C, bytes([0xE8])),                  # inx
    (0x3D, bytes([0xA9, 0x10])),            # lda #$10
    (0x3F, bytes([0x9D, 0x00, 0x14])),      # sta $1400,x (PP high = 0x10)
    (0x42, bytes([0xE8])),                  # inx
    (0x43, bytes([0xA9, 0x00])),            # lda #0
    (0x45, bytes([0x9D, 0x00, 0x14])),      # sta $1400,x (palette|width: 32 cells)
    (0x48, bytes([0xE8])),                  # inx
    (0x49, bytes([0xAD, 0x3D, 0x01])),      # lda $013D
    (0x4C, bytes([0x9D, 0x00, 0x14])),      # sta $1400,x (HPOS = hpos)
    (0x4F, bytes([0xE8])),                  # inx (entry stride = 5)
    (0x50, bytes([0xAD, 0x3D, 0x01])),      # lda $013D
    (0x53, bytes([0x18])),                  # clc
    (0x54, bytes([0x69, 0x20])),            # adc #32 (hpos += 32)
    (0x56, bytes([0x8D, 0x3D, 0x01])),      # sta $013D
    (0x59, bytes([0xAD, 0x3D, 0x01])),      # lda $013D
    (0x5C, bytes([0xC9, 0xA0])),            # cmp #$A0 (five entries: hpos == 160)
    (0x5E, bytes([0xD0, 0xCE])),            # bne dp_entry
    (0x60, bytes([0xE8])),                  # inx (x = 26)
    (0x61, bytes([0xA9, 0x00])),            # lda #0
    (0x63, bytes([0x9D, 0x00, 0x14])),      # sta $1400,x (terminator at $141A)
    # header chain template copy: ROM $C200..$C4D5 → RAM $1420..$16F5 in
    # three passes (256 + 256 + 214 bytes).
    (0x66, bytes([0xA2, 0x00])),            # ldx #0 (p1: $1420..$151F)
    # p1:
    (0x68, bytes([0xBD, 0x00, 0xC2])),      # lda $C200,x
    (0x6B, bytes([0x9D, 0x20, 0x14])),      # sta $1420,x
    (0x6E, bytes([0xE8])),                  # inx
    (0x6F, bytes([0xF0, 0x02])),            # beq p1_done (X wrapped after 256)
    (0x71, bytes([0xD0, 0xF5])),            # bne p1
    # p1_done:
    (0x73, bytes([0xA2, 0x00])),            # ldx #0 (p2: $1520..$161F)
    # p2:
    (0x75, bytes([0xBD, 0x00, 0xC3])),      # lda $C300,x
    (0x78, bytes([0x9D, 0x20, 0x15])),      # sta $1520,x
    (0x7B, bytes([0xE8])),                  # inx
    (0x7C, bytes([0xF0, 0x02])),            # beq p2_done
    (0x7E, bytes([0xD0, 0xF5])),            # bne p2
    # p2_done:
    (0x80, bytes([0xA2, 0x00])),            # ldx #0 (p3: $1620..$16F5)
    # p3:
    (0x82, bytes([0xBD, 0x00, 0xC4])),      # lda $C400,x
    (0x85, bytes([0x9D, 0x20, 0x16])),      # sta $1620,x
    (0x88, bytes([0xE8])),                  # inx
    (0x89, bytes([0xE0, 0xD6])),            # cpx #$D6 (index == 214?)
    (0x8B, bytes([0xD0, 0xF5])),            # bne p3
    # TIA audio channel 0: poly4 tone, frequency 25, volume 32
    (0x8D, bytes([0xA9, 0x00])),            # lda #0
    (0x8F, bytes([0x8D, 0x15, 0x00])),      # sta $0015 (AUDC0 = 0: poly4 tone)
    (0x92, bytes([0xA9, 0x18])),            # lda #$18
    (0x94, bytes([0x8D, 0x17, 0x00])),      # sta $0017 (AUDF0 = 24 → freq 25)
    (0x97, bytes([0xA9, 0x08])),            # lda #8
    (0x99, bytes([0x8D, 0x19, 0x00])),      # sta $0019 (AUDV0 = 8 → volume 32)
    # Maria display: enable (bit 6), read mode 0; DPP → header chain at $1420
    (0x9C, bytes([0xA9, 0x40])),            # lda #$40
    (0x9E, bytes([0x8D, 0x3C, 0x00])),      # sta $003C (CTRL: display on)
    (0xA1, bytes([0xA9, 0x14])),            # lda #$14
    (0xA3, bytes([0x8D, 0x2C, 0x00])),      # sta $002C (DPPH = 0x14)
    (0xA6, bytes([0xA9, 0x20])),            # lda #$20
    (0xA8, bytes([0x8D, 0x30, 0x00])),      # sta $0030 (DPPL = 0x20)
    # loop:
    (0xAB, bytes([0xA9, 0x00])),            # lda #0
    (0xAD, bytes([0x8D, 0x24, 0x00])),      # sta $0024 (WSYNC, one per scanline)
    (0xB0, bytes([0x4C, 0xAB, 0xC0])),      # jmp $C0AB (next scanline)
)


def generate_rom():
    """Build the 16 KiB .a78 image (fully deterministic; pure function)."""
    rom = bytearray(b"\xFF" * ROM_SIZE)

    # Program at file offset 0 (CPU $C000); rest of the region stays 0xFF.
    for offset, code in _PROGRAM:
        rom[offset:offset + len(code)] = code

    # Provenance marker in the never-executed ROM region.
    rom[PROVENANCE_OFFSET:PROVENANCE_OFFSET + len(PROVENANCE)] = PROVENANCE

    # Header chain template (file offsets 0x200..0x4D5): the program copies
    # it into RAM $1420..$16F5 in three passes at every boot.
    rom[TEMPLATE_OFFSET:TEMPLATE_OFFSET + len(TEMPLATE_BYTES)] = TEMPLATE_BYTES

    # In-cart reset vector at CPU $FFFC/$FFFD (file offsets 0x3FFC/0x3FFD):
    # sally_ExecuteRES loads PC from here on every power-on/reset, so it must
    # be the little-endian address of the entry at $C000.
    rom[RESET_VECTOR_OFFSET] = RESET_VECTOR_TARGET & 0xFF
    rom[RESET_VECTOR_OFFSET + 1] = (RESET_VECTOR_TARGET >> 8) & 0xFF

    rom = bytes(rom)
    _validate(rom)
    return rom


def _validate(rom):
    """Self-checks so a generator regression fails at generation time."""
    if len(rom) != ROM_SIZE:
        raise ValueError("ROM is %d bytes, want %d" % (len(rom), ROM_SIZE))
    # Plain .a78 form: no "ATARI7800" header magic at bytes 1..9 (which would
    # reclassify the cart) and no ">>" CC2 marker at bytes 1..2 (rejected).
    if rom[1:10] == b"ATARI7800":
        raise ValueError("image must NOT carry an ATARI7800 header")
    if rom[1:3] == b">>":
        raise ValueError("image must NOT carry a CC2 marker")
    # Entry: the program starts at file offset 0 with `sei` (0x78 in this
    # core's opcode table — 0x06 is ASL zero-page there).
    if rom[ENTRY_OFFSET] != 0x78:
        raise ValueError("entry at file offset 0 is not 'sei' (0x78)")
    # In-cart reset vector: little-endian $C000 at file offsets 0x3FFC/0x3FFD.
    if rom[RESET_VECTOR_OFFSET:RESET_VECTOR_OFFSET + 2] != bytes(
            [RESET_VECTOR_TARGET & 0xFF, (RESET_VECTOR_TARGET >> 8) & 0xFF]):
        raise ValueError("reset vector at $FFFC must point at the entry at $C000")
    # Every assembled instruction must land where the layout says it does.
    for offset, code in _PROGRAM:
        if rom[offset:offset + len(code)] != code:
            raise ValueError("program bytes drifted at file offset 0x%04X" % offset)
    # Program tail must be the loop's `jmp $C0AB`.
    if rom[0xB0:0xB3] != bytes([0x4C, 0xAB, 0xC0]):
        raise ValueError("program tail must be 'jmp $C0AB' (loop:)")
    # Header chain template present at file offset 0x200.
    if rom[TEMPLATE_OFFSET:TEMPLATE_OFFSET + len(TEMPLATE_BYTES)] != TEMPLATE_BYTES:
        raise ValueError("header chain template drifted at file offset 0x%04X"
                         % TEMPLATE_OFFSET)
    # Provenance marker present in the never-executed ROM region.
    if rom[PROVENANCE_OFFSET:PROVENANCE_OFFSET + len(PROVENANCE)] != PROVENANCE:
        raise ValueError("provenance marker missing at file offset 0x%04X"
                         % PROVENANCE_OFFSET)
    # Everything outside the program, provenance marker, header chain
    # template, and reset vector is 0xFF fill — no third-party bytes anywhere
    # in the image.
    covered = bytearray(ROM_SIZE)
    for offset, code in _PROGRAM:
        covered[offset:offset + len(code)] = b"\x01" * len(code)
    covered[PROVENANCE_OFFSET:PROVENANCE_OFFSET + len(PROVENANCE)] = (
        b"\x01" * len(PROVENANCE))
    covered[TEMPLATE_OFFSET:TEMPLATE_OFFSET + len(TEMPLATE_BYTES)] = (
        b"\x01" * len(TEMPLATE_BYTES))
    covered[RESET_VECTOR_OFFSET:RESET_VECTOR_OFFSET + 2] = b"\x01\x01"
    for i in range(ROM_SIZE):
        if not covered[i] and rom[i] != 0xFF:
            raise ValueError("unexpected non-fill byte at file offset 0x%04X" % i)
    # The program table must be contiguous from the entry to its tail (no gap
    # may leave a 0xFF hole inside the code).
    last = 0
    for offset, code in _PROGRAM:
        if offset != last:
            raise ValueError(
                "program table has a gap at file offset 0x%04X" % offset)
        last = offset + len(code)
    if last != 0xB3:
        raise ValueError("program table must end at file offset 0xB3, ends at 0x%04X" % last)


def rom_sha256():
    """SHA256 hex digest of the generated ROM (pinned in unit tests)."""
    return hashlib.sha256(generate_rom()).hexdigest()


if __name__ == "__main__":
    import sys
    rom = generate_rom()
    print("rom bytes: %d" % len(rom))
    print("sha256:    %s" % hashlib.sha256(rom).hexdigest())
    sys.stdout.flush()
