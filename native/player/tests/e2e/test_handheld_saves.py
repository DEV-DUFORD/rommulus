"""Handheld save ABI integration tests, using the production shared libraries.

Run standalone CTest from native/player/tests/handheld, or set ROMM_HANDY_CORE,
ROMM_NGP_CORE and ROMM_HANDHELD_TEST_ROOT to test staged DLLs on Windows.
Ordinary unittest discovery runs fixture tests and skips unavailable binaries.
All generated files stay inside the build tree, including non-ASCII paths.
"""

import ctypes as C
import os
from pathlib import Path
import shutil
import struct
import unittest
import uuid

from handheld_roms import handy_loader, handy_rom, ngp_flash, ngp_rom


class GameInfo(C.Structure):
    _fields_ = [("path", C.c_char_p), ("data", C.c_void_p),
                ("size", C.c_size_t), ("meta", C.c_char_p)]


class Core:
    def __init__(self, library, root):
        self.dll_directories = []
        if os.name == "nt" and os.environ.get("ROMM_HANDHELD_DLL_DIR"):
            self.dll_directories.append(os.add_dll_directory(
                os.environ["ROMM_HANDHELD_DLL_DIR"]))
        self.dll = C.CDLL(str(Path(library).resolve()))
        self.root = root
        self.directory = str(root).encode("utf-8")
        self.frames = 0
        self.audio_frames = 0
        self.callbacks = []

        def environment(command, data):
            if command in (9, 31):  # System and save directories.
                C.cast(data, C.POINTER(C.c_char_p))[0] = self.directory
                return True
            if command == 10:  # Pixel format.
                return True
            if command == 3:  # Can dupe.
                C.cast(data, C.POINTER(C.c_bool))[0] = True
                return True
            return False

        def video(data, width, height, pitch):
            if data and width and height and pitch:
                self.frames += 1

        def audio(data, frames):
            self.audio_frames += frames
            return frames

        signatures = (
            ("retro_set_environment", C.CFUNCTYPE(C.c_bool, C.c_uint, C.c_void_p), environment),
            ("retro_set_video_refresh", C.CFUNCTYPE(None, C.c_void_p, C.c_uint, C.c_uint, C.c_size_t), video),
            ("retro_set_audio_sample", C.CFUNCTYPE(None, C.c_int16, C.c_int16), lambda *_: None),
            ("retro_set_audio_sample_batch", C.CFUNCTYPE(C.c_size_t, C.c_void_p, C.c_size_t), audio),
            ("retro_set_input_poll", C.CFUNCTYPE(None), lambda: None),
            ("retro_set_input_state", C.CFUNCTYPE(C.c_int16, C.c_uint, C.c_uint, C.c_uint, C.c_uint), lambda *_: 0),
        )
        for name, kind, callback in signatures:
            wrapped = kind(callback)
            self.callbacks.append(wrapped)
            getattr(self.dll, name).argtypes = [kind]
            getattr(self.dll, name)(wrapped)
        for name in ("romm_get_save_memory_size", "retro_serialize_size"):
            getattr(self.dll, name).restype = C.c_size_t
        self.dll.romm_get_save_memory_data.restype = C.c_void_p
        self.dll.retro_get_memory_data.argtypes = [C.c_uint]
        self.dll.retro_get_memory_data.restype = C.c_void_p
        for name in ("romm_restore_save_memory", "retro_serialize", "retro_unserialize"):
            getattr(self.dll, name).argtypes = [C.c_void_p, C.c_size_t]
            getattr(self.dll, name).restype = C.c_bool
        self.dll.retro_load_game.argtypes = [C.POINTER(GameInfo)]
        self.dll.retro_load_game.restype = C.c_bool
        self.dll.retro_init()
        self.loaded = False

    def load(self, image, name):
        path = self.root / name
        path.write_bytes(image)
        self.image = C.create_string_buffer(image)
        info = GameInfo(str(path).encode("utf-8"), C.cast(self.image, C.c_void_p),
                        len(image), None)
        if not self.dll.retro_load_game(C.byref(info)):
            raise AssertionError("retro_load_game failed")
        self.loaded = True

    def snapshot(self):
        # Deliberately match the engine's data-before-size checkpoint ordering.
        pointer = self.dll.romm_get_save_memory_data()
        size = self.dll.romm_get_save_memory_size()
        return C.string_at(pointer, size) if pointer and size else b""

    def restore(self, image):
        data = C.create_string_buffer(image)
        return self.dll.romm_restore_save_memory(data, len(image))

    def state(self):
        size = self.dll.retro_serialize_size()
        data = C.create_string_buffer(size)
        if not size or not self.dll.retro_serialize(data, size):
            raise AssertionError("retro_serialize failed")
        return data.raw

    def run(self, frames=3):
        for _ in range(frames):
            self.dll.retro_run()

    def close(self):
        if self.loaded:
            self.dll.retro_unload_game()
        self.dll.retro_deinit()


