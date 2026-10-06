import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    "verify_native_apk", Path(__file__).resolve().parents[1] / "verify-native-apk.py"
)
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class NativeApkTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "app.apk"

    def apk(self, omit=None, compressed=False, wrong_abi=False, malformed=False):
        with zipfile.ZipFile(self.path, "w") as apk:
            for abi, (elf_class, machine) in verifier.ABI_HEADERS.items():
                if abi == omit:
                    continue
                header = bytearray(20)
                header[:4] = b"\x7fELF"
                header[4:6] = bytes([elf_class, 1])
                struct.pack_into("<H", header, 18, 0 if wrong_abi else machine)
                apk.writestr(f"lib/{abi}/libampp_audio.so", b"invalid" if malformed else header,
                             compress_type=zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED)

    def test_complete_apk(self):
        self.apk()
        verifier.verify_apk(self.path)

    def test_missing_library(self):
        self.apk(omit="arm64-v8a")
        with self.assertRaisesRegex(ValueError, "missing lib/arm64-v8a"):
            verifier.verify_apk(self.path)

    def test_compressed_library(self):
        self.apk(compressed=True)
        with self.assertRaisesRegex(ValueError, "must be uncompressed"):
            verifier.verify_apk(self.path)

    def test_wrong_architecture(self):
        self.apk(wrong_abi=True)
        with self.assertRaisesRegex(ValueError, "architecture does not match"):
            verifier.verify_apk(self.path)

    def test_invalid_elf(self):
        self.apk(malformed=True)
        with self.assertRaisesRegex(ValueError, "invalid ELF"):
            verifier.verify_apk(self.path)

    def test_missing_artifact(self):
        with self.assertRaisesRegex(ValueError, "No APKs found"):
            verifier.main([str(self.path)])


if __name__ == "__main__":
    unittest.main()
