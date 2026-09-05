#!/usr/bin/env python3
# player_e2e.py — host-portable synthetic-core E2E harness for rommulus-player.
#
# Drives a STAGED player (rommulus-player.exe + test_core.dll on Windows,
# rommulus_player + libtest_core.so on POSIX) through deterministic
# protocol-v2 scenarios against the app-owned synthetic core
# (app/src/main/cpp/test_core/test_core.c). Python 3 standard library only —
# no third-party imports — so it runs unchanged on macOS/Linux dev hosts and
# on the GUI-less windows-2022 CI runner.
#
# What it does:
#   - creates a temp root tree whose directory names contain SPACES and
#     NON-ASCII (Cyrillic + accented Latin) characters, laid out as the four
#     trusted roots (ROMM_PLAYER_CORE_ROOT / _CACHE_ROOT / _DATA_ROOT /
#     _STATE_ROOT) plus an "outside" directory that sits OUTSIDE every root;
#   - stages a copy of test_core under the core root (and one outside it for
#     the negative path-containment scenario);
#   - emits strict protocol-v2 request JSON (the exact key set the player's
#     strict parser accepts — no unknown fields, expectedSaveSize present as
#     null, video object with exactly its four boolean fields);
#   - launches the player through subprocess APIs with a per-scenario
#     environment (allowed core revision "test_core=1", headless SDL:
#     SDL_VIDEODRIVER=offscreen + SDL_RENDER_DRIVER=software +
#     SDL_AUDIODRIVER=dummy, and test_core's CI-only ROMM_TEST_CORE_MAX_FRAMES
#     hook so a no-content launch reaches the core's own
#     RETRO_ENVIRONMENT_SHUTDOWN with zero interactive input);
#   - asserts the result JSON schema/session/exitKind/frame count and the
#     checkpoint save size + SHA256 (test_core's 64-byte SRAM is fully
#     deterministic: byte 0 counts 60-frame intervals, all other bytes are
#     zero), repeated launch with save restore, concurrent same-session lock
#     rejection, force-kill → kernel lock release → relaunch, negative
#     validation (revision mismatch, core outside root), and finally that no
#     spawned PID survives and the whole state tree deletes cleanly;
#   - drives the CANDIDATE Gambatte core (cores-candidate/gambatte_core.dll
#     on Windows, libgambatte_core.so/.dylib on POSIX) against a fully
#     original, deterministic 32 KiB Game Boy ROM generated in-process by
#     gambatte_rom.py (no Nintendo logo, no third-party bytes) and staged
#     into the trusted CACHE root (the player's validation contract requires
#     contentPath under cacheRoot). The ROM boots through the vendored
#     Gambatte's default no-boot-ROM path, uses the MBC1 + RAM + battery
#     combination (header 0x0147=0x03) with 8 KiB RAM (0x0149=0x02) that
#     Gambatte exposes as exactly 8192 bytes of battery SRAM, enables SRAM
#     on every boot, and mutates SRAM deterministically: a 0x52 marker plus
#     a frame counter that increments exactly once per presented frame
#     (synchronized to the LY==0 → LY!=0 VBlank edge) with a 60-frame
#     interval counter. Each Gambatte launch runs under the player's
#     qualification-only ROMM_PLAYER_MAX_FRAMES presented-frame bound
#     (compiled in via ROMM_PLAYER_QUALIFICATION in the Windows software-only
#     candidate CI build) and the harness asserts: real frames (bounded
#     [limit, limit+2]), result schema, exitKind=completed (player-initiated,
#     checkpoint written before stop), 8192-byte SRAM, and the deterministic
#     marker/counter invariants against the player-reported frame count —
#     not brittle instruction counts. Scenarios cover the candidate's
#     adoption (candidate save moved to the session save path, like the
#     desktop supervisor) with counter persistence across relaunch, repeated
#     load of the same ROM+save, and force-kill → lock release → relaunch.
#   - drives the CANDIDATE FCEUmm core (cores-candidate/fceumm_core.dll on
#     Windows, libfceumm_core.so/.dylib on POSIX) against a fully original,
#     deterministic 40976-byte iNES ROM generated in-process by fceumm_rom.py
#     (mapper 0 NROM + battery bit → the vendored FCEUmm's 8192-byte WRAM as
#     RETRO_MEMORY_SAVE_RAM; no trainer, no third-party bytes) and staged into
#     the trusted CACHE root. The ROM boots at $8000 (X6502_Power starts PC at
#     0), writes real tiles + name table to VRAM and enables the background
#     (real software video), configures APU pulse channel 1 (real audio), and
#     mutates SRAM deterministically: a 0x52 marker plus a frame counter that
#     increments exactly once per presented frame (synchronized to the PPU
#     $2002 VBlank bit's set→clear window) with a 60-frame interval counter.
#     FCEUPPU_Reset's ppudead = 2 means the first two frames carry no VBlank
#     edge, so after N reported frames the SRAM counter holds exactly
#     N - 2 — the harness asserts that exact invariant (plus offset-free
#     deltas across the relaunch chain) against the player-reported frame
#     count. Each FCEUmm launch runs under the same qualification-only
#     ROMM_PLAYER_MAX_FRAMES presented-frame bound and the same scenario set:
#     initial completed run with checkpoint, candidate adoption + restore,
#     repeated load, force-kill → lock release → relaunch.
#
# Scenarios are bounded by per-launch timeouts; cleanup targets the EXACT
# PIDs this harness spawned (Windows: TerminateProcess via ctypes; POSIX:
# SIGKILL). Never a broad taskkill/kill-by-name.
#
# Usage:
#   python3 player_e2e.py --stage <staged artifact dir> --workdir <out dir>
#   python3 player_e2e.py --verify-artifact <staged artifact dir>
#       (Windows job pre-flight: verify every staged file against the
#        import-audit.txt SHA256 section before running any scenario)
#
# Exit codes: 0 = all scenarios passed; 1 = a scenario failed; 2 = usage /
# environment error.

import argparse
import ctypes
import hashlib
import json
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import fceumm_rom  # noqa: E402
import gambatte_rom  # noqa: E402
import prosystem_rom  # noqa: E402
import wswan_rom  # noqa: E402

IS_WINDOWS = os.name == "nt"

PROTOCOL_VERSION = 2
CORE_ID = "test_core"
CORE_REVISION = "1"          # CoreManifest.kt releaseTag pin ("test_core=1")
SRAM_SIZE = 64               # test_core TEST_CORE_SRAM_SIZE
SHUTDOWN_MAX_FRAMES = 400    # frames rendered before the core requests shutdown

# Candidate Gambatte core (NOT advertised in any manifest — staged under
# cores-candidate/ and exercised here as a qualification gate only).
GAMBATTE_CORE_ID = "gambatte"
# The pinned vendored-tree commit (third_party/cores/gambatte,
# libretro/gambatte-libretro master HEAD) — the coreBuildRevision the
# allowed-core entry and every Gambatte launch request must carry.
GAMBATTE_REVISION = "96174369b3c30d9fc57c926fa3379c273dc6a9a5"
GAMBATTE_SRAM_SIZE = gambatte_rom.SRAM_SIZE   # 8192 bytes of battery SRAM
GAMBATTE_ROM_NAME = "rommulus-e2e-gambatte.gb"

# Presented-frame bounds for the Gambatte qualification runs (the player's
# ROMM_PLAYER_MAX_FRAMES hook; the reported frame count lands in
# [limit, limit + 2] — see assert_gambatte_result).
GAMBATTE_RUN1_FRAMES = 240     # ~4.0 s of emulated time
GAMBATTE_RUN2_FRAMES = 180     # ~3.0 s (restored counters keep counting)
GAMBATTE_RUN3_FRAMES = 60      # ~1.0 s (repeated load of the same save)
GAMBATTE_KILL_VICTIM_FRAMES = 3600  # ~60 s budget: alive when force-killed

# Candidate FCEUmm core (NOT advertised in any manifest — staged under
# cores-candidate/ and exercised here as a qualification gate only).
FCEUMM_CORE_ID = "fceumm"
# The pinned vendored-tree commit (third_party/cores/fceumm, identical to the
# approved Android/Linux pin in CoreManifest.kt) — the coreBuildRevision the
# allowed-core entry and every FCEUmm launch request must carry.
FCEUMM_REVISION = "b5e3566515c27dc66c9c20572171673126532e06"
FCEUMM_SRAM_SIZE = fceumm_rom.SRAM_SIZE   # 8192 bytes of battery WRAM
FCEUMM_ROM_NAME = "rommulus-e2e-fceumm.nes"

# Presented-frame bounds for the FCEUmm qualification runs (same semantics as
# the Gambatte ones: reported frame count lands in [limit, limit + 2]).
FCEUMM_RUN1_FRAMES = 240     # ~4.0 s of emulated time
FCEUMM_RUN2_FRAMES = 180     # ~3.0 s (restored counters keep counting)
FCEUMM_RUN3_FRAMES = 60      # ~1.0 s (repeated load of the same save)
FCEUMM_KILL_VICTIM_FRAMES = 3600  # ~60 s budget: alive when force-killed

# Candidate ProSystem core (NOT advertised in any manifest — staged under
# cores-candidate/ and exercised here as a qualification gate only).
PROSYSTEM_CORE_ID = "prosystem"
# The pinned vendored-tree commit (third_party/cores/prosystem,
# libretro/prosystem-libretro master HEAD) — the coreBuildRevision the
# allowed-core entry and every ProSystem launch request must carry.
PROSYSTEM_REVISION = "363b6dfbd3e240762e022c2b4897b4fe55722be3"
PROSYSTEM_ROM_NAME = "rommulus-e2e-prosystem.a78"

# ProSystem exposes NO save RAM at this pin: retro_get_memory_size(
# RETRO_MEMORY_SAVE_RAM) returns 0 (only SYSTEM_RAM). The qualification runs
# therefore assert a rigorous NO-PERSISTENT-SAVE gate instead of SRAM
# invariants: checkpointWritten false, saveSize/saveHash null, and zero .srm
# artifacts anywhere for the session. There is no adoption chain because no
# run ever produces a candidate save — repeated loads are fresh, independent
# runs of the same ROM. Presented-frame bounds keep the same semantics as the
# other candidates (reported frame count lands in [limit, limit + 2]).
PROSYSTEM_RUN_FRAMES = 240     # ~4.0 s of emulated time per run
PROSYSTEM_KILL_VICTIM_FRAMES = 3600  # ~60 s budget: alive when force-killed

# Candidate mednafen_wswan core (NOT advertised in any manifest — staged
# under cores-candidate/ and exercised here as a qualification gate only).
WSWAN_CORE_ID = "mednafen_wswan"
# The pinned vendored-tree commit (third_party/cores/mednafen_wswan,
# libretro/beetle-wswan-libretro master HEAD) — the coreBuildRevision the
# allowed-core entry and every mednafen_wswan launch request must carry.
WSWAN_REVISION = "4b01295838ea89e3f1355bbe4cb5cf98aa6108cd"
WSWAN_SRAM_SIZE = wswan_rom.SRAM_SIZE   # 8192 bytes of battery SRAM (cart header code 0x01)
WSWAN_ROM_NAME = "rommulus-e2e-wswan.ws"

# Presented-frame bounds for the mednafen_wswan qualification runs (same
# semantics as the other candidates: reported frame count lands in
# [limit, limit + 2]). This core exposes a real 8 KiB battery region, so —
# like Gambatte/FCEUmm — the gate asserts the deterministic SRAM oracle
# across the adoption chain: each power-on of F reported frames executes
# exactly wswan_rom.run_iterations(F) = 3392*F - 299 counter iterations
# (first frame is a 145-line/37120-cycle warm-up after GfxReset's wsLine=0;
# every later frame is 159 lines x 256 cycles = 40704, and the loop costs
 # exactly 12 core-model cycles); the counter LIVES IN battery SRAM at
 # SRAM[0x100] (mirrored to SRAM[0]), so restored saves keep counting.
