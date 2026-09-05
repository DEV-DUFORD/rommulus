#!/usr/bin/env python3
# wswan_rom.py — deterministic original WonderSwan / WonderSwan Color ROM
# generator for the rommulus-player mednafen_wswan candidate E2E
# (player_e2e.py).
#
# Authorship / design
# -------------------
# Entirely ORIGINAL, RomMulus-authored NEC V30 bytecode and image bytes. No
# copyrighted ROM content, no Bandai BIOS/boot-ROM bytes, no third-party
# bytes: the image is exactly 512 KiB (0x80000) of raw cartridge data — the
# plain .ws/.wsc form mednafen_wswan's Load() accepts. Every byte of the
# program, fills, and cart-header fields below is generated from this file's
# commit-fixed constants.
#
# The ROM is crafted against the VENDORED Beetle WonderSwan core
# (libretro/beetle-wswan-libretro @ 4b01295838ea89e3f1355bbe4cb5cf98aa6108cd,
# pinned at third_party/cores/mednafen_wswan), not against generic hardware
# folklore. The load-bearing facts, derived from that tree:
#
#   1. Load contract (libretro.c Load()): the image must be >= 64 KiB; it is
#      right-aligned into a power-of-two wsCartROM allocation padded with
#      0xFF (a 512 KiB image needs no padding). The cart header is the LAST
#      10 bytes of the padded image: header[5] selects battery SRAM size
#      (0x01 -> 8 KiB, 0x02 -> 32 KiB, 0x03 -> 128 KiB, 0x04 -> 256 KiB,
#      0x05 -> 512 KiB; other values -> none). NO checksum is enforced for
#      normal carts — the only CRC checks are the 512 KiB WonderWitch
#      firmware detection ("ELISA" signature at bank-F offset 0 + footer
#      CRC32 0x0d05ed64) and the Detective Conan special case (header[8..9]
#      == 0xE18D && header[0] == 0x01 && header[2] == 0x27); this layout
#      avoids both by construction. With header[5] = 0x01 the core exposes
#      retro_get_memory_size(RETRO_MEMORY_SAVE_RAM) == 8192 and
#      retro_get_memory_data returns wsSRAM, which WSwan_MemoryInit()
#      zero-fills at every load — a fresh cart therefore reads SRAM[0] == 0.
#
#   2. V30 reset fetch (v30mz.c + libretro.c Reset()): v30mz_reset() zeroes
#      the CPU and sets PS = 0xFFFF, PC = 0; Reset() then sets SS = 0,
#      SP = 0x2000. Instruction fetches read physical (PS<<4)+PC, so the
#      FIRST fetch is physical 0xFFFF0 — the NEC V30's FFFF:0000 reset
#      vector (unlike x86's FFFF:FFFF). With DS = 0 and SS = 0, data
#      references use bank 0 (the 64 KiB wsRAM) unless a segment override is
#      loaded. After the startio[] port defaults are written at reset,
#      BankSelector[0] = 0x2F&0xF = 0xF, so every ROM-bank read (banks 2..F)
#      maps to the LAST 64 KiB bank of the image — physical 0xFFFF0 is
#      image offset 0x7FFF0 for this 512 KiB cart. The reset stub therefore
#      lives in the first 5 bytes of the final 16-byte window and jumps to
#      the main code earlier in the same bank.
#
#   3. Memory map (wswan-memory.c ReadMemCore/WriteMemCore): bank 0 = wsRAM
#      (64 KiB); bank 1 = cartridge SRAM window — physical 0x10000+offset
#      reads/writes wsSRAM[(offset | BankSelector[1]<<16) & (sram_size-1)],
#      so with 8 KiB SRAM the high bits are masked away and ANY offset in
#      bank 1 addresses wsSRAM[offset & 0x1FFF]. A 16-bit register can only
#      produce offsets < 0x10000, which under DS = 0 stay in bank 0 — so to
#      reach SRAM the program loads DS = 0x1000 (via push ax / pop ds; x86
#      has no direct segment-load immediate) so that DefaultBase(DS) =
#      DS<<4 = 0x10000 and [si]/[bx] with small offsets land in wsSRAM. No port writes are needed: the reset
#      defaults already map code to the last bank. Side-effect checks that
#      keep the run deterministic (wswan-memory.c WriteMemCore + sound.c):
#      the only wsRAM write is the stack word at 0x1FFE/0x1FFF from
#      `push ax` — (0x1FFE >> 6) = 0x7F != SampleRAMPos (0), so
#      WSwan_SoundCheckRAMWrite never fires, and 0x1FFE < 0xFE00 so no WSC
#      palette RAM write is triggered. The counter/output live in SRAM,
#      which has no such side channels.
#
#   4. Reset side state that keeps the run deterministic (start.inc +
#      interrupt.c + comm.c + sound.c): startio[0xB2] = 0x00 -> IEnable = 0,
#      so WSwan_InterruptCheck() is a permanent no-op even though VBLANK
#      asserts every frame (the program never enables interrupts);
#      startio[0x48] = startio[0x52] = 0x00 -> both DMA engines idle;
#      Comm_Process() with Control = 0 is a no-op. The ROM touches no I/O
#      ports at all after reset, so none of this state can feed back into
#      CPU timing.
#
#   5. Frame budget (gfx.c wsExecuteLine + libretro.c Emulate()): one
#      retro_run() renders lines until wsLine reaches 144; each line costs
#      exactly 128+96+32 = 256 V30 cycles (v30mz_execute calls). GfxReset
#      sets wsLine = 0 and LCDVtotal = 158 (restored by startio[0x16] =
#      0x9E), so wsLine wraps modulo 159: the FIRST frame of every power-on
#      runs lines 0..144 = 145 lines = 37120 cycles, and EVERY subsequent
#      frame runs lines 145..158,0..144 = 159 lines = 40704 cycles. The
#      player never calls retro_reset() — each power-on is a fresh
#      retro_load_game(), so every run starts in exactly this state.
#
#   6. OPCODE COST TABLE OF THIS CORE (v30mz.c): the cycle counts below are
#      the core's own model (CLK values; prefetch/bus-wait time is not
#      emulated), verified per opcode:
#        EA d32 jmp far    = 7
#        BE/BB/B8 imm16    = 1   (immediate FETCHes cost no cycles here)
#        50 push ax        = 1
#        1F pop ds         = 3
#        FE /4 inc [si]    = 3   (CLKM(3,1), memory form; ModRM 0x04!)
#        0x40   inc ax     = 1
#        8A /4 mov al,[si] = 1
#        88 /3 mov [bx],al = 1
#        EB d8  jmp short  = 4   (relative to the byte AFTER the operand)
#      NOTE on ModRM encodings (v30mz.c GetEA table): mod=00/rm=110 is the
#      [disp16] form (EA_006 does TWO FETCHes), NOT a register — SI is
#      mod=00/rm=100 (ModRM 0x04) and BX is mod=00/rm=111 (ModRM 0x07);
#      rm=011 (ModRM 0x03) is [BP+DI] with the SS base, not [BX]. The loop
#      is exactly 12 cycles per iteration; the one-shot stub + setup
#      prologue is exactly 14. NOTE on segment bases (v30mz.c DefaultBase):
#      base = seg<<4, so reaching the bank-1 SRAM window at physical
#      0x10000 requires DS = 0x1000 (NOT 1).
#
#   7. THE ORACLE (derived from 5+6 by simulating the core's exact ICount
#      semantics and VERIFIED against the real vendored core — write counts
#      to SRAM[0x100]/SRAM[0] matched for F in {1,2,3,10,60,180,240}):
#      v30mz_execute() adds a chunk to ICount and runs instructions while
#      ICount > 0 (each may overrun). Crucially the instruction stream is
#      CONTINUOUS across chunks: a scanline's three v30mz_execute(128/96/32)
#      calls can each end mid loop-iteration, and the next chunk resumes at
#      that position (a naive per-frame or per-chunk restart model gets the
#      counts wrong). The loop increments a byte counter at SRAM[0x100] and
#      mirrors it into SRAM[0] every iteration; because the counter LIVES IN
#      SRAM, restored saves keep counting across power-ons automatically (a
#      fresh cart's zero-filled wsSRAM starts it at 0). Per power-on of F
#      presented frames (F >= 1): the first frame executes exactly 3093
#      increments and each of the remaining F-1 frames exactly 3392, i.e.
#          run_iterations(F) = 3392*F - 299
#      Every run ends with ICount = -1 (a fixed point across steady frames),
#      and every run — including multi-frame ones — ends mid-iteration right
#      after an increment WITHOUT its mirror write, so at the end of a run:
#        SRAM[0x100] = total-increments mod 256;
#        SRAM[0]     = (total-increments - 1) mod 256.
#      Over a save chain of runs [F_1..F_n] with total S = sum(3392*F_i -
#      299): SRAM[0x100] = S mod 256 and SRAM[0] = (S-1) mod 256. Bytes
#      other than 0 and 0x100 stay 0 — nothing else ever writes wsSRAM.
#      _validate() re-derives all of this with a closed-form frame step plus
#      a brute-force per-instruction cross-check, so the oracle is exact, not
#      bounded.
#
# Program (image offsets; bank F = image offset 0x70000 + bank-F offset)
# ----------------------------------------------------------------------
#   Reset stub @ image 0x7FFF0 (physical 0xFFFF0, the reset fetch point):
#     EA 00 00 10 F0    jmp far 0x0F010:0x0000 -> physical 0xF0100
#   Main code @ image 0x70100 (physical 0xF0100):
#     BE 00 01        mov si,#0x0100      ; counter at SRAM[0x100] (DS=1)
#     BB 00 00        mov bx,#0x0000      ; output at SRAM[0]       (DS=1)
#     B8 00 10        mov ax,#0x1000
#     50              push ax             ; stack word in wsRAM 0x1FFE
#     1F              pop ds              ; DS=0x1000 -> base 0x10000 (bank 1)
#   Loop @ image 0x7010B (physical 0xF010B):
#     FE 04           inc [si]            ; counter++          (3 cycles)
#     40              inc ax              ; cycle padding      (1)
#     40              inc ax              ; cycle padding      (1)
#     40              inc ax              ; cycle padding      (1)
#     8A 04           mov al,[si]         ; mirror             (1)
#     88 07           mov [bx],al         ; SRAM[0] = counter  (1)
#     EB F5           jmp $-11            ; loop               (4)
#
# Real software video and audio behavior (a property of this construction,
# NOT something the E2E gate inspects): the core renders its default
# zero-state WSC frame (blank 224x144 field through the real gfx path) and
# flushes its Blip_Buffer audio every retro_run(), so every presented frame
# carries real rendered video and a real (silent) audio batch for the whole
# bounded E2E window. The E2E gate itself asserts lifecycle, bounded
# presented-frame counts, result schema, and the SRAM invariants above.
#
# Python 3 standard library only (the E2E harness runs on GUI-less CI
# runners with no third-party packages).

