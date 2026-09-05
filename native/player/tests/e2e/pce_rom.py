#!/usr/bin/env python3
"""Deterministic original PC Engine HuCard for Beetle PCE Fast qualification.

The image contains only RomMulus-authored HuC6280 code and generated fill. It
uses the core's ordinary 8 KiB HuCard mapping, needs no firmware, maps physical
bank 0xF7 into CPU page 2, and writes only offsets 8..10 of the core's 2 KiB
battery-backed BRAM. Offsets 0..7 retain Beetle PCE Fast's required
``HUBM\x00\x88\x10\x80`` initializer.
"""

import hashlib

ROM_SIZE = 0x2000
ENTRY_ADDRESS = 0xE000
RESET_VECTOR_OFFSET = 0x1FFE
SRAM_SIZE = 2048
SRAM_PREFIX = b"HUBM\x00\x88\x10\x80"
SRAM_MARKER = 0x52
SRAM_MARKER_OFFSET = 8
SRAM_FRAME_COUNTER = 9
SRAM_60FRAME_COUNTER = 10
# VDC_Power starts at the core's frame boundary. The program reaches its
# first VBlank wait during the first retro_run(), so the first latched VBlank
# is observed after that call's video callback; N reported frames therefore
# produce max(0, N - 1) counter updates on each fresh power-on.
DEAD_FRAME_OFFSET = 1
PROVENANCE = b"ROMMULUS E2E PCE - original deterministic HuC6280 qualification ROM"
PROVENANCE_OFFSET = 0x100


def _vdc_register(register, low, high):
    return bytes((
        0xA9, register,       # lda #register
        0x8D, 0x00, 0x00,    # sta $0000 (VDC register select)
        0xA9, low,
        0x8D, 0x02, 0x00,    # sta $0002 (low byte)
        0xA9, high,
        0x8D, 0x03, 0x00,    # sta $0003 (high byte)
    ))


def _program():
    code = bytearray()

    # Disable maskable interrupts and map physical bank F7 (BRAM) at
    # $4000-$5FFF through HuC6280 MPR2.
    code += bytes((0x78, 0xA9, 0xF7, 0x53, 0x04))

    # Preserve restored counters; initialize only a fresh BRAM image.
    code += bytes((
        0xAD, 0x08, 0x40,       # lda $4008
        0xC9, SRAM_MARKER,      # cmp #marker
        0xF0, 0x0D,             # beq configured
        0xA9, SRAM_MARKER,
        0x8D, 0x08, 0x40,       # sta $4008
        0xA9, 0x00,
        0x8D, 0x09, 0x40,       # frame counter = 0
        0x8D, 0x0A, 0x40,       # 60-frame counter = 0
    ))

    # Stable 256x240-ish display timings followed by background enable and
    # VBlank-status generation. The generated VRAM remains all zero, while
    # VDC_Power's deterministic palette supplies a real software frame.
    code += _vdc_register(0x0A, 0x02, 0x02)  # HSR
    code += _vdc_register(0x0B, 0x1F, 0x04)  # HDR
    code += _vdc_register(0x0C, 0x02, 0x0F)  # VSR
    code += _vdc_register(0x0D, 0xEF, 0x00)  # VDR
    code += _vdc_register(0x0E, 0x03, 0x00)  # VCR
    code += _vdc_register(0x05, 0x88, 0x00)  # CR: background + VBlank IRQ

    # PSG channel 0: load a constant waveform and enable a quiet tone. The
    # core emits its normal mixed audio batch on every retro_run().
    code += bytes((
        0xA9, 0x00, 0x8D, 0x00, 0x08,  # channel 0
        0xA9, 0xFF, 0x8D, 0x01, 0x08,  # global L/R balance
        0xA9, 0x00, 0x8D, 0x04, 0x08,  # disable while loading waveform
        0xA2, 0x20,                    # 32 samples
        0xA9, 0x10,
        0x8D, 0x06, 0x08,
        0xCA,
        0xD0, 0xFA,
        0xA9, 0x40, 0x8D, 0x02, 0x08,  # frequency low
        0xA9, 0x02, 0x8D, 0x03, 0x08,  # frequency high
        0xA9, 0xFF, 0x8D, 0x05, 0x08,  # channel L/R balance
        0xA9, 0x9F, 0x8D, 0x04, 0x08,  # enable, max channel volume
    ))

    # Reading VDC status clears its latched bits. Once CR bit 3 is enabled,
    # bit 5 is set exactly once at each vertical blank, so this loop mutates
    # BRAM once per emulated/presented frame after startup.
    loop_address = ENTRY_ADDRESS + len(code)
    code += bytes((
        0xAD, 0x00, 0x00,       # lda $0000 (VDC status)
        0x29, 0x20,             # and #VDCS_VD
        0xF0, 0xF9,             # beq loop
        0xEE, 0x09, 0x40,       # inc $4009
        0xAD, 0x09, 0x40,
        0xC9, 0x3C,             # 60?
        0xD0, 0x08,             # bne next frame
        0xA9, 0x00,
        0x8D, 0x09, 0x40,
        0xEE, 0x0A, 0x40,
        0x4C, loop_address & 0xFF, (loop_address >> 8) & 0xFF,
    ))
    return bytes(code), loop_address


