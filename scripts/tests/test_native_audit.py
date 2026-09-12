import struct
import json
from pathlib import Path
import unittest

from audit_native_libraries import is_expected_runtime, load_alignments


class NativeAuditTests(unittest.TestCase):
    def test_accepts_only_verified_raw_and_stripped_runtime(self):
        manifest = Path(__file__).resolve().parents[1] / "native-runtime-sha256.json"
        expected = json.loads(manifest.read_text(encoding="utf-8"))
        for abi, hashes in expected.items():
            self.assertEqual(2, len(hashes))
            for value in hashes:
                self.assertTrue(is_expected_runtime({"abi": abi, "sha256": value}, expected))
        # The incompatible ass-kt runtime lacks symbols required by mpv.
        self.assertFalse(is_expected_runtime({"abi": "x86_64", "sha256": "294e0ce4e88589103952c57c9484f917c26509d66170bc86d07d48f0fc879de4"}, expected))

    def elf(self, bits, alignment):
        data = bytearray(128)
        data[:6] = b"\x7fELF" + bytes([2 if bits == 64 else 1, 1])
        struct.pack_into("<Q" if bits == 64 else "<I", data, 32 if bits == 64 else 28, 64)
        struct.pack_into("<HH", data, 54 if bits == 64 else 42, 56 if bits == 64 else 32, 1)
        struct.pack_into("<I", data, 64, 1)
        struct.pack_into("<Q" if bits == 64 else "<I", data, 112 if bits == 64 else 92, alignment)
        return bytes(data)

    def test_64_bit_16k_load(self):
        self.assertEqual([16384], load_alignments(self.elf(64, 16384)))

    def test_32_bit_4k_load(self):
        self.assertEqual([4096], load_alignments(self.elf(32, 4096)))

    def test_rejects_non_elf(self):
        with self.assertRaises(ValueError):
            load_alignments(b"x" * 128)

    def test_rejects_truncated_program_table(self):
        with self.assertRaises(ValueError):
            load_alignments(self.elf(64, 4096)[:100])
