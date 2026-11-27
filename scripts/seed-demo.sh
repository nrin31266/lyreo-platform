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

# Verify seeded TEXT lesson TTS audio exists in active storage (never materialize silent placeholders).
# Any seeded TEXT lesson whose real audio object is absent or silent is kept DRAFT until real audio is installed.
AUDIO_RECONCILE_SQL=$(mktemp "${TMPDIR:-/tmp}/lyreo-lesson-audio-reconcile.XXXXXX.sql")
trap 'rm -f "$AUDIO_RECONCILE_SQL"' EXIT

PYTHON_BIN="python3"
if [[ "${STORAGE_MODE:-local}" == "r2" ]] && [[ -x "$ROOT/tools/data-import/.venv/bin/python" ]]; then
  PYTHON_BIN="$ROOT/tools/data-import/.venv/bin/python"
fi

PYTHONPATH="$ROOT/tools/data-import" "$PYTHON_BIN" - "$STORAGE_ROOT" "$AUDIO_RECONCILE_SQL" <<'PY'
from pathlib import Path
import os
import re
import sys

storage_root = Path(sys.argv[1])
sql_out = Path(sys.argv[2])
lessons_sql_path = Path("infra/postgres/seed/lessons.sql")

if not lessons_sql_path.is_file():
    sys.exit(0)

tts_keys = sorted(set(re.findall(
    r"lessons/[0-9a-f-]+/tts/[0-9a-f-]+\.wav",
    lessons_sql_path.read_text(encoding="utf-8"),
)))
if not tts_keys:
    sys.exit(0)

storage_mode = os.environ.get("STORAGE_MODE", "local").strip().lower()
missing_keys: list[str] = []

def has_nonzero_wav_audio(path: Path) -> bool:
    if not path.is_file():
        return False
    data = path.read_bytes()
    if len(data) <= 44 or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        return False
    return any(b != 0 for b in data[44:])

if storage_mode == "r2":
    r2_ready = bool(os.environ.get("R2_ENDPOINT") and os.environ.get("R2_BUCKET"))
    if not r2_ready:
        missing_keys = list(tts_keys)
    else:
        import release_media
        client, bucket = release_media.media_configuration()
        for key in tts_keys:
            try:
                head = client.head_object(Bucket=bucket, Key=key)
                if int(head.get("ContentLength") or 0) <= 44:
                    missing_keys.append(key)
            except Exception as exc:
                response = getattr(exc, "response", {})
                code = str(response.get("Error", {}).get("Code", ""))
                status = response.get("ResponseMetadata", {}).get("HTTPStatusCode")
                if code in {"404", "NoSuchKey", "NotFound"} or status == 404:
                    missing_keys.append(key)
                else:
                    raise
else:
    for key in tts_keys:
        if not has_nonzero_wav_audio(storage_root / key):
            missing_keys.append(key)

if missing_keys:
    quoted = ", ".join("'" + k.replace("'", "''") + "'" for k in missing_keys)
    sql_out.write_text(
        "UPDATE lesson\n"
        "   SET status = 'DRAFT'\n"
        " WHERE source_type = 'TEXT'\n"
        f"   AND canonical_audio_object_key IN ({quoted});\n",
        encoding="utf-8",
    )
    print(
        f"Warning: {len(missing_keys)}/{len(tts_keys)} seeded TEXT lesson TTS audio files are missing or silent "
        f"in STORAGE_MODE={storage_mode}; keeping those lessons DRAFT until real audio is installed.",
        file=sys.stderr,
    )
else:
    print(f"Verified real TTS audio for all {len(tts_keys)} seeded TEXT lessons (STORAGE_MODE={storage_mode}).")
PY

if [[ -s "$AUDIO_RECONCILE_SQL" ]]; then
  ./mvnw -B -pl apps/core-service spring-boot:run \
    -Dspring-boot.run.main-class=com.lyreo.platform.bootstrap.DatabaseSetup \
    -Dspring-boot.run.arguments="--seed-dev \"$AUDIO_RECONCILE_SQL\""
fi
