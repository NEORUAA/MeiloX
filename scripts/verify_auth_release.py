#!/usr/bin/env python3
"""Audit executable assets, unchanged native libraries and Caesarson's field ABI."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path


def caesarson_fields(dex):
    """Read declared fields from DEX class_data, rather than mere field references."""
    def uint(offset):
        return struct.unpack_from("<I", dex, offset)[0]

    def uleb(offset):
        value = shift = 0
        while True:
            byte = dex[offset]
            offset += 1
            value |= (byte & 0x7f) << shift
            if byte < 0x80:
                return value, offset
            shift += 7

    def string(index):
        offset = uint(uint(60) + index * 4)
        _, offset = uleb(offset)  # UTF-16 length precedes the MUTF-8 bytes.
        return dex[offset:dex.index(b"\0", offset)].decode("utf-8", errors="replace")

    def descriptor(index):
        return string(uint(uint(68) + index * 4))

    for index in range(uint(96)):
        definition = uint(100) + index * 32
        if descriptor(uint(definition)) != "Lcom/netease/cloudmusic/crypto/caesarson/ErrorObject;":
            continue
        offset = uint(definition + 24)
        if not offset:
            return set()
        sizes = []
        for _ in range(4):
            size, offset = uleb(offset)
            sizes.append(size)
        fields = set()
        for count in sizes[:2]:  # Static and instance lists each restart the index.
            field_index = 0
            for _ in range(count):
                delta, offset = uleb(offset)
                flags, offset = uleb(offset)
                field_index += delta
                _, type_index, name_index = struct.unpack_from("<HHI", dex, uint(84) + field_index * 8)
                fields.add((string(name_index), descriptor(type_index), bool(flags & 0x8)))
        return fields
    return set()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--native-baseline", required=True, type=Path)
    args = parser.parse_args()
    baseline = json.loads(args.native_baseline.read_text())
    with zipfile.ZipFile(args.apk) as apk:
        for name in apk.namelist():
            if name.startswith("assets/") and not name.endswith("/"):
                with apk.open(name) as asset:
                    magic = asset.read(4)
                if name.lower().endswith(".dex") or magic in (b"dex\n", b"cdex"):
                    raise SystemExit(f"FAIL: executable asset {name}")
        natives = {name: hashlib.sha256(apk.read(name)).hexdigest()
                   for name in apk.namelist() if name.endswith(".so")}
        if natives != baseline:
            changed = sorted(name for name in natives.keys() | baseline.keys()
                             if natives.get(name) != baseline.get(name))
            raise SystemExit(f"FAIL: native libraries changed: {changed}")
        code = [name for name in apk.namelist() if name.startswith("classes") and name.endswith(".dex")]
        if not code:
            raise SystemExit("FAIL: missing compiled application code")
        fields = set().union(*(caesarson_fields(apk.read(name)) for name in code))
        expected = {("errorCode", "I", False), ("message", "Ljava/lang/String;", False)}
        if not expected <= fields:
            raise SystemExit(f"FAIL: Caesarson JNI fields missing or changed: {sorted(expected - fields)}")
    result = {"apk": str(args.apk), "bytes": args.apk.stat().st_size,
              "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              "assets_dex": 0, "native_libraries_identical": len(natives),
              "compiled_dex_files": len(code), "caesarson_jni_fields_preserved": True}
    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
