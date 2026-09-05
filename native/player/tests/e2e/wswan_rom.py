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
#      DS<<4 = 0x10000 and [si]/[bx]/[di] with small offsets land in wsSRAM.
#      No port writes are needed: the reset defaults already map code to the
#      last bank. Side-effect checks that keep the run deterministic
#      (wswan-memory.c WriteMemCore + sound.c): v30mz_reset() zeroes SP/SS
#      like every other register, but libretro.c Reset() then sets SS = 0,
#      SP = 0x2000 (verified against the real core: post-load SP = 0x2000),
#      so `push ax` with AX = 0x1000 decrements SP to 0x1FFE and — via the
#      PUSH macro's WriteWord, low byte first — writes the stack word as
#      wsRAM[0x1FFE] = 0x00 (low) and wsRAM[0x1FFF] = 0x10 (high). Both
#      bytes are bank 0: (0x1FFE >> 6) = (0x1FFF >> 6) = 0x7F !=
#      SampleRAMPos (0), so WSwan_SoundCheckRAMWrite never fires, but the
#      HIGH byte's offset 0x1FFF >= 0xFE00 DOES trigger one harmless
#      WSwan_GfxWSCPaletteRAMWrite(0x1FFF, 0x10) — palette RAM is not part
#      of the SRAM image and feeds no CPU timing. The word never reaches
#      bank 1: wsSRAM stays untouched by the stack, and SRAM[0] is written
#      only by the loop's `mov [bx],al` mirror (the first mirror write
#      establishes it on a fresh cart). Port READS of 0x02 (wsLine) go
#      through WSwan_GfxRead — a pure read with no state mutation.
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
#      IMPORTANT (libretro.c retro_run + libretro_core_options.h): the core
#      ships with its "wswan_60hz_mode" option DEFAULTED TO "enabled". In
#      that mode retro_run() executes a DETERMINISTIC 5:4 pulldown, not
#      wall-clock pacing: when retro_60hz_counter == 0 — once every four
#      calls (RETRO_60HZ_CYCLE_INDEX = 4) — an EXTRA full Emulate() pass runs
#      before the regular one, so EVERY FOUR presented frames execute FIVE
#      Emulate() passes (N presented frames -> N + ceil(N/4) Emulates). The
#      extra pass is a complete V30 frame: CPU and SRAM state advance exactly
#      as in a visible frame; only its video/audio output is discarded. So
#      with the option at its default, both oracles below scale with the
#      EMULATE count, not the presented-frame count. The player therefore
#      pins wswan_60hz_mode=disabled for this core in qualification builds
#      (compile-time scoped — see emulation_session.cpp); with 60Hz off, one
#      presented frame == one Emulate() == the line schedule above on every
#      platform, and ordinary production builds keep the upstream default.
#
#   6. OPCODE COST TABLE OF THIS CORE (v30mz.c): the cycle counts below are
#      the core's own model (CLK values; prefetch/bus-wait time is not
#      emulated), verified per opcode:
#        EA d32 jmp far    = 7
#        BE/BB/B8 imm16    = 1   (immediate FETCHes cost no cycles here)
#        BF imm16 (mov di)  = 1   (loads IY in this core)
#        50 push ax        = 1
#        1F pop ds         = 3
#        FE /4 inc [si]    = 3   (CLKM(3,1), memory form; ModRM 0x04)
#        FE /5 inc [di]    = 3   (CLKM(3,1), memory form; ModRM 0x05)
#        8A /4 mov al,[si] = 1   (plain CLK(1) in this core — NOT a CLKM form)
#        88 /3 mov [bx],al = 1   (plain CLK(1))
#        E4 d8 inb al,#port= 6
#        3A /r cmp r/m8,rg = 1   (CLKM(2,1), register form)
#        3C d8 cmp al,#imm = 1
#        72/75 d8 jcc      = 3 if TAKEN, 1 if not taken (JMP macro in
#                              v30mz.c: taken path CLK(3)+return, fall-through
#                              CLK(1)) — data-dependent!
#        EB d8  jmp short  = 4   (relative to the byte AFTER the operand)
#      NOTE on ModRM encodings (v30mz.c GetEA table): mod=00/rm=110 is the
#      [disp16] form (EA_006 does TWO FETCHes), NOT a register — SI is
#      mod=00/rm=100 (ModRM 0x04), DI is mod=00/rm=101 (ModRM 0x05) and BX
#      is mod=00/rm=111 (ModRM 0x07). NOTE on segment bases (v30mz.c
#      DefaultBase): base = seg<<4, so reaching the bank-1 SRAM window at
#      physical 0x10000 requires DS = 0x1000 (NOT 1).
#
#   7. THE ORACLE — a per-Emulate FRAME marker plus an exact cycle counter,
#      under the player's REAL restore model:
#      RESTORE MODEL (load-bearing for the chain semantics): every E2E run
#      launches a FRESH player process and therefore a fresh core: reset CPU
#      state (v30mz registers zeroed, PC at the reset vector, ICount = 0)
#      and GfxReset's wsLine = 0. The previous run's candidate save is then
#      restored by emulation_session.cpp restoreSaveRam(), which copies ONLY
#      the RETRO_MEMORY_SAVE_RAM image (wsSRAM) into the core — it does NOT
#      restore retro_serialize state (no v30mz registers, PC, ICount, or
#      wsLine carry over). A save chain [F_1..F_n] of player-reported frame
#      counts therefore models n INDEPENDENT fresh power-ons whose SRAM
#      mutations accumulate — NOT one uninterrupted power-on of sum(F_i)
#      frames. (The core's own serialize/unserialize API would give the
#      uninterrupted model; the player never uses it for save adoption.)
#      PRIMARY INVARIANT (frame marker, exact under the pinned 60Hz-off):
#      the loop samples port 0x02 every iteration (WSwan_GfxRead(0x02)
#      returns wsLine — the same variable gfx.c tests to assert
#      WSINT_VBLANK at the frame boundary). CL holds the line value of the
#      previous sample; when the sampled value CHANGES and the new value is
#      144 — i.e. entry into the VBLANK line, which occurs exactly once per
#      Emulate() (the value 144 is visible for one contiguous 256-cycle
#      window per frame: the 32-cycle tail of line 143 plus the first two
#      chunks of line 144) — SRAM[0x101] is incremented once. Each fresh
#      run therefore adds exactly F_i marker increments ON TOP OF the
#      restored byte (a fresh power-on samples line 0 first, equal to the
#      reset value cl = 0, so it never double-counts), and after the chain:
#          SRAM[0x101] = (sum(F_i)) mod 256        EXACTLY.
#      The loop period (~19..23 cycles) is far smaller than the 256-cycle
#      line-144 window, so the entry edge can never be missed. This equals
#      the presented-frame count only because qualification builds pin
#      wswan_60hz_mode=disabled (one Emulate per presented frame); at the
#      core's default it would instead equal the Emulate count
#      N + ceil(N/4) (fact 5). The marker is stable across platforms: it
#      counts line-144 entries, never cycles.
#      SECONDARY INVARIANT (cycle counter, exact under the pinned 60Hz-off):
#      SRAM[0x100] increments every loop iteration and is mirrored to
#      SRAM[0]. The loop cost is data-dependent (19 cycles when the line
#      value is unchanged, 22 on a non-VBLANK line change, 23 on the VBLANK
#      entry), so per-frame iteration counts are NOT a constant and there is
#      no closed form; the exact values come from _simulate_poweron(), which
#      reproduces the core's ICount semantics instruction-by-instruction
#      INCLUDING chunk boundaries (a scanline's three v30mz_execute(128/96/
#      32) calls can each end mid loop-iteration and the next resumes where
#      it left off) and the per-chunk visible wsLine value (chunks 1+2 of
#      line L see L, chunk 3 sees (L+1) mod 159 — gfx.c increments wsLine
#      between the 96- and 32-cycle calls). Because the counter LIVES IN
#      SRAM, each fresh power-on's inc [si] continues from the RESTORED
#      byte: with Delta_i = the iteration count of a fresh F_i-frame
#      power-on (the _simulate_poweron(F_i) counter — independent of the
#      restored bytes, since no branch reads the counter value) and M_n =
#      the number of mirror writes inside the LAST run (M_n is Delta_n or
#      Delta_n - 1: a run may end between its final inc [si] and that
#      iteration's mirror write), after the chain [F_1..F_n]:
#          SRAM[0x100] = (sum(Delta_i)) mod 256
#          SRAM[0]     = (sum(Delta_i) - (Delta_n - M_n)) mod 256
#      and every other byte stays 0 — nothing else ever writes wsSRAM. A
#      fresh cart's zero-filled wsSRAM starts the accumulator at 0, so a
#      single-run chain [F] reproduces _simulate_poweron(F) exactly.
#      CHAINS ARE NOT UNINTERRUPTED EXECUTION: for n >= 2 the chain image
#      DIFFERS from one uninterrupted power-on of sum(F_i) frames. Each
#      fresh power-on re-runs the 145-line first frame (37120 cycles,
#      wsLine = 0 after GfxReset) where an uninterrupted stream would have
#      continued from wsLine = 145 over 159 lines (40704), and each run's
#      ICount residue is discarded at power-off. The chain's total cycle
#      budget is n*FIRST_FRAME_CYCLES + (sum(F_i) - n)*STEADY_FRAME_CYCLES
#      = the uninterrupted budget minus (n-1)*(40704 - 37120) = minus
#      (n-1)*3584 cycles, so the counter byte diverges from the
#      uninterrupted value (the marker alone cannot tell the models apart —
#      it is sum(F_i) mod 256 either way). expected_sram_image() implements
#      the chain model; _validate() cross-checks the simulation's internal
#      consistency (marker == frames for a spread of frame counts;
#      monotonic counters; bounded end-of-run residue; chain !=
#      uninterrupted) and the E2E itself verifies the oracle against the
#      real vendored core on every CI platform.
#
# Program (image offsets; bank F = image offset 0x70000 + bank-F offset)
# ----------------------------------------------------------------------
#   Reset stub @ image 0x7FFF0 (physical 0xFFFF0, the reset fetch point):
#     EA 00 00 10 F0    jmp far 0x0F010:0x0000 -> physical 0xF0100
#   Main code @ image 0x70100 (physical 0xF0100):
#     BE 00 01        mov si,#0x0100      ; counter at SRAM[0x100] (DS=1)
#     BB 00 00        mov bx,#0x0000      ; mirror at SRAM[0]       (DS=1)
#     BF 01 01        mov di,#0x0101      ; frame marker at SRAM[0x101]
#     B8 00 10        mov ax,#0x1000
#     50              push ax             ; stack word in wsRAM 0x1FFE
#     1F              pop ds              ; DS=0x1000 -> base 0x10000 (bank 1)
#   Loop @ image 0x7010E (physical 0xF010E):
#     FE 04           inc [si]            ; counter++          (3)
#     8A 04           mov al,[si]         ; mirror             (1)
#     88 07           mov [bx],al         ; SRAM[0] = counter  (1)
#     E4 02           inb al,#2           ; al = wsLine        (6)
#     3A C8           cmp al,cl           ; line changed?      (1)
#     74 +8           je hold             ; no                 (3/1)
#     88 D9           mov cl,al           ; remember line      (1)
#     3C 90           cmp al,#144         ; VBLANK entry?      (1)
#     75 +2           jne hold            ; no                 (3/1)
#     FE 05           inc [di]            ; frame marker++     (3)
#   hold:
#     EB -22          jmp loop                          (4)
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

