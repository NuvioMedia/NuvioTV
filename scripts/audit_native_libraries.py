"""Inspect packaged ELF LOAD alignment and duplicate native inputs without executing them."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def load_alignments(data: bytes) -> list[int]:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ValueError("Not an ELF binary")
    byte_order = {1: "<", 2: ">"}.get(data[5])
    if byte_order is None:
        raise ValueError("Unsupported ELF byte order")
    if data[4] == 2:
        phoff = struct.unpack_from(byte_order + "Q", data, 32)[0]
        entry_size, count = struct.unpack_from(byte_order + "HH", data, 54)
        alignment_offset, alignment_format = 48, "Q"
    elif data[4] == 1:
        phoff = struct.unpack_from(byte_order + "I", data, 28)[0]
        entry_size, count = struct.unpack_from(byte_order + "HH", data, 42)
        alignment_offset, alignment_format = 28, "I"
    else:
        raise ValueError("Unsupported ELF class")
    if entry_size < alignment_offset + struct.calcsize(alignment_format):
        raise ValueError("Truncated ELF program header")
    result = []
    for index in range(count):
        offset = phoff + index * entry_size
        if offset + entry_size > len(data):
            raise ValueError("ELF program header outside file")
        if struct.unpack_from(byte_order + "I", data, offset)[0] == 1:
            result.append(struct.unpack_from(byte_order + alignment_format, data, offset + alignment_offset)[0])
    if not result:
        raise ValueError("ELF has no LOAD segments")
    return result


def inspect_archive(path: Path) -> list[dict]:
    rows = []
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            parts = entry.filename.split("/")
            if len(parts) != 3 or parts[0] not in ("lib", "jni") or not parts[-1].endswith(".so"):
                continue
            data = archive.read(entry)
            alignments = load_alignments(data)
            rows.append({"archive": path.name, "abi": parts[1], "library": parts[2],
                         "sha256": hashlib.sha256(data).hexdigest(), "loadAlignments": alignments,
                         "elf16kAligned": all(value >= 16384 for value in alignments),
                         "zipCompressed": entry.compress_type != zipfile.ZIP_STORED})
    return rows


def is_expected_runtime(row: dict, expected: dict) -> bool:
    return row["sha256"] in expected.get(row["abi"], [])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archives", type=Path, nargs="+")
    parser.add_argument("--require-16k", action="store_true", help="Fail for 64-bit Android ELF LOAD alignment below 16 KiB")
    parser.add_argument("--verify-runtime", action="store_true", help="Require the audited mpv libc++ runtime for every packaged ABI")
    args = parser.parse_args()
    rows = [row for path in args.archives for row in inspect_archive(path)]
    groups: dict[tuple[str, str], list[dict]] = {}
    for row in rows:
        groups.setdefault((row["abi"], row["library"]), []).append(row)
    duplicates = [{"abi": abi, "library": library, "identical": len({r["sha256"] for r in group}) == 1,
                   "archives": [r["archive"] for r in group]}
                  for (abi, library), group in sorted(groups.items()) if len(group) > 1]
    unaligned_64 = [row for row in rows if row["abi"] in ("arm64-v8a", "x86_64") and not row["elf16kAligned"]]
    runtime_failures = []
    if args.verify_runtime:
        expected = json.loads(Path(__file__).with_name("native-runtime-sha256.json").read_text())
        for archive in args.archives:
            archive_rows = [row for row in rows if row["archive"] == archive.name]
            for abi in {row["abi"] for row in archive_rows}:
                runtimes = [row for row in archive_rows if row["abi"] == abi and row["library"] == "libc++_shared.so"]
                if len(runtimes) != 1 or not is_expected_runtime(runtimes[0], expected):
                    runtime_failures.append({"archive": archive.name, "abi": abi})
            if not archive_rows:
                runtime_failures.append({"archive": archive.name, "abi": "no native libraries"})
    print(json.dumps({"libraries": rows, "duplicates": duplicates,
                      "unalignedCount": sum(not row["elf16kAligned"] for row in rows),
                      "unaligned64BitCount": len(unaligned_64),
                      "runtimeFailures": runtime_failures}, indent=2))
    return int(bool(runtime_failures) or (args.require_16k and bool(unaligned_64)))


if __name__ == "__main__":
    raise SystemExit(main())