import hashlib

ROM_SIZE = 0x80000            # 512 KiB raw .ws/.wsc image (8 x 64 KiB banks)
SRAM_SIZE = 8192              # battery SRAM: cart header code 0x01 (header[5])

# --- the program (every byte RomMulus-authored) ------------------------------
# Reset stub: 5 bytes, 7 core-model cycles. The 11 bytes after it
# (image 0x7FFF5..0x7FFFF) are free fill; header[5] at 0x7FFFB is set to the
# 8 KiB SRAM code in generate_rom() — see _validate() for the cross-checks.
STUB = bytes([
    0xEA,               # jmp far:
    0x00, 0x00,         #   IP = 0x0000
    0x10, 0xF0,         #   CS = 0x0F010 -> physical (0x0F010<<4)|0 = 0xF0100
])

# Main code: 10 bytes, 7 core-model cycles (BE/BB/B8 = 1 each, push = 1,
# pop ds = 3). Loads DS = 0x1000 (segment base = DS<<4 = 0x10000) so the
# loop's [si]/[bx] references address bank 1 (the SRAM window) with small
# 16-bit offsets.
MAIN_CODE = bytes([
    0xBE, 0x00, 0x01,   # mov si,#0x0100
    0xBB, 0x00, 0x00,   # mov bx,#0x0000
    0xB8, 0x00, 0x10,   # mov ax,#0x1000
    0x50,               # push ax
    0x1F,               # pop ds      (DS = 0x1000 -> base DS<<4 = 0x10000)
])

