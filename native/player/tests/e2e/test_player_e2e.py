#!/usr/bin/env python3
# test_player_e2e.py — unit tests for the pure parts of player_e2e.py.
#
# Runs on any host with Python 3 (macOS/Linux/Windows) and NO player binary:
#   python3 -m unittest discover -s native/player/tests/e2e -p "test_*.py" -v
#
# Covers the fixture generator (strict protocol-v2 request JSON), the
# deterministic save-hash oracle, and the strict result-schema validator.

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fceumm_rom  # noqa: E402
import gambatte_rom  # noqa: E402
import player_e2e  # noqa: E402
from player_e2e import (  # noqa: E402
    build_request, expected_save_hash, validate_result_schema,
    sram_byte_after_frames,
    PROTOCOL_VERSION, CORE_ID, CORE_REVISION, SRAM_SIZE, VIDEO_KEYS,
    RESULT_REQUIRED_KEYS, RESULT_KNOWN_KEYS, EXIT_KINDS,
    GAMBATTE_CORE_ID, GAMBATTE_REVISION, GAMBATTE_SRAM_SIZE,
    FCEUMM_CORE_ID, FCEUMM_REVISION, FCEUMM_SRAM_SIZE,
)

# Pinned hash of the deterministic ROM the generator must produce. The
# generator is a pure function of its (commit-fixed) constants, so any byte
# drift — an opcode edit, a header change, a fill change — changes this
# digest and fails the gate.
PINNED_GAMBATTE_ROM_SHA256 = (
    "7eeb9a0ab9bf958dc98b0d04378529dd4687259a1f644e1dbb7e46973e18707d"
)

# Pinned hash of the deterministic iNES ROM fceumm_rom.py must produce
# (16-byte header + 32 KiB PRG + 8 KiB CHR = 40976 bytes).
PINNED_FCEUMM_ROM_SHA256 = (
    "d1d4869696dcf53aeb7f207890d6f0cc7ad87fdcbc3054064fd935f042c281ea"
)