WSWAN_RUN1_FRAMES = 240     # ~4.0 s of emulated time
WSWAN_RUN2_FRAMES = 180     # ~3.0 s (restored counters keep counting)
WSWAN_RUN3_FRAMES = 60      # ~1.0 s (repeated load of the same save)
WSWAN_KILL_VICTIM_FRAMES = 3600  # ~60 s budget: alive when force-killed


def sram_byte_after_frames(frames):
    """test_core's SRAM byte 0 after `frames` rendered frames.

    The core increments sram[0] at the START of each retro_run whose
    frame_count is a multiple of 60; over frames 0..frames-1 that fires once
    per multiple of 60 in [0, frames-1] — (frames-1)//60 + 1 for frames > 0.
    Bytes 1..63 stay zero.
    """
    if frames <= 0:
        return 0
    return (frames - 1) // 60 + 1

RESULT_REQUIRED_KEYS = (
    "protocolVersion", "sessionId", "exitKind", "checkpointWritten",
    "candidateSavePath", "saveHash", "saveSize", "frames",
    "audioUnderrunFrames", "audioOverrunFrames", "errorCode", "errorMessage",
)
RESULT_KNOWN_KEYS = RESULT_REQUIRED_KEYS + ("video",)
VIDEO_KEYS = ("fullscreen", "integerScaling", "scanlines", "sharpFilter")
EXIT_KINDS = (
    "completed", "user_cancelled_before_start", "core_requested_shutdown",
    "launch_failed", "runtime_failed",
)


# ---------------------------------------------------------------------------
# Pure helpers (unit-tested in test_player_e2e.py — no player required)
# ---------------------------------------------------------------------------

def build_request(session_id, core_path, system_dir, save_path,
                  candidate_save_path, result_path,
                  core_build_revision=CORE_REVISION, core_id=CORE_ID,
                  content_path=""):
    """One strict protocol-v2 launch request (LINUX_X64.md section 12.2).

    Every path is emitted in forward-slash form: native on POSIX and the
    canonical form the player's Win32 layer converts to UTF-16. contentPath
    is empty by default — test_core is a no-content core (SET_SUPPORT_NO_
    GAME); the Gambatte candidate scenarios pass the ROM staged under the
    trusted cache root (the player's validation contract requires a
    non-empty contentPath to stay inside cacheRoot).
    """
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "sessionId": session_id,
        "coreId": core_id,
        "coreBuildRevision": core_build_revision,
        "corePath": as_posix(core_path),
        "contentPath": as_posix(content_path) if content_path else "",
        "contentHash": "",
        "systemDir": as_posix(system_dir),
        "savePath": as_posix(save_path),
        "candidateSavePath": as_posix(candidate_save_path),
        "resultPath": as_posix(result_path),
        "expectedSaveSize": None,
        "video": {k: False for k in VIDEO_KEYS},
    }


def expected_save_hash(rendered_frames):
    """SHA256 of the exact 64-byte SRAM checkpoint after `rendered_frames`.

    test_core zeros its SRAM at init and increments byte 0 once per 60
    rendered frames; nothing else ever writes SRAM, so the whole image is
    predictable and the player-reported saveHash must match it exactly.
    """
    image = bytes([sram_byte_after_frames(rendered_frames)]) + bytes(SRAM_SIZE - 1)
    return hashlib.sha256(image).hexdigest()


def validate_result_schema(obj):
    """Strict result-JSON validation mirroring the player's parseResult().

    Returns a list of human-readable problems (empty = valid). Unknown keys
    are rejected exactly as the player rejects them, so a schema typo on
    either side fails the gate.
    """
    if not isinstance(obj, dict):
        return ["top-level JSON must be an object"]
    problems = []
    for key in RESULT_REQUIRED_KEYS:
        if key not in obj:
            problems.append("missing required field: %s" % key)
    for key in obj:
        if key not in RESULT_KNOWN_KEYS:
            problems.append("unknown field: %s" % key)
    if problems:
        return problems
    if not _is_int(obj["protocolVersion"]) or obj["protocolVersion"] != PROTOCOL_VERSION:
        problems.append("protocolVersion must be the integer %d" % PROTOCOL_VERSION)
    if not isinstance(obj["sessionId"], str) or not obj["sessionId"]:
        problems.append("sessionId must be a non-empty string")
    if not isinstance(obj["exitKind"], str) or obj["exitKind"] not in EXIT_KINDS:
        problems.append("unknown exitKind: %r" % (obj.get("exitKind"),))
    if not isinstance(obj["checkpointWritten"], bool):
        problems.append("checkpointWritten must be a boolean")
    if not isinstance(obj["candidateSavePath"], str):
        problems.append("candidateSavePath must be a string")
    for key in ("saveHash", "errorCode", "errorMessage"):
        if obj[key] is not None and not isinstance(obj[key], str):
            problems.append("%s must be null or a string" % key)
    if obj["saveSize"] is not None and (not _is_int(obj["saveSize"]) or obj["saveSize"] < 0):
        problems.append("saveSize must be null or a non-negative integer")
    for key in ("frames", "audioUnderrunFrames", "audioOverrunFrames"):
        if not _is_int(obj[key]) or obj[key] < 0:
            problems.append("%s must be a non-negative integer" % key)
    if "video" in obj and obj["video"] is not None:
        video = obj["video"]
        if not isinstance(video, dict):
            problems.append("video must be an object")
        else:
            for key in VIDEO_KEYS:
                if not isinstance(video.get(key), bool):
                    problems.append("video field %s must be a boolean" % key)
            for key in video:
                if key not in VIDEO_KEYS:
                    problems.append("unknown video field: %s" % key)
    return problems


def _is_int(value):
    # bool is an int subclass in Python; the JSON schema forbids it here.
    return isinstance(value, int) and not isinstance(value, bool)


def as_posix(path):
    """Forward-slash absolute form (Win32 APIs accept 'C:/...'; POSIX native).

    Both separator styles are normalized on every host: the fixture only ever
    contains paths this harness built, and a literal backslash can never be
    part of one — so converting unconditionally keeps request JSON canonical
    even when a Windows-style path is passed in on a POSIX test host.
    """
    return os.path.abspath(str(path)).replace(os.sep, "/").replace("\\", "/")


# ---------------------------------------------------------------------------
# Process control (exact PIDs only)
# ---------------------------------------------------------------------------

