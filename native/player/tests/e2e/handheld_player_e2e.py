#!/usr/bin/env python3
"""Standalone Handy/NGP player save-adoption and force-kill qualification.

Uses the existing protocol/process helpers without changing the shared runner.
Requires a ROMM_PLAYER_QUALIFICATION player. Example:
  python3 handheld_player_e2e.py --player build/player/rommulus_player \
    --core build/handheld/handy_core.dll --core-id handy --work-dir build/reports

This is a generated-content lifecycle gate, not physical audio/video/controller
qualification. No ROM, BIOS, or historical loader bytes are downloaded.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import time
import uuid

from handheld_roms import handy_loader, handy_rom, ngp_flash, ngp_rom
from player_e2e import PlayerProcess, as_posix, build_request, validate_result_schema


REVISIONS = {
    "handy": "bc55d462f0b2d6b073ea93dc552ebd73cec60fd1",
    "mednafen_ngp": "a50d5ac288a81f2104ddf43195a4efdd15c72227",
}


def assert_save_unchanged(label, before, after, report, logs):
    details = {
        "name": label,
        "beforeSize": len(before),
        "afterSize": len(after),
        "beforeSha256": hashlib.sha256(before).hexdigest(),
        "afterSha256": hashlib.sha256(after).hexdigest(),
    }
    report.setdefault("authoritativeSaveChecks", []).append(details)
    if before != after:
        differences = []
        for offset in range(max(len(before), len(after))):
            left = before[offset] if offset < len(before) else None
            right = after[offset] if offset < len(after) else None
            if left != right:
                differences.append({"offset": offset, "before": left, "after": right})
            if len(differences) == 32:
                break
        details["firstDifferences"] = differences
        (logs / (label + ".save-before.bin")).write_bytes(before)
        (logs / (label + ".save-after.bin")).write_bytes(after)
        raise AssertionError(label + ": authoritative save overwritten: " +
                             json.dumps(details, sort_keys=True))


def qualify(args):
    root = Path(args.work_dir).resolve() / ("handheld-тест état-" + uuid.uuid4().hex)
    roots = {name: root / name for name in ("cores", "cache", "data", "state", "logs")}
    for path in roots.values():
        path.mkdir(parents=True)
    core = roots["cores"] / Path(args.core).name
    shutil.copyfile(args.core, core)
    system = roots["data"] / "system"
    system.mkdir()
    is_handy = args.core_id == "handy"
    content = roots["cache"] / ("original.lnx" if is_handy else "original.ngc")
    content.write_bytes(handy_rom() if is_handy else ngp_rom())
    if is_handy:
        (system / "howard.o").write_bytes(handy_loader(write_eeprom=True))
    save = roots["data"] / "save.srm"
    candidate = roots["state"] / "candidate.srm"
    result_path = roots["state"] / "result.json"
    request_path = root / "request.json"
    request = build_request(
        "handheld-e2e", core, system, save, candidate, result_path,
        core_build_revision=REVISIONS[args.core_id], core_id=args.core_id,
        content_path=content)
    request_path.write_text(json.dumps(request, ensure_ascii=False), encoding="utf-8")
    env = dict(os.environ)
    for name in ("cores", "cache", "data", "state"):
        key = "CORE" if name == "cores" else name.upper()
        env["ROMM_PLAYER_" + key + "_ROOT"] = as_posix(roots[name])
    env["ROMM_PLAYER_ALLOWED_CORES"] = args.core_id + "=" + REVISIONS[args.core_id]
    env["SDL_VIDEODRIVER"] = args.video_driver
    env["SDL_AUDIODRIVER"] = "dummy"
    env["SDL_RENDER_DRIVER"] = "software"
    env["ROMM_PLAYER_TEST_FOCUS_LOSS"] = "1"
    env.pop("ROMM_TEST_CORE_MAX_FRAMES", None)
    if os.name == "nt":
        windows = Path(os.environ.get("SystemRoot", r"C:\Windows"))
        env["PATH"] = os.pathsep.join((str(Path(args.player).resolve().parent),
                                       str(windows / "System32"), str(windows)))
    report = {"core": args.core_id, "passed": False, "scenarios": []}

    def prepare(frames):
        candidate.unlink(missing_ok=True)
        result_path.unlink(missing_ok=True)
        # Sidecars must not conceal a broken generic restore path.
        for pattern in ("*.eeprom", "*.flash"):
            for path in roots["data"].rglob(pattern):
                path.unlink()
        env["ROMM_PLAYER_MAX_FRAMES"] = str(frames)

    def launch(label, expected):
        prepare(90)
        before = save.read_bytes()
        process = PlayerProcess(str(Path(args.player).resolve()), str(request_path),
                                dict(env), str(roots["logs"] / (label + ".log")))
        process.start()
        try:
            rc = process.wait(args.timeout)
            if rc or process.timed_out:
                raise AssertionError(label + ": player failed; see " + str(process.log_path))
            result = json.loads(result_path.read_text(encoding="utf-8"))
            errors = validate_result_schema(result)
            if errors:
                raise AssertionError(str(errors))
            assert result["sessionId"] == "handheld-e2e", result
            assert result["exitKind"] == "completed", result
            assert result["checkpointWritten"], result
            assert result["frames"] >= 90, result
            assert result["saveSize"] == len(expected), result
            assert result["saveHash"] == hashlib.sha256(expected).hexdigest(), result
            assert candidate.read_bytes() == expected, label + ": wrong save bytes"
            assert_save_unchanged(label, before, save.read_bytes(), report, roots["logs"])
            log = Path(process.log_path).read_text(encoding="utf-8", errors="replace")
            assert "qualification focus-loss events queued: lost=1 gained=1" in log, (
                label + ": qualification player did not inject focus-loss events")
            os.replace(candidate, save)
            report["scenarios"].append({"name": label, "result": result})
        finally:
            if process.alive():
                process.terminate()

    try:
        for iteration in range(3):
            value = 0x39 + iteration
            seed = bytes((value,)) * (1024 if is_handy else 256)
            expected = b"\x5a\xa5" + seed[2:] if is_handy else (
                ngp_flash((0x204000, b"\x5a" + seed[1:])))
            save.write_bytes(seed if is_handy else ngp_flash((0x204000, seed)))
            launch("restore-and-adopt-" + str(iteration), expected)

        prepare(36000)
        before = save.read_bytes()
        victim = PlayerProcess(str(Path(args.player).resolve()), str(request_path),
                               dict(env), str(roots["logs"] / "force-kill.log"))
        victim.start()
        try:
            time.sleep(2)
            assert victim.alive(), "force-kill victim exited before termination"
        finally:
            victim.terminate()
            victim.wait(args.timeout)
        assert not victim.alive(), "force-kill victim survived"
        assert_save_unchanged("force-kill", before, save.read_bytes(), report, roots["logs"])
        launch("same-session-after-force-kill", before)
        report["passed"] = True
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        report_path = root / "handheld-e2e-report.json"
        report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(report_path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--player", required=True)
    parser.add_argument("--core", required=True)
    parser.add_argument("--core-id", required=True, choices=tuple(REVISIONS))
    parser.add_argument("--work-dir", required=True)
    parser.add_argument("--video-driver", default="dummy")
    parser.add_argument("--timeout", type=int, default=45)
    qualify(parser.parse_args())


if __name__ == "__main__":
    main()
