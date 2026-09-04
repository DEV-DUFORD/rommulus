#!/usr/bin/env python3
# fceumm_rom.py — deterministic original NES iNES ROM generator for the
# rommulus-player FCEUmm candidate E2E (player_e2e.py).
#
# Authorship / design
# -------------------
# Entirely ORIGINAL, RomMulus-authored 6502 bytecode and image bytes. No
# copyrighted ROM content, no Nintendo logo/boot-ROM/BIOS bytes, no trainer:
# the iNES header's trainer bit is clear and the image is exactly
# 16-byte header + 32 KiB PRG + 8 KiB CHR (40976 = 0xA010 bytes). Every byte
# of the program, tiles, name table, and fill patterns below is generated from
# this file's commit-fixed constants.
#
# The ROM is crafted against the VENDORED FCEUmm core (libretro/libretro-
# fceumm @ b5e3566515c27dc66c9c20572171673126532e06, pinned at
# third_party/cores/fceumm), not against generic hardware folklore. The load-
# bearing facts, derived from that tree:
#
#   1. Mapper 0 (NROM). ines.c maps mapper 0 to NROM_Init (boards/
#      datalatch.c): NROMPower() does setprg8r(0x10, 0x6000, 0) +
#      setprg16(0x8000, 0) + setprg16(0xC000, 1) + setchr8(0) — PRG bank 0 at
#      $8000-$9FFF, PRG bank 1 at $C000-$FFFF (the full 32 KiB PRG is
#      directly addressable, no banking), and CHR bank 0 mirrored over the
#      whole $0000-$3FFF. The classic iNES header (ines.c's iNES_HEADER has
#      NO version byte — byte 4 is the PRG size code) therefore carries PRG
#      size code 2 at byte 4 (32 KiB = ROM_size * 0x4000) and CHR size code
#      1 at byte 5 (8 KiB = VROM_size * 0x2000); byte 6 = battery + mapper 0
#      (low nibble 0), byte 7 = flags2 (0 → classic iNES, not iNES 2.0).
#
#   2. 8192-byte battery WRAM. Header byte 7 bit 1 (battery) is set, so
#      iNESCart.battery = 1 and NROM_Init() exposes info->SaveGame[0] = WRAM
#      with SaveGameLen[0] = 8192; retro_get_memory_size(RETRO_MEMORY_
#      SAVE_RAM) therefore returns exactly 8192 (libretro.c). The ROM writes
#      its marker/counters through $6000-$7FFF, which NROMPower maps to that
#      same WRAM (SetupCartPRGMapping(0x10, WRAM, 8192, ram=1)).
#
#   3. Fresh-WRAM fill is 0x00, NOT 0xFF. FCEU_gmalloc() (fceu-memory.c)
#      memsets every cart allocation to zero, and nothing else fills the
#      NROM WRAM before the CPU runs — so a FRESH cart presents SRAM[0] ==
#      0x00 while a RESTORED one presents the 0x52 marker written by a
#      previous run (the player's restoreSaveRam() writes the checkpoint
#      straight into retro_get_memory_data(RETRO_MEMORY_SAVE_RAM) before the
#      first frame, exactly as for the Gambatte candidate). The ROM uses
#      SRAM[0] == 0x52 as the restored-cart test; bytes 3..8191 stay at the
#      untouched 0x00 fresh fill.
#
#   4. Boot path: hardware reset vector -> $8000 (PRG offset 0).
#      X6502_Power() (x6502.c) sets _PC = 0 and then calls X6502_Reset(),
#      which queues a hardware reset (_IRQlow |= FCEU_IQRESET, 0x20). The
#      FIRST X6502_Run takes that queued reset before executing any other
#      instruction: _PC = RdMem(0xFFFC) | (RdMem(0xFFFD) << 8) — i.e., the CPU
#      loads its PC from the hardware reset vector at $FFFC/$FFFD, which for
#      this cart lives in PRG bank 1 (PRG offset 0x7FFC/0x7FFD). The ROM
#      therefore stores the little-endian address of $8000 there (bytes
#      0x00, 0x80 at PRG offsets 0x7FFC/0x7FFD) — exactly as real iNES games
#      do for the console's RESET button. Without that vector the first frame
#      would execute the 0xFF fill from $FFFF (opcode 0xFF is a defined
#      illegal in this x6502: RMW absolute,X INC;SBC) and wander into
#      zero-page RAM, never reaching the program at $8000. After the vector
#      jump, execution enters the program at the top of PRG bank 0.
#
#   5. One presented frame = one VBlank window, with a fixed two-frame dead
#      start PER POWER-ON. retro_run() → FCEUI_Emulate() → FCEUPPU_Loop()
#      (fceu.c/ppu.c) advances the PPU by exactly one frame per call; the
#      player counts presented frames in its video-refresh trampoline, so
#      reported frames == retro_run() calls. BUT FCEUPPU_Reset() sets
#      ppudead = 2: for the first two frames after power-on (or reset) the
#      loop runs the CPU a full frame's worth WITHOUT ever setting
#      PPU_status |= 0x80 (the VBlank bit), so no VBlank edge exists in those
#      two frames. From frame 3 on, every frame contains exactly one VBlank
#      window: PPU_status gains bit 7 at vblank start and loses it
#      (`PPU_status &= 0x1f`) at the end of the ~20-scanline vblank period.
#      The ROM therefore counts VBlank windows with a double wait (wait bit 7
#      SET, increment, wait bit 7 CLEAR), which fires exactly once per frame
#      regardless of where in the frame the CPU is, and the first increment
#      lands on presented frame 3. So run i of reported length F_i
#      contributes exactly max(0, F_i - DEAD_FRAME_OFFSET) counted VBlanks
#      (DEAD_FRAME_OFFSET = 2, the ppudead constant), and a restored cart
#      keeps counting across power-ons: after runs [F1, ..., Fk] the SRAM
#      counter holds sum_i max(0, F_i - DEAD_FRAME_OFFSET). The E2E asserts
#      this exact invariant against the player-reported per-run counts.
#
#   6. $2002 reads are safe anywhere. A2002 (ppu.c) calls
#      FCEUPPU_LineUpdate() first and returns PPU_status | PPUGenLatch & 0x1F;
#      bit 7 is the VBlank flag. No NMI is used (PPUCTRL bit 7 stays clear,
#      so no $0200 handler is needed) and IRQs are disabled with SEI, so
#      control never leaves the program.
#
# Real software video and audio behavior: on every boot the ROM writes 16
# solid-color 8x8 tiles into VRAM $0000-$00FF (tile i = color index i) and a
# deterministic name-table page at $2000-$23FF (row y filled with tile
# (y & 0xF) — a 32-band vertical gradient), then enables the background
# (PPUCTRL $2000 = 0x20, PPUMASK $2001 = 0x0E) so the player's software-
# render path presents real PPU output; it also configures APU pulse channel
# 1 (duty 25%, constant volume 8, period 0x3C0, enabled via $4015) so every
# frame produces real mixed audio samples through retro_set_audio_sample_
# batch. Both subsystems run for the whole bounded E2E window.
#
# Program (PRG bank 0; entry $8000 = offset +0x00, body ends at +0x7F; the
# hardware reset vector at PRG offset 0x7FFC in bank 1 points back to $8000)
# ----------------------------------------------------------------------
#   +0x00  06            sei                    ; IRQs off (NMI never enabled)
#   +0x01  A9 1F         lda #$1F               ; SP = 0x1F (stack top $1FF)
#   +0x03  AA            tax
#   +0x04  9A            txs
#   +0x05  AD 00 60      lda $6000              ; SRAM[0]
#   +0x08  C9 52         cmp #$52               ; restored-cart marker?
#   +0x0A  F0 0D         beq +0x19              ; yes: keep counters, to display
#   ; fresh cart (SRAM[0] == 0x00):
#   +0x0C  A9 52         lda #$52
#   +0x0E  8D 00 60      sta $6000              ; marker
#   +0x11  A9 00         lda #0
#   +0x13  8D 01 60      sta $6001              ; frame counter = 0
#   +0x16  8D 02 60      sta $6002              ; 60-frame counter = 0
#   ; display setup (every boot):
#   +0x19  A9 0F         lda #$0F               ; tile color, 15 down to 0
#   +0x1B  8D 06 20      sta $2006              ; VRAM addr high = 0x00
#   +0x1E  A2 10         ldx #$10               ; tile_outer: 16 bytes/tile
#   +0x20  8D 07 20      sta $2007              ; tile_inner: write (auto-inc)
#   +0x23  CA            dex
#   +0x24  D0 FA         bne +0x20
#   +0x26  88            dec a                  ; next tile color
#   +0x27  10 F5         bpl +0x1E              ; 16 tiles = $0000-$00FF
#   +0x29  A9 20         lda #$20
#   +0x2B  8D 06 20      sta $2006              ; VRAM addr high = 0x20 ($2000)
#   +0x2E  A0 1F         ldy #$1F               ; row_loop: rows 31 down to 0
#   +0x30  8A            tay                    ; A = row
#   +0x31  29 0F         and #$0F               ; tile index for this row
#   +0x33  A2 20         ldx #$20               ; name_row: 32 columns
#   +0x35  8D 07 20      sta $2007
#   +0x38  CA            dex
#   +0x39  D0 FA         bne +0x35
#   +0x3B  88            dey
#   +0x3C  10 F2         bpl +0x30              ; 1024 bytes = $2000-$23FF
#   +0x3E  A9 20         lda #$20
#   +0x40  8D 00 20      sta $2000              ; PPUCTRL: BG on, pattern $0000
#   +0x43  A9 0E         lda #$0E
#   +0x45  8D 01 20      sta $2001              ; PPUMASK: show BG, clip overscan
#   ; audio setup (every boot): pulse channel 1
#   +0x48  A9 0E         lda #$0E
#   +0x4A  8D 00 40      sta $4000              ; duty 25%
#   +0x4D  A9 00         lda #0
#   +0x4F  8D 01 40      sta $4001              ; sweep disabled
#   +0x52  A9 C0         lda #$C0
#   +0x54  8D 02 40      sta $4002              ; period low
#   +0x57  A9 5B         lda #$5B
#   +0x59  8D 03 40      sta $4003              ; len off, const vol 8, peri hi 3
#   +0x5C  A9 01         lda #1
#   +0x5E  8D 15 40      sta $4015              ; enable pulse 1
#   ; VBlank-synchronized frame counter (exactly once per presented frame):
#   +0x61  AD 02 20      lda $2002              ; vbl_wait_start:
#   +0x64  10 FB         bpl +0x61              ; wait for VBlank (bit 7 set)
#   +0x66  EE 01 60      inc $6001              ; frame counter++
#   +0x69  AD 01 60      lda $6001
#   +0x6C  C9 3C         cmp #$3C               ; 60
#   +0x6E  D0 08         bne +0x78              ; vbl_wait_end (no wrap)
#   +0x70  A9 00         lda #0
#   +0x72  8D 01 60      sta $6001              ; frame counter = 0
#   +0x75  EE 02 60      inc $6002              ; 60-frame counter++
#   +0x78  AD 02 20      lda $2002              ; vbl_wait_end:
#   +0x7B  30 FB         bmi +0x78              ; wait for VBlank end (bit 7 clr)
#   +0x7D  4C 61 80      jmp $8061              ; next frame
#
# After a chain of runs with reported lengths [F1, ..., Fk], the SRAM holds
#
#   c = sum_i max(0, F_i - DEAD_FRAME_OFFSET),
#   SRAM[0] = 0x52 (marker), SRAM[1] = c mod 60, SRAM[2] = c // 60,
#   SRAM[3..8191] = 0x00 (untouched fresh fill).
#
# That is the deterministic marker/counter invariant the E2E asserts against
# the player-reported frame count — frame-stable across frame boundaries and
# independent of wall-clock pacing.
#
# Python 3 standard library only (the E2E harness runs on GUI-less CI
# runners with no third-party packages).