class GambatteRomTest(unittest.TestCase):
    def test_size_and_determinism(self):
        rom1 = gambatte_rom.generate_rom()
        rom2 = gambatte_rom.generate_rom()
        self.assertEqual(len(rom1), gambatte_rom.ROM_SIZE)
        self.assertEqual(len(rom1), 0x8000)  # exactly 32 KiB
        self.assertEqual(rom1, rom2)        # fully deterministic

    def test_pinned_sha256(self):
        self.assertEqual(gambatte_rom.rom_sha256(), PINNED_GAMBATTE_ROM_SHA256)

    def test_header_fields(self):
        rom = gambatte_rom.generate_rom()
        self.assertEqual(rom[0x0134:0x0143], b"ROMMULUS E2E GB")
        self.assertEqual(rom[0x0143], 0xC0, "DMG CGB-flag byte")
        self.assertEqual(rom[0x0144:0x0146], b"RM")
        self.assertEqual(rom[0x0146], 0x00)
        # MBC1 + RAM + battery (Gambatte hasBattery(0x03) == true, no RTC)
        # with 8 KiB RAM (Gambatte rambanks == 1) → 8192-byte battery SRAM.
        self.assertEqual(rom[0x0147], 0x03)
        self.assertEqual(rom[0x0148], 0x00, "32 KiB ROM size code")
        self.assertEqual(rom[0x0149], 0x02)

    def test_header_checksums(self):
        rom = gambatte_rom.generate_rom()
        # Header checksum (0x014D): the actual Game Boy algorithm — the DMG
        # boot ROM subtraction over 0x0134-0x014C.
        c = 0
        for i in range(0x0134, 0x014D):
            c = (c - rom[i] - 1) & 0xFF
        self.assertEqual(rom[0x014D], c)
        # Global checksum (0x014E-0x014F): the 16-bit sum of the whole ROM
        # excluding the two checksum bytes, stored big-endian (high byte
        # first).
        gsum = (sum(rom[:0x014E]) + sum(rom[0x0150:])) & 0xFFFF
        self.assertEqual(rom[0x014E], (gsum >> 8) & 0xFF)
        self.assertEqual(rom[0x014F], gsum & 0xFF)

    def test_no_nintendo_logo_no_sachen_pattern(self):
        rom = gambatte_rom.generate_rom()
        # The vendored Gambatte detector (cartridge.cpp detectSachenMmc1)
        # consults these sums; the original image must match neither.
        self.assertNotEqual(sum(rom[0x0104:0x0134]), 5446,
                            "logo region matches the Nintendo-logo sum")
        scrambled = sum(rom[gambatte_rom._sachen_scramble(0x184 + i)]
                        for i in range(0x30))
        self.assertNotIn(scrambled, (5542, 7484),
                         "logo region matches a Sachen scrambled-logo sum")
        # The logo region is the conventional 0xFF unused-ROM fill — no
        # third-party bytes anywhere in the header window.
        self.assertEqual(set(rom[0x0104:0x0134]), {0xFF})

    def test_entry_point_and_bank1_fill(self):
        rom = gambatte_rom.generate_rom()
        # No-boot-ROM path: the CPU starts at 0x0100 (initstate.cpp sets
        # state.cpu.pc = 0x100; gambatte.cpp only forces pc = 0x0000 when a
        # bootloader is in use). The entry is therefore a `jp 0x0150` at
        # 0x0100, and the RST 0x38 handler (the 0xFF opcode target in
        # cpu.cpp) is a `jp 0x0150` at 0x0038 so a stray RST recovers into
        # the program instead of looping on 0xFF fill.
        jp = bytes([0xC3, 0x50, 0x01])
        self.assertEqual(rom[0x0100:0x0103], jp, "entry at 0x0100")
        self.assertEqual(rom[0x0038:0x003B], jp, "RST 0x38 handler")
        # Bank 1 is entirely unused 0xFF fill.
        self.assertEqual(set(rom[0x4000:0x8000]), {0xFF})

    def test_sram_oracle_fresh(self):
        image = gambatte_rom.expected_sram_image(0)
        self.assertEqual(len(image), 8192)
        self.assertEqual(image[0x0000], 0x52)          # marker
        self.assertEqual(image[0x0001], 0)             # frame counter
        self.assertEqual(image[0x0002], 0)             # 60-frame counter
        self.assertEqual(set(image[3:]), {0xFF})       # untouched fill

    def test_sram_oracle_counter_invariants(self):
        # SRAM[1] = N mod 60, SRAM[2] = N // 60 — the frame-stable
        # invariants the E2E asserts against the reported frame count.
        for n in (1, 59, 60, 61, 120, 241, 480, 481):
            image = gambatte_rom.expected_sram_image(n)
            self.assertEqual(image[0x0001], n % 60, "n=%d" % n)
            self.assertEqual(image[0x0002], n // 60, "n=%d" % n)
            self.assertEqual(image[0x0000], 0x52)

    def test_sram_oracle_restore_semantics(self):
        # A restored run keeps counting: the image for F1+F2 frames differs
        # from both the fresh-F2 image and the run1 image, which is what
        # proves persistence across relaunch.
        f1, f2 = 241, 181
        self.assertNotEqual(gambatte_rom.expected_sram_image(f1 + f2),
                            gambatte_rom.expected_sram_image(f2))
        self.assertNotEqual(gambatte_rom.expected_sram_image(f1 + f2),
                            gambatte_rom.expected_sram_image(f1))

    def test_harness_constants_match_generator(self):
        self.assertEqual(GAMBATTE_SRAM_SIZE, gambatte_rom.SRAM_SIZE)
        self.assertEqual(GAMBATTE_CORE_ID, "gambatte")
        self.assertEqual(GAMBATTE_REVISION,
                         "96174369b3c30d9fc57c926fa3379c273dc6a9a5")


class FceummRomTest(unittest.TestCase):
    def test_size_and_determinism(self):
        rom1 = fceumm_rom.generate_rom()
        rom2 = fceumm_rom.generate_rom()
        # 16-byte iNES header + 32 KiB PRG + 8 KiB CHR = 40976 bytes.
        self.assertEqual(len(rom1), fceumm_rom.ROM_SIZE)
        self.assertEqual(len(rom1), 0xA010)
        self.assertEqual(rom1, rom2)        # fully deterministic

    def test_pinned_sha256(self):
        self.assertEqual(fceumm_rom.rom_sha256(), PINNED_FCEUMM_ROM_SHA256)

    def test_ines_header_fields(self):
        rom = fceumm_rom.generate_rom()
        # Classic iNES layout (ines.c's iNES_HEADER has NO version byte):
        # magic, PRG size code (byte 4), CHR size code (byte 5), flags
        # (byte 6), flags2 (byte 7).
        self.assertEqual(rom[0:4], b"NES\x1a")
        # Mapper 0 (NROM): PRG size code 2 (32 KiB), CHR size code 1 (8 KiB),
        # battery bit set (byte 6 bit 1, FCEUmm: ROM_type & 2), no trainer
        # (byte 6 bit 2, FCEUmm: ROM_type & 4) → the vendored FCEUmm's
        # NROM_Init exposes exactly 8192 bytes of battery WRAM as
        # RETRO_MEMORY_SAVE_RAM.
        self.assertEqual(rom[4], 0x02, "32 KiB PRG size code")
        self.assertEqual(rom[5], 0x01, "8 KiB CHR size code")
        self.assertEqual(rom[6] & 0x02, 0x02, "battery bit set")
        self.assertEqual(rom[6] & 0x04, 0x00, "no trainer")
        # Mapper 0: low nibble = byte 6 >> 4, high nibble = byte 7 & 0xF0.
        self.assertEqual((rom[6] >> 4) | (rom[7] & 0xF0), 0x00, "mapper 0")
        # flags2 marks classic iNES (not NES 2.0): byte 7 & 0x0C == 0.
        self.assertEqual(rom[7] & 0x0C, 0x00, "classic iNES, not NES 2.0")

    def test_entry_vector_and_program_vectors(self):
        rom = fceumm_rom.generate_rom()
        prg = fceumm_rom.PRG_BASE
        # FCEUmm's X6502_Power sets _PC = 0 but then queues FCEU_IQRESET via
        # X6502_Reset(); the first X6502_Run takes that hardware reset and
        # loads PC from $FFFC/$FFFD (PRG offset 0x7FFC, little-endian). The
        # vector must point at the program entry at $8000 = PRG offset 0,
        # where the program begins with `sei` (IRQs off; NMI never enabled).
        self.assertEqual(rom[prg + 0x7FFC:prg + 0x7FFE], bytes([0x00, 0x80]),
                         "reset vector at $FFFC must be the address of $8000")
        self.assertEqual(rom[prg + 0x00], 0x06, "entry 'sei' at PRG offset 0")
        # Restored-cart branch: beq from +0x0A to the display setup at +0x19
        # (next PC 0x0C + offset 0x0D = 0x19).
        self.assertEqual(rom[prg + 0x0A:prg + 0x0C], bytes([0xF0, 0x0D]))
        # VBlank-synchronized counter: wait set (bpl +0x61), inc $6001, wrap
        # at 60, wait clear (bmi +0x78), and the loop's tail `jmp $8061`.
        self.assertEqual(rom[prg + 0x64:prg + 0x66], bytes([0x10, 0xFB]))
        self.assertEqual(rom[prg + 0x66:prg + 0x69], bytes([0xEE, 0x01, 0x60]))
        # Fresh (no-wrap) per-frame path: bne from +0x6E to vbl_wait_end at
        # +0x78 (next PC 0x70 + offset 0x08 = 0x78).
        self.assertEqual(rom[prg + 0x6E:prg + 0x70], bytes([0xD0, 0x08]))
        self.assertEqual(rom[prg + 0x7B:prg + 0x7D], bytes([0x30, 0xFB]))
        self.assertEqual(rom[prg + 0x7D:prg + 0x80], bytes([0x4C, 0x61, 0x80]))

    def test_no_trainer_region_and_original_fill(self):
        rom = fceumm_rom.generate_rom()
        # No trainer bit → the image is exactly header + PRG + CHR; there is
        # no 512-byte trainer region anywhere.
        self.assertEqual(len(rom),
                         fceumm_rom.HEADER_SIZE + fceumm_rom.PRG_SIZE
                         + fceumm_rom.CHR_SIZE)
        # Provenance marker in the never-executed PRG bank 1; the rest of the
        # program region is 0xFF fill — no third-party bytes.
        self.assertTrue(rom[fceumm_rom.PRG_BASE + fceumm_rom.PRG_SIZE // 2:]
                        .startswith(fceumm_rom.PROVENANCE))
        tail = rom[fceumm_rom.PRG_BASE + 0x80:
                   fceumm_rom.PRG_BASE + fceumm_rom.PRG_SIZE // 2]
        self.assertEqual(set(tail), {0xFF})

    def test_sram_oracle_fresh(self):
        # FCEUmm's fresh WRAM is 0x00 (FCEU_gmalloc memsets to zero) — unlike
        # Gambatte's 0xFF fill. After the ppudead offset, counters start at 0.
        image = fceumm_rom.expected_sram_image(fceumm_rom.DEAD_FRAME_OFFSET)
        self.assertEqual(len(image), 8192)
        self.assertEqual(image[0x0000], 0x52)          # marker
        self.assertEqual(image[0x0001], 0)             # frame counter
        self.assertEqual(image[0x0002], 0)             # 60-frame counter
        self.assertEqual(set(image[3:]), {0x00})       # untouched fresh fill

    def test_sram_oracle_counter_invariants(self):
        # SRAM[1] = (N - ppudead_offset) mod 60, SRAM[2] = (N - offset) // 60
        # — the frame-stable invariants the E2E asserts against the reported
        # frame count (the two dead frames carry no VBlank edge).
        off = fceumm_rom.DEAD_FRAME_OFFSET
        for n in (off, off + 1, off + 59, off + 60, off + 61, off + 240):
            image = fceumm_rom.expected_sram_image(n)
            c = n - off
            self.assertEqual(image[0x0001], c % 60, "n=%d" % n)
            self.assertEqual(image[0x0002], c // 60, "n=%d" % n)
            self.assertEqual(image[0x0000], 0x52)

    def test_sram_oracle_restore_semantics(self):
        # A restored run keeps counting: the image for the chain [F1, F2]
        # differs from both the fresh-F2 image and the run1 image, which is
        # what proves persistence across relaunch. Each power-on contributes
        # its own two dead frames (ppudead = 2), so the chain's counted total
        # is (F1-2) + (F2-2), not F1+F2-2 — a single-run oracle for F1+F2
        # would be wrong, and that difference is asserted here.
        f1, f2 = 240, 180
        off = fceumm_rom.DEAD_FRAME_OFFSET
        chain = fceumm_rom.expected_sram_image([f1, f2])
        self.assertNotEqual(chain, fceumm_rom.expected_sram_image([f2]))
        self.assertNotEqual(chain, fceumm_rom.expected_sram_image([f1]))
        self.assertEqual(chain[0x0001], (f1 + f2 - 2 * off) % 60)
        self.assertEqual(chain[0x0002], (f1 + f2 - 2 * off) // 60)

    def test_sram_oracle_accepts_int_as_single_run(self):
        # Convenience form: a bare int is one power-on's reported count.
        self.assertEqual(fceumm_rom.expected_sram_image(242),
                         fceumm_rom.expected_sram_image([242]))

    def test_harness_constants_match_generator(self):
        self.assertEqual(FCEUMM_SRAM_SIZE, fceumm_rom.SRAM_SIZE)
        self.assertEqual(FCEUMM_CORE_ID, "fceumm")
        self.assertEqual(FCEUMM_REVISION,
                         "b5e3566515c27dc66c9c20572171673126532e06")


class BuildRequestTest(unittest.TestCase):
    def test_has_exactly_the_strict_v2_key_set(self):
        req = build_request("s-1", "/c/core.dll", "/d/system", "/d/save.srm",
                            "/st/cand.srm", "/st/result.json")
        # The player's parseRequest() rejects unknown fields; the request must
        # carry EXACTLY its allowed key set (kFields in protocol.cpp).
        allowed = {
            "protocolVersion", "sessionId", "coreId", "coreBuildRevision",
            "corePath", "contentPath", "contentHash", "systemDir",
            "savePath", "candidateSavePath", "resultPath",
            "expectedSaveSize", "video", "controllerBindings",
            "keyboardBindings", "rendererOverride", "theme", "controllerSlots",
        }
        self.assertEqual(set(req), allowed & set(req))  # no unknown keys
        required = {
            "protocolVersion", "sessionId", "coreId", "coreBuildRevision",
            "corePath", "contentPath", "contentHash", "systemDir",
            "savePath", "candidateSavePath", "resultPath",
            "expectedSaveSize", "video",
        }
        self.assertTrue(required <= set(req), "missing required keys")

    def test_field_values(self):
        req = build_request("s-1", "/c/core.dll", "/d/system", "/d/save.srm",
                            "/st/cand.srm", "/st/result.json")
        self.assertEqual(req["protocolVersion"], PROTOCOL_VERSION)
        self.assertEqual(req["coreId"], CORE_ID)
        self.assertEqual(req["coreBuildRevision"], CORE_REVISION)
        self.assertEqual(req["contentPath"], "")      # no-content launch
        self.assertEqual(req["contentHash"], "")      # hash check skipped
        self.assertIsNone(req["expectedSaveSize"])    # present-but-null is legal
        self.assertEqual(set(req["video"]), set(VIDEO_KEYS))
        for key in VIDEO_KEYS:
            self.assertIsInstance(req["video"][key], bool)

    def test_revision_override(self):
        req = build_request("s-1", "/c/core.dll", "/d/system", "/d/save.srm",
                            "/st/cand.srm", "/st/result.json",
                            core_build_revision="999")
        self.assertEqual(req["coreBuildRevision"], "999")

    def test_gambatte_candidate_request(self):
        # The Gambatte candidate launch: real contentPath (the ROM staged
        # under the trusted cache root) and the pinned candidate revision.
        req = build_request("s-gb", "/c/gambatte_core.dll", "/d/system",
                            "/d/save.srm", "/st/cand.srm", "/st/result.json",
                            core_build_revision=GAMBATTE_REVISION,
                            core_id=GAMBATTE_CORE_ID,
                            content_path="/cache état/rommulus-e2e-gambatte.gb")
        self.assertEqual(req["coreId"], GAMBATTE_CORE_ID)
        self.assertEqual(req["coreBuildRevision"], GAMBATTE_REVISION)
        self.assertEqual(req["contentPath"],
                         "/cache état/rommulus-e2e-gambatte.gb")
        self.assertNotIn("\\", req["contentPath"])

    def test_fceumm_candidate_request(self):
        # The FCEUmm candidate launch: real contentPath (the iNES ROM staged
        # under the trusted cache root) and the pinned candidate revision.
        req = build_request("s-nes", "/c/fceumm_core.dll", "/d/system",
                            "/d/save.srm", "/st/cand.srm", "/st/result.json",
                            core_build_revision=FCEUMM_REVISION,
                            core_id=FCEUMM_CORE_ID,
                            content_path="/cache état/rommulus-e2e-fceumm.nes")
        self.assertEqual(req["coreId"], FCEUMM_CORE_ID)
        self.assertEqual(req["coreBuildRevision"], FCEUMM_REVISION)
        self.assertEqual(req["contentPath"],
                         "/cache état/rommulus-e2e-fceumm.nes")
        self.assertNotIn("\\", req["contentPath"])

    def test_paths_are_forward_slash(self):
        req = build_request("s-1", r"C:\cores\test_core.dll", r"D:\data\system",
                            r"D:\data\save.srm", r"S:\state\c.srm",
                            r"S:\state\r.json")
        for key in ("corePath", "systemDir", "savePath", "candidateSavePath",
                    "resultPath"):
            self.assertNotIn("\\", req[key], "%s must be slash form" % key)

    def test_round_trips_through_json(self):
        req = build_request("s-1", "/c/core.dll", "/d/system", "/d/save.srm",
                            "/st/cand.srm", "/st/result.json")
        text = json.dumps(req, indent=2, ensure_ascii=False)
        self.assertEqual(json.loads(text), req)


class ExpectedSaveHashTest(unittest.TestCase):
    def test_matches_manual_sha256(self):
        import hashlib
        for frames in (0, 30, 60, 399, 400, 800):
            image = bytes([sram_byte_after_frames(frames)]) + bytes(SRAM_SIZE - 1)
            self.assertEqual(expected_save_hash(frames),
                             hashlib.sha256(image).hexdigest())

    def test_sram_byte_counts_60_frame_intervals(self):
        # Increments fire at frame_count 0, 60, 120, ... (start of each run),
        # so the count over rendered frames 0..N-1 is the number of multiples
        # of 60 in [0, N-1] = (N-1)//60 + 1 for N > 0.
        self.assertEqual(sram_byte_after_frames(0), 0)
        self.assertEqual(sram_byte_after_frames(1), 1)
        self.assertEqual(sram_byte_after_frames(59), 1)
        self.assertEqual(sram_byte_after_frames(60), 1)   # only frame_count 0 fired
        self.assertEqual(sram_byte_after_frames(61), 2)   # 0 and 60 fired
        self.assertEqual(sram_byte_after_frames(400), 7)  # run1 checkpoint
        self.assertEqual(sram_byte_after_frames(800), 14)

    def test_restore_semantics(self):
        # The harness' relaunch assertion: a RESTORED core keeps byte 0 (7)
        # and adds another 7 increments over its own frames 0..399 → 14,
        # which equals the fresh-core oracle for 800 frames — but differs from
        # run1's 7, which is what proves the restore happened.
        self.assertNotEqual(expected_save_hash(400), expected_save_hash(800))

    def test_distinct_images_distinct_hashes(self):
        hashes = {expected_save_hash(f) for f in (0, 60, 120, 400)}
        self.assertEqual(len(hashes), 4)


class ValidateResultSchemaTest(unittest.TestCase):
    def make_result(self, **overrides):
        obj = {
            "protocolVersion": PROTOCOL_VERSION,
            "sessionId": "s-1",
            "exitKind": "core_requested_shutdown",
            "checkpointWritten": True,
            "candidateSavePath": "/st/c.srm",
            "saveHash": "ab" * 32,
            "saveSize": SRAM_SIZE,
            "frames": 400,
            "audioUnderrunFrames": 0,
            "audioOverrunFrames": 0,
            "errorCode": None,
            "errorMessage": None,
        }
        obj.update(overrides)
        return obj

    def test_well_formed_result_passes(self):
        self.assertEqual(validate_result_schema(self.make_result()), [])

    def test_video_optional_and_validated(self):
        self.assertEqual(validate_result_schema(
            self.make_result(video={k: False for k in VIDEO_KEYS})), [])
        problems = validate_result_schema(self.make_result(video={"oops": True}))
        self.assertTrue(any("unknown video field" in p for p in problems))

    def test_unknown_field_rejected(self):
        problems = validate_result_schema(self.make_result(token="x"))
        self.assertEqual(problems, ["unknown field: token"])

    def test_missing_required_rejected(self):
        obj = self.make_result()
        del obj["frames"]
        problems = validate_result_schema(obj)
        self.assertTrue(any(p.startswith("missing required field: frames")
                            for p in problems))

    def test_bad_types_rejected(self):
        cases = (
            {"protocolVersion": "2"},
            {"exitKind": "exploded"},
            {"checkpointWritten": "yes"},
            {"saveSize": -1},
            {"frames": 400.5},
            {"audioUnderrunFrames": True},   # bool is not an int here
            {"saveHash": 42},
        )
        for overrides in cases:
            with self.subTest(overrides=overrides):
                self.assertTrue(validate_result_schema(self.make_result(**overrides)))

    def test_null_optionals_allowed(self):
        self.assertEqual(validate_result_schema(
            self.make_result(saveHash=None, saveSize=None)), [])

    def test_non_object_rejected(self):
        self.assertTrue(validate_result_schema([1, 2, 3]))

    def test_exit_kinds_match_player(self):
        # Keep the harness enum in lockstep with protocol.cpp's toString().
        self.assertEqual(tuple(EXIT_KINDS), (
            "completed", "user_cancelled_before_start",
            "core_requested_shutdown", "launch_failed", "runtime_failed"))
        self.assertEqual(len(RESULT_KNOWN_KEYS), len(RESULT_REQUIRED_KEYS) + 1)


class AsPosixTest(unittest.TestCase):
    def test_forward_slashes(self):
        out = player_e2e.as_posix(os.path.join("a", "b c", "тест"))
        self.assertNotIn("\\", out)
        self.assertTrue(out.startswith(("/", os.sep[0])))


if __name__ == "__main__":
    unittest.main(verbosity=2)