# Loop: 11 bytes, exactly 12 core-model cycles per iteration (12 | 40704).
# ModRM 0x04 = mod=00/rm=100 = [si]; ModRM 0x07 = mod=00/rm=111 = [bx].
LOOP = bytes([
    0xFE, 0x04,         # inc [si]      (3)   -> wsSRAM[0x100]++
    0x40,               # inc ax        (1)
    0x40,               # inc ax        (1)
    0x40,               # inc ax        (1)
    0x8A, 0x04,         # mov al,[si]   (1)
    0x88, 0x07,         # mov [bx],al   (1)  -> wsSRAM[0]
    0xEB,               # jmp short:
    0xF5,               #   rel -11 (relative to the byte after this operand)
])

# --- layout -----------------------------------------------------------------
BANK_F_BASE = 0x70000         # last 64 KiB bank of the image
STUB_OFFSET = ROM_SIZE - 16   # 0x7FFF0 (physical 0xFFFF0, reset fetch)
MAIN_OFFSET = BANK_F_BASE + 0x0100     # main code (physical 0xF0100)
LOOP_OFFSET = MAIN_OFFSET + len(MAIN_CODE)   # 0x7010B (physical 0xF010B)
SRAM_CODE_OFFSET = ROM_SIZE - 5      # 0x7FFFB == header[5] (cart header)
COUNTER_OFFSET = 0x100               # byte counter inside wsSRAM
PROVENANCE_OFFSET = BANK_F_BASE + 0x0200
PROVENANCE = b"ROMMULUS E2E WSWAN - original deterministic WonderSwan qualification ROM"