# Main code: 14 bytes, 8 core-model cycles (BE/BB/BF/B8/push = 1 each,
# pop ds = 3). Loads DS = 0x1000 (segment base = DS<<4 = 0x10000) so the
# loop's [si]/[bx]/[di] references address bank 1 (the SRAM window) with
# small 16-bit offsets. DI selects the frame-marker byte at SRAM[0x101].
MAIN_CODE = bytes([
    0xBE, 0x00, 0x01,   # mov si,#0x0100   (cycle counter; loads IX here)
    0xBB, 0x00, 0x00,   # mov bx,#0x0000   (mirror)
    0xBF, 0x01, 0x01,   # mov di,#0x0101   (frame marker; loads IY here)
    0xB8, 0x00, 0x10,   # mov ax,#0x1000
    0x50,               # push ax
    0x1F,               # pop ds      (DS = 0x1000 -> base DS<<4 = 0x10000)
])

# Loop: 22 bytes. Per-iteration cost is data-dependent (see fact 7):
#   line unchanged:            3+1+1+6+1+3(je taken)+4       = 19
#   line changed, not VBLANK:  3+1+1+6+1+1+1+1+3(jne taken)+4 = 22
#   VBLANK entry (once/frame): 3+1+1+6+1+1+1+1+1+3+4         = 23
# ModRM 0x04 = mod=00/rm=100 = [si]; ModRM 0x05 = mod=00/rm=101 = [di];
# ModRM 0x07 = mod=00/rm=111 = [bx]; 0xC8/0xD9/0xF6 are register forms.
LOOP = bytes([
    0xFE, 0x04,         # inc [si]      (3)   -> wsSRAM[0x100]++
    0x8A, 0x04,         # mov al,[si]   (1)
    0x88, 0x07,         # mov [bx],al   (1)  -> wsSRAM[0] = counter
    0xE4, 0x02,         # inb al,#2     (6)  al = wsLine (port 0x02)
    0x3A, 0xC8,         # cmp al,cl     (1)  cl = previous sample's line
    0x74, 0x08,         # je hold       (3/1) rel +8 -> offset 20
    0x88, 0xC1,         # mov cl,al     (1)  ModRM mod=11 reg=AL rm=CL
    0x3C, 0x90,         # cmp al,#144   (1)  VBLANK line?
    0x75, 0x02,         # jne hold      (3/1) rel +2 -> offset 20
    0xFE, 0x05,         # inc [di]      (3)  -> wsSRAM[0x101] frame marker++
    # hold:
    0xEB, 0xEA,         # jmp loop      (4)  rel -22 -> offset 0
])

