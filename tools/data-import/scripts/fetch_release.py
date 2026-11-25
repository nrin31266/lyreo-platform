#!/usr/bin/env python3
"""Install a verified Grammar/TOEIC clean release from a versioned archive."""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fetch_utils import archive_root as util_archive_root, safe_extract_archive, stage_verified_archive
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


def archive_root(archive: Path, version: str) -> str:
    return util_archive_root(archive, {f"grammar-toeic-{version}", version})


def extract(archive: Path, destination: Path, version: str) -> Path:
    return safe_extract_archive(archive, destination, {f"grammar-toeic-{version}", version})


def verify_release(config: Config, release: Path, archive: Path | None = None, *, catalog_only: bool = False) -> None:
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
    result = validate(release, None, archive, catalog_only=catalog_only)
    if result["status"] != "PASS":
        raise ValueError("Clean release validator did not pass")


def run(config: Config, check: bool = False, force: bool = False, catalog_only: bool = False) -> None:
    target = config.release_dir
    if check:
        if not target.is_dir():
            raise ValueError(f"Clean release missing: {target}")
        verify_release(config, target, catalog_only=catalog_only)
        print(f"OK   Grammar/TOEIC release {config.version}: {target}")
        return
    if target.exists():
        try:
            verify_release(config, target, catalog_only=catalog_only)
        except (ValueError, KeyError, OSError, json.JSONDecodeError) as exc:
            if not force:
                raise ValueError(f"Existing release is invalid ({exc}); inspect it or use --force") from exc
        else:
            if not force:
                print(f"OK   Grammar/TOEIC release {config.version} already installed: {target}")
                return
    config.workspace.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="run.", dir=config.workspace) as temp:
        work = Path(temp)
        archive = work / "release.tar.gz"
        stage_verified_archive(target, config.url, config.archive_sha256, archive)
        extracted = extract(archive, work / "extracted", config.version)
        verify_release(config, extracted, archive, catalog_only=catalog_only)
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
    parser.add_argument("--catalog-only", action="store_true", help="Allow archives and installed releases whose media objects live in external storage")
    args = parser.parse_args()
    env = dict(os.environ)
    env_catalog_only = env.get("GRAMMAR_TOEIC_CATALOG_ONLY", "").strip().lower() in {"1", "true", "yes"}
    try:
        run(
            config_from_env(env),
            check=args.check,
            force=args.force,
            catalog_only=args.catalog_only or env_catalog_only,
        )
    except (ValueError, OSError, subprocess.CalledProcessError, tarfile.TarError) as exc:
        parser.exit(1, f"Release fetch failed: {exc}\n")


if __name__ == "__main__":
    main()
