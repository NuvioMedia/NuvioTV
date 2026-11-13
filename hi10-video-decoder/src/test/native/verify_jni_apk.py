"""Check actual DEX native registrations, not R8 mapping (identity names may be omitted)."""
import re
import struct
import sys
import zipfile
from pathlib import Path


def native_methods(data):
    if data[:4] != b"dex\n":
        raise ValueError("Unsupported DEX header")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def uleb(offset):
        value = shift = 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value, offset
            shift += 7
            if shift > 28:
                raise ValueError("Invalid ULEB128")

    strings = []
    for i in range(u32(56)):
        _, start = uleb(u32(u32(60) + 4 * i))
        end = data.index(0, start)
        strings.append(data[start:end].decode("utf-8", errors="replace"))
    types = [strings[u32(u32(68) + 4 * i)] for i in range(u32(64))]
    protos = []
    for i in range(u32(72)):
        _, ret, params = struct.unpack_from("<III", data, u32(76) + 12 * i)
        args = [] if not params else [types[struct.unpack_from("<H", data, params + 4 + 2 * j)[0]]
                                     for j in range(u32(params))]
        protos.append("(" + "".join(args) + ")" + types[ret])
    method_ids = [struct.unpack_from("<HHI", data, u32(92) + 8 * i) for i in range(u32(88))]
    found = set()
    for i in range(u32(96)):
        fields = struct.unpack_from("<8I", data, u32(100) + 32 * i)
        pos = fields[6]
        if not pos:
            continue
        counts = []
        for _ in range(4):
            n, pos = uleb(pos)
            counts.append(n)
        for _ in range(counts[0] + counts[1]):
            _, pos = uleb(pos)
            _, pos = uleb(pos)
        for count in counts[2:]:
            index = 0
            for _ in range(count):
                delta, pos = uleb(pos)
                flags, pos = uleb(pos)
                code, pos = uleb(pos)
                index += delta
                if flags & 0x100:
                    if code:
                        raise ValueError("Native method unexpectedly has DEX code")
                    cls, proto, name = method_ids[index]
                    found.add((types[cls], strings[name], protos[proto]))
    return found


source = (Path(__file__).resolve().parents[2] / "main/jni/nuvio_hi10_video_jni.cc").read_text()
expected = set()
for table, cls in (("kLibraryMethods", "NuvioHi10VideoLibrary"),
                   ("kDecoderMethods", "FfmpegHigh10VideoDecoder")):
    match = re.search(table + r"\[\]\s*=\s*\{(.*?)\n\};", source, re.S)
    if not match:
        raise ValueError("Registration table missing: " + table)
    for name, signature in re.findall(r'\{"(native[^"]+)"\s*,\s*"([^"]+)"', match[1]):
        expected.add(("Lcom/nuvio/hi10video/" + cls + ";", name, signature))
if not expected:
    raise ValueError("No registrations checked")
actual = set()
with zipfile.ZipFile(sys.argv[1]) as apk:
    for name in apk.namelist():
        if re.fullmatch(r"classes\d*\.dex", name):
            actual.update(native_methods(apk.read(name)))
missing = expected - actual
if missing:
    raise SystemExit("FAIL JNI registrations absent/renamed in actual DEX: " + repr(sorted(missing)))
print(f"PASS: all {len(expected)} registered native names/signatures and both JNI class names exist in APK DEX")
