#!/usr/bin/env python3
"""Read-only lock validation for frozen Google Drive release imports."""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

LOCK_PATH = Path(__file__).resolve().parents[1] / "release-lock.json"
_DRIVE_HOSTS = {"drive.google.com", "drive.usercontent.google.com"}


def load_release_lock(lock_path: Path = LOCK_PATH) -> dict[str, Any]:
    """Load the pinned read-only release lock manifest."""
    if not lock_path.is_file():
        raise ValueError(f"Release lockfile is missing: {lock_path}")
    data = json.loads(lock_path.read_text(encoding="utf-8"))
    if data.get("format") != "lyreo.release-lock.v1":
        raise ValueError(f"Unsupported release lock format in {lock_path}")
    return data


def extract_drive_file_id(url: str) -> str | None:
    """Return the Google Drive file ID for Drive URLs, or None for non-Drive sources."""
    parsed = urlparse((url or "").strip())
    if parsed.hostname not in _DRIVE_HOSTS:
        return None
    match = re.fullmatch(r"/file/d/([A-Za-z0-9_-]+)/?(?:view)?", parsed.path)
    if match:
        return match.group(1)
    file_id = parse_qs(parsed.query).get("id", [""])[0]
    if re.fullmatch(r"[A-Za-z0-9_-]+", file_id):
        return file_id
    return ""


def assert_drive_url_locked(
    domain: str,
    url: str,
    expected_sha256: str,
    *,
    lock_path: Path = LOCK_PATH,
) -> None:
    """Reject unpinned or modified Google Drive imports against tools/data-import/release-lock.json."""
    parsed = urlparse((url or "").strip())
    file_id = extract_drive_file_id(url)
    if file_id is None and parsed.hostname not in _DRIVE_HOSTS:
        return

    lock = load_release_lock(lock_path)
    if lock.get("frozen") is not True or lock.get("policy") != "read-only":
        raise ValueError(f"Drive release lockfile {lock_path} is not frozen read-only.")
    if not file_id:
        raise ValueError(
            f"Drive imports for {domain} are frozen to {lock_path.name}; "
            "Google Drive URL must reference an approved file ID."
        )

    release_entry = (lock.get("releases") or {}).get(domain)
    if not isinstance(release_entry, dict):
        raise ValueError(f"Unknown release domain '{domain}' in {lock_path.name}")

    allowed = (release_entry.get("allowed_drive_files") or {}).get(file_id)
    if not isinstance(allowed, dict):
        raise ValueError(
            f"Drive imports are frozen ({lock_path.name}); Drive file ID '{file_id}' is not pinned "
            f"for {domain}. Do not modify Drive imports directly."
        )

    normalized_sha = (expected_sha256 or "").strip().lower()
    pinned_sha = str(allowed.get("sha256", "")).strip().lower()
    if normalized_sha != pinned_sha:
        raise ValueError(
            f"Drive imports are frozen ({lock_path.name}); SHA-256 '{normalized_sha}' does not match "
            f"pinned Drive checksum '{pinned_sha}' for {domain} ({file_id}). Do not modify Drive imports directly."
        )


def default_release_pin(domain: str, *, lock_path: Path = LOCK_PATH) -> tuple[str, str]:
    """Return the pinned (default_url, default_sha256) pair for a release domain."""
    lock = load_release_lock(lock_path)
    release_entry = (lock.get("releases") or {}).get(domain)
    if not isinstance(release_entry, dict):
        raise ValueError(f"Unknown release domain '{domain}' in {lock_path.name}")
    return str(release_entry["default_url"]), str(release_entry["default_sha256"])