PROGRAM, LOOP_ADDRESS = _program()


def generate_rom():
    rom = bytearray(b"\xFF" * ROM_SIZE)
    rom[:len(PROGRAM)] = PROGRAM
    rom[PROVENANCE_OFFSET:PROVENANCE_OFFSET + len(PROVENANCE)] = PROVENANCE

    # Reset, NMI, timer, and both IRQ vectors all return to the deterministic
    # entry. IRQs are disabled, but fully initialized vectors avoid executing
    # fill bytes if an unexpected exception occurs.
    for offset in range(0x1FF6, 0x2000, 2):
        rom[offset] = ENTRY_ADDRESS & 0xFF
        rom[offset + 1] = (ENTRY_ADDRESS >> 8) & 0xFF

    image = bytes(rom)
    _validate(image)
    return image


def _validate(rom):
    if len(rom) != ROM_SIZE:
        raise ValueError("ROM is %d bytes, want %d" % (len(rom), ROM_SIZE))
    if rom[:len(PROGRAM)] != PROGRAM:
        raise ValueError("HuC6280 program bytes drifted")
    if rom[PROVENANCE_OFFSET:PROVENANCE_OFFSET + len(PROVENANCE)] != PROVENANCE:
        raise ValueError("provenance marker missing")
    vector = bytes((ENTRY_ADDRESS & 0xFF, (ENTRY_ADDRESS >> 8) & 0xFF))
    for offset in range(0x1FF6, 0x2000, 2):
        if rom[offset:offset + 2] != vector:
            raise ValueError("vector at 0x%04X does not point to entry" % offset)


def rom_sha256():
    return hashlib.sha256(generate_rom()).hexdigest()


def expected_sram_image(run_frames):
    if isinstance(run_frames, int):
        run_frames = (run_frames,)
    for frames in run_frames:
        if not isinstance(frames, int) or isinstance(frames, bool) or frames < 0:
            raise ValueError("each reported frame count must be an int >= 0")
    counted = sum(max(0, frames - DEAD_FRAME_OFFSET) for frames in run_frames)
    image = bytearray(b"\x00" * SRAM_SIZE)
    image[:len(SRAM_PREFIX)] = SRAM_PREFIX
    image[SRAM_MARKER_OFFSET] = SRAM_MARKER
    image[SRAM_FRAME_COUNTER] = counted % 60
    image[SRAM_60FRAME_COUNTER] = counted // 60
    return bytes(image)


if __name__ == "__main__":
    image = generate_rom()
    print("rom bytes: %d" % len(image))
    print("sha256:    %s" % hashlib.sha256(image).hexdigest())