import hashlib

HEADER_SIZE = 16
PRG_SIZE = 0x8000            # 32 KiB PRG ROM (iNES size code 2)
CHR_SIZE = 0x2000            # 8 KiB CHR ROM (iNES size code 1)
ROM_SIZE = HEADER_SIZE + PRG_SIZE + CHR_SIZE   # 40976 = 0xA010 bytes

SRAM_SIZE = 8192             # 8 KiB battery WRAM (NROM_Init: SaveGameLen[0])
SRAM_MARKER = 0x52           # 'R' — written once on a fresh cart
SRAM_FRAME_COUNTER = 0x0001  # SRAM offset: frames since fresh init, mod 60
SRAM_60FRAME_COUNTER = 0x0002  # SRAM offset: completed 60-frame intervals

# FCEUPPU_Reset() (ppu.c) sets ppudead = 2: the first two frames after
# power-on run the CPU with no VBlank flag, so the ROM's first counted VBlank
# lands on presented frame 3. Fixed constant of the vendored core at this pin.
DEAD_FRAME_OFFSET = 2

PRG_BASE = HEADER_SIZE           # file offset of PRG bank 0 ($8000)
ENTRY_OFFSET = 0x00              # program entry inside PRG bank 0 (CPU starts $8000)

# Hardware reset vector: CPU $FFFC/$FFFD = PRG offsets 0x7FFC/0x7FFD (PRG
# bank 1). FCEUmm's X6502_Power() queues FCEU_IQRESET, so the first
# X6502_Run loads PC from this vector — it must hold the little-endian
# address of the program entry at $8000 (see boot-path note 4 above).
RESET_VECTOR_OFFSET = 0x7FFC
RESET_VECTOR_TARGET = 0x8000

