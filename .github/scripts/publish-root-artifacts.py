from __future__ import annotations

import hashlib
import re
import shutil
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ANDROID_SOURCE = ROOT / ".release" / "android" / "LocalCameraSender.apk"
WINDOWS_SOURCE = ROOT / ".release" / "windows" / "LocalCameraReceiverSetup.exe"
ANDROID_DESTINATION = ROOT / "LocalCameraSender.apk"
WINDOWS_DESTINATION = ROOT / "LocalCameraReceiverSetup.exe"
CHECKSUMS = ROOT / "SHA256SUMS.txt"
README = ROOT / "README.md"
CLI_CONFIG = ROOT / "cli" / "lib" / "config.js"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def replace_exact(text: str, pattern: str, replacement: str, description: str) -> str:
    updated, count = re.subn(pattern, replacement, text, flags=re.MULTILINE)
    if count != 1:
        raise RuntimeError(f"Expected exactly one {description} replacement, found {count}.")
    return updated


def main() -> int:
    for source in (ANDROID_SOURCE, WINDOWS_SOURCE):
        if not source.is_file():
            raise FileNotFoundError(f"Required build artifact is missing: {source}")

    shutil.copy2(ANDROID_SOURCE, ANDROID_DESTINATION)
    shutil.copy2(WINDOWS_SOURCE, WINDOWS_DESTINATION)

    android_hash = sha256(ANDROID_DESTINATION)
    windows_hash = sha256(WINDOWS_DESTINATION)

    CHECKSUMS.write_text(
        f"{android_hash.upper()}  LocalCameraSender.apk\n"
        f"{windows_hash.upper()}  LocalCameraReceiverSetup.exe\n",
        encoding="utf-8",
        newline="\n",
    )

    readme = README.read_text(encoding="utf-8")
    readme = replace_exact(
        readme,
        r"(\| Android \| \[LocalCameraSender\.apk\]\(\./LocalCameraSender\.apk\) \| `)[0-9A-Fa-f]{64}(` \|)",
        rf"\g<1>{android_hash.upper()}\g<2>",
        "Android README hash",
    )
    readme = replace_exact(
        readme,
        r"(\| Windows / OBS \| \[LocalCameraReceiverSetup\.exe\]\(\./LocalCameraReceiverSetup\.exe\) \| `)[0-9A-Fa-f]{64}(` \|)",
        rf"\g<1>{windows_hash.upper()}\g<2>",
        "Windows README hash",
    )
    README.write_text(readme, encoding="utf-8", newline="\n")

    cli_config = CLI_CONFIG.read_text(encoding="utf-8")
    cli_config = replace_exact(
        cli_config,
        r"const EXPECTED_SHA256 = '[0-9a-fA-F]{64}';",
        f"const EXPECTED_SHA256 = '{windows_hash.lower()}';",
        "CLI installer hash",
    )
    CLI_CONFIG.write_text(cli_config, encoding="utf-8", newline="\n")

    print(f"Android SHA-256: {android_hash.upper()}")
    print(f"Windows SHA-256: {windows_hash.upper()}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # CI should fail closed on any publication mismatch.
        print(f"publish-root-artifacts failed: {exc}", file=sys.stderr)
        raise
