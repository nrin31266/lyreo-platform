#!/usr/bin/env python3
"""Install a verified Lexicon clean release from a versioned archive."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fetch_utils import archive_root as util_archive_root, download_archive, safe_extract_archive, sha256_file
from validate_lexicon_release import validate  # noqa: E402


@dataclass(frozen=True)
class Config:
    version: str
    schema_version: str
    url: str
    archive_sha256: str
    release_dir: Path
    workspace: Path


def config_from_env(env: dict[str, str]) -> Config:
    prefix = "LEXICON_RELEASE_"
    version = env.get(prefix + "VERSION", "").strip()
    schema_version = env.get(prefix + "SCHEMA_VERSION", "").strip()
    directory = env.get(prefix + "DIR", "").strip()
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError(f"{prefix}VERSION must be a semantic version")
    if not re.fullmatch(r"\d+\.\d+\.\d+", schema_version):
        raise ValueError(f"{prefix}SCHEMA_VERSION must be a semantic version")
    if not directory:
        raise ValueError(f"{prefix}DIR is required")
    release_dir = Path(directory).expanduser().resolve()
    if release_dir.name != version or release_dir.parent.name != "lexicon":
        raise ValueError(f"{prefix}DIR must end in lexicon/{version}")
    workspace = release_dir.parent.parent.parent / "dataset-fetch"
    return Config(
        version=version,
        schema_version=schema_version,
        url=env.get(prefix + "URL", "").strip(),
        archive_sha256=env.get(prefix + "SHA256", "").strip().lower(),
        release_dir=release_dir,
        workspace=workspace,
    )


def sha256(path: Path) -> str:
    return sha256_file(path)


def download(url: str, target: Path) -> None:
    download_archive(url, target)


def archive_root(archive: Path, version: str) -> str:
    return util_archive_root(archive, {f"lexicon-{version}", version})


def extract(archive: Path, destination: Path, version: str) -> Path:
    return safe_extract_archive(archive, destination, {f"lexicon-{version}", version})


def verify_release(config: Config, release: Path, archive: Path | None = None) -> None:
    manifest_path = release / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError(f"Release manifest missing: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    package = manifest.get("package", {})
    if package.get("domain") != "lexicon" or package.get("version") != config.version:
        raise ValueError(f"Expected lexicon package {config.version}")
    if package.get("schema_version") != config.schema_version:
        raise ValueError(f"Expected Lexicon schema {config.schema_version}")
    if manifest.get("validation", {}).get("status") != "PASS":
        raise ValueError("Release validation status is not acceptable")
    result = validate(release, archive=archive)
    if result["status"] != "PASS":
        raise ValueError("Clean release validator did not pass")


def run(config: Config, check: bool = False, force: bool = False) -> None:
    target = config.release_dir
    if check:
        if not target.is_dir():
            raise ValueError(f"Clean release missing: {target}")
        verify_release(config, target)
        print(f"OK   Lexicon release {config.version}: {target}")
        return
    if target.exists():
        try:
            verify_release(config, target)
        except (ValueError, KeyError, OSError, json.JSONDecodeError) as exc:
            if not force:
                raise ValueError(f"Existing release is invalid ({exc}); inspect it or use --force") from exc
        else:
            if not force:
                print(f"OK   Lexicon release {config.version} already installed: {target}")
                return
    if not config.url:
        raise ValueError("LEXICON_RELEASE_URL is empty; set it to the clean archive URL")
    if not re.fullmatch(r"[0-9a-f]{64}", config.archive_sha256):
        raise ValueError("LEXICON_RELEASE_SHA256 must be the full 64-character archive checksum")
    config.workspace.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="lexicon_run.", dir=config.workspace) as temp:
        work = Path(temp)
        archive = work / "release.tar.gz"
        download(config.url, archive)
        actual_hash = sha256(archive)
        if actual_hash != config.archive_sha256:
            raise ValueError(f"Release archive SHA-256 mismatch: expected {config.archive_sha256}, got {actual_hash}")
        extracted = extract(archive, work / "extracted", config.version)
        verify_release(config, extracted, archive)
        target.parent.mkdir(parents=True, exist_ok=True)
        backup = work / "previous-release"
        if target.exists():
            target.rename(backup)
        try:
            extracted.rename(target)
        except OSError:
            if backup.exists():
                backup.rename(target)
            raise
        print(f"OK   Lexicon release {config.version} installed: {target}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify the installed clean release without downloading")
    parser.add_argument("--force", action="store_true", help="Replace an existing release only after the new archive passes verification")
    args = parser.parse_args()
    try:
        run(config_from_env(dict(os.environ)), check=args.check, force=args.force)
    except (ValueError, OSError, subprocess.CalledProcessError, tarfile.TarError) as exc:
        parser.exit(1, f"Release fetch failed: {exc}\n")


if __name__ == "__main__":
    main()
