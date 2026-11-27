"""Check lesson seed audio before installing the published snapshots."""
from contextlib import closing
from io import BytesIO
import os
from pathlib import Path
import re
import sys
import wave


def has_audible_pcm(source):
    try:
        with wave.open(source, "rb") as audio:
            silence = 128 if audio.getsampwidth() == 1 else 0
            while frames := audio.readframes(8192):
                if any(value != silence for value in frames):
                    return True
    except (EOFError, wave.Error):
        return False
    return False


def unavailable_keys(keys, mode):
    if mode != "r2":
        root = Path(__file__).resolve().parents[3] / "apps/core-service"
        storage = root / Path(os.getenv("LOCAL_STORAGE_ROOT", "../../.data/storage")).expanduser()
        for key in keys:
            path = storage / key
            if not path.is_file() or not has_audible_pcm(str(path)):
                yield key
        return

    if not (os.getenv("R2_ENDPOINT") and os.getenv("R2_BUCKET")):
        yield from keys
        return

    from release_media import media_configuration

    client, bucket = media_configuration()
    for key in keys:
        try:
            response = client.get_object(Bucket=bucket, Key=key)
        except client.exceptions.ClientError as error:
            code = str(error.response.get("Error", {}).get("Code", ""))
            status = error.response.get("ResponseMetadata", {}).get("HTTPStatusCode")
            if code not in {"404", "NoSuchKey", "NotFound"} and status != 404:
                raise
            yield key
        else:
            with closing(response["Body"]) as body:
                if not has_audible_pcm(BytesIO(body.read())):
                    yield key


def main(filename):
    keys = sorted(set(re.findall(
        r"lessons/[0-9a-f-]+/tts/[0-9a-f-]+\.wav",
        Path(filename).read_text(encoding="utf-8"),
    )))
    if not keys:
        return

    missing = list(unavailable_keys(keys, os.getenv("STORAGE_MODE", "local").strip().lower()))
    # The key pattern above contains no SQL string delimiters.
    checked_sql = ", ".join(f"'{key}'" for key in keys)
    print("BEGIN;")
    print(f"DELETE FROM seed_lesson_audio_unavailable WHERE object_key IN ({checked_sql});")
    if missing:
        values = ", ".join(f"('{key}')" for key in missing)
        print(f"INSERT INTO seed_lesson_audio_unavailable (object_key) VALUES {values} ON CONFLICT DO NOTHING;")
        print(f"Hiding {len(missing)}/{len(keys)} seeded lessons with missing or silent audio.", file=sys.stderr)
    print("COMMIT;")


if __name__ == "__main__":
    main(sys.argv[1])