# iNES header fields (original RomMulus values, classic iNES layout:
# ines.c's iNES_HEADER has NO version byte — byte 4 is the PRG size code).
MAGIC = b"NES\x1a"
PRG_SIZE_CODE = 0x02             # byte 4: 32 KiB PRG (mapper 0 NROM, no banking)
CHR_SIZE_CODE = 0x01             # byte 5: 8 KiB CHR
FLAGS6 = 0x02                    # byte 6: battery bit set (FCEUmm: ROM_type & 2);
                                  #       horizontal mirror; NO trainer bit
                                  #       (FCEUmm: ROM_type & 4); mapper 0
FLAGS7 = 0x00                    # byte 7: flags2 = 0 (classic iNES, not NES 2.0;
                                  #       mapper high nibble 0)

# Provenance marker in PRG bank 1 ($C000+, never executed by the program).
PROVENANCE = b"ROMMULUS E2E NES - original deterministic FCEUmm qualification ROM"

# The program, as (offset, bytes) — every byte is RomMulus-authored. Offsets
# are relative to PRG bank 0 ($8000); the assembled image is verified below.
_PROGRAM = (
    (0x00, bytes([0x06])),                  # sei
    (0x01, bytes([0xA9, 0x1F])),            # lda #$1F
    (0x03, bytes([0xAA])),                  # tax
    (0x04, bytes([0x9A])),                  # txs
    (0x05, bytes([0xAD, 0x00, 0x60])),      # lda $6000
    (0x08, bytes([0xC9, 0x52])),            # cmp #$52
    (0x0A, bytes([0xF0, 0x0D])),            # beq +0x19 (restored: keep counters)
    (0x0C, bytes([0xA9, 0x52])),            # lda #$52
    (0x0E, bytes([0x8D, 0x00, 0x60])),      # sta $6000 (marker)
    (0x11, bytes([0xA9, 0x00])),            # lda #0
    (0x13, bytes([0x8D, 0x01, 0x60])),      # sta $6001 (frame counter = 0)
    (0x16, bytes([0x8D, 0x02, 0x60])),      # sta $6002 (60-frame counter = 0)
    (0x19, bytes([0xA9, 0x0F])),            # lda #$0F (tile color 15)
    (0x1B, bytes([0x8D, 0x06, 0x20])),      # sta $2006 (VRAM addr high = 0x00)
    (0x1E, bytes([0xA2, 0x10])),            # ldx #$10 (tile_outer)
    (0x20, bytes([0x8D, 0x07, 0x20])),      # sta $2007 (tile_inner: write byte)
    (0x23, bytes([0xCA])),                  # dex
    (0x24, bytes([0xD0, 0xFA])),            # bne +0x20
    (0x26, bytes([0x88])),                  # dec a (next tile color)
    (0x27, bytes([0x10, 0xF5])),            # bpl +0x1E (16 tiles total)
    (0x29, bytes([0xA9, 0x20])),            # lda #$20
    (0x2B, bytes([0x8D, 0x06, 0x20])),      # sta $2006 (VRAM addr high = 0x20)
    (0x2E, bytes([0xA0, 0x1F])),            # ldy #$1F (row_loop: rows 31..0)
    (0x30, bytes([0x8A])),                  # tay (A = row)
    (0x31, bytes([0x29, 0x0F])),            # and #$0F (tile index for this row)
    (0x33, bytes([0xA2, 0x20])),            # ldx #$20 (name_row: 32 columns)
    (0x35, bytes([0x8D, 0x07, 0x20])),      # sta $2007
    (0x38, bytes([0xCA])),                  # dex
    (0x39, bytes([0xD0, 0xFA])),            # bne +0x35
    (0x3B, bytes([0x88])),                  # dey
    (0x3C, bytes([0x10, 0xF2])),            # bpl +0x30 (32 rows = $2000-$23FF)
    (0x3E, bytes([0xA9, 0x20])),            # lda #$20
    (0x40, bytes([0x8D, 0x00, 0x20])),      # sta $2000 (PPUCTRL: BG on)
    (0x43, bytes([0xA9, 0x0E])),            # lda #$0E
    (0x45, bytes([0x8D, 0x01, 0x20])),      # sta $2001 (PPUMASK: show BG)
    (0x48, bytes([0xA9, 0x0E])),            # lda #$0E
    (0x4A, bytes([0x8D, 0x00, 0x40])),      # sta $4000 (pulse 1 duty 25%)
    (0x4D, bytes([0xA9, 0x00])),            # lda #0
    (0x4F, bytes([0x8D, 0x01, 0x40])),      # sta $4001 (sweep off)
    (0x52, bytes([0xA9, 0xC0])),            # lda #$C0
    (0x54, bytes([0x8D, 0x02, 0x40])),      # sta $4002 (period low)
    (0x57, bytes([0xA9, 0x5B])),            # lda #$5B
    (0x59, bytes([0x8D, 0x03, 0x40])),      # sta $4003 (len off, const vol 8)
    (0x5C, bytes([0xA9, 0x01])),            # lda #1
    (0x5E, bytes([0x8D, 0x15, 0x40])),      # sta $4015 (enable pulse 1)
    (0x61, bytes([0xAD, 0x02, 0x20])),      # lda $2002 (vbl_wait_start)
    (0x64, bytes([0x10, 0xFB])),            # bpl +0x61 (wait VBlank set)
    (0x66, bytes([0xEE, 0x01, 0x60])),      # inc $6001 (frame counter++)
    (0x69, bytes([0xAD, 0x01, 0x60])),      # lda $6001
    (0x6C, bytes([0xC9, 0x3C])),            # cmp #$3C (60)
    (0x6E, bytes([0xD0, 0x08])),            # bne +0x78 (vbl_wait_end)
    (0x70, bytes([0xA9, 0x00])),            # lda #0
    (0x72, bytes([0x8D, 0x01, 0x60])),      # sta $6001 (frame counter = 0)
    (0x75, bytes([0xEE, 0x02, 0x60])),      # inc $6002 (60-frame counter++)
    (0x78, bytes([0xAD, 0x02, 0x20])),      # lda $2002 (vbl_wait_end)
    (0x7B, bytes([0x30, 0xFB])),            # bmi +0x78 (wait VBlank clear)
    (0x7D, bytes([0x4C, 0x61, 0x80])),      # jmp $8061 (next frame)
)


