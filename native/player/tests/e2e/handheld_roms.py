"""Original, firmware-free Handy and NeoPop persistence fixtures.

Handy's unencrypted cartridge path loads our own howard.o, not Atari firmware
or the historical Howard loader. The LNX header declares a real EEPROM.
NeoPop's TLCS-900H program writes a flash byte through the emulated bus.
"""

import struct


def handy_rom(eeprom_type=4):
    rom = bytearray(64 + 65536)
    rom[:4] = b"LYNX"
    struct.pack_into("<HHH", rom, 4, 256, 0, 1)
    rom[10:22] = b"ROMMULUS E2E"
    rom[42:50] = b"ORIGINAL"
    rom[60] = eeprom_type
    return bytes(rom)


def handy_loader(write_eeprom=False):
    # BLL object header executes BRA over itself; PC begins at $0200.
    code = bytearray(b"\x78")  # SEI.
    loop = 0x20A + len(code)

    def store(address, value):
        code.extend(bytes((0xA9, value, 0x8D)) + struct.pack("<H", address))

    def command(bits):
        # Reset cartridge counter, then clock it to bit 7 (EEPROM chip select).
        store(0xFD87, 3)
        store(0xFD87, 2)
        code.extend(b"\xad\xb2\xfc" * 128)
        for bit in bits:
            store(0xFD8B, 0x10 if bit == "1" else 0)
            code.extend(b"\xad\xb2\xfc" * 4)  # Counter bit 1 is EEPROM clock.

    if write_eeprom:
        store(0xFD8A, 0x10)  # AUDIN output.
        command("1" + "00" + "11" + "0" * 8)  # EWEN, 93C76 word mode.
        command("1" + "01" + "0" * 10 + format(0xA55A, "016b"))
    code.extend(b"\x4c" + struct.pack("<H", loop))
    return b"\x80\x08" + struct.pack(">HH", 0x20A, 10 + len(code)) + b"BS93" + bytes(code)


def ngp_rom(color=True, write_flash=True):
    rom = bytearray(65536)
    rom[:19] = b"ROMMULUS ORIGINAL  "
    struct.pack_into("<I", rom, 0x1C, 0x200040)
    struct.pack_into("<H", rom, 0x20, 1)
    rom[0x23] = 0x10 if color else 0
    rom[0x24:0x30] = b"ROMMULUS E2E"
    code = bytearray()
    if write_flash:
        # LD (24-bit address), immediate byte: command then cartridge data.
        for address, value in ((0x202AAA, 0xAA), (0x204000, 0x5A)):
            code += b"\xf2" + address.to_bytes(3, "little") + bytes((0, value))
    loop = 0x200040 if write_flash else 0x200040 + len(code)
    code += b"\x1b" + loop.to_bytes(3, "little")  # JP24 loop.
    rom[0x40:0x40 + len(code)] = code
    return bytes(rom)


def ngp_flash(*blocks):
    payload = b"".join(struct.pack("<IH2x", address, len(data)) + data
                       for address, data in blocks)
    return struct.pack("<HHI", 0x53, len(blocks), 8 + len(payload)) + payload