# --- layout -----------------------------------------------------------------
BANK_F_BASE = 0x70000         # last 64 KiB bank of the image
STUB_OFFSET = ROM_SIZE - 16   # 0x7FFF0 (physical 0xFFFF0, reset fetch)
MAIN_OFFSET = BANK_F_BASE + 0x0100     # main code (physical 0xF0100)
LOOP_OFFSET = MAIN_OFFSET + len(MAIN_CODE)   # 0x7010E (physical 0xF010E)
SRAM_CODE_OFFSET = ROM_SIZE - 5      # 0x7FFFB == header[5] (cart header)
COUNTER_OFFSET = 0x100               # byte counter inside wsSRAM
MIRROR_OFFSET = 0                    # mirror of the counter
MARKER_OFFSET = 0x101                # per-retro_run frame marker
VBLANK_LINE = 144                    # gfx.c asserts WSINT_VBLANK at wsLine==144
PROVENANCE_OFFSET = BANK_F_BASE + 0x0200
PROVENANCE = b"ROMMULUS E2E WSWAN - original deterministic WonderSwan qualification ROM"

# --- oracle constants (derived; see header facts 5+6+7) ----------------------
CYCLES_PER_LINE = 256                    # v30mz_execute(128)+96+32 per line
FIRST_FRAME_LINES = 145                  # wsLine 0..144 (GfxReset: wsLine=0)
STEADY_FRAME_LINES = 159                 # LCDVtotal=158 -> wrap modulo 159
FIRST_FRAME_CYCLES = FIRST_FRAME_LINES * CYCLES_PER_LINE    # 37120
STEADY_FRAME_CYCLES = STEADY_FRAME_LINES * CYCLES_PER_LINE  # 40704
STUB_COST = 7                            # EA jmp far
MAIN_CODE_INSTRUCTION_COSTS = (1, 1, 1, 1, 1, 3)   # BE/BB/BF/B8/push/pop-ds
PROLOGUE_INSTRUCTION_COSTS = (STUB_COST,) + MAIN_CODE_INSTRUCTION_COSTS
# Loop body instruction costs in program order; the two Jcc entries are the
# NOT-TAKEN costs — the simulation adds the taken penalty itself (fact 6:
# taken = 3, not-taken = 1, so the delta is +2 when taken).
LOOP_INSTRUCTION_COSTS = (3, 1, 1, 6, 1, 1, 1, 1, 1, 3, 4)
JCC_TAKEN_DELTA = 2                      # extra cycles when a Jcc is taken
# Per-chunk visible wsLine: chunks 1+2 of line L read L; chunk 3 reads the
# ALREADY-INCREMENTED value (L+1) mod 159 — gfx.c updates wsLine between the
# 96- and 32-cycle v30mz_execute calls.
LINE_CHUNKS = ((128, 0), (96, 0), (32, 1))   # (cycles, line offset)
_sim_checked = False      # memoizes the expensive simulation cross-check


