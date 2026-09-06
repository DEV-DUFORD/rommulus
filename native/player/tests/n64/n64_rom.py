"""Original N64 diagnostic cartridge; no SDK, firmware, logo or game bytes.

The original IPL3 executes from SP DMEM after the core's HLE PIF bootstrap.
It initializes VI, paints an RGBA5551 framebuffer, queues an original square
wave through AI, writes SRAM through PI DMA, and polls controller 1 via PIF.
This is a core smoke fixture, not a claim of physical-console compatibility.
"""

import argparse
import pathlib
import struct

ROM_NAME = "rommulus-n64-probe.z64"
SRAM_OFFSET = 0x800 + 4 * 0x8000
SAVE_SIZE = SRAM_OFFSET + 0x8000 + 0x20000
MARKER = (0x524F4D4D, 0x4E363431)


class Mips:
    def __init__(self):
        self.words = []
        self.labels = {}
        self.branches = []

    def emit(self, word):
        self.words.append(word)

    def li(self, reg, value):
        self.emit(0x3C000000 | reg << 16 | (value >> 16 & 0xFFFF))
        self.emit(0x34000000 | reg << 21 | reg << 16 | (value & 0xFFFF))

    def store(self, address, value):
        self.li(8, address)
        self.li(9, value)
        self.emit(0xAD090000)

    def label(self, name):
        self.labels[name] = len(self.words)

    def branch(self, rs, rt, name, unequal=False):
        self.branches.append((len(self.words), name))
        self.emit((0x14000000 if unequal else 0x10000000) | rs << 21 | rt << 16)
        self.emit(0)

    def finish(self):
        for index, name in self.branches:
            delta = self.labels[name] - index - 1
            assert -32768 <= delta <= 32767
            self.words[index] |= delta & 0xFFFF
        return struct.pack(">%dI" % len(self.words), *self.words)


def generate_rom():
    p = Mips()
    # Keep CPU exceptions masked: VI/AI still advance and yield to libretro.
    p.li(8, 0x34000000)
    p.emit(0x40886000)  # mtc0 t0, Status
    p.store(0xA0000318, 0x800000)
    p.store(0xA00003F0, 0x800000)
    p.li(8, 0xA0100000)
    p.li(9, 0xA061A061)
    p.li(10, 320 * 240 // 2)
    p.label("fill")
    p.emit(0xAD090000)
    p.emit(0x25080004)
    p.emit(0x254AFFFF)
    p.branch(10, 0, "fill", unequal=True)
    for offset, value in (
        (0x00, 0x0000320E), (0x04, 0x00100000), (0x08, 320),
        (0x0C, 2), (0x14, 0x03E52239), (0x18, 525),
        (0x1C, 0x00000C15), (0x20, 0x0C150C15),
        (0x24, 0x006C02EC), (0x28, 0x002501FF),
        (0x2C, 0x000E0204), (0x30, 0x200), (0x34, 0x400),
    ):
        p.store(0xA4400000 + offset, value)

    # Interleaved stereo square wave, 64 frames; repeats without external data.
    for i in range(64):
        value = 0x18001800 if i % 32 < 16 else 0xE800E800
        p.store(0xA0080000 + i * 4, value)
    p.store(0xA4500010, 1519)  # NTSC clock / 1520 ~= 32 kHz.
    p.store(0xA4500014, 15)
    p.store(0xA4500008, 1)

    # A PI RAM->cart transfer selects SRAM without an upstream ROM DB entry.
    for i, value in enumerate(MARKER):
        p.store(0xA0090000 + 4 * i, value)
    p.store(0xA4600000, 0x90000)
    p.store(0xA4600004, 0x08000000)
    p.store(0xA4600008, 7)

    p.label("loop")
    p.store(0xA0090040, 0x01040100)
    p.store(0xA0090044, 0x000000FE)
    p.store(0xA009007C, 1)
    p.store(0xA4800000, 0x90040)
    p.store(0xA4800010, 0x1FC007C0)
    p.li(8, 0xA4800018)
    p.label("wait_si_write")
    p.emit(0x8D090000)
    p.emit(0x31290003)  # andi t1,t1,3: SI DMA/IO busy.
    p.branch(9, 0, "wait_si_write", unequal=True)
    p.store(0xA4800004, 0x1FC007C0)
    p.li(8, 0xA4800018)
    p.label("wait_si_read")
    p.emit(0x8D090000)
    p.emit(0x31290003)
    p.branch(9, 0, "wait_si_read", unequal=True)
    p.li(8, 0xA0090040)
    p.emit(0x8D090000)
    p.li(8, 0xA8000008)
    p.emit(0xAD090000)
    p.li(8, 0xA450000C)
    p.emit(0x8D090000)
    p.li(10, 0x80000000)
    p.emit(0x012A4824)  # and t1,t1,t2: AI FIFO full.
    p.branch(9, 0, "loop", unequal=True)
    p.store(0xA4500000, 0x80000)
    p.store(0xA4500004, 256)
    p.branch(0, 0, "loop")

    program = p.finish()
    assert len(program) <= 0xFC0, "original IPL3 exceeds SP DMEM"
    rom = bytearray(0x2000)
    struct.pack_into(">IIII", rom, 0, 0x80371240, 0x0000000F, 0xA4000040, 0)
    rom[0x20:0x34] = b"ROMMULUS N64 PROBE  ".ljust(20)
    rom[0x3E] = ord("E")
    rom[0x40:0x40 + len(program)] = program
    return bytes(rom)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=pathlib.Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(generate_rom())