# --- oracle constants (derived; see header facts 5+6+7) ----------------------
CYCLES_PER_LINE = 256                    # v30mz_execute(128)+96+32 per line
FIRST_FRAME_LINES = 145                  # wsLine 0..144 (GfxReset: wsLine=0)
STEADY_FRAME_LINES = 159                 # LCDVtotal=158 -> wrap modulo 159
FIRST_FRAME_CYCLES = FIRST_FRAME_LINES * CYCLES_PER_LINE    # 37120
STEADY_FRAME_CYCLES = STEADY_FRAME_LINES * CYCLES_PER_LINE  # 40704
LOOP_COST = 12                           # cycles per loop iteration
STUB_COST = 7                            # EA jmp far
MAIN_CODE_COST = 1 + 1 + 1 + 1 + 3       # BE/BB/B8/push/pop-ds = 7
PROLOGUE_COST = STUB_COST + MAIN_CODE_COST   # one-shot, first frame only
# Per-instruction cost streams (v30mz_execute granularity: an instruction
# runs whenever ICount > 0 and may overrun the budget). The CPU's instruction
# stream is CONTINUOUS across v30mz_execute() calls: when a chunk ends mid
# loop-iteration, the next chunk resumes at that same position (verified
# against the real core with per-chunk ICount traces — see fact 7).
STUB_INSTRUCTION_COSTS = (7,)
MAIN_CODE_INSTRUCTION_COSTS = (1, 1, 1, 1, 3)
LOOP_INSTRUCTION_COSTS = (3, 1, 1, 1, 1, 1, 4)   # FE, 40, 40, 40, 8A, 88, EB
PROLOGUE_INSTRUCTION_COSTS = STUB_INSTRUCTION_COSTS + MAIN_CODE_INSTRUCTION_COSTS
LOOP_FE_INDEX = 0            # inc [si] position within the loop
LOOP_MIRROR_INDEX = 5        # mov [bx],al position within the loop
LINE_CHUNKS = (128, 96, 32)  # the three v30mz_execute() calls per scanline
ITERATIONS_PER_STEADY_FRAME = STEADY_FRAME_CYCLES // LOOP_COST   # 3392
# First frame of every power-on executes exactly 3093 increments.
FIRST_RUN_ITERATIONS = 3093
# Per-power-on contribution: iterations for F presented frames (F >= 1).
PER_POWERON_OVERHEAD = ITERATIONS_PER_STEADY_FRAME - FIRST_RUN_ITERATIONS  # 299
_oracle_sim_checked = False   # memoizes the expensive simulation cross-check


