"""Original PS-X EXE and memory card for firmware-free PCSX qualification.

No SDK, BIOS, logo, or game bytes. A tiny MIPS assembler emits direct GPU/SPU
register writes and HLE BIOS card-sector calls. Sector 64 holds a boot counter:
each fresh process increments it once, then renders/plays indefinitely. The
program polls a qualification marker before touching it, accommodating save restore
after the player's software run thread starts. No wall-clock/frame oracle is
needed: the entire card is stable after this one transaction.
"""

import struct

CARD_SIZE = 131072
COUNTER_OFFSET = 64 * 128
MARKER = b"ROMMPSX1"
ROM_NAME = "rommulus-e2e-ps1.exe"
BASE = 0x80010000
BUFFER = 0x80018000


def blank_card():
    card = bytearray(CARD_SIZE)
    card[:2] = b"MC"
    card[127] = ord("M") ^ ord("C")
    for sector in range(1, 16):
        offset = sector * 128
        card[offset] = 0xA0
        card[offset + 8:offset + 10] = b"\xff\xff"
        card[offset + 127] = 0xA0
    for sector in range(16, 36):
        offset = sector * 128
        card[offset:offset + 4] = b"\xff" * 4
        card[offset + 8:offset + 10] = b"\xff\xff"
    # Upstream already formats its default card: MC alone cannot distinguish
    # the restored image. An unallocated data sector carries our restore token.
    card[COUNTER_OFFSET:COUNTER_OFFSET + 8] = MARKER
    return bytes(card)


def expected_card(boots):
    if not 0 <= boots <= 0xFFFFFFFF:
        raise ValueError("boot count outside uint32")
    card = bytearray(blank_card())
    if boots:
        card[COUNTER_OFFSET:COUNTER_OFFSET + 8] = MARKER
        struct.pack_into("<I", card, COUNTER_OFFSET + 8, boots)
    return bytes(card)


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

    def label(self, name):
        self.labels[name] = len(self.words)

    def branch(self, rs, rt, name, unequal=False):
        self.branches.append((len(self.words), name))
        self.emit((0x14000000 if unequal else 0x10000000) | rs << 21 | rt << 16)
        self.emit(0)  # Branch delay slot.

    def store(self, value, address, half=False):
        self.li(8, address)
        self.li(9, value)
        self.emit((0xA5000000 if half else 0xAD000000) | 9 << 16)

    def card(self, function, sector):
        self.li(4, 0)  # Slot 1.
        self.li(5, sector)
        self.li(6, BUFFER)
        self.li(9, function)
        self.emit(0x0C00002C)  # jal 0x800000b0
        self.emit(0)

    def finish(self):
        for index, name in self.branches:
            self.words[index] |= (self.labels[name] - index - 1) & 0xFFFF
        return struct.pack("<%dI" % len(self.words), *self.words)


def generate_rom():
    p = Mips()
    # Visible 320x240 NTSC framebuffer; fill VRAM with a non-black RGB color.
    for value in (0, 0x03000000, 0x05000000, 0x06C60260, 0x07042018, 0x08000001):
        p.store(value, 0x1F801814)
    for value in (0x023060A0, 0, 0x00F00140):
        p.store(value, 0x1F801810)

    # Original repeating ADPCM wave at SPU RAM 0x1000 (loop-start/end/repeat).
    p.store(0x800, 0x1F801DA6, half=True)
    for value in (0x0700,) + (0x7777, 0x3333, 0xBBBB, 0xFFFF) * 2:
        p.store(value, 0x1F801DA8, half=True)
    for address, value in (
        (0x1F801DAA, 0xC000), (0x1F801D80, 0x3FFF), (0x1F801D82, 0x3FFF),
        (0x1F801C00, 0x3FFF), (0x1F801C02, 0x3FFF), (0x1F801C04, 0x1000),
        (0x1F801C06, 0x800), (0x1F801C08, 0x00FF), (0x1F801C0A, 0),
        (0x1F801D88, 1),
    ):
        p.store(value, address, half=True)

    p.label("wait_restore")
    p.card(0x4F, 64)
    p.li(8, BUFFER)
    p.emit(0x8D0A0000)  # lw t2, 0(t0)
    p.li(11, int.from_bytes(MARKER[:4], "little"))
    p.branch(10, 11, "wait_restore", unequal=True)
    p.emit(0x8D0A0004)  # lw t2, 4(t0)
    p.li(11, int.from_bytes(MARKER[4:], "little"))
    p.branch(10, 11, "wait_restore", unequal=True)
    p.emit(0x8D0A0008)  # lw t2, 8(t0)
    p.emit(0)
    p.emit(0x254A0001)  # addiu t2, t2, 1
    p.emit(0xAD0A0008)  # sw t2, 8(t0)
    for offset in (0, 4):
        p.li(9, int.from_bytes(MARKER[offset:offset + 4], "little"))
        p.emit(0xAD090000 | offset)
    p.card(0x4E, 64)
    p.label("done")
    p.branch(0, 0, "done")
    payload = p.finish()
    payload += bytes((-len(payload)) % 2048)
    header = bytearray(2048)
    header[:8] = b"PS-X EXE"
    struct.pack_into("<I", header, 0x10, BASE)
    struct.pack_into("<II", header, 0x18, BASE, len(payload))
    struct.pack_into("<I", header, 0x30, 0x801FFF00)
    return bytes(header) + payload
