#!/usr/bin/env python3
"""Install a verified Grammar/TOEIC clean release from a versioned archive."""
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
from validate_grammar_toeic_release import validate  # noqa: E402


@dataclass(frozen=True)
class Config:
    version: str
    schema_version: str
    url: str
    archive_sha256: str
    release_dir: Path
    workspace: Path


def config_from_env(env: dict[str, str]) -> Config:
    prefix = "GRAMMAR_TOEIC_RELEASE_"
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
    if release_dir.name != version or release_dir.parent.name != "grammar-toeic":
        raise ValueError(f"{prefix}DIR must end in grammar-toeic/{version}")
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
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, target: Path) -> None:
    parsed = urlparse(url)
    if parsed.scheme == "file":
        if parsed.netloc not in ("", "localhost"):
            raise ValueError("file:// URL must refer to a local path")
        shutil.copyfile(Path(unquote(parsed.path)).expanduser(), target)
    elif parsed.scheme in ("http", "https") and parsed.hostname == "drive.google.com":
        subprocess.run(["uvx", "--from", "gdown==6.2.0", "gdown", url, "-O", str(target)], check=True)
    elif parsed.scheme in ("http", "https"):
        subprocess.run(["curl", "--fail", "--location", "--retry", "3", "--retry-delay", "2", "--output", str(target), url], check=True)
    elif not parsed.scheme:
        shutil.copyfile(Path(url).expanduser(), target)
    else:
        raise ValueError("Release URL must be HTTPS, a Google Drive file link, file://, or a local archive path")


def archive_root(archive: Path, version: str) -> str:
    allowed_roots = {f"grammar-toeic-{version}", version}
    with tarfile.open(archive, "r:gz") as stream:
        members = stream.getmembers()
        roots: set[str] = set()
        paths: set[str] = set()
        if not members:
            raise ValueError("Release archive is empty")
        for member in members:
            path = PurePosixPath(member.name)
            if path.is_absolute() or len(path.parts) < 2 or ".." in path.parts or not member.isfile():
                raise ValueError(f"Unsafe release archive member: {member.name}")
            if member.name in paths:
                raise ValueError(f"Duplicate release archive member: {member.name}")
            paths.add(member.name)
            roots.add(path.parts[0])
        if len(roots) != 1 or next(iter(roots)) not in allowed_roots:
            raise ValueError(f"Expected one Grammar/TOEIC {version} release root; found {sorted(roots)}")
        return next(iter(roots))


def extract(archive: Path, destination: Path, version: str) -> Path:
    root_name = archive_root(archive, version)
    with tarfile.open(archive, "r:gz") as stream:
        for member in stream:
            parts = PurePosixPath(member.name).parts
            target = destination.joinpath(*parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            source = stream.extractfile(member)
            if source is None:
                raise ValueError(f"Unreadable release archive member: {member.name}")
            with source, target.open("xb") as output:
                shutil.copyfileobj(source, output)
    return destination / root_name


def verify_release(config: Config, release: Path, archive: Path | None = None) -> None:
    manifest_path = release / "manifest.json"
    if not manifest_path.is_file():
        raise ValueError(f"Release manifest missing: {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    package = manifest.get("package", {})
    if package.get("domain") != "grammar-toeic" or package.get("version") != config.version:
        raise ValueError(f"Expected grammar-toeic package {config.version}")
    if package.get("schema_version") != config.schema_version:
        raise ValueError(f"Expected Grammar/TOEIC schema {config.schema_version}")
    if manifest.get("validation", {}).get("status") not in ("PASS", "PASS_WITH_ISSUES"):
        raise ValueError("Release validation status is not acceptable")
    result = validate(release, None, archive)
    if result["status"] != "PASS":
        raise ValueError("Clean release validator did not pass")


def run(config: Config, check: bool = False, force: bool = False) -> None:
    target = config.release_dir
    if check:
        if not target.is_dir():
            raise ValueError(f"Clean release missing: {target}")
        verify_release(config, target)
        print(f"OK   Grammar/TOEIC release {config.version}: {target}")
        return
    if target.exists():
        try:
            verify_release(config, target)
        except (ValueError, KeyError, OSError, json.JSONDecodeError) as exc:
            if not force:
                raise ValueError(f"Existing release is invalid ({exc}); inspect it or use --force") from exc
        else:
            if not force:
                print(f"OK   Grammar/TOEIC release {config.version} already installed: {target}")
                return
    if not config.url:
        raise ValueError("GRAMMAR_TOEIC_RELEASE_URL is empty; set it to the uploaded clean archive URL")
    if not re.fullmatch(r"[0-9a-f]{64}", config.archive_sha256):
        raise ValueError("GRAMMAR_TOEIC_RELEASE_SHA256 must be the full 64-character archive checksum")
    config.workspace.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="run.", dir=config.workspace) as temp:
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
        print(f"OK   Grammar/TOEIC release {config.version} installed: {target}")


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