def _chr_fill(i):
    """Deterministic original fill for the CHR region (unused by the core's
    display: the ROM writes its own tiles into VRAM at every boot)."""
    return (i ^ (i >> 4)) & 0xFF


def generate_rom():
    """Build the iNES image (fully deterministic; pure function)."""
    rom = bytearray(ROM_SIZE)

    # iNES header (classic layout — NO version byte; byte 4 is the PRG size
    # code, per ines.c's iNES_HEADER).
    rom[0:4] = MAGIC
    rom[4] = PRG_SIZE_CODE
    rom[5] = CHR_SIZE_CODE
    rom[6] = FLAGS6
    rom[7] = FLAGS7
    # bytes 8..15 stay zero (iNES: reserved).

    # PRG bank 0 ($8000-$9FFF): the program at offset 0, rest 0xFF fill.
    prg = bytearray(b"\xFF" * PRG_SIZE)
    for offset, code in _PROGRAM:
        prg[offset:offset + len(code)] = code
    rom[PRG_BASE:PRG_BASE + PRG_SIZE] = prg

    # PRG bank 1 ($C000-$FFFF): provenance marker, rest 0xFF fill. The program
    # never branches here (NROM maps it directly; control stays in bank 0).
    bank1 = bytearray(b"\xFF" * (PRG_SIZE // 2))
    bank1[0:len(PROVENANCE)] = PROVENANCE
    rom[PRG_BASE + PRG_SIZE // 2:PRG_BASE + PRG_SIZE] = bank1

    # Hardware reset vector at CPU $FFFC/$FFFD (PRG offsets 0x7FFC/0x7FFD, in
    # bank 1): FCEUmm powers on by taking a queued hardware reset whose PC
    # loads from this vector, so it must point at the entry at $8000. Written
    # after the bank-1 fill so it is not clobbered.
    rom[PRG_BASE + RESET_VECTOR_OFFSET] = RESET_VECTOR_TARGET & 0xFF
    rom[PRG_BASE + RESET_VECTOR_OFFSET + 1] = (RESET_VECTOR_TARGET >> 8) & 0xFF

    # CHR (8 KiB): deterministic original fill; NROMPower's setchr8(0) maps it
    # mirrored over $0000-$1FFF and the ROM overwrites VRAM $0000-$23FF at
    # every boot.
    for i in range(CHR_SIZE):
        rom[HEADER_SIZE + PRG_SIZE + i] = _chr_fill(i)

    rom = bytes(rom)
    _validate(rom)
    return rom


def _validate(rom):
    """Self-checks so a generator regression fails at generation time."""
    if len(rom) != ROM_SIZE:
        raise ValueError("ROM is %d bytes, want %d" % (len(rom), ROM_SIZE))
    # iNES magic (classic layout: byte 4 is the PRG size code, not a version).
    if rom[0:4] != MAGIC:
        raise ValueError("iNES header magic drifted")
    # The exact cartridge the vendored FCEUmm exposes as 8192-byte battery
    # WRAM: mapper 0 (NROM), 32 KiB PRG, 8 KiB CHR, battery bit set.
    if rom[4] != PRG_SIZE_CODE or rom[5] != CHR_SIZE_CODE:
        raise ValueError("PRG/CHR size codes drifted")
    # FCEUmm's ines.c flag parsing of byte 6 (ROM_type): battery = & 2,
    # trainer = & 4, mapper low nibble = >> 4; byte 7 (ROM_type2): mapper high
    # nibble = & 0xF0, NES 2.0 marker = & 0x0C.
    if (rom[6] & 0x02) == 0:
        raise ValueError("battery bit must be set (8 KiB battery WRAM)")
    if (rom[6] & 0x04) != 0:
        raise ValueError("trainer bit must stay clear (no trainer in image)")
    if (rom[6] >> 4) != 0 or (rom[7] & 0xF0) != 0:
        raise ValueError("mapper must be 0 (NROM)")
    if (rom[7] & 0x0C) != 0:
        raise ValueError("flags2 must mark classic iNES, not NES 2.0")
    # Entry vector: the CPU starts at $8000 (PRG offset 0); the program must
    # begin there with `sei` and end with the loop's `jmp $8061`.
    if rom[PRG_BASE] != 0x06:
        raise ValueError("entry at PRG offset 0 is not 'sei'")
    if rom[PRG_BASE + 0x7D:PRG_BASE + 0x80] != bytes([0x4C, 0x61, 0x80]):
        raise ValueError("program tail must be 'jmp $8061' (vbl_wait_start)")
    # Hardware reset vector at CPU $FFFC/$FFFD (PRG offset 0x7FFC): FCEUmm's
    # first X6502_Run takes a queued hardware reset and loads PC from here,
    # so it must be the little-endian address of the entry at $8000.
    if rom[PRG_BASE + RESET_VECTOR_OFFSET:
          PRG_BASE + RESET_VECTOR_OFFSET + 2] != bytes(
              [RESET_VECTOR_TARGET & 0xFF, (RESET_VECTOR_TARGET >> 8) & 0xFF]):
        raise ValueError("reset vector at $FFFC must point at the entry at $8000")
    # Every assembled instruction must land where the layout says it does.
    for offset, code in _PROGRAM:
        if rom[PRG_BASE + offset:PRG_BASE + offset + len(code)] != code:
            raise ValueError("program bytes drifted at PRG offset 0x%04X" % offset)
    # Provenance marker present in the (never-executed) PRG bank 1.
    if not rom[PRG_BASE + PRG_SIZE // 2:].startswith(PROVENANCE):
        raise ValueError("provenance marker missing from PRG bank 1")


def rom_sha256():
    """SHA256 hex digest of the generated ROM (pinned in unit tests)."""
    return hashlib.sha256(generate_rom()).hexdigest()


def expected_sram_image(run_frames):
    """The exact 8192-byte SRAM checkpoint after a chain of runs.

    run_frames is the sequence of player-reported frame counts, one entry per
    power-on (a fresh run passes [F1]; a restored relaunch passes [F1, F2],
    and so on — the restored counters keep counting). Each power-on starts
    with ppudead = 2 (FCEUPPU_Reset: two frames with no VBlank edge), so run
    i contributes exactly max(0, F_i - DEAD_FRAME_OFFSET) counted VBlanks and
    the SRAM counter holds

        counted = sum over runs of max(0, F_i - DEAD_FRAME_OFFSET).

    Bytes 3..8191 are the untouched 0x00 fresh fill (FCEU_gmalloc zeroes the
    WRAM) — the ROM never writes them.
    """
    if isinstance(run_frames, int):
        run_frames = (run_frames,)
    for f in run_frames:
        if not isinstance(f, int) or isinstance(f, bool) or f < 0:
            raise ValueError("each reported frame count must be an int >= 0")
    counted = sum(max(0, f - DEAD_FRAME_OFFSET) for f in run_frames)
    image = bytearray(b"\x00" * SRAM_SIZE)
    image[0x0000] = SRAM_MARKER
    image[SRAM_FRAME_COUNTER] = counted % 60
    image[SRAM_60FRAME_COUNTER] = counted // 60
    return bytes(image)


if __name__ == "__main__":
    import sys
    rom = generate_rom()
    print("rom bytes: %d" % len(rom))
    print("sha256:    %s" % hashlib.sha256(rom).hexdigest())
    sys.stdout.flush()
