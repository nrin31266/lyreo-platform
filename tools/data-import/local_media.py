"""Local content-addressed media using the same object keys as Core's local storage."""
from pathlib import Path
import hashlib
import os
import shutil
import tempfile


class MissingObject(Exception):
    response = {"Error": {"Code": "NoSuchKey"}}


class LocalMediaClient:
    def __init__(self, root: Path):
        self.root = root.resolve()

    def _path(self, key: str) -> Path:
        path = (self.root / key).resolve()
        if not path.is_relative_to(self.root) or path == self.root:
            raise ValueError("Media key escapes local storage")
        return path

    def head_object(self, *, Bucket, Key):
        path = self._path(Key)
        if not path.is_file():
            raise MissingObject()
        with path.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        return {"ContentLength": path.stat().st_size, "Metadata": {"sha256": digest}}

    def get_object(self, *, Bucket, Key):
        return {"Body": self._path(Key).open("rb")}

    def upload_file(self, filename, bucket, key, ExtraArgs):
        target = self._path(key)
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(dir=target.parent)
        try:
            with os.fdopen(descriptor, "wb") as output, open(filename, "rb") as source:
                shutil.copyfileobj(source, output)
                output.flush()
                os.fsync(output.fileno())
            # Another process may have uploaded the same content. Never overwrite an object.
            try:
                os.link(temporary, target)
            except FileExistsError:
                with target.open("rb") as existing:
                    if hashlib.file_digest(existing, "sha256").hexdigest() != ExtraArgs["Metadata"]["sha256"]:
                        raise ValueError("Conflicting local media object")
        finally:
            os.unlink(temporary)
