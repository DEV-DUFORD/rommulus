import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "stage_windows_native", Path(__file__).with_name("stage-windows-native.py"),
)
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


class NativeAuditTest(unittest.TestCase):
    def test_reads_pe_imports_and_exact_named_exports(self):
        with patch.object(stage.subprocess, "check_output", side_effect=[
            "core.dll: file format pei-x86-64\n",
            "DLL Name: KERNEL32.dll\nDLL Name: libGLESv2.dll\n"
            "[Ordinal/Name Pointer] Table\n"
            "\t[   0] retro_init\n\t[   1] romm_get_save_memory_size\n\n"
            "PE File Base Relocations\n",
        ]):
            imports, exports = stage.inspect_pe(Path("core.dll"))
        self.assertEqual(imports, {"KERNEL32.dll", "libGLESv2.dll"})
        self.assertEqual(exports, {"retro_init", "romm_get_save_memory_size"})

    def test_rejects_other_architectures(self):
        with patch.object(stage.subprocess, "check_output", return_value="file format pei-i386"):
            with self.assertRaisesRegex(ValueError, "Not a Windows x64"):
                stage.inspect_pe(Path("core.dll"))

    def test_reads_new_binutils_export_addresses(self):
        with patch.object(stage.subprocess, "check_output", side_effect=[
            "file format pei-x86-64",
            "[Ordinal/Name Pointer] Table\n"
            "[   0] +base[   1] 0000000000001234 retro_init\n\n",
        ]):
            _, exports = stage.inspect_pe(Path("core.dll"))
        self.assertEqual(exports, {"retro_init"})

    def test_export_definitions_include_save_extensions_not_comments(self):
        self.assertEqual(len(stage.expected_exports("gambatte")), 22)
        genesis = stage.expected_exports("genesis_plus_gx")
        self.assertEqual(len(genesis), 26)
        self.assertIn("romm_restore_save_memory", genesis)


if __name__ == "__main__":
    unittest.main()
