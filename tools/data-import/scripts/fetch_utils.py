from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlparse, parse_qs


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _download_file(url: str, target: Path) -> None:
    parsed = urlparse(url)
    if parsed.scheme == "file":
        if parsed.netloc not in ("", "localhost"):
            raise ValueError("file:// URL must refer to a local path")
        shutil.copyfile(Path(unquote(parsed.path)).expanduser(), target)
    elif parsed.scheme in ("http", "https") and parsed.hostname == "drive.google.com":
        match = re.fullmatch(r"/file/d/([A-Za-z0-9_-]+)/?(?:view)?", parsed.path)
        file_id = match.group(1) if match else parse_qs(parsed.query).get("id", [""])[0]
        if not re.fullmatch(r"[A-Za-z0-9_-]+", file_id):
            raise ValueError("Google Drive release URL requires a file ID")
        remote = os.getenv("DATA_DRIVE_REMOTE", "").strip()
        if remote:
            if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_-]*", remote):
                raise ValueError("DATA_DRIVE_REMOTE must be an rclone remote name")
            subprocess.run(["rclone", "backend", "copyid", remote + ":", file_id, str(target.resolve()), "--retries", "2", "--contimeout", "20s", "--timeout", "120s"], check=True, timeout=600)
        else:
            public_url = f"https://drive.usercontent.google.com/download?id={file_id}&export=download&confirm=t"
            subprocess.run(["curl", "--fail", "--location", "--no-progress-meter", "--connect-timeout", "20", "--max-time", "600",
                            "--retry", "2", "--output", str(target), public_url], check=True, timeout=650)
    elif parsed.scheme in ("http", "https"):
        subprocess.run(
            ["curl", "--fail", "--location", "--retry", "3", "--retry-delay", "2", "--output", str(target), url],
            check=True,
        )
    elif not parsed.scheme:
        shutil.copyfile(Path(url).expanduser(), target)
    else:
        raise ValueError("Release URL must be HTTPS, a Google Drive file link, file://, or a local archive path")


def download_archive(url: str, target: Path) -> None:
    """A transport index may split a large immutable archive; package checksum stays unchanged."""
    _download_file(url, target)
    if target.stat().st_size > 65536:
        return
    with target.open("rb") as source:
        if source.read(1) != b"{":
            return
    index = json.loads(target.read_text())
    parts = index.get("parts")
    if index.get("format") != "lyreo.archive-parts.v1" or not isinstance(parts, list) or not 1 <= len(parts) <= 64:
        raise ValueError("Invalid multipart archive index")
    expected = index.get("archive_sha256", "")
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("Multipart archive requires a full checksum")
    remote = urlparse(url).scheme in ("http", "https")
    with tempfile.TemporaryDirectory(prefix="archive-parts.", dir=target.parent) as directory:
        downloads = []
        for number, part in enumerate(parts):
            child = urlparse(part.get("url", ""))
            if remote and (child.scheme != "https" or child.hostname != "drive.google.com" or child.username or child.password):
                raise ValueError("Remote multipart indices may reference only HTTPS Google Drive files")
            size = part.get("size_bytes")
            if not isinstance(size, int) or isinstance(size, bool) or not 0 < size <= 536870912 or not re.fullmatch(r"[0-9a-f]{64}", part.get("sha256", "")):
                raise ValueError("Invalid archive part size/checksum")
            downloads.append((number, part))

        def download_part(download):
            number, part = download
            path = Path(directory) / str(number)
            _download_file(part["url"], path)
            if path.stat().st_size != part["size_bytes"] or sha256_file(path) != part["sha256"]:
                raise ValueError(f"Archive part {number + 1} failed size/SHA-256 verification")
            return path

        with ThreadPoolExecutor(max_workers=4 if remote else 1) as pool:
            verified = list(pool.map(download_part, downloads))
        with target.open("wb") as result:
            for path in verified:
                with path.open("rb") as source:
                    shutil.copyfileobj(source, result)
    if sha256_file(target) != expected:
        raise ValueError("Reassembled archive checksum mismatch")


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
