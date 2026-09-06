import struct
import unittest

import n64_rom


class N64RomTest(unittest.TestCase):
    def test_original_header_and_boot_entry(self):
        rom = n64_rom.generate_rom()
        self.assertEqual(len(rom), 8192)
        self.assertEqual(struct.unpack_from(">I", rom)[0], 0x80371240)
        self.assertEqual(struct.unpack_from(">I", rom, 8)[0], 0xA4000040)
        self.assertEqual(rom[0x3E], ord("E"))
        self.assertEqual(rom[0x1000:], bytes(4096))
        self.assertEqual(rom, n64_rom.generate_rom())

    def test_branch_delay_slot_and_fixup(self):
        program = n64_rom.Mips()
        program.label("again")
        program.li(8, 0x12345678)
        program.branch(8, 0, "again", unequal=True)
        words = struct.unpack(">4I", program.finish())
        self.assertEqual(words, (0x3C081234, 0x35085678, 0x1500FFFD, 0))

    def test_fixture_has_no_branch_outside_original_ipl3(self):
        rom = n64_rom.generate_rom()
        words = struct.unpack(">1008I", rom[0x40:0x1000])
        branches = 0
        for i, word in enumerate(words):
            if word >> 26 not in (4, 5):
                continue
            branches += 1
            displacement = word & 0xFFFF
            if displacement >= 0x8000:
                displacement -= 0x10000
            self.assertGreaterEqual(i + 1 + displacement, 0)
            self.assertLess(i + 1 + displacement, len(words))
            self.assertEqual(words[i + 1], 0)
        self.assertGreaterEqual(branches, 5)

    def test_upstream_save_layout(self):
        self.assertEqual(n64_rom.SRAM_OFFSET, 0x20800)
        self.assertEqual(n64_rom.SAVE_SIZE, 0x48800)


if __name__ == "__main__":
    unittest.main()
