"""Regression for builtin plugin loading under the Windows PCSX adapter."""

import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[4]
CORE = ROOT / "third_party/cores/pcsx_rearmed"
ADAPTER = ROOT / "native/cmake/cores/pcsx_rearmed-windows.cmake"


class PcsxBuiltinPluginsTest(unittest.TestCase):
    def test_windows_disables_external_plugin_loader(self):
        definitions = ADAPTER.read_text(encoding="utf-8").split(
            "target_compile_definitions(pcsx_rearmed_core PRIVATE", 1)[1].split(")", 1)[0]
        self.assertRegex(definitions, r"(?m)^\s*NO_DYLIB\s*$")

    def test_real_windows_loader_and_partial_teardown(self):
        compiler = shutil.which("cc") or shutil.which("gcc")
        if compiler is None:
            self.skipTest("native C compiler unavailable; content E2E remains mandatory")
        # _WIN32 selects the actual problematic upstream error path even on
        # POSIX, without needing Wine or weakening the Windows content gate.
        with tempfile.TemporaryDirectory(prefix="ps1-plugin-test-", dir=ROOT) as directory:
            executable = Path(directory) / ("plugins-test.exe" if os.name == "nt"
                                             else "plugins-test")
            command = [
                compiler, "-D_WIN32", "-DNO_FRONTEND", "-DHAVE_LIBRETRO",
                "-ffunction-sections", "-fdata-sections",
                "-I" + str(CORE), "-I" + str(CORE / "include"),
                "-I" + str(CORE / "deps/libretro-common/include"),
                str(ROOT / "native/player/tests/core_smoke/pcsx_builtin_plugins_test.c"),
                str(CORE / "frontend/main.c"), str(CORE / "libpcsxcore/plugins.c"),
                "-Wl,-dead_strip" if sys.platform == "darwin" else "-Wl,--gc-sections",
                "-o", str(executable),
            ]
            environment = dict(os.environ, TMPDIR=directory, TEMP=directory, TMP=directory)
            # Negative control reproduces the hosted failure before GPU/SPU
            # initialization; the configured adapter must make this succeed.
            subprocess.run(command, env=environment, check=True, capture_output=True)
            failed = subprocess.run([str(executable)], capture_output=True, text=True)
            self.assertEqual(failed.returncode, 1, failed.stdout + failed.stderr)
            self.assertIn("not supported", failed.stderr)
            definitions = ADAPTER.read_text(encoding="utf-8")
            if re.search(r"(?m)^\s*NO_DYLIB\s*$", definitions):
                command.insert(1, "-DNO_DYLIB")
            subprocess.run(command, env=environment, check=True, capture_output=True)
            passed = subprocess.run([str(executable)], capture_output=True, text=True)
            self.assertEqual(passed.returncode, 0, passed.stdout + passed.stderr)
            self.assertIn("partial teardown passed", passed.stdout)


if __name__ == "__main__":
    unittest.main()