class PlayerProcess:
    """One rommulus-player launch with captured output and bounded lifetime."""

    def __init__(self, exe, request_path, env, log_path):
        self.exe = exe
        self.request_path = request_path
        self.env = env
        self.log_path = log_path
        self.proc = None
        self.returncode = None
        self.timed_out = False

    def start(self):
        kwargs = dict(
            cwd=os.path.dirname(self.exe) or ".",
            env=self.env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        if IS_WINDOWS:
            # No console window on the GUI-less runner; the PE loader still
            # resolves the full import closure from the app directory.
            kwargs["creationflags"] = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        self.proc = subprocess.Popen(
            [self.exe, "--request", self.request_path], **kwargs)

    def wait(self, timeout_sec):
        """Wait up to `timeout_sec`; on expiry terminate the EXACT pid."""
        deadline = time.monotonic() + timeout_sec
        pump = threading.Thread(target=self._pump_output, daemon=True)
        pump.start()
        while self.proc.poll() is None:
            if time.monotonic() >= deadline:
                self.timed_out = True
                self.terminate()
                break
            time.sleep(0.1)
        pump.join(timeout=5)
        self.returncode = self.proc.wait()
        return self.returncode

    def _pump_output(self):
        with open(self.log_path, "wb") as log:
            while True:
                chunk = self.proc.stdout.read(4096)
                if not chunk:
                    break
                log.write(chunk)

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def pid(self):
        return self.proc.pid if self.proc else None

    def terminate(self):
        """Force-terminate the EXACT pid (TerminateProcess / SIGKILL)."""
        pid = self.pid()
        if pid is None:
            return
        try:
            if IS_WINDOWS:
                PROCESS_TERMINATE = 0x0001
                kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
                handle = kernel32.OpenProcess(PROCESS_TERMINATE, False, pid)
                if handle:
                    kernel32.TerminateProcess(handle, 1)
                    kernel32.CloseHandle(handle)
            else:
                os.kill(pid, signal.SIGKILL)
        except OSError as exc:
            print("warning: terminate of pid %d failed: %s" % (pid, exc))
        try:
            self.proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            pass


def pid_alive(pid):
    if pid is None:
        return False
    try:
        if IS_WINDOWS:
            # A retained process object can still be opened after exit. Query
            # its signaled state instead of treating an open handle as alive.
            kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
            handle = kernel32.OpenProcess(0x00100000, False, pid)  # SYNCHRONIZE
            if handle:
                wait = kernel32.WaitForSingleObject(handle, 0)
                kernel32.CloseHandle(handle)
                return wait == 0x00000102  # WAIT_TIMEOUT
            return ctypes.get_last_error() not in (3, 87)  # NOT_FOUND / INVALID_PARAMETER
        os.kill(pid, 0)
        return True
    except OSError:
        return False


# ---------------------------------------------------------------------------
# Fixture tree + scenario runner
# ---------------------------------------------------------------------------

class Runner:
    def __init__(self, stage_dir, workdir, player_exe, core_dll, timeout_sec,
                 video_driver="offscreen", audio_driver="dummy",
                 render_driver="software", candidate_core=None,
                 fceumm_candidate_core=None, prosystem_candidate_core=None,
                 wswan_candidate_core=None):
        self.stage_dir = os.path.abspath(stage_dir)
        self.workdir = os.path.abspath(workdir)
        self.player_exe = os.path.abspath(player_exe)
        self.core_dll = os.path.abspath(core_dll)
        self.candidate_core = (os.path.abspath(candidate_core)
                               if candidate_core else None)
        self.fceumm_candidate_core = (os.path.abspath(fceumm_candidate_core)
                                       if fceumm_candidate_core else None)
        self.prosystem_candidate_core = (os.path.abspath(prosystem_candidate_core)
                                          if prosystem_candidate_core else None)
        self.wswan_candidate_core = (os.path.abspath(wswan_candidate_core)
                                      if wswan_candidate_core else None)
        self.timeout_sec = timeout_sec
        self.video_driver = video_driver
        self.audio_driver = audio_driver
        self.render_driver = render_driver
        # SPACES + NON-ASCII in every root name: the point of this fixture is
        # that path handling (UTF-8 env contract, request JSON, lock paths)
        # survives "тест état" on both platforms.
        stamp = time.strftime("%Y%m%d-%H%M%S")
        self.base = os.path.join(tempfile.gettempdir(),
                                 "rommulus-e2e-%s-тест état" % stamp)
        self.core_root = os.path.join(self.base, "cores тест")
        self.cache_root = os.path.join(self.base, "cache état")
        self.data_root = os.path.join(self.base, "data données")
        self.state_root = os.path.join(self.base, "state état")
        self.outside_root = os.path.join(self.base, "outside no root")
        self.requests_dir = os.path.join(self.base, "requests reqüêtes")
        self.logs_dir = os.path.join(self.workdir, "logs")
        self.results_dir = os.path.join(self.workdir, "results")
        self.spawned_pids = []
        self.scenarios = []

    # -- fixture ----------------------------------------------------------

    def create_tree(self):
        for d in (self.core_root, self.cache_root, self.data_root,
                  self.state_root, self.outside_root, self.requests_dir,
                  self.logs_dir, self.results_dir):
            os.makedirs(d, exist_ok=True)
        shutil.copyfile(self.core_dll, os.path.join(self.core_root, self.core_filename()))
        shutil.copyfile(self.core_dll, os.path.join(
            self.outside_root, "outside_" + self.core_filename()))
        if self.candidate_core:
            # The candidate Gambatte core is staged into the trusted core
            # root under its canonical name (the player's corePath
            # containment check requires it) and the generated ROM into the
            # trusted CACHE root (contentPath containment).
            shutil.copyfile(self.candidate_core,
                            os.path.join(self.core_root, self.candidate_core_filename()))
            self.generate_rom()
        if self.fceumm_candidate_core:
            # The candidate FCEUmm core is staged the same way (canonical
            # name under the trusted core root) and its generated iNES ROM
            # into the trusted CACHE root.
            shutil.copyfile(self.fceumm_candidate_core,
                            os.path.join(self.core_root, self.fceumm_core_filename()))
            self.generate_fceumm_rom()
        if self.prosystem_candidate_core:
            # The candidate ProSystem core is staged the same way (canonical
            # name under the trusted core root) and its generated 16 KiB
            # .a78 ROM into the trusted CACHE root.
            shutil.copyfile(self.prosystem_candidate_core,
                            os.path.join(self.core_root, self.prosystem_core_filename()))
            self.generate_prosystem_rom()
        if self.wswan_candidate_core:
            # The candidate mednafen_wswan core is staged the same way
            # (canonical name under the trusted core root) and its generated
            # 512 KiB .ws ROM into the trusted CACHE root.
            shutil.copyfile(self.wswan_candidate_core,
                            os.path.join(self.core_root, self.wswan_core_filename()))
            self.generate_wswan_rom()

    def core_filename(self):
        return "test_core.dll" if IS_WINDOWS else "libtest_core.so"

    def candidate_core_filename(self):
        if IS_WINDOWS:
            return "gambatte_core.dll"
        return "libgambatte_core.so"

    def fceumm_core_filename(self):
        if IS_WINDOWS:
            return "fceumm_core.dll"
        if sys.platform == "darwin":
            return "libfceumm_core.dylib"
        return "libfceumm_core.so"

    def prosystem_core_filename(self):
        if IS_WINDOWS:
            return "prosystem_core.dll"
        if sys.platform == "darwin":
            return "libprosystem_core.dylib"
        return "libprosystem_core.so"

    def wswan_core_filename(self):
        if IS_WINDOWS:
            return "mednafen_wswan_core.dll"
        if sys.platform == "darwin":
            return "libmednafen_wswan_core.dylib"
        return "libmednafen_wswan_core.so"

    def generate_rom(self):
        """Generate the deterministic 32 KiB ROM into the cache root."""
        rom_path = os.path.join(self.cache_root, GAMBATTE_ROM_NAME)
        rom = gambatte_rom.generate_rom()
        with open(rom_path, "wb") as f:
            f.write(rom)
        return rom_path

    def generate_fceumm_rom(self):
        """Generate the deterministic iNES ROM into the cache root."""
        rom_path = os.path.join(self.cache_root, FCEUMM_ROM_NAME)
        rom = fceumm_rom.generate_rom()
        with open(rom_path, "wb") as f:
            f.write(rom)
        return rom_path

    def generate_prosystem_rom(self):
        """Generate the deterministic 16 KiB .a78 ROM into the cache root."""
        rom_path = os.path.join(self.cache_root, PROSYSTEM_ROM_NAME)
        rom = prosystem_rom.generate_rom()
        with open(rom_path, "wb") as f:
            f.write(rom)
        return rom_path

    def generate_wswan_rom(self):
        """Generate the deterministic 512 KiB .ws ROM into the cache root."""
        rom_path = os.path.join(self.cache_root, WSWAN_ROM_NAME)
        rom = wswan_rom.generate_rom()
        with open(rom_path, "wb") as f:
            f.write(rom)
        return rom_path

    def gambatte_core_path(self):
        return os.path.join(self.core_root, self.candidate_core_filename())

    def fceumm_core_path(self):
        return os.path.join(self.core_root, self.fceumm_core_filename())

    def prosystem_core_path(self):
        return os.path.join(self.core_root, self.prosystem_core_filename())

    def wswan_core_path(self):
        return os.path.join(self.core_root, self.wswan_core_filename())

    def env_for(self, max_frames=None, player_max_frames=None):
        env = dict(os.environ)
        # Sanitized loader PATH (Windows): staged artifact bin + system dirs
        # only — never the MSYS2 toolchain bin. On POSIX the ambient PATH is
        # fine (the player resolves SDL3 from its install prefix).
        if IS_WINDOWS:
            system_root = os.environ.get("SystemRoot", r"C:\Windows")
            env["PATH"] = os.pathsep.join((
                os.path.join(self.stage_dir, "bin"),
                os.path.join(system_root, "System32"),
                system_root,
            ))
        env["ROMM_PLAYER_CORE_ROOT"] = as_posix(self.core_root)
        env["ROMM_PLAYER_CACHE_ROOT"] = as_posix(self.cache_root)
        env["ROMM_PLAYER_DATA_ROOT"] = as_posix(self.data_root)
        env["ROMM_PLAYER_STATE_ROOT"] = as_posix(self.state_root)
        # All five cores are allowed: the synthetic test_core (revision pin
        # from CoreManifest.kt) and the CANDIDATE gambatte + fceumm +
        # prosystem + mednafen_wswan cores pinned to their vendored-tree
        # commits. The candidates appear in NO production manifest — this env
        # entry is the qualification gate's adoption.
        env["ROMM_PLAYER_ALLOWED_CORES"] = ("%s=%s;%s=%s;%s=%s;%s=%s;%s=%s" % (
            CORE_ID, CORE_REVISION, GAMBATTE_CORE_ID, GAMBATTE_REVISION,
            FCEUMM_CORE_ID, FCEUMM_REVISION, PROSYSTEM_CORE_ID,
            PROSYSTEM_REVISION, WSWAN_CORE_ID, WSWAN_REVISION))
        # Headless SDL for the GUI-less runner: offscreen video driver with a
        # real window framebuffer (the software renderer works against it),
        # forced software render backend (no GPU on the runner), dummy audio.
        env["SDL_VIDEODRIVER"] = self.video_driver
        env["SDL_AUDIODRIVER"] = self.audio_driver
        env["SDL_RENDER_DRIVER"] = self.render_driver
        if max_frames is not None:
            # test_core's CI-only hook (compiled into the player's test_core
            # build only): request RETRO_ENVIRONMENT_SHUTDOWN after N frames
            # with no input — deterministic core-requested shutdown.
            env["ROMM_TEST_CORE_MAX_FRAMES"] = str(max_frames)
        else:
            env.pop("ROMM_TEST_CORE_MAX_FRAMES", None)
        if player_max_frames is not None:
            # The player's qualification-only presented-frame bound
            # (compiled into the player only when ROMM_PLAYER_QUALIFICATION
            # is ON — the Windows software-only candidate CI build): a clean
            # player-initiated completed exit once the presented-frame count
            # reaches N, checkpoint written before stop. Inert in players
            # built without the hook.
            env["ROMM_PLAYER_MAX_FRAMES"] = str(player_max_frames)
        else:
            env.pop("ROMM_PLAYER_MAX_FRAMES", None)
        return env

    # -- one launch --------------------------------------------------------

    def _write_request(self, name, session_id, core_path, core_build_revision,
                       core_id=CORE_ID, content_path=""):
        session_dir = os.path.join(self.data_root, session_id)
        system_dir = os.path.join(session_dir, "system")
        os.makedirs(system_dir, exist_ok=True)
        save_path = os.path.join(session_dir, "save.srm")
        candidate = os.path.join(self.state_root, session_id + ".candidate.srm")
        result_path = os.path.join(self.state_root, session_id + ".result.json")
        request = build_request(session_id, core_path, system_dir, save_path,
                                candidate, result_path,
                                core_build_revision=core_build_revision,
                                core_id=core_id, content_path=content_path)
        request_path = os.path.join(self.requests_dir, name + ".request.json")
        with open(request_path, "w", encoding="utf-8") as f:
            json.dump(request, f, indent=2, ensure_ascii=False)
        return request_path, result_path, save_path

    def launch(self, name, session_id, max_frames=None, core_path=None,
               core_build_revision=CORE_REVISION, core_id=CORE_ID,
               content_path="", player_max_frames=None):
        """Launch the player for one request; returns (rc, result|None)."""
        core_path = core_path or os.path.join(self.core_root, self.core_filename())
        request_path, result_path, _ = self._write_request(
            name, session_id, core_path, core_build_revision,
            core_id=core_id, content_path=content_path)
        proc = PlayerProcess(self.player_exe, request_path,
                             self.env_for(max_frames=max_frames,
                                         player_max_frames=player_max_frames),
                             os.path.join(self.logs_dir, name + ".log"))
        proc.start()
        self.spawned_pids.append(proc.pid())
        rc = proc.wait(self.timeout_sec)
        if proc.timed_out:
            self.fail(name, "launch timed out after %ds (pid %d terminated)"
                      % (self.timeout_sec, proc.pid()))
        result = None
        if os.path.isfile(result_path):
            with open(result_path, "rb") as f:
                raw = f.read()
            try:
                result = json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, ValueError) as exc:
                self.fail(name, "result file is not valid UTF-8 JSON: %s" % exc)
                return rc, None
            shutil.copyfile(result_path, os.path.join(
                self.results_dir, name + ".result.json"))
        return rc, result

    def read_result(self, name, result_path):
        if not os.path.isfile(result_path):
            return None
        with open(result_path, "rb") as f:
            raw = f.read()
        try:
            return json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            return None

    # -- assertions ---------------------------------------------------------

    def check(self, name, condition, detail):
        if not condition:
            self.fail(name, detail)
        return condition

    def fail(self, name, detail):
        entry = self.scenarios[-1]
        entry["passed"] = False
        entry.setdefault("failures", []).append(detail)
        print("FAIL [%s] %s" % (name, detail), file=sys.stderr)

    def assert_clean_result(self, name, result, session_id, want_frames):
        """Schema + session + exitKind + frame-count assertions for a run
        that must have ended in the core's own requested shutdown."""
        problems = validate_result_schema(result)
        self.check(name, not problems, "result schema: %s" % "; ".join(problems))
        if problems:
            return False
        self.check(name, result["sessionId"] == session_id,
                   "sessionId %r != %r" % (result["sessionId"], session_id))
        self.check(name, result["exitKind"] == "core_requested_shutdown",
                   "exitKind %r — the core's own RETRO_ENVIRONMENT_SHUTDOWN must be reported"
                   % result["exitKind"])
        self.check(name, result["checkpointWritten"] is True,
                   "checkpointWritten must be true")
        self.check(name, result["frames"] == want_frames,
                   "frames %r != %d (deterministic max-frames hook)"
                   % (result["frames"], want_frames))
        return True

    # -- scenarios ----------------------------------------------------------

    def assert_gambatte_result(self, name, result, session_id, limit, total_frames):
        """Gambatte qualification-run assertions: schema + session +
        exitKind=completed (player-initiated) + bounded real frames +
        8192-byte SRAM + the deterministic marker/counter invariants.

        total_frames is the cumulative presented-frame count across the
        relaunch chain (restored counters keep counting); for a fresh run it
        equals the run's own reported frame count. The invariants are
        checked against the player-REPORTED frame count, so the assertion is
        frame-stable across frame boundaries: the ROM's SRAM state is an
        exact function of the presented frames, never of instruction counts
        or wall-clock timing.
        """
        problems = validate_result_schema(result)
        self.check(name, not problems, "result schema: %s" % "; ".join(problems))
        if problems:
            return False
        self.check(name, result["sessionId"] == session_id,
                   "sessionId %r != %r" % (result["sessionId"], session_id))
        self.check(name, result["exitKind"] == "completed",
                   "exitKind %r — the player's qualification frame bound must "
                   "exit as a clean player-initiated completed run (the core "
                   "never requested shutdown)" % result["exitKind"])
        self.check(name, result["checkpointWritten"] is True,
                   "checkpointWritten must be true (checkpoint-before-stop)")
        frames = result["frames"]
        self.check(name, limit <= frames <= limit + 2,
                   "frames %r outside the bounded range [%d, %d] for the "
                   "presented-frame bound (real frames must have been "
                   "presented, and the bound must be honored)"
                   % (frames, limit, limit + 2))
        if not (limit <= frames <= limit + 2):
            return False
        want_image = gambatte_rom.expected_sram_image(total_frames)
        want_hash = hashlib.sha256(want_image).hexdigest()
        self.check(name, result["saveSize"] == GAMBATTE_SRAM_SIZE,
                   "saveSize %r != %d (Gambatte must expose 8192 bytes of "
                   "battery SRAM for the 0x03/0x02 cartridge combination)"
                   % (result["saveSize"], GAMBATTE_SRAM_SIZE))
        self.check(name, result["saveHash"] == want_hash,
                   "saveHash %s != expected %s — SRAM marker/counter "
                   "invariants broken (expected image for %d cumulative "
                   "frames, got %d reported)"
                   % (result["saveHash"], want_hash, total_frames, frames))
        candidate = os.path.join(self.state_root, session_id + ".candidate.srm")
        if self.check(name, os.path.isfile(candidate),
                      "candidate save missing on disk"):
            with open(candidate, "rb") as f:
                blob = f.read()
            self.check(name, len(blob) == GAMBATTE_SRAM_SIZE,
                       "candidate file is %d bytes (want %d)"
                       % (len(blob), GAMBATTE_SRAM_SIZE))
            self.check(name, blob == want_image,
                       "candidate file on disk != the deterministic SRAM "
                       "image (marker 0x52 + frame counter %d + 60-frame "
                       "counter %d over 0xFF fill)"
                       % (total_frames % 60, total_frames // 60))
        return True

    def assert_fceumm_result(self, name, result, session_id, limit, run_frames):
        """FCEUmm qualification-run assertions: schema + session +
        exitKind=completed (player-initiated) + bounded real frames +
        8192-byte SRAM + the deterministic marker/counter invariants.

        run_frames is the list of player-reported frame counts for every
        power-on in this save chain (restored counters keep counting). Unlike
        Gambatte, FCEUmm's PPU starts EVERY power-on with ppudead = 2 (two
        frames with no VBlank edge), so each run of reported length F_i
        contributes exactly max(0, F_i - DEAD_FRAME_OFFSET) counted VBlanks —
        an exact, core-derived constant. The invariants are checked against
        the player-REPORTED per-run frame counts, so the assertion is
        frame-stable across frame boundaries: the ROM's SRAM state is an exact
        function of the presented frames, never of instruction counts or
        wall-clock timing.
        """
        problems = validate_result_schema(result)
        self.check(name, not problems, "result schema: %s" % "; ".join(problems))
        if problems:
            return False
        self.check(name, result["sessionId"] == session_id,
                   "sessionId %r != %r" % (result["sessionId"], session_id))
        self.check(name, result["exitKind"] == "completed",
                   "exitKind %r — the player's qualification frame bound must "
                   "exit as a clean player-initiated completed run (the core "
                   "never requested shutdown)" % result["exitKind"])
        self.check(name, result["checkpointWritten"] is True,
                   "checkpointWritten must be true (checkpoint-before-stop)")
        frames = result["frames"]
        self.check(name, limit <= frames <= limit + 2,
                   "frames %r outside the bounded range [%d, %d] for the "
                   "presented-frame bound (real frames must have been "
                   "presented, and the bound must be honored)"
                   % (frames, limit, limit + 2))
        if not (limit <= frames <= limit + 2):
            return False
        want_image = fceumm_rom.expected_sram_image(run_frames)
        want_hash = hashlib.sha256(want_image).hexdigest()
        self.check(name, result["saveSize"] == FCEUMM_SRAM_SIZE,
                   "saveSize %r != %d (FCEUmm must expose 8192 bytes of "
                   "battery WRAM for the mapper-0 NROM + battery header)"
                   % (result["saveSize"], FCEUMM_SRAM_SIZE))
        counted = sum(max(0, f - fceumm_rom.DEAD_FRAME_OFFSET)
                      for f in run_frames)
        self.check(name, result["saveHash"] == want_hash,
                   "saveHash %s != expected %s — SRAM marker/counter "
                   "invariants broken (expected image for per-run reported "
                   "frames %s minus the %d-frame ppudead offset per power-on; "
                   "this run reported %d)"
                   % (result["saveHash"], want_hash, list(run_frames),
                      fceumm_rom.DEAD_FRAME_OFFSET, frames))
        candidate = os.path.join(self.state_root, session_id + ".candidate.srm")
        if self.check(name, os.path.isfile(candidate),
                      "candidate save missing on disk"):
            with open(candidate, "rb") as f:
                blob = f.read()
            self.check(name, len(blob) == FCEUMM_SRAM_SIZE,
                       "candidate file is %d bytes (want %d)"
                       % (len(blob), FCEUMM_SRAM_SIZE))
            self.check(name, blob == want_image,
                       "candidate file on disk != the deterministic SRAM "
                       "image (marker 0x52 + frame counter %d + 60-frame "
                       "counter %d over 0x00 fresh fill)"
                       % (counted % 60, counted // 60))
        return True

    def assert_wswan_result(self, name, result, session_id, limit, run_frames):
        """mednafen_wswan qualification-run assertions: schema + session +
        exitKind=completed (player-initiated) + bounded real frames +
        8192-byte SRAM + the deterministic per-frame counter oracle.

        run_frames is the list of player-reported frame counts for every
        power-on in this save chain. Unlike Gambatte/FCEUmm, the WonderSwan
        core's per-power-on overhead is not a fixed dead-frame count: the
        first frame after every retro_load_game() runs only 145 scanlines
        (GfxReset leaves wsLine at 0 and the frame ends at line 144) while
        LCDVtotal = 158 makes every later frame 159 lines, so one power-on
        of F reported frames executes EXACTLY wswan_rom.run_iterations(F) =
        3392*F - 299 counter iterations (the 12-cycle loop divides the
        40704-cycle steady frame; the end-of-frame ICount residue is a fixed
        point). The ROM's byte counter LIVES IN SRAM (offset 0x100, mirrored
        to SRAM[0] every loop iteration; every run ends mid-iteration right
        after its final increment, so SRAM[0] = total-1 mod 256), so restored
        saves keep counting — an un-restored core would hash like this run
        alone. The invariants are checked against the
        player-REPORTED per-run frame counts, so the assertion is
        frame-stable across frame boundaries: the ROM's SRAM state is an
        exact function of the presented frames, never of instruction counts
        or wall-clock timing.
        """
        problems = validate_result_schema(result)
        self.check(name, not problems, "result schema: %s" % "; ".join(problems))
        if problems:
            return False
        self.check(name, result["sessionId"] == session_id,
                   "sessionId %r != %r" % (result["sessionId"], session_id))
        self.check(name, result["exitKind"] == "completed",
                   "exitKind %r — the player's qualification frame bound must "
                   "exit as a clean player-initiated completed run (the core "
                   "never requested shutdown)" % result["exitKind"])
        self.check(name, result["checkpointWritten"] is True,
                   "checkpointWritten must be true (checkpoint-before-stop)")
        frames = result["frames"]
        self.check(name, limit <= frames <= limit + 2,
                   "frames %r outside the bounded range [%d, %d] for the "
                   "presented-frame bound (real frames must have been "
                   "presented, and the bound must be honored)"
                   % (frames, limit, limit + 2))
        if not (limit <= frames <= limit + 2):
            return False
        want_image = wswan_rom.expected_sram_image(run_frames)
        want_hash = hashlib.sha256(want_image).hexdigest()
        self.check(name, result["saveSize"] == WSWAN_SRAM_SIZE,
                   "saveSize %r != %d (mednafen_wswan must expose 8192 bytes "
                   "of battery SRAM for cart header code 0x01)"
                   % (result["saveSize"], WSWAN_SRAM_SIZE))
        counted = sum(wswan_rom.run_iterations(f) for f in run_frames)
        self.check(name, result["saveHash"] == want_hash,
                   "saveHash %s != expected %s — SRAM counter oracle broken "
                   "(expected %d total iterations over per-run reported "
                   "frames %s; this run reported %d)"
                   % (result["saveHash"], want_hash, counted, list(run_frames),
                      frames))
        candidate = os.path.join(self.state_root, session_id + ".candidate.srm")
        if self.check(name, os.path.isfile(candidate),
                      "candidate save missing on disk"):
            with open(candidate, "rb") as f:
                blob = f.read()
            self.check(name, len(blob) == WSWAN_SRAM_SIZE,
                       "candidate file is %d bytes (want %d)"
                       % (len(blob), WSWAN_SRAM_SIZE))
            self.check(name, blob == want_image,
                       "candidate file on disk != the deterministic SRAM "
                       "image (counter %d over 0x00 fresh fill)"
                       % (counted % 256))
        return True

    def scenario_valid_launch_core_shutdown(self):
        name = "valid-launch-core-shutdown"
        self.scenarios.append({"name": name, "passed": True})
        session = "e2e-run1"
        rc, result = self.launch(name, session, max_frames=SHUTDOWN_MAX_FRAMES)
        self.check(name, rc == 0, "exit code %r (want 0); see log" % rc)
        if not self.check(name, result is not None, "no result JSON written"):
            return
        if not self.assert_clean_result(name, result, session, SHUTDOWN_MAX_FRAMES):
            return
        want_hash = expected_save_hash(SHUTDOWN_MAX_FRAMES)
        self.check(name, result["saveSize"] == SRAM_SIZE,
                   "saveSize %r != %d" % (result["saveSize"], SRAM_SIZE))
        self.check(name, result["saveHash"] == want_hash,
                   "saveHash %s != expected %s (SRAM byte 0 must be %d)"
                   % (result["saveHash"], want_hash,
                      sram_byte_after_frames(SHUTDOWN_MAX_FRAMES)))
        candidate = os.path.join(self.state_root, session + ".candidate.srm")
        if self.check(name, os.path.isfile(candidate), "candidate save missing on disk"):
            with open(candidate, "rb") as f:
                blob = f.read()
            self.check(name, len(blob) == SRAM_SIZE,
                       "candidate file is %d bytes (want %d)" % (len(blob), SRAM_SIZE))
            self.check(name, hashlib.sha256(blob).hexdigest() == want_hash,
                       "candidate file hash on disk != player-reported saveHash")

    def scenario_relaunch_save_restore(self):
        name = "relaunch-save-restore"
        self.scenarios.append({"name": name, "passed": True})
        # The desktop supervisor moves the previous run's candidate into the
        # session's save path; mirror that exactly.
        prev = os.path.join(self.state_root, "e2e-run1.candidate.srm")
        session = "e2e-run2"
        if not self.check(name, os.path.isfile(prev),
                          "previous candidate missing — run1 failed?"):
            return
        save_path = os.path.join(self.data_root, session, "save.srm")
        os.makedirs(os.path.dirname(save_path), exist_ok=True)
        shutil.copyfile(prev, save_path)

        rc, result = self.launch(name, session, max_frames=SHUTDOWN_MAX_FRAMES)
        self.check(name, rc == 0, "exit code %r (want 0); see log" % rc)
        if not self.check(name, result is not None, "no result JSON written"):
            return
        if not self.assert_clean_result(name, result, session, SHUTDOWN_MAX_FRAMES):
            return
        # Restore-on-launch must have loaded run1's SRAM (byte 0 = 7), so this
        # run adds another 7 increments → byte 0 = 14. A fresh (unrestored)
        # core would stop at 7 — the hash difference proves the restore.
        want_hash = expected_save_hash(2 * SHUTDOWN_MAX_FRAMES)
        self.check(name, result["saveSize"] == SRAM_SIZE,
                   "saveSize %r != %d" % (result["saveSize"], SRAM_SIZE))
        self.check(name, result["saveHash"] == want_hash,
                   "saveHash %s != expected %s — restore-on-launch did not apply the "
                   "previous checkpoint (fresh SRAM would hash like run1)"
                   % (result["saveHash"], want_hash))

    def scenario_concurrent_same_session_rejected(self):
        name = "concurrent-same-session-rejected"
        self.scenarios.append({"name": name, "passed": True})
        session = "e2e-lock"
        holder_frames = 1200  # ~20 s of frames: alive well past the probe
        core_path = os.path.join(self.core_root, self.core_filename())
        request_path, result_path, _ = self._write_request(
            name + "-holder", session, core_path, CORE_REVISION)

        holder = PlayerProcess(self.player_exe, request_path,
                               self.env_for(max_frames=holder_frames),
                               os.path.join(self.logs_dir, name + "-holder.log"))
        holder.start()
        self.spawned_pids.append(holder.pid())
        # The session lock is taken during request validation — long before
        # SDL/core startup. Bounded settle window, then the holder must still
        # be alive (otherwise the "holder" premise is broken).
        time.sleep(5)
        if not self.check(name, holder.alive(),
                          "lock-holding player exited during startup (rc=%r) — cannot "
                          "probe concurrent rejection" % holder.proc.returncode):
            return
        # Concurrent launch, SAME session id → same resultPath: must be
        # rejected at validation with a launch_failed result.
        challenger = PlayerProcess(
            self.player_exe, request_path,
            self.env_for(max_frames=SHUTDOWN_MAX_FRAMES),
            os.path.join(self.logs_dir, name + "-challenger.log"))
        challenger.start()
        self.spawned_pids.append(challenger.pid())
        rc = challenger.wait(30)
        # Read the (launch_failed) result NOW: the holder will finish shortly
        # and overwrite the same resultPath with its own clean result.
        result = self.read_result(name, result_path)
        self.check(name, rc == 1, "challenger exit code %r (want 1)" % rc)
        if self.check(name, result is not None, "challenger wrote no result JSON"):
            problems = validate_result_schema(result)
            self.check(name, not problems,
                       "challenger result schema: %s" % "; ".join(problems))
            if not problems:
                self.check(name, result["exitKind"] == "launch_failed",
                           "challenger exitKind %r (want launch_failed)"
                           % result["exitKind"])
                self.check(name, result["errorMessage"] is not None and
                           "session already active" in result["errorMessage"],
                           "challenger errorMessage %r must report the active session"
                           % (result.get("errorMessage"),))
        # Let the holder finish cleanly within its bounded frame budget.
        rc_holder = holder.wait(self.timeout_sec)
        self.check(name, not holder.timed_out, "holder launch timed out")
        self.check(name, rc_holder == 0, "holder exit code %r (want 0)" % rc_holder)
        holder_result = self.read_result(name + "-holder", result_path)
        if holder_result is not None:
            shutil.copyfile(result_path, os.path.join(
                self.results_dir, name + "-holder.result.json"))
            if isinstance(holder_result, dict):
                self.check(name,
                           holder_result.get("exitKind") == "core_requested_shutdown",
                           "holder exitKind %r (want core_requested_shutdown)"
                           % holder_result.get("exitKind"))
                self.check(name, holder_result.get("frames") == holder_frames,
                           "holder frames %r != %d"
                           % (holder_result.get("frames"), holder_frames))

    def scenario_force_kill_lock_release_relaunch(self):
        name = "force-kill-lock-release-relaunch"
        self.scenarios.append({"name": name, "passed": True})
        session = "e2e-kill"
        # Victim: large frame budget so it is definitely mid-session when the
        # exact pid is force-killed (no cleanup code runs; the kernel must
        # release the byte-range lock when its handles close).
        victim_frames = 3600
        core_path = os.path.join(self.core_root, self.core_filename())
        request_path, result_path, _ = self._write_request(
            name + "-victim", session, core_path, CORE_REVISION)
        victim = PlayerProcess(self.player_exe, request_path,
                               self.env_for(max_frames=victim_frames),
                               os.path.join(self.logs_dir, name + "-victim.log"))
        victim.start()
        pid = victim.pid()
        self.spawned_pids.append(pid)
        time.sleep(5)  # settle: lock acquired during validation, core running
        if not self.check(name, victim.alive(), "victim exited before the force-kill"):
            return
        victim.terminate()
        if not self.check(name, not victim.alive(),
                          "pid %d still alive after force-kill" % pid):
            return
        # Immediate relaunch of the SAME session: succeeds only if the lock
        # was released when the victim died.
        rc, result = self.launch(name + "-relaunch", session,
                                 max_frames=SHUTDOWN_MAX_FRAMES)
        self.check(name, rc == 0, "relaunch exit code %r (want 0) — lock not released?" % rc)
        if not self.check(name, result is not None, "relaunch wrote no result JSON"):
            return
        if not self.assert_clean_result(name, result, session, SHUTDOWN_MAX_FRAMES):
            return

    # -- Gambatte candidate scenarios (qualification gate) ------------------

    def _require_candidate(self, name):
        if self.candidate_core is None:
            self.fail(name, "candidate Gambatte core not staged — nothing to qualify")
            return False
        return True

    def scenario_gambatte_valid_launch_completed(self):
        name = "gambatte-valid-launch-completed"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_candidate(name):
            return
        session = "e2e-gb-run1"
        rc, result = self.launch(
            name, session,
            core_id=GAMBATTE_CORE_ID, core_build_revision=GAMBATTE_REVISION,
            core_path=self.gambatte_core_path(),
            content_path=os.path.join(self.cache_root, GAMBATTE_ROM_NAME),
            player_max_frames=GAMBATTE_RUN1_FRAMES)
        self.check(name, rc == 0, "exit code %r (want 0); see log" % rc)
        if not self.check(name, result is not None, "no result JSON written"):
            return
        # Fresh cart: the cumulative count IS this run's reported count.
        if not self.assert_gambatte_result(
                name, result, session, GAMBATTE_RUN1_FRAMES, result["frames"]):
            return
        self.gambatte_chain_frames = result["frames"]

    def scenario_gambatte_relaunch_persistence(self):
        name = "gambatte-relaunch-persistence"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_candidate(name):
            return
        # Candidate adoption: the desktop supervisor moves the previous
        # run's candidate into the session's save path; mirror that exactly.
        prev = os.path.join(self.state_root, "e2e-gb-run1.candidate.srm")
        session = "e2e-gb-run2"
        if not self.check(name, os.path.isfile(prev),
                          "previous candidate missing — run1 failed?"):
            return
        save_path = os.path.join(self.data_root, session, "save.srm")
        os.makedirs(os.path.dirname(save_path), exist_ok=True)
        shutil.copyfile(prev, save_path)

        rc, result = self.launch(
            name, session,
            core_id=GAMBATTE_CORE_ID, core_build_revision=GAMBATTE_REVISION,
            core_path=self.gambatte_core_path(),
            content_path=os.path.join(self.cache_root, GAMBATTE_ROM_NAME),
            player_max_frames=GAMBATTE_RUN2_FRAMES)
        self.check(name, rc == 0, "exit code %r (want 0); see log" % rc)
        if not self.check(name, result is not None, "no result JSON written"):
            return
        # Restore-on-launch must have applied run1's SRAM: the ROM sees its
        # own 0x52 marker and KEEPS the counters, so the cumulative count is
        # run1 + run2. A fresh (unrestored) core would hash like run1 alone —
        # the difference is what proves the restore.
        total = getattr(self, "gambatte_chain_frames", 0) + result["frames"]
        if not self.assert_gambatte_result(name, result, session,
                                           GAMBATTE_RUN2_FRAMES, total):
            return
        self.gambatte_chain_frames = total

    def scenario_gambatte_repeated_load(self):
        name = "gambatte-repeated-load"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_candidate(name):
            return
        # Third load of the SAME ROM + save chain (candidate adopted again):
        # the deterministic invariants must hold on every repeated load.
        prev = os.path.join(self.state_root, "e2e-gb-run2.candidate.srm")
        session = "e2e-gb-run3"
        if not self.check(name, os.path.isfile(prev),
                          "previous candidate missing — run2 failed?"):
            return
        save_path = os.path.join(self.data_root, session, "save.srm")
        os.makedirs(os.path.dirname(save_path), exist_ok=True)
        shutil.copyfile(prev, save_path)

        rc, result = self.launch(
            name, session,
            core_id=GAMBATTE_CORE_ID, core_build_revision=GAMBATTE_REVISION,
            core_path=self.gambatte_core_path(),
            content_path=os.path.join(self.cache_root, GAMBATTE_ROM_NAME),
            player_max_frames=GAMBATTE_RUN3_FRAMES)
        self.check(name, rc == 0, "exit code %r (want 0); see log" % rc)
        if not self.check(name, result is not None, "no result JSON written"):
            return
        total = getattr(self, "gambatte_chain_frames", 0) + result["frames"]
        if not self.assert_gambatte_result(name, result, session,
                                           GAMBATTE_RUN3_FRAMES, total):
            return
        self.gambatte_chain_frames = total

    def scenario_gambatte_force_kill_lock_recovery(self):
        name = "gambatte-force-kill-lock-recovery"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_candidate(name):
            return
        session = "e2e-gb-kill"
        # Victim: large presented-frame budget so it is definitely mid-
        # session when the exact pid is force-killed (no cleanup code runs;
        # the kernel must release the byte-range lock when its handles
        # close). The kill happens well before any autosave, so the
        # relaunch starts from a FRESH cart.
        core_path = self.gambatte_core_path()
        content_path = os.path.join(self.cache_root, GAMBATTE_ROM_NAME)
        request_path, result_path, _ = self._write_request(
            name + "-victim", session, core_path, GAMBATTE_REVISION,
            core_id=GAMBATTE_CORE_ID, content_path=content_path)
        victim = PlayerProcess(
            self.player_exe, request_path,
            self.env_for(player_max_frames=GAMBATTE_KILL_VICTIM_FRAMES),
            os.path.join(self.logs_dir, name + "-victim.log"))
        victim.start()
        pid = victim.pid()
        self.spawned_pids.append(pid)
        time.sleep(5)  # settle: lock acquired, ROM running past its init
        if not self.check(name, victim.alive(),
                          "victim exited before the force-kill"):
            return
        victim.terminate()
        if not self.check(name, not victim.alive(),
                          "pid %d still alive after force-kill" % pid):
            return
        # Immediate relaunch of the SAME session: succeeds only if the lock
        # was released when the victim died.
        rc, result = self.launch(
            name + "-relaunch", session,
            core_id=GAMBATTE_CORE_ID, core_build_revision=GAMBATTE_REVISION,
            core_path=core_path, content_path=content_path,
            player_max_frames=GAMBATTE_RUN3_FRAMES)
        self.check(name, rc == 0,
                   "relaunch exit code %r (want 0) — lock not released?" % rc)
        if not self.check(name, result is not None,
                          "relaunch wrote no result JSON"):
            return
        # Fresh cart (the victim never checkpointed): the invariants hold
        # against the relaunch's own reported frame count.
        self.assert_gambatte_result(name, result, session,
                                    GAMBATTE_RUN3_FRAMES, result["frames"])

    # -- FCEUmm candidate scenarios (qualification gate) -------------------

    def _require_fceumm_candidate(self, name):
        if self.fceumm_candidate_core is None:
            self.fail(name, "candidate FCEUmm core not staged — nothing to qualify")
            return False
        return True

    def scenario_fceumm_valid_launch_completed(self):
        name = "fceumm-valid-launch-completed"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_fceumm_candidate(name):
            return
        session = "e2e-nes-run1"
        rc, result = self.launch(
            name, session,
            core_id=FCEUMM_CORE_ID, core_build_revision=FCEUMM_REVISION,
            core_path=self.fceumm_core_path(),
            content_path=os.path.join(self.cache_root, FCEUMM_ROM_NAME),
            player_max_frames=FCEUMM_RUN1_FRAMES)
        self.check(name, rc == 0, "exit code %r (want 0); see log" % rc)
        if not self.check(name, result is not None, "no result JSON written"):
            return
        # Fresh cart: the save chain is just this run's reported count.
        if not self.assert_fceumm_result(
                name, result, session, FCEUMM_RUN1_FRAMES, [result["frames"]]):
            return
        self.fceumm_chain = [result["frames"]]

    def scenario_fceumm_relaunch_persistence(self):
        name = "fceumm-relaunch-persistence"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_fceumm_candidate(name):
            return
        # Candidate adoption: the desktop supervisor moves the previous
        # run's candidate into the session's save path; mirror that exactly.
        prev = os.path.join(self.state_root, "e2e-nes-run1.candidate.srm")
        session = "e2e-nes-run2"
        if not self.check(name, os.path.isfile(prev),
                          "previous candidate missing — run1 failed?"):
            return
        save_path = os.path.join(self.data_root, session, "save.srm")
        os.makedirs(os.path.dirname(save_path), exist_ok=True)
        shutil.copyfile(prev, save_path)

        rc, result = self.launch(
            name, session,
            core_id=FCEUMM_CORE_ID, core_build_revision=FCEUMM_REVISION,
            core_path=self.fceumm_core_path(),
            content_path=os.path.join(self.cache_root, FCEUMM_ROM_NAME),
            player_max_frames=FCEUMM_RUN2_FRAMES)
        self.check(name, rc == 0, "exit code %r (want 0); see log" % rc)
        if not self.check(name, result is not None, "no result JSON written"):
            return
        # Restore-on-launch must have applied run1's SRAM: the ROM sees its
        # own 0x52 marker and KEEPS the counters, so the save chain is
        # [run1, run2] of reported frames. A fresh (unrestored) core would
        # hash like run1 alone — the difference is what proves the restore.
        # Each power-on contributes its own ppudead offset; the oracle sums
        # max(0, F_i - 2) over the chain.
        chain = list(getattr(self, "fceumm_chain", [])) + [result["frames"]]
        if not self.assert_fceumm_result(name, result, session,
                                         FCEUMM_RUN2_FRAMES, chain):
            return
        self.fceumm_chain = chain

    def scenario_fceumm_repeated_load(self):
        name = "fceumm-repeated-load"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_fceumm_candidate(name):
            return
        # Third load of the SAME ROM + save chain (candidate adopted again):
        # the deterministic invariants must hold on every repeated load.
        prev = os.path.join(self.state_root, "e2e-nes-run2.candidate.srm")
        session = "e2e-nes-run3"
        if not self.check(name, os.path.isfile(prev),
                          "previous candidate missing — run2 failed?"):
            return
        save_path = os.path.join(self.data_root, session, "save.srm")
        os.makedirs(os.path.dirname(save_path), exist_ok=True)
        shutil.copyfile(prev, save_path)

        rc, result = self.launch(
            name, session,
            core_id=FCEUMM_CORE_ID, core_build_revision=FCEUMM_REVISION,
            core_path=self.fceumm_core_path(),
            content_path=os.path.join(self.cache_root, FCEUMM_ROM_NAME),
            player_max_frames=FCEUMM_RUN3_FRAMES)
        self.check(name, rc == 0, "exit code %r (want 0); see log" % rc)
        if not self.check(name, result is not None, "no result JSON written"):
            return
        chain = list(getattr(self, "fceumm_chain", [])) + [result["frames"]]
        if not self.assert_fceumm_result(name, result, session,
                                         FCEUMM_RUN3_FRAMES, chain):
            return
        self.fceumm_chain = chain

    def scenario_fceumm_force_kill_lock_recovery(self):
        name = "fceumm-force-kill-lock-recovery"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_fceumm_candidate(name):
            return
        session = "e2e-nes-kill"
        # Victim: large presented-frame budget so it is definitely mid-
        # session when the exact pid is force-killed (no cleanup code runs;
        # the kernel must release the byte-range lock when its handles
        # close). The kill happens well before any autosave, so the
        # relaunch starts from a FRESH cart.
        core_path = self.fceumm_core_path()
        content_path = os.path.join(self.cache_root, FCEUMM_ROM_NAME)
        request_path, result_path, _ = self._write_request(
            name + "-victim", session, core_path, FCEUMM_REVISION,
            core_id=FCEUMM_CORE_ID, content_path=content_path)
        victim = PlayerProcess(
            self.player_exe, request_path,
            self.env_for(player_max_frames=FCEUMM_KILL_VICTIM_FRAMES),
            os.path.join(self.logs_dir, name + "-victim.log"))
        victim.start()
        pid = victim.pid()
        self.spawned_pids.append(pid)
        time.sleep(5)  # settle: lock acquired, ROM running past its init
        if not self.check(name, victim.alive(),
                          "victim exited before the force-kill"):
            return
        victim.terminate()
        if not self.check(name, not victim.alive(),
                          "pid %d still alive after force-kill" % pid):
            return
        # Immediate relaunch of the SAME session: succeeds only if the lock
        # was released when the victim died.
        rc, result = self.launch(
            name + "-relaunch", session,
            core_id=FCEUMM_CORE_ID, core_build_revision=FCEUMM_REVISION,
            core_path=core_path, content_path=content_path,
            player_max_frames=FCEUMM_RUN3_FRAMES)
        self.check(name, rc == 0,
                   "relaunch exit code %r (want 0) — lock not released?" % rc)
        if not self.check(name, result is not None,
                          "relaunch wrote no result JSON"):
            return
        # Fresh cart (the victim never checkpointed): the save chain is just
        # the relaunch's own reported frame count.
        self.assert_fceumm_result(name, result, session,
                                  FCEUMM_RUN3_FRAMES, [result["frames"]])

    # -- ProSystem candidate (NO save RAM — no-persistent-save gate) --------

    def _require_prosystem_candidate(self, name):
        if self.prosystem_candidate_core is None:
            self.fail(name, "candidate ProSystem core not staged — nothing to qualify")
            return False
        return True

    def _assert_no_save_artifacts(self, name, session_id):
        """No .srm may exist for this session anywhere in the fixture tree.

        The state root holds exactly one candidate file per session
        (<session>.candidate.srm) and the data root holds the session's own
        save path — both must be absent, and nothing else under the session
        directory may carry a .srm either. Other sessions' legitimate
        artifacts (test_core/Gambatte/FCEUmm runs) are out of scope by design.
        Returns True only when no artifact exists anywhere.
        """
        clean = True
        candidate = os.path.join(self.state_root, session_id + ".candidate.srm")
        if not self.check(name, not os.path.exists(candidate),
                          "candidate save %r exists on disk — ProSystem "
                          "exposes no save region and must never produce one"
                          % candidate):
            clean = False
        for dirpath, _dirnames, filenames in os.walk(
                os.path.join(self.data_root, session_id)):
            for fn in filenames:
                if fn.endswith(".srm"):
                    if not self.check(name, False,
                                      "unexpected .srm artifact under the "
                                      "session tree: %r"
                                      % os.path.join(dirpath, fn)):
                        clean = False
        return clean

    def assert_prosystem_result(self, name, result, session_id, limit):
        """ProSystem qualification-run assertions: schema + session +
        exitKind=completed (player-initiated) + bounded real frames + the
        rigorous NO-PERSISTENT-SAVE gate.

        This pin's ProSystem core exposes no save RAM at all
        (retro_get_memory_size(RETRO_MEMORY_SAVE_RAM) == 0; only SYSTEM_RAM),
        so a correct run must not write any checkpoint: checkpointWritten
        false, saveSize and saveHash null, and zero .srm artifacts for the
        session. This is the no-save analogue of the SRAM invariant checks
        the Gambatte/FCEUmm runs perform — it proves the player did not
        fabricate a save region for a core that has none (and, by absence,
        that there is no adoption chain: nothing ever becomes a candidate).
        """
        problems = validate_result_schema(result)
        self.check(name, not problems, "result schema: %s" % "; ".join(problems))
        if problems:
            return False
        ok = True
        if not self.check(name, result["sessionId"] == session_id,
                          "sessionId %r != %r"
                          % (result["sessionId"], session_id)):
            ok = False
        if not self.check(name, result["exitKind"] == "completed",
                          "exitKind %r — the player's qualification frame "
                          "bound must exit as a clean player-initiated "
                          "completed run (the core never requested shutdown)"
                          % result["exitKind"]):
            ok = False
        if not self.check(name, result["checkpointWritten"] is False,
                          "checkpointWritten must be false (this pin's "
                          "ProSystem exposes no save RAM — nothing may be "
                          "checkpointed)"):
            ok = False
        if not self.check(name, result["saveSize"] is None,
                          "saveSize %r must be null — the core exposes no "
                          "save region" % (result["saveSize"],)):
            ok = False
        if not self.check(name, result["saveHash"] is None,
                          "saveHash %r must be null — nothing was "
                          "checkpointed" % (result["saveHash"],)):
            ok = False
        frames = result["frames"]
        if not self.check(name, limit <= frames <= limit + 2,
                          "frames %r outside the bounded range [%d, %d] for "
                          "the presented-frame bound (real frames must have "
                          "been presented, and the bound must be honored)"
                          % (frames, limit, limit + 2)):
            return False
        return ok and self._assert_no_save_artifacts(name, session_id)

    def scenario_prosystem_valid_launch_completed(self):
        name = "prosystem-valid-launch-completed"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_prosystem_candidate(name):
            return
        session = "e2e-a78-run1"
        rc, result = self.launch(
            name, session,
            core_id=PROSYSTEM_CORE_ID, core_build_revision=PROSYSTEM_REVISION,
            core_path=self.prosystem_core_path(),
            content_path=os.path.join(self.cache_root, PROSYSTEM_ROM_NAME),
            player_max_frames=PROSYSTEM_RUN_FRAMES)
        self.check(name, rc == 0, "exit code %r (want 0); see log" % rc)
        if not self.check(name, result is not None, "no result JSON written"):
            return
        # No save chain exists: the core has no save region to persist, so
        # the gate is pure absence — null save fields, false checkpoint, and
        # zero .srm files for the session.
        self.assert_prosystem_result(name, result, session, PROSYSTEM_RUN_FRAMES)

    def scenario_prosystem_repeated_load(self):
        name = "prosystem-repeated-load"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_prosystem_candidate(name):
            return
        # Second and third loads of the SAME ROM. With no save region there is
        # nothing to adopt (the supervisor's candidate→save move has no input),
        # so each load is a fresh, independent run — and the no-save gate must
        # hold on every repeated load, including the absence of any candidate
        # from the previous one.
        for i, session in enumerate(("e2e-a78-run2", "e2e-a78-run3"), start=2):
            prev = os.path.join(
                self.state_root, "e2e-a78-run%d.candidate.srm" % (i - 1))
            if not self.check(name, not os.path.exists(prev),
                              "previous candidate %r exists — ProSystem must "
                              "never produce one" % prev):
                return
            rc, result = self.launch(
                name + "-load%d" % i, session,
                core_id=PROSYSTEM_CORE_ID, core_build_revision=PROSYSTEM_REVISION,
                core_path=self.prosystem_core_path(),
                content_path=os.path.join(self.cache_root, PROSYSTEM_ROM_NAME),
                player_max_frames=PROSYSTEM_RUN_FRAMES)
            self.check(name, rc == 0,
                       "load%d exit code %r (want 0); see log" % (i, rc))
            if not self.check(name, result is not None,
                              "load%d wrote no result JSON" % i):
                return
            if not self.assert_prosystem_result(
                    name, result, session, PROSYSTEM_RUN_FRAMES):
                return

    def scenario_prosystem_force_kill_lock_recovery(self):
        name = "prosystem-force-kill-lock-recovery"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_prosystem_candidate(name):
            return
        session = "e2e-a78-kill"
        # Victim: large presented-frame budget so it is definitely mid-
        # session when the exact pid is force-killed (no cleanup code runs;
        # the kernel must release the byte-range lock when its handles
        # close). With no save region at all, the victim could never have
        # checkpointed anything — the relaunch starts from a fresh cart by
        # construction, and the no-save gate must still hold afterwards.
        core_path = self.prosystem_core_path()
        content_path = os.path.join(self.cache_root, PROSYSTEM_ROM_NAME)
        request_path, result_path, _ = self._write_request(
            name + "-victim", session, core_path, PROSYSTEM_REVISION,
            core_id=PROSYSTEM_CORE_ID, content_path=content_path)
        victim = PlayerProcess(
            self.player_exe, request_path,
            self.env_for(player_max_frames=PROSYSTEM_KILL_VICTIM_FRAMES),
            os.path.join(self.logs_dir, name + "-victim.log"))
        victim.start()
        pid = victim.pid()
        self.spawned_pids.append(pid)
        time.sleep(5)  # settle: lock acquired, ROM running past its init
        if not self.check(name, victim.alive(),
                          "victim exited before the force-kill"):
            return
        victim.terminate()
        if not self.check(name, not victim.alive(),
                          "pid %d still alive after force-kill" % pid):
            return
        # Immediate relaunch of the SAME session: succeeds only if the lock
        # was released when the victim died.
        rc, result = self.launch(
            name + "-relaunch", session,
            core_id=PROSYSTEM_CORE_ID, core_build_revision=PROSYSTEM_REVISION,
            core_path=core_path, content_path=content_path,
            player_max_frames=PROSYSTEM_RUN_FRAMES)
        self.check(name, rc == 0,
                   "relaunch exit code %r (want 0) — lock not released?" % rc)
        if not self.check(name, result is not None,
                          "relaunch wrote no result JSON"):
            return
        self.assert_prosystem_result(name, result, session, PROSYSTEM_RUN_FRAMES)

    # -- mednafen_wswan candidate scenarios (qualification gate) -------------

    def _require_wswan_candidate(self, name):
        if self.wswan_candidate_core is None:
            self.fail(name, "candidate mednafen_wswan core not staged — nothing to qualify")
            return False
        return True

    def scenario_wswan_valid_launch_completed(self):
        name = "wswan-valid-launch-completed"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_wswan_candidate(name):
            return
        session = "e2e-ws-run1"
        rc, result = self.launch(
            name, session,
            core_id=WSWAN_CORE_ID, core_build_revision=WSWAN_REVISION,
            core_path=self.wswan_core_path(),
            content_path=os.path.join(self.cache_root, WSWAN_ROM_NAME),
            player_max_frames=WSWAN_RUN1_FRAMES)
        self.check(name, rc == 0, "exit code %r (want 0); see log" % rc)
        if not self.check(name, result is not None, "no result JSON written"):
            return
        # Fresh cart: wsSRAM is zero-filled at load, so the counter starts
        # from 0 and the save chain is just this run's reported count.
        if not self.assert_wswan_result(
                name, result, session, WSWAN_RUN1_FRAMES, [result["frames"]]):
            return
        self.wswan_chain = [result["frames"]]

    def scenario_wswan_relaunch_persistence(self):
        name = "wswan-relaunch-persistence"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_wswan_candidate(name):
            return
        # Candidate adoption: the desktop supervisor moves the previous
        # run's candidate into the session's save path; mirror that exactly.
        prev = os.path.join(self.state_root, "e2e-ws-run1.candidate.srm")
        session = "e2e-ws-run2"
        if not self.check(name, os.path.isfile(prev),
                          "previous candidate missing — run1 failed?"):
            return
        save_path = os.path.join(self.data_root, session, "save.srm")
        os.makedirs(os.path.dirname(save_path), exist_ok=True)
        shutil.copyfile(prev, save_path)

        rc, result = self.launch(
            name, session,
            core_id=WSWAN_CORE_ID, core_build_revision=WSWAN_REVISION,
            core_path=self.wswan_core_path(),
            content_path=os.path.join(self.cache_root, WSWAN_ROM_NAME),
            player_max_frames=WSWAN_RUN2_FRAMES)
        self.check(name, rc == 0, "exit code %r (want 0); see log" % rc)
        if not self.check(name, result is not None, "no result JSON written"):
            return
        # Restore-on-launch must have applied run1's SRAM: the counter lives
        # in battery SRAM at SRAM[0x100] (mirrored to SRAM[0]), so the
        # restored save keeps counting from where run1 left off and the save
        # chain is [run1, run2] of reported frames. A fresh (unrestored) core
        # would hash like run2 alone — the difference is what proves the
        # restore.
        chain = list(getattr(self, "wswan_chain", [])) + [result["frames"]]
        if not self.assert_wswan_result(name, result, session,
                                        WSWAN_RUN2_FRAMES, chain):
            return
        self.wswan_chain = chain

    def scenario_wswan_repeated_load(self):
        name = "wswan-repeated-load"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_wswan_candidate(name):
            return
        # Third load of the SAME ROM + save chain (candidate adopted again):
        # the deterministic invariants must hold on every repeated load.
        prev = os.path.join(self.state_root, "e2e-ws-run2.candidate.srm")
        session = "e2e-ws-run3"
        if not self.check(name, os.path.isfile(prev),
                          "previous candidate missing — run2 failed?"):
            return
        save_path = os.path.join(self.data_root, session, "save.srm")
        os.makedirs(os.path.dirname(save_path), exist_ok=True)
        shutil.copyfile(prev, save_path)

        rc, result = self.launch(
            name, session,
            core_id=WSWAN_CORE_ID, core_build_revision=WSWAN_REVISION,
            core_path=self.wswan_core_path(),
            content_path=os.path.join(self.cache_root, WSWAN_ROM_NAME),
            player_max_frames=WSWAN_RUN3_FRAMES)
        self.check(name, rc == 0, "exit code %r (want 0); see log" % rc)
        if not self.check(name, result is not None, "no result JSON written"):
            return
        chain = list(getattr(self, "wswan_chain", [])) + [result["frames"]]
        if not self.assert_wswan_result(name, result, session,
                                        WSWAN_RUN3_FRAMES, chain):
            return
        self.wswan_chain = chain

    def scenario_wswan_force_kill_lock_recovery(self):
        name = "wswan-force-kill-lock-recovery"
        self.scenarios.append({"name": name, "passed": True})
        if not self._require_wswan_candidate(name):
            return
        session = "e2e-ws-kill"
        # Victim: large presented-frame budget so it is definitely mid-
        # session when the exact pid is force-killed (no cleanup code runs;
        # the kernel must release the byte-range lock when its handles
        # close). The kill happens well before any autosave, so the
        # relaunch starts from a FRESH cart.
        core_path = self.wswan_core_path()
        content_path = os.path.join(self.cache_root, WSWAN_ROM_NAME)
        request_path, result_path, _ = self._write_request(
            name + "-victim", session, core_path, WSWAN_REVISION,
            core_id=WSWAN_CORE_ID, content_path=content_path)
        victim = PlayerProcess(
            self.player_exe, request_path,
            self.env_for(player_max_frames=WSWAN_KILL_VICTIM_FRAMES),
            os.path.join(self.logs_dir, name + "-victim.log"))
        victim.start()
        pid = victim.pid()
        self.spawned_pids.append(pid)
        time.sleep(5)  # settle: lock acquired, ROM running past its init
        if not self.check(name, victim.alive(),
                          "victim exited before the force-kill"):
            return
        victim.terminate()
        if not self.check(name, not victim.alive(),
                          "pid %d still alive after force-kill" % pid):
            return
        # Immediate relaunch of the SAME session: succeeds only if the lock
        # was released when the victim died.
        rc, result = self.launch(
            name + "-relaunch", session,
            core_id=WSWAN_CORE_ID, core_build_revision=WSWAN_REVISION,
            core_path=core_path, content_path=content_path,
            player_max_frames=WSWAN_RUN3_FRAMES)
        self.check(name, rc == 0,
                   "relaunch exit code %r (want 0) — lock not released?" % rc)
        if not self.check(name, result is not None,
                          "relaunch wrote no result JSON"):
            return
        # Fresh cart (the victim never checkpointed): the save chain is just
        # the relaunch's own reported frame count.
        self.assert_wswan_result(name, result, session,
                                 WSWAN_RUN3_FRAMES, [result["frames"]])

    def scenario_negative_revision_mismatch(self):
        name = "negative-revision-mismatch"
        self.scenarios.append({"name": name, "passed": True})
        rc, result = self.launch(name, "e2e-badrevis", max_frames=None,
                                 core_build_revision="999")
        self.check(name, rc == 1, "exit code %r (want 1)" % rc)
        if not self.check(name, result is not None, "no result JSON for rejected request"):
            return
        problems = validate_result_schema(result)
        self.check(name, not problems, "result schema: %s" % "; ".join(problems))
        if problems:
            return
        self.check(name, result["exitKind"] == "launch_failed",
                   "exitKind %r (want launch_failed)" % result["exitKind"])
        self.check(name, result["errorMessage"] is not None and
                   "coreBuildRevision mismatch for coreId: test_core" in result["errorMessage"],
                   "errorMessage %r must name the revision mismatch"
                   % (result.get("errorMessage"),))

    def scenario_negative_core_outside_root(self):
        name = "negative-core-outside-root"
        self.scenarios.append({"name": name, "passed": True})
        outside = os.path.join(self.outside_root, "outside_" + self.core_filename())
        rc, result = self.launch(name, "e2e-outside", max_frames=None,
                                 core_path=outside)
        self.check(name, rc == 1, "exit code %r (want 1)" % rc)
        if not self.check(name, result is not None, "no result JSON for rejected request"):
            return
        problems = validate_result_schema(result)
        self.check(name, not problems, "result schema: %s" % "; ".join(problems))
        if problems:
            return
        self.check(name, result["exitKind"] == "launch_failed",
                   "exitKind %r (want launch_failed)" % result["exitKind"])
        self.check(name, result["errorMessage"] is not None and
                   "escapes trusted root" in result["errorMessage"],
                   "errorMessage %r must report the corePath escape"
                   % (result.get("errorMessage"),))

    def scenario_no_orphans_tree_deletable(self):
        name = "no-orphans-tree-deletable"
        self.scenarios.append({"name": name, "passed": True})
        deadline = time.monotonic() + 30
        for pid in self.spawned_pids:
            while pid_alive(pid) and time.monotonic() < deadline:
                time.sleep(0.2)
            self.check(name, not pid_alive(pid),
                       "orphan player pid %d still alive" % pid)
        # The whole fixture tree — including the deliberately-kept .lock files
        # in the state root — must delete cleanly: no process may hold any of
        # it open. (Lock files are never unlinked by design; they become
        # deletable once their holding process is gone.)
        try:
            shutil.rmtree(self.base)
        except OSError as exc:
            self.fail(name, "state tree not fully deletable: %s" % exc)
            return
        self.check(name, not os.path.exists(self.base),
                   "state tree still exists after rmtree")

    # -- driver -------------------------------------------------------------

    def run(self):
        t0 = time.monotonic()
        self.create_tree()
        for fn in (self.scenario_valid_launch_core_shutdown,
                   self.scenario_relaunch_save_restore,
                   self.scenario_concurrent_same_session_rejected,
                   self.scenario_force_kill_lock_release_relaunch,
                    self.scenario_gambatte_valid_launch_completed,
                    self.scenario_gambatte_relaunch_persistence,
                    self.scenario_gambatte_repeated_load,
                    self.scenario_gambatte_force_kill_lock_recovery,
                     self.scenario_fceumm_valid_launch_completed,
                     self.scenario_fceumm_relaunch_persistence,
                     self.scenario_fceumm_repeated_load,
                     self.scenario_fceumm_force_kill_lock_recovery,
                      self.scenario_prosystem_valid_launch_completed,
                      self.scenario_prosystem_repeated_load,
                      self.scenario_prosystem_force_kill_lock_recovery,
                      self.scenario_wswan_valid_launch_completed,
                      self.scenario_wswan_relaunch_persistence,
                      self.scenario_wswan_repeated_load,
                      self.scenario_wswan_force_kill_lock_recovery,
                      self.scenario_negative_revision_mismatch,
                   self.scenario_negative_core_outside_root,
                   self.scenario_no_orphans_tree_deletable):
            fn()
        elapsed = time.monotonic() - t0
        report = {
            "platform": platform.platform(),
            "player": self.player_exe,
            "core": self.core_dll,
            "baseTree": self.base,
            "elapsedSec": round(elapsed, 2),
            "scenarios": self.scenarios,
            "passed": all(s["passed"] for s in self.scenarios),
        }
        report_path = os.path.join(self.workdir, "e2e-report.json")
        with open(report_path, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2, ensure_ascii=False)
        for s in self.scenarios:
            print("%s  %s%s" % ("PASS" if s["passed"] else "FAIL", s["name"],
                                "" if s["passed"] else "  " + "; ".join(s.get("failures", []))))
        print("report: %s" % report_path)
        return 0 if report["passed"] else 1


# ---------------------------------------------------------------------------
# Artifact verification (Windows job pre-flight)
# ---------------------------------------------------------------------------

def verify_artifact(stage_dir):
    """Verify every staged file against import-audit.txt's SHA256 section."""
    audit = os.path.join(stage_dir, "import-audit.txt")
    if not os.path.isfile(audit):
        print("FAIL: import-audit.txt missing from %s" % stage_dir, file=sys.stderr)
        return 2
    with open(audit, "r", encoding="utf-8") as f:
        lines = f.read().splitlines()
    try:
        start = next(i for i, l in enumerate(lines)
                     if l.startswith("=== SHA256 of staged files ==="))
    except StopIteration:
        print("FAIL: import-audit.txt has no SHA256 section", file=sys.stderr)
        return 2
    checked = 0
    failed = False
    for line in lines[start + 1:]:
        m = re.match(r"^([0-9a-f]{64}) [ *]\./(.+)$", line.strip())
        if not m:
            continue
        digest, rel = m.group(1), m.group(2).replace("/", os.sep)
        path = os.path.join(stage_dir, rel)
        if not os.path.isfile(path):
            print("FAIL: staged file missing: %s" % rel, file=sys.stderr)
            failed = True
            continue
        with open(path, "rb") as f:
            actual = hashlib.sha256(f.read()).hexdigest()
        if actual != digest:
            print("FAIL: SHA256 mismatch for %s (audit %s, actual %s)"
                  % (rel, digest, actual), file=sys.stderr)
            failed = True
        checked += 1
    if checked == 0:
        print("FAIL: no staged files listed in the audit manifest", file=sys.stderr)
        return 2
    if failed:
        return 1
    print("OK: %d staged files verified against import-audit.txt" % checked)
    return 0


# ---------------------------------------------------------------------------

def discover_player(stage_dir, explicit_player=None, explicit_core=None,
                    explicit_candidate=None, explicit_fceumm_candidate=None,
                    explicit_prosystem_candidate=None,
                    explicit_wswan_candidate=None):
    """(player, test_core, gambatte candidate, fceumm candidate, prosystem
    candidate, mednafen_wswan candidate) — a candidate is None when the stage
    carries no cores-candidate/ build for it (the harness then fails that
    core's scenarios: the qualification gate must not silently shrink)."""
    if IS_WINDOWS:
        player = explicit_player or os.path.join(stage_dir, "bin", "rommulus-player.exe")
        core = explicit_core or os.path.join(stage_dir, "cores", "test_core.dll")
        candidate = explicit_candidate or os.path.join(
            stage_dir, "cores-candidate", "gambatte_core.dll")
        fceumm_candidate = explicit_fceumm_candidate or os.path.join(
            stage_dir, "cores-candidate", "fceumm_core.dll")
        prosystem_candidate = explicit_prosystem_candidate or os.path.join(
            stage_dir, "cores-candidate", "prosystem_core.dll")
        wswan_candidate = explicit_wswan_candidate or os.path.join(
            stage_dir, "cores-candidate", "mednafen_wswan_core.dll")
    else:
        player, core = explicit_player, explicit_core
        if not player:
            for cand in (os.path.join(stage_dir, "bin", "rommulus-player"),
                          os.path.join(stage_dir, "rommulus_player")):
                if os.path.isfile(cand):
                    player = cand
                    break
        if not core:
            for cand in (os.path.join(stage_dir, "cores", "libtest_core.so"),
                          os.path.join(stage_dir, "bin", "libtest_core.so"),
                          os.path.join(stage_dir, "libtest_core.so"),
                          # macOS local dev builds (brew SDL3): CMake emits a
                          # .dylib at the build-tree root.
                          os.path.join(stage_dir, "libtest_core.dylib")):
                if os.path.isfile(cand):
                    core = cand
                    break
        candidate = explicit_candidate
        if not candidate:
            for cand in (os.path.join(stage_dir, "cores-candidate", "libgambatte_core.so"),
                          os.path.join(stage_dir, "cores-candidate", "gambatte_core.dll"),
                          # POSIX player build trees emit the core at the
                          # build-tree root (CMAKE_LIBRARY_OUTPUT_DIRECTORY).
                          os.path.join(stage_dir, "libgambatte_core.so"),
                          os.path.join(stage_dir, "libgambatte_core.dylib")):
                if os.path.isfile(cand):
                    candidate = cand
                    break
        fceumm_candidate = explicit_fceumm_candidate
        if not fceumm_candidate:
            for cand in (os.path.join(stage_dir, "cores-candidate", "libfceumm_core.so"),
                          os.path.join(stage_dir, "cores-candidate", "fceumm_core.dll"),
                          # POSIX player build trees emit the core at the
                          # build-tree root (CMAKE_LIBRARY_OUTPUT_DIRECTORY).
                          os.path.join(stage_dir, "libfceumm_core.so"),
                          os.path.join(stage_dir, "libfceumm_core.dylib")):
                if os.path.isfile(cand):
                    fceumm_candidate = cand
                    break
        prosystem_candidate = explicit_prosystem_candidate
        if not prosystem_candidate:
            for cand in (os.path.join(stage_dir, "cores-candidate", "libprosystem_core.so"),
                          os.path.join(stage_dir, "cores-candidate", "prosystem_core.dll"),
                          # POSIX player build trees emit the core at the
                          # build-tree root (CMAKE_LIBRARY_OUTPUT_DIRECTORY).
                          os.path.join(stage_dir, "libprosystem_core.so"),
                          os.path.join(stage_dir, "libprosystem_core.dylib")):
                if os.path.isfile(cand):
                    prosystem_candidate = cand
                    break
        wswan_candidate = explicit_wswan_candidate
        if not wswan_candidate:
            for cand in (os.path.join(stage_dir, "cores-candidate", "libmednafen_wswan_core.so"),
                          os.path.join(stage_dir, "cores-candidate", "mednafen_wswan_core.dll"),
                          # POSIX player build trees emit the core at the
                          # build-tree root (CMAKE_LIBRARY_OUTPUT_DIRECTORY).
                          os.path.join(stage_dir, "libmednafen_wswan_core.so"),
                          os.path.join(stage_dir, "libmednafen_wswan_core.dylib")):
                if os.path.isfile(cand):
                    wswan_candidate = cand
                    break
    if candidate is not None and not os.path.isfile(candidate):
        candidate = None
    if fceumm_candidate is not None and not os.path.isfile(fceumm_candidate):
        fceumm_candidate = None
    if prosystem_candidate is not None and not os.path.isfile(prosystem_candidate):
        prosystem_candidate = None
    if wswan_candidate is not None and not os.path.isfile(wswan_candidate):
        wswan_candidate = None
    return player, core, candidate, fceumm_candidate, prosystem_candidate, wswan_candidate


def main(argv=None):
    ap = argparse.ArgumentParser(description="host-portable rommulus-player E2E harness")
    ap.add_argument("--stage", help="staged artifact directory (bin/ + cores/)")
    ap.add_argument("--workdir", help="output directory for report/logs/results")
    ap.add_argument("--player", help="explicit player executable path")
    ap.add_argument("--core", help="explicit test_core shared library path")
    ap.add_argument("--candidate-core",
                    help="explicit candidate Gambatte core path "
                         "(default: cores-candidate/ in the stage dir)")
    ap.add_argument("--fceumm-core",
                    help="explicit candidate FCEUmm core path "
                         "(default: cores-candidate/ in the stage dir)")
    ap.add_argument("--prosystem-core",
                    help="explicit candidate ProSystem core path "
                         "(default: cores-candidate/ in the stage dir)")
    ap.add_argument("--wswan-core",
                    help="explicit candidate mednafen_wswan core path "
                         "(default: cores-candidate/ in the stage dir)")
    ap.add_argument("--verify-artifact", metavar="DIR",
                    help="verify DIR against its import-audit.txt and exit")
    ap.add_argument("--timeout-sec", type=int, default=90,
                    help="per-launch wall-clock timeout (default 90)")
    ap.add_argument("--video-driver", default="offscreen")
    ap.add_argument("--audio-driver", default="dummy")
    ap.add_argument("--render-driver", default="software")
    args = ap.parse_args(argv)

    if args.verify_artifact:
        return verify_artifact(args.verify_artifact)
    if not args.stage or not args.workdir:
        ap.error("--stage and --workdir are required (or use --verify-artifact)")

    player, core, candidate, fceumm_candidate, prosystem_candidate, \
        wswan_candidate = discover_player(
            args.stage, args.player, args.core,
            args.candidate_core, args.fceumm_core,
            args.prosystem_core, args.wswan_core)
    for label, path in (("player", player), ("test_core", core)):
        if not path or not os.path.isfile(path):
            print("FAIL: %s not found at %r" % (label, path), file=sys.stderr)
            return 2
    if candidate is None:
        # The Gambatte qualification scenarios cannot run without the
        # candidate core; fail loudly (environment error) rather than
        # silently shrinking the gate.
        print("FAIL: candidate Gambatte core not found in %r (expected "
              "cores-candidate/gambatte_core.dll on Windows or "
              "libgambatte_core.so/.dylib on POSIX; pass --candidate-core)"
              % args.stage, file=sys.stderr)
        return 2
    if fceumm_candidate is None:
        # The FCEUmm qualification scenarios cannot run without the candidate
        # core; fail loudly (environment error) rather than silently shrinking
        # the gate.
        print("FAIL: candidate FCEUmm core not found in %r (expected "
              "cores-candidate/fceumm_core.dll on Windows or "
              "libfceumm_core.so/.dylib on POSIX; pass --fceumm-core)"
              % args.stage, file=sys.stderr)
        return 2
    if prosystem_candidate is None:
        # The ProSystem qualification scenarios cannot run without the
        # candidate core; fail loudly (environment error) rather than
        # silently shrinking the gate.
        print("FAIL: candidate ProSystem core not found in %r (expected "
              "cores-candidate/prosystem_core.dll on Windows or "
              "libprosystem_core.so/.dylib on POSIX; pass --prosystem-core)"
              % args.stage, file=sys.stderr)
        return 2
    if wswan_candidate is None:
        # The mednafen_wswan qualification scenarios cannot run without the
        # candidate core; fail loudly (environment error) rather than
        # silently shrinking the gate.
        print("FAIL: candidate mednafen_wswan core not found in %r (expected "
              "cores-candidate/mednafen_wswan_core.dll on Windows or "
              "libmednafen_wswan_core.so/.dylib on POSIX; pass --wswan-core)"
              % args.stage, file=sys.stderr)
        return 2

    os.makedirs(args.workdir, exist_ok=True)
    runner = Runner(args.stage, args.workdir, player, core, args.timeout_sec,
                    args.video_driver, args.audio_driver, args.render_driver,
                    candidate_core=candidate,
                    fceumm_candidate_core=fceumm_candidate,
                    prosystem_candidate_core=prosystem_candidate,
                    wswan_candidate_core=wswan_candidate)
    print("player:   %s" % player)
    print("core:     %s" % core)
    print("candidate: %s" % candidate)
    print("fceumm candidate: %s" % fceumm_candidate)
    print("prosystem candidate: %s" % prosystem_candidate)
    print("wswan candidate: %s" % wswan_candidate)
    print("tree:     %s" % runner.base)
    return runner.run()


if __name__ == "__main__":
    sys.exit(main())
