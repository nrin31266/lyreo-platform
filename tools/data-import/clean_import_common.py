from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable, Iterator, Sequence

from psycopg.types.json import Jsonb

from common import stable_uuid


@dataclass(frozen=True)
class ReleasePackage:
    root: Path
    manifest: dict[str, Any]
    validation_report: dict[str, Any]
    checksum: str
    release_id: uuid.UUID

    @property
    def domain(self) -> str:
        return self.manifest["package"]["domain"]

    @property
    def version(self) -> str:
        return self.manifest["package"]["version"]

    @property
    def schema_version(self) -> str:
        return self.manifest["package"]["schema_version"]

    @property
    def counts(self) -> dict[str, int]:
        return self.manifest["counts"]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_clean_release(root: Path, *, domain: str, validator_name: str) -> ReleasePackage:
    """Run the independent domain validator before opening any write transaction."""
    root = root.resolve(strict=True)
    manifest_path = root / "manifest.json"
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    if manifest.get("format") != "lyreo.dataset-release":
        raise ValueError("Unsupported clean release envelope")
    if manifest.get("manifest_schema_version") != 1:
        raise ValueError("Unsupported manifest schema version")
    package = manifest.get("package") or {}
    if package.get("domain") != domain:
        raise ValueError(f"Expected {domain} release; got {package.get('domain')!r}")
    if not package.get("version") or not package.get("schema_version"):
        raise ValueError("Release package version and schema_version are required")

    validator = Path(__file__).resolve().parent / validator_name
    completed = subprocess.run(
        [sys.executable, str(validator), "--release", str(root)],
        cwd=validator.parent,
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode:
        tail = completed.stderr.strip() or completed.stdout.strip()
        raise ValueError(f"Clean release validation failed: {tail[-4000:]}")
    try:
        report = json.loads(completed.stdout)
    except json.JSONDecodeError as exc:
        raise ValueError(f"Validator returned invalid JSON: {exc}") from exc
    if report.get("status") != "PASS":
        raise ValueError(f"Validator status is not PASS: {report.get('status')!r}")

    checksum = hashlib.sha256(manifest_bytes).hexdigest()
    release_id = stable_uuid("dataset-release", f"{domain}:{package['version']}:{checksum}")
    return ReleasePackage(root, manifest, report, checksum, release_id)


def jsonl_rows(path: Path) -> Iterator[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"Invalid JSON in {path.name}:{line_number}: {exc}") from exc
            if not isinstance(row, dict):
                raise ValueError(f"Expected JSON object in {path.name}:{line_number}")
            yield row


def chunks(rows: Iterable[Any], size: int) -> Iterator[list[Any]]:
    if size < 1:
        raise ValueError("batch size must be positive")
    batch: list[Any] = []
    for row in rows:
        batch.append(row)
        if len(batch) >= size:
            yield batch
            batch = []
    if batch:
        yield batch


def scoped_id(release_id: uuid.UUID, entity: str, package_id: Any) -> uuid.UUID:
    return stable_uuid("release-content", f"{release_id}:{entity}:{package_id}")


def package_uuid(value: Any, *, field: str) -> uuid.UUID:
    try:
        return uuid.UUID(str(value))
    except (ValueError, TypeError, AttributeError) as exc:
        raise ValueError(f"{field} must be a UUID; got {value!r}") from exc


def pg_json(value: Any) -> Jsonb:
    return Jsonb(value)


def insert_values(
    conn,
    table: str,
    columns: Sequence[str],
    rows: Sequence[Sequence[Any]],
) -> int:
    """Insert one bounded multi-value batch; conflicts are immutable/idempotent no-ops."""
    if not rows:
        return 0
    # Identifiers are fixed by importer code, never supplied by the release.
    column_sql = ",".join(columns)
    placeholder = "(" + ",".join(["%s"] * len(columns)) + ")"
    sql = (
        f"INSERT INTO {table} ({column_sql}) VALUES "
        + ",".join([placeholder] * len(rows))
        + " ON CONFLICT DO NOTHING"
    )
    params = [value for row in rows for value in row]
    with conn.cursor() as cursor:
        cursor.execute(sql, params)
        return cursor.rowcount


def register_release(conn, package: ReleasePackage) -> None:
    license_file = package.root / "LICENSE"
    license_text = license_file.read_text(encoding="utf-8") if license_file.is_file() else None
    with conn.transaction():
        conn.execute(
            """INSERT INTO dataset_release(
                   id,domain,package_version,schema_version,checksum_sha256,manifest_json,license_text
               ) VALUES(%s,%s,%s,%s,%s,%s,%s)
               ON CONFLICT(domain,package_version) DO NOTHING""",
            (
                package.release_id,
                package.domain,
                package.version,
                package.schema_version,
                package.checksum,
                pg_json(package.manifest),
                license_text,
            ),
        )
        row = conn.execute(
            """SELECT id,checksum_sha256,schema_version FROM dataset_release
               WHERE domain=%s AND package_version=%s""",
            (package.domain, package.version),
        ).fetchone()
        if row is None:
            raise RuntimeError("Release insert was not visible")
        actual_id, actual_checksum, actual_schema = row
        if actual_checksum != package.checksum or actual_schema != package.schema_version:
            raise ValueError(
                f"Immutable release conflict for {package.domain}-{package.version}: "
                "stored checksum/schema differs from this package"
            )
        if actual_id != package.release_id:
            raise ValueError("Stored dataset release ID does not match its deterministic identity")


def begin_run(conn, package: ReleasePackage, run_type: str) -> uuid.UUID:
    run_id = uuid.uuid4()
    with conn.transaction():
        # The caller holds the domain advisory lock. Any earlier RUNNING attempt
        # for this release was interrupted before it could record completion.
        conn.execute(
            """UPDATE dataset_import_run
                  SET status='FAILED', completed_at=now(),
                      error_message='Interrupted import superseded by a retry'
                WHERE release_id=%s AND run_type=%s AND status='RUNNING'""",
            (package.release_id, run_type),
        )
        conn.execute(
            """INSERT INTO dataset_import_run(id,release_id,run_type,status,details_json)
               VALUES(%s,%s,%s,'RUNNING','{}'::jsonb)""",
            (run_id, package.release_id, run_type),
        )
    return run_id


def update_run_progress(conn, run_id: uuid.UUID, records_read: int, records_inserted: int) -> None:
    with conn.transaction():
        conn.execute(
            """UPDATE dataset_import_run
               SET records_read=%s,records_inserted=%s WHERE id=%s""",
            (records_read, records_inserted, run_id),
        )


def finish_run(conn, run_id: uuid.UUID, records_read: int, records_inserted: int, details: dict) -> None:
    with conn.transaction():
        conn.execute(
            """UPDATE dataset_import_run
               SET status='SUCCEEDED',records_read=%s,records_inserted=%s,
                   details_json=%s,completed_at=now(),error_message=NULL
               WHERE id=%s""",
            (records_read, records_inserted, pg_json(details), run_id),
        )


def fail_run(conn, run_id: uuid.UUID, message: str) -> None:
    with conn.transaction():
        conn.execute(
            """UPDATE dataset_import_run SET status='FAILED',error_message=%s,
                   completed_at=now() WHERE id=%s""",
            (message[:4000], run_id),
        )


def prior_release_exists(conn, package: ReleasePackage) -> bool:
    return bool(
        conn.execute(
            """SELECT EXISTS(
                   SELECT 1
                   FROM dataset_release dr
                   JOIN dataset_import_run r ON r.release_id=dr.id
                   WHERE dr.domain=%s AND dr.id<>%s
                     AND r.run_type='APPLY' AND r.status='SUCCEEDED'
                     AND r.completed_at IS NOT NULL
               )""",
            (package.domain, package.release_id),
        ).fetchone()[0]
    )


def audit_orphans(conn, release_id: uuid.UUID, checks: dict[str, str]) -> dict[str, int]:
    """Run fixed, importer-owned relationship probes and return observed orphan counts."""
    counts: dict[str, int] = {}
    for name, query in checks.items():
        counts[name] = conn.execute(query, (release_id,)).fetchone()[0]
    return counts


def reconcile_counts(conn, release_id: uuid.UUID, expected_by_table: dict[str, int]) -> dict[str, dict[str, int]]:
    actual_by_table: dict[str, dict[str, int]] = {}
    for table, expected in expected_by_table.items():
        actual = conn.execute(f"SELECT count(*) FROM {table} WHERE release_id=%s", (release_id,)).fetchone()[0]
        actual_by_table[table] = {"expected": expected, "actual": actual}
        if actual != expected:
            raise ValueError(f"Count mismatch for {table}: expected {expected}, found {actual}")
    return actual_by_table


def skipped_collection_counts(package: ReleasePackage) -> dict[str, int]:
    return {
        name: count
        for name, count in package.counts.items()
        if name.startswith("quarantine/") or name.startswith("source_only/")
    }


def package_record_count(package: ReleasePackage) -> int:
    return sum(package.counts.values())


def activate_release(
    conn,
    package: ReleasePackage,
    *,
    activated_by: str,
    action: str,
) -> None:
    if not activated_by.strip():
        raise ValueError("activated_by must not be empty")
    with conn.transaction():
        current = conn.execute(
            "SELECT release_id FROM dataset_active_release WHERE domain=%s FOR UPDATE",
            (package.domain,),
        ).fetchone()
        previous_id = current[0] if current else None
        if previous_id == package.release_id:
            return
        conn.execute(
            """INSERT INTO dataset_activation_history(
                   id,domain,release_id,previous_release_id,action,activated_by
               ) VALUES(%s,%s,%s,%s,%s,%s)""",
            (uuid.uuid4(), package.domain, package.release_id, previous_id, action, activated_by),
        )
        conn.execute(
            """INSERT INTO dataset_active_release(domain,release_id,activated_by)
               VALUES(%s,%s,%s)
               ON CONFLICT(domain) DO UPDATE SET release_id=excluded.release_id,
                   activated_at=now(),activated_by=excluded.activated_by""",
            (package.domain, package.release_id, activated_by),
        )


def db_url_from_args(value: str | None) -> str:
    import os

    result = value or os.environ.get("DATABASE_URL")
    if not result:
        raise ValueError("DATABASE_URL is required with --apply")
    return result


def connect(url: str):
    import psycopg

    connection = psycopg.connect(url)
    connection.autocommit = True
    return connection


def lock_import_domain(conn, domain: str) -> None:
    conn.execute("SELECT pg_advisory_lock(hashtextextended(%s,0))", (f"lyreo.import.{domain}",))


def unlock_import_domain(conn, domain: str) -> None:
    conn.execute("SELECT pg_advisory_unlock(hashtextextended(%s,0))", (f"lyreo.import.{domain}",))


def activate_existing_release(conn, package: ReleasePackage, *, activated_by: str, action: str,
                              expected_by_table: dict[str, int]) -> dict[str, dict[str, int]]:
    release = conn.execute(
        """SELECT id,checksum_sha256,schema_version FROM dataset_release
           WHERE domain=%s AND package_version=%s""",
        (package.domain, package.version),
    ).fetchone()
    if release is None:
        raise ValueError(f"Release {package.domain}-{package.version} has not been imported")
    if release[0] != package.release_id or release[1] != package.checksum or release[2] != package.schema_version:
        raise ValueError("Imported release metadata differs from the validated package")
    applied = conn.execute(
        """SELECT EXISTS(SELECT 1 FROM dataset_import_run
           WHERE release_id=%s AND run_type='APPLY' AND status='SUCCEEDED' AND completed_at IS NOT NULL)""",
        (package.release_id,),
    ).fetchone()[0]
    if not applied:
        raise ValueError("Release has no successful APPLY run")
    counts = reconcile_counts(conn, package.release_id, expected_by_table)
    activate_release(conn, package, activated_by=activated_by, action=action)
    return counts


def insert_batches(conn, run_id: uuid.UUID, rows: Iterable[dict], *, batch_size: int,
                   table: str, columns: Sequence[str], map_row, records_read: int,
                   records_inserted: int) -> tuple[int, int]:
    """Helper for simple collections. Each bounded chunk is its own retryable transaction."""
    for batch in chunks(rows, batch_size):
        mapped = [map_row(row) for row in batch]
        with conn.transaction():
            inserted = insert_values(conn, table, columns, mapped)
            records_read += len(batch)
            records_inserted += inserted
            conn.execute(
                "UPDATE dataset_import_run SET records_read=%s,records_inserted=%s WHERE id=%s",
                (records_read, records_inserted, run_id),
            )
    return records_read, records_inserted
