#!/usr/bin/env python3
"""Verify that published APKs contain loadable USB Direct libraries for every ABI."""

import glob
import struct
import sys
import zipfile
from pathlib import Path

ABI_HEADERS = {
    "arm64-v8a": (2, 183),
    "armeabi-v7a": (1, 40),
    "x86": (1, 3),
    "x86_64": (2, 62),
}


def verify_apk(path: Path) -> None:
    with zipfile.ZipFile(path) as apk:
        for abi, (elf_class, machine) in ABI_HEADERS.items():
            name = f"lib/{abi}/libampp_audio.so"
            try:
                entry = apk.getinfo(name)
            except KeyError as error:
                raise ValueError(f"{path}: missing {name}") from error
            if entry.compress_type != zipfile.ZIP_STORED:
                raise ValueError(f"{path}: {name} must be uncompressed for module class-loader lookup")
            with apk.open(entry) as library:
                header = library.read(20)
            if len(header) < 20 or header[:4] != b"\x7fELF" or header[5] != 1:
                raise ValueError(f"{path}: invalid ELF header in {name}")
            if header[4] != elf_class or struct.unpack_from("<H", header, 18)[0] != machine:
                raise ValueError(f"{path}: ELF architecture does not match {abi}")
    print(f"Verified USB Direct native libraries: {path}")


def main(patterns: list[str]) -> None:
    paths = sorted({Path(path) for pattern in patterns for path in glob.glob(pattern)})
    if not paths:
        raise ValueError("No APKs found; pass the built APK path or glob")
    for path in paths:
        verify_apk(path)


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        sys.exit(str(error))
