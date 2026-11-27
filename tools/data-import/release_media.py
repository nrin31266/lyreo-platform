"""Content-addressed storage shared by clean release importers."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import tempfile
from typing import Any

from common import s3_client


def normalize_prefix(prefix: str = "") -> str:
    """Validate and normalize an optional storage key prefix."""
    cleaned = prefix.strip("/")
    if not cleaned:
        return ""
    if "\\" in cleaned:
        raise ValueError(f"Unsafe storage prefix: {prefix!r}")
    parts = PurePosixPath(cleaned).parts
    if not parts or any(part in ("", ".", "..") for part in parts):
        raise ValueError(f"Unsafe storage prefix: {prefix!r}")
    return "/".join(parts)


def media_object_key(sha256: str, prefix: str = "") -> str:
    base = f"media/sha256/{sha256[:2]}/{sha256}"
    cleaned = normalize_prefix(prefix)
    return f"{cleaned}/{base}" if cleaned else base


def _hash_stream(stream) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    while chunk := stream.read(1024 * 1024):
        digest.update(chunk)
        size += len(chunk)
    return digest.hexdigest(), size


def _sha256_file(path: Path) -> tuple[str, int]:
    with path.open("rb") as stream:
        return _hash_stream(stream)


def release_media_assets(release_root: Path) -> tuple[str, str, str, list[dict[str, Any]]]:
    """Read manifest-referenced media assets from a clean release directory."""
    root = release_root.resolve(strict=True)
    manifest_bytes = (root / "manifest.json").read_bytes()
    manifest = json.loads(manifest_bytes)
    manifest_sha256 = hashlib.sha256(manifest_bytes).hexdigest()
    package = manifest.get("package") or {}
    domain = str(package.get("domain") or "")
    version = str(package.get("version") or "")
    inventory = {item["path"]: item for item in manifest.get("files", []) if "path" in item}

    assets_path: Path | None = None
    for candidate in (root / "assets.jsonl", root / "shared/assets.jsonl"):
        if candidate.is_file():
            assets_path = candidate
            break
    if assets_path is None:
        return domain, version, manifest_sha256, []

    assets: list[dict[str, Any]] = []
    with assets_path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            row = json.loads(line)
            digest = row["id"]
            size_bytes = int(row["size_bytes"])
            rel_path = row["path"]
            meta = inventory.get(rel_path)
            if meta is not None:
                expected_size = meta.get("bytes", meta.get("size_bytes"))
                if meta.get("sha256") != digest or expected_size != size_bytes:
                    raise ValueError(
                        f"Asset manifest mismatch in {assets_path.name}:{line_number} for {rel_path}"
                    )
            assets.append(row)
    return domain, version, manifest_sha256, assets


def backup_media(
    release_root: Path,
    backup_dir: Path,
    client: Any = None,
    bucket: str | None = None,
    *,
    prefix: str = "",
    workers: int = 8,
) -> dict[str, Any]:
    """Export manifest-referenced media objects into backup_dir with SHA-256 verification."""
    clean_prefix = normalize_prefix(prefix)
    domain, version, manifest_sha256, assets = release_media_assets(release_root)
    target_root = backup_dir.resolve()
    target_root.mkdir(parents=True, exist_ok=True)
    resolved_release = release_root.resolve(strict=True)

    def export_one(asset: dict[str, Any]) -> int:
        sha256 = asset["id"]
        size_bytes = int(asset["size_bytes"])
        dest = (target_root / media_object_key(sha256)).resolve()
        if not dest.is_relative_to(target_root):
            raise ValueError(f"Backup path escapes backup directory: {sha256}")
        if dest.is_file():
            existing_sha, existing_size = _sha256_file(dest)
            if (existing_sha, existing_size) == (sha256, size_bytes):
                return size_bytes
            raise ValueError(f"Existing backup file failed SHA-256 verification: {dest}")

        dest.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temp_path_str = tempfile.mkstemp(dir=dest.parent, prefix=".backup-")
        temp_path = Path(temp_path_str)
        try:
            source_key = media_object_key(sha256, prefix=clean_prefix)
            digest = hashlib.sha256()
            actual_size = 0
            if client is not None and bucket:
                response = client.get_object(Bucket=bucket, Key=source_key)
                body = response["Body"]
                try:
                    with os.fdopen(descriptor, "wb") as output:
                        while chunk := body.read(1024 * 1024):
                            output.write(chunk)
                            digest.update(chunk)
                            actual_size += len(chunk)
                        output.flush()
                        os.fsync(output.fileno())
                finally:
                    body.close()
            else:
                local_src = (resolved_release / asset["path"]).resolve(strict=True)
                if not local_src.is_relative_to(resolved_release):
                    raise ValueError(f"Release media path escapes root: {asset['path']}")
                with os.fdopen(descriptor, "wb") as output, local_src.open("rb") as source:
                    while chunk := source.read(1024 * 1024):
                        output.write(chunk)
                        digest.update(chunk)
                        actual_size += len(chunk)
                    output.flush()
                    os.fsync(output.fileno())
            if digest.hexdigest() != sha256 or actual_size != size_bytes:
                raise ValueError(f"Media object failed SHA-256 verification during backup: {sha256}")
            temp_path.replace(dest)
            return actual_size
        except BaseException:
            if temp_path.exists():
                temp_path.unlink(missing_ok=True)
            raise

    total_bytes = 0
    if assets:
        with ThreadPoolExecutor(max_workers=max(1, min(workers, len(assets)))) as pool:
            total_bytes = sum(pool.map(export_one, assets))

    backup_manifest = {
        "format": "lyreo.media-backup",
        "version": 1,
        "domain": domain,
        "package_version": version,
        "manifest_sha256": manifest_sha256,
        "prefix": clean_prefix,
        "objects": [
            {
                "sha256": asset["id"],
                "size_bytes": int(asset["size_bytes"]),
                "mime_type": asset["mime_type"],
                "path": media_object_key(asset["id"]),
            }
            for asset in assets
        ],
    }
    (target_root / "backup-manifest.json").write_text(
        json.dumps(backup_manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return {
        "mode": "BACKUP_MEDIA",
        "domain": domain,
        "version": version,
        "backup_dir": str(target_root),
        "prefix": clean_prefix,
        "objects_exported": len(assets),
        "bytes_exported": total_bytes,
    }


def restore_media(
    backup_dir: Path,
    client: Any,
    bucket: str,
    *,
    release_root: Path | None = None,
    prefix: str = "",
    workers: int = 8,
) -> dict[str, Any]:
    """Verify checksums of backed-up media files and restore them to target storage/prefix."""
    clean_prefix = normalize_prefix(prefix)
    source_root = backup_dir.resolve(strict=True)
    manifest_file = source_root / "backup-manifest.json"
    backup_objects: dict[str, dict[str, Any]] = {}
    if manifest_file.is_file():
        manifest_data = json.loads(manifest_file.read_text(encoding="utf-8"))
        if manifest_data.get("format") != "lyreo.media-backup":
            raise ValueError("Unsupported media backup manifest format")
        for item in manifest_data.get("objects", []):
            backup_objects[item["sha256"]] = item

    if release_root is not None:
        _, _, _, release_assets = release_media_assets(release_root)
        assets = [
            {
                "sha256": row["id"],
                "size_bytes": int(row["size_bytes"]),
                "mime_type": row["mime_type"],
            }
            for row in release_assets
        ]
        for asset in assets:
            recorded = backup_objects.get(asset["sha256"])
            if recorded is not None and (
                int(recorded["size_bytes"]) != asset["size_bytes"]
                or recorded["mime_type"] != asset["mime_type"]
            ):
                raise ValueError(f"Backup metadata mismatch for {asset['sha256']}")
    elif backup_objects:
        assets = [
            {
                "sha256": item["sha256"],
                "size_bytes": int(item["size_bytes"]),
                "mime_type": item["mime_type"],
            }
            for item in backup_objects.values()
        ]
    else:
        raise ValueError("Media restore requires backup-manifest.json or --release")

    def restore_one(asset: dict[str, Any]) -> tuple[bool, int]:
        sha256 = asset["sha256"]
        size_bytes = int(asset["size_bytes"])
        mime_type = asset["mime_type"]
        source_file = (source_root / media_object_key(sha256)).resolve()
        if not source_file.is_relative_to(source_root) or not source_file.is_file():
            raise ValueError(f"Backed-up media file missing: {media_object_key(sha256)}")
        target_key = media_object_key(sha256, prefix=clean_prefix)
        uploaded = verify_or_upload_media(
            client,
            bucket,
            source_file,
            sha256=sha256,
            size_bytes=size_bytes,
            mime_type=mime_type,
            object_key=target_key,
        )
        return uploaded, size_bytes

    uploaded_count = 0
    total_bytes = 0
    if assets:
        with ThreadPoolExecutor(max_workers=max(1, min(workers, len(assets)))) as pool:
            for uploaded, size_bytes in pool.map(restore_one, assets):
                uploaded_count += int(uploaded)
                total_bytes += size_bytes

    return {
        "mode": "RESTORE_MEDIA",
        "backup_dir": str(source_root),
        "prefix": clean_prefix,
        "objects_verified": len(assets),
        "objects_uploaded": uploaded_count,
        "objects_reused": len(assets) - uploaded_count,
        "bytes_verified": total_bytes,
    }


def remote_object_valid(client, bucket: str, key: str, sha256: str, size: int) -> bool:
    try:
        head = client.head_object(Bucket=bucket, Key=key)
    except Exception as exc:
        response = getattr(exc, "response", {})
        code = str(response.get("Error", {}).get("Code", ""))
        status = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
        if code in {"404", "NoSuchKey", "NotFound"} or status == 404:
            return False
        raise
    if head.get("ContentLength") != size:
        raise ValueError(f"Object storage key {key} exists with wrong size")
    metadata = {str(k).lower(): v for k, v in (head.get("Metadata") or {}).items()}
    if metadata.get("sha256") == sha256:
        return True
    response = client.get_object(Bucket=bucket, Key=key)
    try:
        actual_hash, actual_size = _hash_stream(response["Body"])
    finally:
        response["Body"].close()
    if (actual_hash, actual_size) != (sha256, size):
        raise ValueError(f"Existing media object failed SHA-256 verification: {key}")
    return True


def verify_or_upload_media(client, bucket: str, path: Path, *, sha256: str,
                           size_bytes: int, mime_type: str, object_key: str) -> bool:
    """Verify immutable content-addressed storage; return True when an upload was needed."""
    actual_hash = hashlib.sha256()
    actual_size = 0
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            actual_hash.update(chunk)
            actual_size += len(chunk)
    if actual_hash.hexdigest() != sha256 or actual_size != size_bytes:
        raise ValueError(f"Package media changed after validation: {path}")
    if remote_object_valid(client, bucket, object_key, sha256, size_bytes):
        return False
    client.upload_file(
        str(path),
        bucket,
        object_key,
        ExtraArgs={"ContentType": mime_type, "Metadata": {"sha256": sha256}},
    )
    return True


def media_configuration(*, max_pool_connections: int = 10) -> tuple[Any, str]:
    mode = os.getenv("STORAGE_MODE", "r2")
    if mode == "local":
        from local_media import LocalMediaClient
        root = os.getenv("LOCAL_STORAGE_ROOT")
        if not root:
            raise ValueError("LOCAL_STORAGE_ROOT is required for local media import")
        return LocalMediaClient(Path(root)), "local"
    if mode != "r2":
        raise ValueError("STORAGE_MODE must be local or r2")
    bucket = os.getenv("R2_BUCKET")
    client = s3_client(max_pool_connections=max_pool_connections)
    if client is None or not bucket:
        raise ValueError("R2_ENDPOINT/R2_ACCESS_KEY_ID/R2_SECRET_ACCESS_KEY/R2_BUCKET are required for media import")
    return client, bucket


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path, help="Validated clean release directory")
    parser.add_argument("--backup-media", type=Path, help="Export manifest-referenced media to this directory")
    parser.add_argument("--restore-media", type=Path, help="Restore backed-up media from this directory")
    parser.add_argument("--prefix", default="", help="Optional storage key prefix for backup/restore")
    args = parser.parse_args()
    if bool(args.backup_media) == bool(args.restore_media):
        parser.error("Specify exactly one of --backup-media or --restore-media")
    client, bucket = media_configuration()
    if args.backup_media:
        if not args.release:
            parser.error("--backup-media requires --release")
        result = backup_media(args.release, args.backup_media, client, bucket, prefix=args.prefix)
    else:
        result = restore_media(args.restore_media, client, bucket, release_root=args.release, prefix=args.prefix)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