def run_iterations(frames):
    """Loop increments executed by one power-on of `frames` presented frames.

    Exact (not bounded): the first frame runs 3093 increments and every later
    frame exactly 3392 — verified against the real core for F in {1, 2, 3,
    10, 60, 180, 240} by _simulate_poweron (run from _validate). Requires
    frames >= 1 (the E2E bounds guarantee this).
    """
    if frames < 1:
        raise ValueError("wswan oracle requires at least one presented frame")
    return FIRST_RUN_ITERATIONS + ITERATIONS_PER_STEADY_FRAME * (frames - 1)


def expected_sram_image(run_frames):
    """The exact 8192-byte wsSRAM image after the given run chain.

    `run_frames` is either one int (a single power-on) or the list of
    player-reported frame counts for every power-on in the save chain
    (the counter lives IN SRAM, so restored saves keep counting). The byte
    counter sits at SRAM[0x100] and SRAM[0] mirrors it once per completed
    loop iteration. Every run ends mid-iteration right after an increment
    WITHOUT its mirror write (the end-of-frame ICount residue -1 is a fixed
    point that always leaves the final increment unmirrored — verified
    against the real core), so after a chain with total S = sum(3392*F_i -
    299) increments: SRAM[0x100] = S mod 256 and SRAM[0] = (S-1) mod 256.
    All other bytes stay 0: nothing else ever writes wsSRAM.
    """
    if isinstance(run_frames, int):
        runs = [run_frames]
    else:
        runs = list(run_frames)
    total = sum(run_iterations(f) for f in runs)
    image = bytearray(SRAM_SIZE)
    image[COUNTER_OFFSET] = total % 256
    image[0] = (total - 1) % 256
    return bytes(image)


def _simulate_poweron(frames):
    """Exact simulation of one power-on under the core's real semantics.

    Models v30mz_execute() exactly: each scanline adds three chunks (128+96+
    32 cycles) to ICount, and instructions run while ICount > 0 (each may
    overrun). The instruction stream is continuous across chunk boundaries —
    a chunk ending mid-iteration resumes at that position in the next chunk.
    Returns (increments, icount_end, mirror_synced), where mirror_synced says
    whether the final executed loop instruction was the mirror write or later
    within its iteration (i.e. whether SRAM[0] equals the counter).
    """
    ic = 0
    pos = 0                    # position in the continuous instruction stream
    incs = 0
    synced = False
    prologue_len = len(PROLOGUE_INSTRUCTION_COSTS)
    loop_len = len(LOOP_INSTRUCTION_COSTS)
    for f in range(frames):
        lines = FIRST_FRAME_LINES if f == 0 else STEADY_FRAME_LINES
        for _line in range(lines):
            for chunk in LINE_CHUNKS:
                ic += chunk
                while ic > 0:
                    if pos < prologue_len:
                        ic -= PROLOGUE_INSTRUCTION_COSTS[pos]
                        pos += 1
                        continue
                    li = (pos - prologue_len) % loop_len
                    if li == 0 and ic > LOOP_COST:
                        # Bulk-skip one complete iteration (safe: an iteration
                        # starting with ICount > 12 always finishes all 7
                        # instructions, ending after the mirror).
                        ic -= LOOP_COST
                        pos += loop_len
                        incs += 1
                        synced = True
                        continue
                    ic -= LOOP_INSTRUCTION_COSTS[li]
                    if li == LOOP_FE_INDEX:
                        incs += 1
                        synced = False
                    elif li >= LOOP_MIRROR_INDEX:
                        synced = True
                    pos += 1
    return incs, ic, synced


