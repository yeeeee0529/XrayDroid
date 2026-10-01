#!/usr/bin/env python3
"""驗證鎖定的發行檔，僅擷取 Android 所需資產。"""
import hashlib
import pathlib
import sys
import zipfile


def main() -> None:
    archive, digest, expected_archive, expected_digest, output = sys.argv[1:]
    for filename, expected in [(archive, expected_archive), (digest, expected_digest)]:
        with open(filename, "rb") as source:
            actual = hashlib.file_digest(source, "sha256").hexdigest()
        if actual != expected:
            raise ValueError(f"SHA256 mismatch for {pathlib.Path(filename).name}")
    if expected_archive not in pathlib.Path(digest).read_text().lower():
        raise ValueError("Official digest does not contain the pinned archive SHA256")
    target = pathlib.Path(output)
    with zipfile.ZipFile(archive) as release:
        for name, destination in [
            ("xray", target / "jniLibs/arm64-v8a/libxray.so"),
            ("geoip.dat", target / "assets/core/geoip.dat"),
            ("geosite.dat", target / "assets/core/geosite.dat"),
            ("LICENSE", target / "assets/licenses/xray/LICENSE"),
            ("README.md", target / "assets/licenses/xray/README.md"),
        ]:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(release.read(name))
        (target / "jniLibs/arm64-v8a/libxray.so").chmod(0o755)


if __name__ == "__main__":
    main()
