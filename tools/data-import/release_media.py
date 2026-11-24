"""Content-addressed storage shared by clean release importers."""
import hashlib
import os
from pathlib import Path
from typing import Any

from common import s3_client


def media_object_key(sha256: str) -> str:
    return f"media/sha256/{sha256[:2]}/{sha256}"


def _hash_stream(stream) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    while chunk := stream.read(1024 * 1024):
        digest.update(chunk)
        size += len(chunk)
    return digest.hexdigest(), size


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