def generate_rom():
    """Build the 512 KiB .ws/.wsc image (fully deterministic; pure function)."""
    rom = bytearray(b"\x00" * ROM_SIZE)

    rom[STUB_OFFSET:STUB_OFFSET + len(STUB)] = STUB
    rom[MAIN_OFFSET:MAIN_OFFSET + len(MAIN_CODE)] = MAIN_CODE
    rom[LOOP_OFFSET:LOOP_OFFSET + len(LOOP)] = LOOP
    rom[PROVENANCE_OFFSET:PROVENANCE_OFFSET + len(PROVENANCE)] = PROVENANCE

    # Cart header SRAM code (header[5] == image 0x7FFFB): 8 KiB battery SRAM.
    rom[SRAM_CODE_OFFSET] = 0x01

    rom = bytes(rom)
    _validate(rom)
    return rom


def _validate(rom):
    """Self-checks so a generator regression fails at generation time."""
    if len(rom) != ROM_SIZE:
        raise ValueError("ROM is %d bytes, want %d" % (len(rom), ROM_SIZE))

    # Cart header (last 10 bytes of the image): header[5] must select 8 KiB
    # battery SRAM. The stub occupies 0x7FFF0..0x7FFF4, so header[5] at
    # 0x7FFFB sits in free fill and is set explicitly by generate_rom().
    header = rom[ROM_SIZE - 10:ROM_SIZE]
    if header[5] != 0x01:
        raise ValueError("cart header SRAM code at image offset 0x%05X is %02X, want 0x01"
                         % (ROM_SIZE - 10 + 5, header[5]))
    # Detective Conan special case must NOT trigger (it would patch the ROM).
    if (header[8] | (header[9] << 8)) == 0x8DE1 and header[0] == 0x01 \
            and header[2] == 0x27:
        raise ValueError("image must not match the Detective Conan special case")
    # WonderWitch firmware detection must NOT trigger (512 KiB + "ELISA" at
    # bank-F base + footer CRC).
    if rom[BANK_F_BASE:BANK_F_BASE + 5] == b"ELISA":
        raise ValueError("image must not carry the WonderWitch ELISA signature")

    # Program placement.
    if rom[STUB_OFFSET:STUB_OFFSET + len(STUB)] != STUB:
        raise ValueError("reset stub drifted at image offset 0x%05X" % STUB_OFFSET)
    if rom[MAIN_OFFSET:MAIN_OFFSET + len(MAIN_CODE)] != MAIN_CODE:
        raise ValueError("main code drifted at image offset 0x%05X" % MAIN_OFFSET)
    if rom[LOOP_OFFSET:LOOP_OFFSET + len(LOOP)] != LOOP:
        raise ValueError("loop bytes drifted at image offset 0x%05X" % LOOP_OFFSET)

    # The loop must sit exactly where MAIN_CODE leaves off (the far jump
    # targets the main code; the prologue flows straight into the loop).
    if LOOP_OFFSET != MAIN_OFFSET + len(MAIN_CODE):
        raise ValueError("loop does not immediately follow the main code")

    # Far-jump target arithmetic: (CS<<4)|IP must be the main code's physical
    # address, i.e. bank-F base + its in-bank offset. The EA opcode is stub
    # byte 0; IP_lo/IP_hi/CS_lo/CS_hi are bytes 1..4.
    ip = STUB[1] | (STUB[2] << 8)
    cs = STUB[3] | (STUB[4] << 8)
    main_physical = 0xF0000 + (MAIN_OFFSET - BANK_F_BASE)
    if ((cs << 4) + ip) & 0xFFFFF != main_physical:
        raise ValueError("far jump target 0x%05X != main code physical address 0x%05X"
                         % (((cs << 4) + ip) & 0xFFFFF, main_physical))

    # Loop-back arithmetic: EB rel8 is relative to the byte AFTER the operand
    # (the core's i_jmp_d8 applies the offset to the already-advanced PC).
    loop_in_bank = LOOP_OFFSET - BANK_F_BASE
    jmp_rel = LOOP[10]
    if jmp_rel > 127:
        jmp_rel -= 256
    pc_after_operand = loop_in_bank + len(LOOP)
    if (pc_after_operand + jmp_rel) & 0xFFFF != loop_in_bank:
        raise ValueError("loop jmp rel %d does not return to the loop start" % jmp_rel)

    # ModRM sanity: the loop must use the register forms [si] (0x04) and
    # [bx] (0x03), never the mod=00/rm=110 [disp16] form (0x06), which would
    # swallow two displacement bytes and derail execution.
    if LOOP[1] != 0x04 or LOOP[6] != 0x04 or LOOP[8] != 0x07:
        raise ValueError("loop ModRM bytes drifted (want FE/4, 8A/4, 88/7)")

    # Provenance marker present in the never-executed ROM region.
    if rom[PROVENANCE_OFFSET:PROVENANCE_OFFSET + len(PROVENANCE)] != PROVENANCE:
        raise ValueError("provenance marker missing at image offset 0x%05X"
                         % PROVENANCE_OFFSET)

    # Everything outside the stub, main code, loop, provenance marker, and
    # the one header byte is 0x00 fill — no third-party bytes anywhere.
    covered = bytearray(ROM_SIZE)
    for off, blob in ((STUB_OFFSET, STUB), (MAIN_OFFSET, MAIN_CODE),
                      (LOOP_OFFSET, LOOP),
                      (PROVENANCE_OFFSET, PROVENANCE)):
        covered[off:off + len(blob)] = b"\x01" * len(blob)
    covered[SRAM_CODE_OFFSET] = 1
    for i in range(ROM_SIZE):
        if not covered[i] and rom[i] != 0x00:
            raise ValueError("unexpected non-fill byte at image offset 0x%05X" % i)

    # Oracle self-consistency (the derivation must stay exact):
    if STEADY_FRAME_CYCLES % LOOP_COST != 0:
        raise ValueError("steady frame cycles not divisible by the loop cost")
    if sum(PROLOGUE_INSTRUCTION_COSTS) != PROLOGUE_COST:
        raise ValueError("prologue instruction costs disagree with PROLOGUE_COST")
    if sum(LOOP_INSTRUCTION_COSTS) != LOOP_COST:
        raise ValueError("loop instruction costs disagree with LOOP_COST")

    # The exact chunk-aware simulation must reproduce run_iterations() for a
    # spread of frame counts, end every run at ICount -1 (the fixed point),
    # and leave the final increment unmirrored (SRAM[0] lags by one). This is
    # memoized: the F=60 case is the expensive one.
    global _oracle_sim_checked
    if not _oracle_sim_checked:
        for frames in (1, 2, 3, 60):
            incs, ic, synced = _simulate_poweron(frames)
            if incs != run_iterations(frames):
                raise ValueError("simulation %d != oracle %d for %d frames"
                                 % (incs, run_iterations(frames), frames))
            if ic != -1:
                raise ValueError("%d-frame run ends at ICount %d, want -1"
                                 % (frames, ic))
            if synced:
                raise ValueError("%d-frame run ended mirror-synced; the "
                                 "oracle requires the final increment to be "
                                 "unmirrored" % frames)
        _oracle_sim_checked = True


def rom_sha256():
    """SHA256 hex digest of the generated ROM (pinned in unit tests)."""
    return hashlib.sha256(generate_rom()).hexdigest()


if __name__ == "__main__":
    import sys
    rom = generate_rom()
    print("rom bytes: %d" % len(rom))
    print("sha256:    %s" % hashlib.sha256(rom).hexdigest())
    print("oracle:    increments(F) = %d*F - %d; SRAM[0x100] = total mod 256"
          % (ITERATIONS_PER_STEADY_FRAME, PER_POWERON_OVERHEAD))
    sys.stdout.flush()