class FixtureTests(unittest.TestCase):
    def test_original_headers(self):
        self.assertEqual(handy_rom()[:4], b"LYNX")
        self.assertEqual(handy_rom()[60], 4)
        self.assertEqual(handy_loader()[6:10], b"BS93")
        self.assertEqual(ngp_rom()[0x23], 0x10)
        self.assertEqual(ngp_rom(False)[0x23], 0)
        self.assertEqual(ngp_flash(), b"\x53\0\0\0\x08\0\0\0")


class BinaryTests(unittest.TestCase):
    def setUp(self):
        root = Path(os.environ.get("ROMM_HANDHELD_TEST_ROOT", "build/handheld-tests"))
        self.root = root / ("saves-тест-état-" + uuid.uuid4().hex)
        self.root.mkdir(parents=True)
        self.core = None

    def tearDown(self):
        if self.core:
            self.core.close()
        shutil.rmtree(self.root)

    def open(self, key):
        library = os.environ.get(key)
        if not library:
            self.skipTest(key + " not configured")
        self.core = Core(library, self.root)
        return self.core

    def test_handy_eeprom_exact_restore_and_state(self):
        core = self.open("ROMM_HANDY_CORE")
        (self.root / "howard.o").write_bytes(handy_loader())
        core.load(handy_rom(), "original.lnx")
        expected = bytes((i * 37 + 11) & 255 for i in range(1024))
        self.assertEqual(core.snapshot(), b"\xff" * 1024)
        self.assertTrue(core.restore(expected))
        self.assertEqual(core.snapshot(), expected)
        self.assertFalse(core.restore(expected[:-1]))
        self.assertFalse(core.restore(expected + b"\0"))
        self.assertFalse(core.restore(b""))
        self.assertEqual(core.snapshot(), expected)
        state = core.state()
        self.assertTrue(core.restore(b"\xA5" * 1024))
        self.assertTrue(core.dll.retro_unserialize(C.create_string_buffer(state), len(state)))
        self.assertEqual(core.snapshot(), expected)
        core.run()
        self.assertGreater(core.frames, 0)
        self.assertGreater(core.audio_frames, 0)
        core.dll.retro_reset()
        self.assertEqual(core.snapshot(), expected)
        core.close()
        self.core = None
        # A fresh instance also exercises the legacy .eeprom file reader.
        core = self.open("ROMM_HANDY_CORE")
        core.load(handy_rom(), "original.lnx")
        self.assertEqual(core.snapshot(), expected)

    def test_handy_no_eeprom_reports_none(self):
        core = self.open("ROMM_HANDY_CORE")
        (self.root / "howard.o").write_bytes(handy_loader())
        core.load(handy_rom(0), "no-eeprom.lnx")
        self.assertEqual(core.snapshot(), b"")
        self.assertFalse(core.restore(b"\0" * 128))

    def test_handy_in_game_eeprom_write(self):
        core = self.open("ROMM_HANDY_CORE")
        (self.root / "howard.o").write_bytes(handy_loader(write_eeprom=True))
        core.load(handy_rom(), "writer.lnx")
        self.assertTrue(core.restore(b"\x39" * 1024))
        core.run()
        self.assertEqual(core.snapshot(), b"\x5a\xa5" + b"\x39" * 1022)

    def test_handy_largest_eeprom_legacy_file(self):
        core = self.open("ROMM_HANDY_CORE")
        (self.root / "howard.o").write_bytes(handy_loader())
        expected = bytes((i * 37 + 11) & 255 for i in range(2048))
        (self.root / "large.eeprom").write_bytes(expected)
        core.load(handy_rom(5), "large.lnx")
        self.assertEqual(core.snapshot(), expected)

    def test_ngp_flash_write_restore_empty_and_state(self):
        core = self.open("ROMM_NGP_CORE")
        core.load(ngp_rom(), "original.ngc")
        self.assertEqual(core.snapshot(), ngp_flash())
        empty_state = core.state()
        core.run()
        self.assertGreater(core.frames, 0)
        self.assertGreater(core.audio_frames, 0)
        expected = ngp_flash((0x204000, b"\x5A" + b"\0" * 255))
        self.assertEqual(core.snapshot(), expected)
        self.assertTrue(core.dll.retro_unserialize(
            C.create_string_buffer(empty_state), len(empty_state)))
        self.assertEqual(core.snapshot(), ngp_flash())
        core.run()
        self.assertEqual(core.snapshot(), expected)
        state = core.state()
        changed = ngp_flash((0x204000, b"\xB6" * 256))
        self.assertTrue(core.restore(changed))
        self.assertEqual(core.snapshot(), changed)
        self.assertTrue(core.dll.retro_unserialize(C.create_string_buffer(state), len(state)))
        self.assertEqual(core.snapshot(), expected)
        self.assertTrue(core.restore(ngp_flash()))
        self.assertEqual(core.snapshot(), ngp_flash())
        # Replacing, not merging, means a later block restores with no stale bytes.
        self.assertTrue(core.restore(changed))
        self.assertEqual(core.snapshot(), changed)
        core.close()
        self.core = None
        core = self.open("ROMM_NGP_CORE")
        core.load(ngp_rom(False, False), "original.ngc")
        self.assertEqual(core.snapshot(), changed)
        self.assertTrue(core.restore(ngp_flash()))
        core.close()
        self.core = None
        core = self.open("ROMM_NGP_CORE")
        core.load(ngp_rom(False, False), "original.ngc")
        self.assertEqual(core.snapshot(), ngp_flash())

    def test_ngp_malformed_import_is_atomic(self):
        core = self.open("ROMM_NGP_CORE")
        core.load(ngp_rom(write_flash=False), "validation.ngc")
        expected = ngp_flash((0x204000, b"\x35" * 256))
        self.assertTrue(core.restore(expected))
        malformed = [
            b"", expected[:-1], expected + b"\0",
            b"\0\0" + expected[2:],
            struct.pack("<HHI", 0x53, 257, 8),
            ngp_flash((0x4000, b"\xA5")),
            ngp_flash((0x210000, b"\xA5")),
            ngp_flash((0x800000, b"\xA5")),
            ngp_flash((0xFFFFFFFF, b"\xA5")),
            ngp_flash((0x204000, b"")),
            ngp_flash((0x204000, b"\x11"), (0xFFFFFF, b"\x22")),
        ]
        for image in malformed:
            with self.subTest(image=image[:16]):
                self.assertFalse(core.restore(image))
                self.assertEqual(core.snapshot(), expected)

    def test_ngp_optimisation_preserves_contained_blocks(self):
        core = self.open("ROMM_NGP_CORE")
        core.load(ngp_rom(write_flash=False), "overlap.ngc")
        self.assertTrue(core.restore(ngp_flash(
            (0x204000, b"\x35" * 256), (0x204010, b"\x99" * 16))))
        expected = b"\x35" * 16 + b"\x99" * 16 + b"\x35" * 224
        self.assertEqual(core.snapshot(), ngp_flash((0x204000, expected)))

    def test_ngp_large_adjacent_blocks_do_not_wrap(self):
        core = self.open("ROMM_NGP_CORE")
        image = ngp_rom(write_flash=False) + bytes(65536)
        core.load(image, "large.ngc")
        expected = ngp_flash((0x200000, b"\x35" * 65535),
                             (0x20FFFF, b"\x99" * 257))
        self.assertTrue(core.restore(expected))
        self.assertEqual(core.snapshot(), expected)

    def test_ngp_high_bank_restore(self):
        core = self.open("ROMM_NGP_CORE")
        image = ngp_rom(write_flash=False) + bytes(0x200000)
        core.load(image, "high-bank.ngc")
        expected = ngp_flash((0x800000, b"\x73" * 256))
        self.assertTrue(core.restore(expected))
        self.assertEqual(core.snapshot(), expected)

    def test_ngp_fragmented_writes_do_not_overflow_block_table(self):
        core = self.open("ROMM_NGP_CORE")
        image = bytearray(ngp_rom(write_flash=False) + bytes(0x30000))
        code = bytearray()
        expected_rom = bytearray(image)
        for i in range(257):
            address = 0x204001 + i * 512
            for location, value in ((0x202AAA, 0xAA), (address, 0x5A)):
                code += b"\xf2" + location.to_bytes(3, "little") + bytes((0, value))
            expected_rom[address - 0x200000] = 0x5A
        code += b"\x1b" + (0x200040 + len(code)).to_bytes(3, "little")
        image[0x40:0x40 + len(code)] = code
        expected_rom[0x40:0x40 + len(code)] = code
        core.load(bytes(image), "fragmented.ngc")
        core.run(10)
        expected = ngp_flash(*(
            (0x200000 + offset, bytes(expected_rom[offset:offset + 65535]))
            for offset in range(0, len(expected_rom), 65535)))
        self.assertEqual(core.snapshot(), expected)


if __name__ == "__main__":
    unittest.main()
