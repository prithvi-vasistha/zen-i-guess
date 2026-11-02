#!/usr/bin/env python3
"""Check 64-bit ELF LOAD alignment in an AAB/APK, plus ZIP alignment in APKs."""

import argparse
from pathlib import Path
import struct
import sys
import zipfile

PAGE_SIZE = 16384
ABIS = {"arm64-v8a", "x86_64"}


def check_archive(path: Path) -> bool:
    failures = []
    checked = 0
    with zipfile.ZipFile(path) as archive, path.open("rb") as raw:
        for entry in archive.infolist():
            parts = Path(entry.filename).parts
            if len(parts) < 3 or parts[-3] != "lib" or parts[-2] not in ABIS:
                continue
            if not entry.filename.endswith(".so"):
                continue
            checked += 1
            data = archive.read(entry)
            if data[:6] != b"\x7fELF\x02\x01":
                failures.append(f"{entry.filename}: expected a little-endian ELF64 library")
                continue
            phoff = struct.unpack_from("<Q", data, 32)[0]
            phsize, phnum = struct.unpack_from("<HH", data, 54)
            loads = []
            for index in range(phnum):
                header = struct.unpack_from("<IIQQQQQQ", data, phoff + index * phsize)
                if header[0] == 1:  # PT_LOAD
                    loads.append(header)
            if not loads:
                failures.append(f"{entry.filename}: no LOAD segments")
            for index, header in enumerate(loads):
                offset, address, alignment = header[2], header[3], header[7]
                if alignment < PAGE_SIZE or alignment & (alignment - 1):
                    failures.append(
                        f"{entry.filename}: LOAD {index} alignment {alignment:#x} < 16 KB or invalid"
                    )
                if (address - offset) % PAGE_SIZE:
                    failures.append(f"{entry.filename}: LOAD {index} is not 16 KB congruent")
            # Bundle entries are repackaged by Play; only APKs need ZIP offset checks.
            if path.suffix.lower() == ".apk" and entry.compress_type == zipfile.ZIP_STORED:
                raw.seek(entry.header_offset + 26)
                name_size, extra_size = struct.unpack("<HH", raw.read(4))
                offset = entry.header_offset + 30 + name_size + extra_size
                if offset % PAGE_SIZE:
                    failures.append(f"{entry.filename}: APK ZIP offset {offset} is not 16 KB aligned")
    for failure in failures:
        print(f"FAIL {failure}", file=sys.stderr)
    print(f"{path}: {checked} native libraries checked, {len(failures)} alignment failures")
    return not failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, help="App bundle (.aab) or APK to inspect")
    args = parser.parse_args()
    try:
        return 0 if check_archive(args.archive) else 1
    except (OSError, ValueError, struct.error, zipfile.BadZipFile) as error:
        print(f"Cannot inspect {args.archive}: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