def _simulate_poweron(frames):
    """Exact simulation of one power-on under the core's real semantics.

    Models v30mz_execute() exactly: each scanline adds three chunks to the
    PERSISTENT ICount and the CPU executes one instruction at a time while
    ICount > 0 (each may overrun it). The instruction stream is continuous
    across chunk boundaries AND frames — an iteration interrupted by a chunk
    boundary resumes at exactly that point in the next chunk, because the
    core's v30mz_ICount/PC/registers persist between v30mz_execute() calls
    (v30mz.c: `v30mz_ICount += cycles`). The loop's branch outcomes depend on
    the per-chunk visible wsLine value (fact 7), so this simulation tracks
    AL/CL and the frame marker exactly like the ROM.

    Line model (gfx.c): frame 0 starts at wsLine=0 (GfxReset) and draws lines
    0..144 — Emulate() returns once VBLANK is asserted on entry to line 144,
    after that line's three chunks have run. Every later frame continues from
    wsLine=145 and draws 145..158, 0..144 (159 lines). Within a line L the
    three chunks see L, L, (L+1) mod 159.

    Returns (counter, mirror, marker, ic_end, mirror_writes): total inc [si]
    iterations, the counter value last mirrored into SRAM[0], total frame-
    marker increments (= frames, checked by _validate), the end-of-run ICount
    residue, and the number of mirror writes executed (mirror_writes is
    counter or counter - 1 — a run may end between its final inc [si] and
    that iteration's mirror write; expected_sram_image() needs the exact
    count because the mirrored byte must be expressed against the RESTORED
    counter, where the mod-256 byte alone is ambiguous).
    """
    if frames < 1:
        raise ValueError("wswan oracle requires at least one presented frame")
    ic = 0
    pos = 0                    # position in the prologue instruction stream
    stage = 0                  # loop-body stage 0..10 (resumable mid-iteration)
    counter = 0
    mirror = 0                 # last value written to SRAM[0]
    marker = 0
    mirror_writes = 0          # number of stage-2 (mov [bx],al) executions
    al = 0                     # v30mz_reset() zeroes all registers
    cl = 0
    changed = False            # result of the current iteration's cmp al,cl
    prologue_len = len(PROLOGUE_INSTRUCTION_COSTS)
    for f in range(frames):
        start_line = 0 if f == 0 else FIRST_FRAME_LINES   # wsLine=145 carry
        lines = FIRST_FRAME_LINES if f == 0 else STEADY_FRAME_LINES
        for line_idx in range(lines):
            line = (start_line + line_idx) % STEADY_FRAME_LINES
            for cycles, line_off in LINE_CHUNKS:
                visible = (line + line_off) % STEADY_FRAME_LINES
                ic += cycles
                while ic > 0:
                    if pos < prologue_len:
                        ic -= PROLOGUE_INSTRUCTION_COSTS[pos]
                        pos += 1
                        continue
                    # --- loop body: one instruction per pass, resumable ----
                    if stage == 0:        # FE 04  inc [si]
                        ic -= LOOP_INSTRUCTION_COSTS[0]
                        counter += 1
                        stage = 1
                    elif stage == 1:      # 8A 04  mov al,[si]
                        ic -= LOOP_INSTRUCTION_COSTS[1]
                        stage = 2
                    elif stage == 2:      # 88 07  mov [bx],al (mirror)
                        ic -= LOOP_INSTRUCTION_COSTS[2]
                        mirror = counter & 0xFF
                        mirror_writes += 1
                        stage = 3
                    elif stage == 3:      # E4 02  in al,#2 (this chunk's wsLine)
                        ic -= LOOP_INSTRUCTION_COSTS[3]
                        al = visible
                        stage = 4
                    elif stage == 4:      # 3A C8  cmp al,cl
                        ic -= LOOP_INSTRUCTION_COSTS[4]
                        changed = (al != cl)
                        stage = 5
                    elif stage == 5:      # 74 +8  je hold
                        if changed:
                            ic -= LOOP_INSTRUCTION_COSTS[5]       # not taken
                            stage = 6
                        else:
                            ic -= LOOP_INSTRUCTION_COSTS[5] + JCC_TAKEN_DELTA
                            stage = 10
                    elif stage == 6:      # 88 C1  mov cl,al
                        ic -= LOOP_INSTRUCTION_COSTS[6]
                        cl = al
                        stage = 7
                    elif stage == 7:      # 3C 90  cmp al,#144
                        ic -= LOOP_INSTRUCTION_COSTS[7]
                        stage = 8
                    elif stage == 8:      # 75 +2  jne hold
                        if al != VBLANK_LINE:
                            ic -= LOOP_INSTRUCTION_COSTS[8] + JCC_TAKEN_DELTA
                            stage = 10
                        else:
                            ic -= LOOP_INSTRUCTION_COSTS[8]       # not taken
                            stage = 9
                    elif stage == 9:      # FE 05  inc [di] (frame marker)
                        ic -= LOOP_INSTRUCTION_COSTS[9]
                        marker += 1
                        stage = 10
                    else:                 # EB -22 jmp loop
                        ic -= LOOP_INSTRUCTION_COSTS[10]
                        stage = 0
    return counter, mirror, marker, ic, mirror_writes


