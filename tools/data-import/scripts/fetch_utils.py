from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlparse


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download_archive(url: str, target: Path) -> None:
    parsed = urlparse(url)
    if parsed.scheme == "file":
        if parsed.netloc not in ("", "localhost"):
            raise ValueError("file:// URL must refer to a local path")
        shutil.copyfile(Path(unquote(parsed.path)).expanduser(), target)
    elif parsed.scheme in ("http", "https") and parsed.hostname == "drive.google.com":
        subprocess.run(["uvx", "--from", "gdown==6.2.0", "gdown", url, "-O", str(target)], check=True)
    elif parsed.scheme in ("http", "https"):
        subprocess.run(
            ["curl", "--fail", "--location", "--retry", "3", "--retry-delay", "2", "--output", str(target), url],
            check=True,
        )
    elif not parsed.scheme:
        shutil.copyfile(Path(url).expanduser(), target)
    else:
        raise ValueError("Release URL must be HTTPS, a Google Drive file link, file://, or a local archive path")


def archive_root(archive: Path, expected_roots: set[str]) -> str:
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
        if len(roots) != 1 or next(iter(roots)) not in expected_roots:
            raise ValueError(f"Expected one release root in {sorted(expected_roots)}; found {sorted(roots)}")
        return next(iter(roots))


def safe_extract_archive(archive: Path, destination: Path, expected_roots: set[str]) -> Path:
    root_name = archive_root(archive, expected_roots)
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
