#!/usr/bin/env python3
"""Stage the complete Windows native runtime and audit its PE/export closure."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SYSTEM_DLLS = {
    "kernel32.dll", "ntdll.dll", "kernelbase.dll", "user32.dll", "gdi32.dll",
    "gdiplus.dll", "advapi32.dll", "shell32.dll", "ole32.dll", "oleaut32.dll",
    "psapi.dll", "setupapi.dll", "version.dll", "winmm.dll", "imm32.dll",
    "ucrtbase.dll", "bcrypt.dll", "cfgmgr32.dll", "avrt.dll", "shcore.dll",
    "dwmapi.dll", "dxgi.dll", "d3d11.dll", "d3d12.dll", "d3dcompiler_47.dll",
    "dinput8.dll", "ws2_32.dll", "comdlg32.dll", "comctl32.dll",
}


def inspect_pe(path: Path) -> tuple[set[str], set[str]]:
    header = subprocess.check_output(["objdump", "-f", str(path)], text=True)
    if not re.search(r"file format (pei|pe)-x86-64", header):
        raise ValueError(f"Not a Windows x64 PE image: {path}")
    output = subprocess.check_output(["objdump", "-p", str(path)], text=True)
    imports = set(re.findall(r"DLL Name:\s*(\S+)", output))
    export_section = output.split("[Ordinal/Name Pointer] Table")
    exports = set()
    if len(export_section) > 1:
        for line in export_section[1].lstrip("\r\n").splitlines():
            if not line.strip():
                break
            if re.match(r"\s*\[\s*\d+\]\s+", line):
                exports.add(line.split()[-1])
    return imports, exports


def expected_exports(core_id: str) -> set[str]:
    definition = ROOT / "native/cmake/cores" / f"{core_id}-windows.def"
    lines = definition.read_text().split("EXPORTS", 1)[1].splitlines()
    return {line.split(";", 1)[0].strip().split()[0] for line in lines
            if line.split(";", 1)[0].strip()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--sdl", type=Path, required=True)
    parser.add_argument("--angle", type=Path, required=True)
    parser.add_argument("--runtime-bin", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError(f"Use a fresh staging directory: {args.output}")
    cores = json.loads((ROOT / "packaging/share/rommulus/windows-core-manifest.json").read_text())["cores"]
    binary_dir = args.output / "bin"
    core_dir = args.output / "cores"
    licenses = args.output / "licenses"
    for directory in (binary_dir, core_dir, licenses):
        directory.mkdir(parents=True)
    shutil.copy2(args.build / "bin/rommulus-player.exe", binary_dir)
    shutil.copy2(args.sdl / "bin/SDL3.dll", binary_dir)
    for name in ("libEGL.dll", "libGLESv2.dll"):
        shutil.copy2(args.angle / name, binary_dir)
    shutil.copy2(args.angle / "LICENSE", licenses / "ANGLE-LICENSE.txt")
    shutil.copy2(ROOT / "deps/sdl3/LICENSE.txt", licenses / "SDL3-LICENSE.txt")
    for core in cores:
        shutil.copy2(args.build / "bin" / f"{core['coreId']}_core.dll", core_dir)

    pending = list(binary_dir.iterdir()) + list(core_dir.iterdir())
    audit = {}
    while pending:
        path = pending.pop()
        relative = path.relative_to(args.output).as_posix()
        if relative in audit:
            continue
        imports, exports = inspect_pe(path)
        if path.parent == core_dir:
            core_id = path.name.removesuffix("_core.dll")
            expected = expected_exports(core_id)
            if exports != expected:
                raise ValueError(f"{core_id} export mismatch: missing {expected - exports}; extra {exports - expected}")
        audit[relative] = {"imports": sorted(imports), "exports": sorted(exports)}
        for name in imports:
            lower = name.lower()
            if lower in SYSTEM_DLLS or lower.startswith("api-ms-win-"):
                continue
            existing = next((file for file in binary_dir.iterdir() if file.name.lower() == lower), None)
            if existing is None:
                if lower != "libwinpthread-1.dll":
                    raise ValueError(f"{path.name} imports unstaged or forbidden runtime {name}")
                existing = binary_dir / name
                shutil.copy2(args.runtime_bin / name, existing)
            pending.append(existing)
    for path in args.output.rglob("*"):
        if path.is_file():
            relative = path.relative_to(args.output).as_posix()
            audit.setdefault(relative, {})["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    (args.output / "native-audit.json").write_text(json.dumps(audit, indent=2) + "\n")
    print(f"Staged and audited Windows player, GPU runtime, and {len(cores)} cores.")


if __name__ == "__main__":
    main()
