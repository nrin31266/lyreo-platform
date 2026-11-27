#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

CORE_ENV="$ROOT/apps/core-service/.env"
if [[ -f "$CORE_ENV" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$CORE_ENV"
  set +a
fi

LOCAL_STORAGE_ROOT="${LOCAL_STORAGE_ROOT:-../../.data/storage}"
STORAGE_ROOT=$(
  cd "$ROOT/apps/core-service"
  python3 - "$LOCAL_STORAGE_ROOT" <<'PY'
from pathlib import Path
import sys
print(Path(sys.argv[1]).expanduser().resolve())
PY
)

# Materialize deterministic WAV (RIFF/WAVE PCM) and PNG (16x16) blobs matching demo.sql.
python3 - "$STORAGE_ROOT" <<'PY'
from pathlib import Path
import hashlib
import struct
import sys
import zlib

storage_root = Path(sys.argv[1])

samples = b"\x00\x00" * 1600
wav_bytes = (
    b"RIFF"
    + struct.pack("<I", 36 + len(samples))
    + b"WAVE"
    + b"fmt "
    + struct.pack("<IHHIIHH", 16, 1, 1, 16000, 32000, 2, 16)
    + b"data"
    + struct.pack("<I", len(samples))
    + samples
)

def png_chunk(kind: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + kind
        + data
        + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    )

raw_rows = b"".join(b"\x00" + (b"\x0f\x76\x6e" * 16) for _ in range(16))
png_bytes = (
    b"\x89PNG\r\n\x1a\n"
    + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0))
    + png_chunk(b"IDAT", zlib.compress(raw_rows, level=9))
    + png_chunk(b"IEND", b"")
)

expected = [
    ("2976da01e205a110c9fa41d47659e238a5c6d3c3f3137582f2949853faa201dd", wav_bytes),
    ("a4c92f6340eb8e16caff9b6ba2e726d1b5cf080be70639aa696e6423f309903f", png_bytes),
]

for sha, payload in expected:
    actual = hashlib.sha256(payload).hexdigest()
    if actual != sha:
        raise SystemExit(f"Deterministic media hash mismatch: {actual} != {sha}")
    target = storage_root / "media" / "sha256" / sha[:2] / sha
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest() != sha:
        target.write_bytes(payload)

assets_dir = Path("infra/postgres/seed/assets")
if assets_dir.is_dir():
    for asset_path in sorted(assets_dir.rglob("*")):
        if not asset_path.is_file() or asset_path.suffix.lower() not in {".webp", ".wav", ".png", ".jpg"}:
            continue
        payload = asset_path.read_bytes()
        sha = hashlib.sha256(payload).hexdigest()
        target = storage_root / "media" / "sha256" / sha[:2] / sha
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest() != sha:
            target.write_bytes(payload)

print(f"Demo local media ready under {storage_root}")
PY

if [[ "${STORAGE_MODE:-local}" == "r2" ]] && [[ -n "${R2_ENDPOINT:-}" ]] && [[ -n "${R2_BUCKET:-}" ]] && [[ -x "$ROOT/tools/data-import/.venv/bin/python" ]]; then
  PYTHONPATH="$ROOT/tools/data-import" "$ROOT/tools/data-import/.venv/bin/python" - "$STORAGE_ROOT" <<'PY'
from pathlib import Path
import hashlib
import mimetypes
import sys

import release_media

storage_root = Path(sys.argv[1])
client, bucket = release_media.media_configuration()

mime_overrides = {
    "2976da01e205a110c9fa41d47659e238a5c6d3c3f3137582f2949853faa201dd": "audio/wav",
    "a4c92f6340eb8e16caff9b6ba2e726d1b5cf080be70639aa696e6423f309903f": "image/png",
}

candidates: list[tuple[Path, str]] = [
    (storage_root / "media" / "sha256" / sha[:2] / sha, mime)
    for sha, mime in mime_overrides.items()
]
assets_dir = Path("infra/postgres/seed/assets")
if assets_dir.is_dir():
    for asset_path in sorted(assets_dir.rglob("*")):
        if not asset_path.is_file() or asset_path.suffix.lower() not in {".webp", ".wav", ".png", ".jpg"}:
            continue
        mime = mimetypes.guess_type(asset_path.name)[0] or "application/octet-stream"
        candidates.append((asset_path, mime))

uploaded = 0
for src_path, mime in candidates:
    if not src_path.is_file():
        continue
    data = src_path.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    key = release_media.media_object_key(sha)
    if release_media.verify_or_upload_media(
        client,
        bucket,
        src_path,
        sha256=sha,
        size_bytes=len(data),
        mime_type=mime,
        object_key=key,
    ):
        uploaded += 1
print(f"Demo R2 media synchronized ({uploaded} uploaded, {len(candidates) - uploaded} reused) in bucket {bucket}")
PY
fi

# Link Keycloak demo learners when Keycloak is reachable; otherwise demo.sql ensures fallback rows.
KC_URL="${KEYCLOAK_ISSUER_URI:-http://localhost:8081/realms/lyreo}"
if [[ -f "$ROOT/infra/keycloak/.env" ]] && command -v curl >/dev/null 2>&1 && curl -sf --max-time 2 "$KC_URL" >/dev/null 2>&1; then
  bash "$ROOT/scripts/seed-learners.sh" || echo "Warning: Keycloak learner linking failed; falling back to offline demo rows." >&2
fi

./mvnw -B -pl apps/core-service spring-boot:run \
  -Dspring-boot.run.main-class=com.lyreo.platform.bootstrap.DatabaseSetup \
  -Dspring-boot.run.arguments="--seed-dev \"$ROOT/infra/postgres/seed/demo.sql\""
