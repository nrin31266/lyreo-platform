from __future__ import annotations

import gzip
import hashlib
import json
import os
import tarfile
import uuid
from collections.abc import Iterable
from contextlib import contextmanager
from pathlib import Path


def checksum(paths: list[Path]) -> str:
    """Content checksum for an import manifest.

    A real content hash is slower than hashing mtime/size, but import idempotency must not
    silently accept a replaced dataset with the same file metadata. Files are streamed in
    chunks so multi-GB inputs do not enter memory.
    """
    h = hashlib.sha256()
    for path in sorted(paths, key=lambda p: str(p)):
        h.update(str(path.name).encode())
        with path.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                h.update(chunk)
    return h.hexdigest()


def package_release(release: Path, archive: Path, root_name: str, *, paths: Iterable[Path] | None = None) -> str:
    archive.parent.mkdir(parents=True, exist_ok=True)
    with archive.open("wb") as output, gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=0, compresslevel=6) as compressed, tarfile.open(fileobj=compressed, mode="w") as tar:
        for path in sorted(release.rglob("*") if paths is None else paths):
            if not path.is_file():
                continue
            info = tar.gettarinfo(str(path), arcname=f"{root_name}/{path.relative_to(release).as_posix()}")
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mtime = 0
            info.mode = 0o644
            with path.open("rb") as stream:
                tar.addfile(info, stream)
    with archive.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def load_json(path: Path):
    with path.open(encoding="utf-8") as file:
        return json.load(file)


def normalized(value: str | None) -> str:
    return " ".join((value or "").strip().lower().split())


def stable_uuid(namespace: str, value: str) -> uuid.UUID:
    return uuid.uuid5(uuid.NAMESPACE_URL, f"lyreo:{namespace}:{value}")


@contextmanager
def db(url: str | None):
    if not url:
        raise SystemExit("DATABASE_URL required for --apply")
    import psycopg

    with psycopg.connect(url) as conn:
        with conn.transaction():
            yield conn


def begin_import(conn, code: str, version: str, source: Path, sha: str):
    ident = stable_uuid("dataset-import", f"{code}:{sha}")
    conn.execute(
        """INSERT INTO dataset_import(id,dataset_code,dataset_version,source_path,checksum,status,started_at)
           VALUES(%s,%s,%s,%s,%s,'RUNNING',now())
           ON CONFLICT(dataset_code,checksum) DO UPDATE
             SET status='RUNNING',started_at=now(),completed_at=NULL,error_message=NULL""",
        (ident, code, version, str(source), sha),
    )
    return ident


def finish_import(conn, ident, records: int, media: int = 0):
    conn.execute(
        """UPDATE dataset_import
           SET status='SUCCEEDED',records_imported=%s,media_imported=%s,
               completed_at=now(),error_message=NULL
           WHERE id=%s""",
        (records, media, ident),
    )


def fail_import(conn, ident, message: str):
    conn.execute(
        """UPDATE dataset_import
           SET status='FAILED',completed_at=now(),error_message=%s
           WHERE id=%s""",
        (message[:4000], ident),
    )


def s3_client(*, max_pool_connections: int = 10):
    endpoint = os.getenv("R2_ENDPOINT")
    key = os.getenv("R2_ACCESS_KEY_ID")
    secret = os.getenv("R2_SECRET_ACCESS_KEY")
    if not endpoint or not key or not secret:
        return None
    import boto3
    from botocore.config import Config

    return boto3.client(
        "s3",
        endpoint_url=endpoint,
        aws_access_key_id=key,
        aws_secret_access_key=secret,
        region_name=os.getenv("R2_REGION", "auto"),
        config=Config(max_pool_connections=max_pool_connections),
    )
