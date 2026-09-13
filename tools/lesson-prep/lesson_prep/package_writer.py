"""Portable lesson source package writer (*.lesson-source.zip).

Packages lesson-source.json and owned media files (audio, optional thumbnail) into a single
portable archive. Verifies integrity before finalizing with an atomic rename.
"""

from __future__ import annotations

import datetime
import hashlib
import json
import random
import re
import zipfile
from pathlib import Path

from .models import PreparedSource
from .validation import validate_export


class PackageWriterError(RuntimeError):
    pass


def build_package_filename(title: str, timestamp: datetime.datetime | None = None) -> str:
    slug = re.sub(r"[^A-Za-z0-9._-]+", "-", (title or "").strip()).strip("-")
    slug = slug[:60] or "lesson"
    stamp = (timestamp or datetime.datetime.now()).strftime("%Y%m%d-%H%M%S")
    suffix = f"{random.randrange(0, 9999):04d}"
    return f"{slug}-{stamp}-{suffix}.lesson-source.zip"


def write_lesson_package(
    source: PreparedSource,
    local_audio_path: Path,
    export_dir: Path,
    local_thumbnail_path: Path | None = None,
    custom_filename: str | None = None,
) -> Path:
    """Creates a verified, portable *.lesson-source.zip archive.

    Writes to a temporary file first, performs self-verification, and then atomically
    renames the file to the target name.
    """
    if not local_audio_path.is_file():
        raise PackageWriterError(f"Audio file not found: {local_audio_path}")
    if local_thumbnail_path is not None and not local_thumbnail_path.is_file():
        raise PackageWriterError(f"Thumbnail file not found: {local_thumbnail_path}")

    # Validate prepared source schema and constraints before packaging
    problems = validate_export(source)
    if problems:
        raise PackageWriterError("PreparedSource validation failed: " + "; ".join(problems))

    export_dir.mkdir(parents=True, exist_ok=True)
    filename = custom_filename or build_package_filename(source.source.title)
    final_target = export_dir / filename
    temp_target = export_dir / f"{filename}.{random.randrange(0, 999999):06d}.tmp"

    try:
        with zipfile.ZipFile(temp_target, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            # 1. Manifest
            manifest_json = json.dumps(source.export_dict(), indent=2, ensure_ascii=False)
            zf.writestr("lesson-source.json", manifest_json.encode("utf-8"))

            # 2. Audio
            audio_arcname = source.media.audio.path
            zf.write(local_audio_path, arcname=audio_arcname)

            # 3. Optional thumbnail
            if source.media.thumbnail is not None and local_thumbnail_path is not None:
                thumb_arcname = source.media.thumbnail.path
                zf.write(local_thumbnail_path, arcname=thumb_arcname)

        # 4. Self-verification (re-open and inspect)
        verify_package(temp_target)

        # 5. Atomic rename
        temp_target.replace(final_target)
        return final_target
    except Exception as exc:
        if temp_target.exists():
            try:
                temp_target.unlink()
            except OSError:
                pass
        raise PackageWriterError(f"Package creation failed: {exc}") from exc


def verify_package(zip_path: Path) -> PreparedSource:
    """Verifies a lesson source package:
    1. Valid ZIP archive.
    2. Contains lesson-source.json.
    3. Valid schema and passes validate_export.
    4. All declared media items exist in the ZIP with matching size and SHA-256.
    5. No path traversal or forbidden paths.
    """
    if not zip_path.is_file():
        raise PackageWriterError(f"Package file not found: {zip_path}")

    try:
        with zipfile.ZipFile(zip_path, "r") as zf:
            namelist = zf.namelist()

            # Path traversal check
            for name in namelist:
                if name.startswith("/") or "\\" in name or ".." in name.split("/"):
                    raise PackageWriterError(f"ZIP contains unsafe path: {name}")

            if "lesson-source.json" not in namelist:
                raise PackageWriterError("ZIP is missing 'lesson-source.json'")

            manifest_bytes = zf.read("lesson-source.json")
            try:
                manifest_dict = json.loads(manifest_bytes.decode("utf-8"))
            except Exception as exc:
                raise PackageWriterError(f"Failed to parse 'lesson-source.json': {exc}") from exc

            problems = validate_export(manifest_dict)
            if problems:
                raise PackageWriterError(f"Manifest validation failed: {'; '.join(problems)}")

            source = PreparedSource.model_validate(manifest_dict, by_alias=True)

            # Verify audio in ZIP
            audio_path = source.media.audio.path
            if audio_path not in namelist:
                raise PackageWriterError(f"ZIP is missing declared audio member: {audio_path}")

            audio_bytes = zf.read(audio_path)
            if len(audio_bytes) != source.media.audio.size_bytes:
                raise PackageWriterError(
                    f"Audio size mismatch: declared {source.media.audio.size_bytes} bytes, "
                    f"found {len(audio_bytes)} bytes in ZIP"
                )
            audio_sha256 = hashlib.sha256(audio_bytes).hexdigest()
            if audio_sha256 != source.media.audio.sha256:
                raise PackageWriterError(
                    f"Audio SHA-256 mismatch: declared {source.media.audio.sha256}, computed {audio_sha256}"
                )

            # Verify thumbnail if declared
            if source.media.thumbnail is not None:
                thumb_path = source.media.thumbnail.path
                if thumb_path not in namelist:
                    raise PackageWriterError(f"ZIP is missing declared thumbnail member: {thumb_path}")
                thumb_bytes = zf.read(thumb_path)
                if len(thumb_bytes) != source.media.thumbnail.size_bytes:
                    raise PackageWriterError(
                        f"Thumbnail size mismatch: declared {source.media.thumbnail.size_bytes} bytes, "
                        f"found {len(thumb_bytes)} bytes in ZIP"
                    )
                thumb_sha256 = hashlib.sha256(thumb_bytes).hexdigest()
                if thumb_sha256 != source.media.thumbnail.sha256:
                    raise PackageWriterError(
                        f"Thumbnail SHA-256 mismatch: declared {source.media.thumbnail.sha256}, computed {thumb_sha256}"
                    )

            return source
    except zipfile.BadZipFile as exc:
        raise PackageWriterError(f"Corrupt or invalid ZIP archive: {exc}") from exc