def expected_sram_image(run_frames):
    """The exact 8192-byte wsSRAM image after the given run chain.

    `run_frames` is either one int (a single power-on) or the list of
    player-reported frame counts for every power-on in the save chain. Each
    element models an INDEPENDENT fresh power-on (fresh process, reset CPU,
    wsLine = 0) whose restored SRAM — copied by restoreSaveRam(), which
    restores ONLY RETRO_MEMORY_SAVE_RAM, never retro_serialize state —
    carries the counter/mirror/marker bytes accumulated by all earlier runs.
    The ROM's writes accumulate on those restored bytes (header fact 7):
        SRAM[0x101] = sum(F_i) mod 256                    (frame marker:
            each fresh run adds exactly F_i line-144 entries on top of the
            restored byte — exact under the pinned wswan_60hz_mode=disabled)
        SRAM[0x100] = sum(Delta_i) mod 256                (cycle counter:
            Delta_i = the iteration count of a fresh F_i-frame power-on,
            from _simulate_poweron(F_i); independent of restored bytes)
        SRAM[0]     = (sum(Delta_i) - (Delta_n - M_n)) mod 256
            (last mirror write of the LAST run: M_n is its mirror-write
            count, Delta_n or Delta_n - 1, expressed against the restored
            accumulator sum(Delta_1..Delta_{n-1}) + Delta_n)
    All other bytes stay 0: nothing else ever writes wsSRAM. A single-run
    chain [F] reproduces _simulate_poweron(F) exactly (fresh cart starts
    the accumulators at 0).

    For n >= 2 this image is NOT equal to one uninterrupted power-on of
    sum(F_i) frames: each fresh power-on re-runs the 145-line first frame
    (37120 cycles) where an uninterrupted stream would continue from
    wsLine = 145 over 159 lines (40704), so the chain's cycle budget is
    smaller by (n-1)*3584 and the counter byte diverges — while the marker
    is sum(F_i) mod 256 under BOTH models. _validate() pins that difference.
    """
    runs = [run_frames] if isinstance(run_frames, int) else list(run_frames)
    if not runs:
        raise ValueError("wswan oracle requires at least one power-on")
    counter_acc = 0            # sum of per-run iteration counts (unwrapped)
    mirror_value = 0           # value written by the last mirror write
    marker_acc = 0
    for frames in runs:
        delta, _mirror, marker, _ic, mw = _simulate_poweron(frames)
        if marker != frames:
            raise ValueError("simulation marked %d frames for a %d-frame "
                             "power-on (want exactly %d)"
                             % (marker, frames, frames))
        counter_acc += delta
        if mw > 0:
            # The run's last mirror write stored (restored counter + mw);
            # against the new accumulator that is counter_acc - (delta-mw).
            mirror_value = counter_acc - (delta - mw)
        marker_acc += frames
    image = bytearray(SRAM_SIZE)
    image[MIRROR_OFFSET] = mirror_value & 0xFF
    image[COUNTER_OFFSET] = counter_acc & 0xFF
    image[MARKER_OFFSET] = marker_acc & 0xFF
    return bytes(image)


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
    jmp_rel = LOOP[21]
    if jmp_rel > 127:
        jmp_rel -= 256
    pc_after_operand = loop_in_bank + len(LOOP)
    if (pc_after_operand + jmp_rel) & 0xFFFF != loop_in_bank:
        raise ValueError("loop jmp rel %d does not return to the loop start" % jmp_rel)

    # Frame-detection branch arithmetic (same PC-after-operand semantics):
    # je at offset 10 must land on hold (offset 20); jne at offset 16 must
    # also land on hold.
    for off, rel in ((10, LOOP[11]), (16, LOOP[17])):
        if rel > 127:
            rel -= 256
        if (off + 2 + rel) % len(LOOP) != 20:
            raise ValueError("loop branch at offset %d (rel %d) does not land on hold"
                             % (off, LOOP[off + 1]))

    # ModRM sanity: the loop must use the register/memory forms [si] (0x04),
    # [di] (0x05) and [bx] (0x07), never the mod=00/rm=110 [disp16] form
    # (0x06), which would swallow two displacement bytes and derail execution.
    if LOOP[1] != 0x04 or LOOP[3] != 0x04 or LOOP[5] != 0x07 \
            or LOOP[6] != 0xE4 or LOOP[7] != 0x02 or LOOP[19] != 0x05:
        raise ValueError("loop ModRM/port bytes drifted (want FE/4, 8A/4, "
                         "88/7, E4/2, FE/5)")

    # Main code must load the three SRAM offsets and DS = 0x1000.
    if MAIN_CODE[0:3] != bytes([0xBE, 0x00, 0x01]) \
            or MAIN_CODE[3:6] != bytes([0xBB, 0x00, 0x00]) \
            or MAIN_CODE[6:9] != bytes([0xBF, 0x01, 0x01]) \
            or MAIN_CODE[9:12] != bytes([0xB8, 0x00, 0x10]):
        raise ValueError("main code register setup drifted")

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

    # Oracle self-consistency (the simulation must stay exact):
    global _sim_checked
    if not _sim_checked:
        prev_counter = 0
        for frames in (1, 2, 3, 60):
            counter, mirror, marker, ic, mw = _simulate_poweron(frames)
            # Headline invariant: the frame marker counts exactly one per
            # presented frame, every power-on.
            if marker != frames:
                raise ValueError("simulation marked %d frames for a %d-frame "
                                 "run (want exactly %d)" % (marker, frames, frames))
            # The counter must grow monotonically with the frame budget. Every
            # iteration consumes at least its inc [si] (3 cycles) and at most
            # the full VBLANK-entry body (23), so the total is bounded by the
            # chunk-added cycle budget (the prologue executes out of the first
            # chunk's budget, not an extra one).
            if counter <= prev_counter:
                raise ValueError("simulation counter not monotonic at %d frames"
                                 % frames)
            prev_counter = counter
            added = FIRST_FRAME_CYCLES + (frames - 1) * STEADY_FRAME_CYCLES
            lo, hi = added // 23, (added + 4) // 3
            if not (lo <= counter <= hi):
                raise ValueError("simulation counter %d outside [%d, %d] for "
                                 "%d frames" % (counter, lo, hi, frames))
            # End-of-run residue: the last instruction may overrun its chunk
            # by at most its own cost (6 cycles for the loop's inb al,#2).
            if not (-6 <= ic <= 0):
                raise ValueError("%d-frame run ends at ICount %d, want in [-6, 0]"
                                 % (frames, ic))
            # The mirror must equal either the final counter or one behind it
            # (the run can end between inc [si] and its mirror write), and the
            # mirror-write count must match that lag exactly.
            if mirror not in ((counter - 1) & 0xFF, counter & 0xFF):
                raise ValueError("%d-frame run mirror %d inconsistent with "
                                 "counter %d" % (frames, mirror, counter))
            if mw not in (counter - 1, counter):
                raise ValueError("%d-frame run mirror-write count %d outside "
                                 "{%d, %d}" % (frames, mw, counter - 1, counter))
        # Chain semantics (header fact 7): a save chain of independent fresh
        # power-ons accumulates per-run deltas on the RESTORED SRAM bytes and
        # must NOT equal one uninterrupted power-on of the summed frames —
        # each fresh run re-runs the 145-line first frame, so the chain's
        # cycle budget is smaller by (n-1)*3584 cycles for n >= 2.
        chain = [240, 180, 60]
        chain_image = expected_sram_image(chain)
        total_frames = sum(chain)
        if chain_image[MARKER_OFFSET] != total_frames & 0xFF:
            raise ValueError("chain marker %d != %d mod 256"
                             % (chain_image[MARKER_OFFSET], total_frames))
        deltas = [w for w, _m, _k, _ic, _mw in
                  (_simulate_poweron(f) for f in chain)]
        if chain_image[COUNTER_OFFSET] != sum(deltas) & 0xFF:
            raise ValueError("chain counter byte %d != sum of per-run deltas "
                             "%d mod 256"
                             % (chain_image[COUNTER_OFFSET], sum(deltas)))
        uninterrupted = expected_sram_image(total_frames)
        if chain_image == uninterrupted:
            raise ValueError("chain [240, 180, 60] unexpectedly equals one "
                             "uninterrupted %d-frame power-on — the restore "
                             "model must treat runs as independent fresh "
                             "power-ons" % total_frames)
        if chain_image[COUNTER_OFFSET] == uninterrupted[COUNTER_OFFSET]:
            raise ValueError("chain counter byte %d unexpectedly equals the "
                             "uninterrupted value (marker alone cannot tell "
                             "the models apart; the counter must)"
                             % chain_image[COUNTER_OFFSET])
        # A single-element chain is exactly that one fresh power-on.
        for frames in (60, 240):
            if expected_sram_image([frames]) != expected_sram_image(frames):
                raise ValueError("single-run chain [%d] differs from the "
                                 "one-int form" % frames)
        _sim_checked = True


def rom_sha256():
    """SHA256 hex digest of the generated ROM (pinned in unit tests)."""
    return hashlib.sha256(generate_rom()).hexdigest()


if __name__ == "__main__":
    import sys
    rom = generate_rom()
    print("rom bytes: %d" % len(rom))
    print("sha256:    %s" % hashlib.sha256(rom).hexdigest())
    counter, mirror, marker, ic, mw = _simulate_poweron(240)
    print("oracle:    F=240 -> counter=%d (SRAM[0x100]=%d) mirror=%d "
          "marker=%d (SRAM[0x101]) ic_end=%d mirror_writes=%d"
          % (counter, counter & 0xFF, mirror, marker, ic, mw))
    image = expected_sram_image([240, 180, 60])
    print("chain:     [240, 180, 60] -> SRAM[0]=%d SRAM[0x100]=%d "
          "SRAM[0x101]=%d (independent fresh power-ons; NOT an "
          "uninterrupted 480-frame run)"
          % (image[MIRROR_OFFSET], image[COUNTER_OFFSET],
             image[MARKER_OFFSET]))
    sys.stdout.flush()
