import hashlib
from pathlib import Path

import pytest

from local_media import LocalMediaClient
from import_clean_grammar_toeic import verify_or_upload_media


def test_local_media_is_verified_and_reused(tmp_path):
    source = tmp_path / "source.wav"
    source.write_bytes(b"test recording")
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    client = LocalMediaClient(tmp_path / "storage")
    args = dict(sha256=digest, size_bytes=source.stat().st_size,
                mime_type="audio/wav", object_key=f"media/sha256/{digest[:2]}/{digest}")
    assert verify_or_upload_media(client, "local", source, **args)
    assert not verify_or_upload_media(client, "local", source, **args)
    stored = tmp_path / "storage" / args["object_key"]
    stored.write_bytes(b"broken content")
    with pytest.raises(ValueError):
        verify_or_upload_media(client, "local", source, **args)


def test_local_media_rejects_path_escape(tmp_path):
    client = LocalMediaClient(tmp_path)
    with pytest.raises(ValueError):
        client.head_object(Bucket="local", Key="../outside")


def test_asset_import_workers_reuse_existing_cloud_objects_without_writing_database_from_workers(tmp_path, monkeypatch):
    import json
    import threading
    import uuid
    from types import SimpleNamespace
    from unittest.mock import MagicMock
    import import_clean_grammar_toeic as importer
    import clean_import_common

    (tmp_path / 'shared').mkdir()
    source = tmp_path / 'recording.wav'
    source.write_bytes(b'cloud recording')
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    rows = [{'id': digest, 'path': 'recording.wav', 'size_bytes': source.stat().st_size,
             'mime_type': 'audio/wav', 'source_paths': []}] * 3
    (tmp_path / 'shared/assets.jsonl').write_text('\n'.join(json.dumps(row) for row in rows))
    package = SimpleNamespace(root=tmp_path, release_id=uuid.uuid4())
    main_thread = threading.get_ident()
    worker_threads = []
    client = MagicMock()
    def head(**kwargs):
        worker_threads.append(threading.get_ident())
        return {'ContentLength': source.stat().st_size, 'Metadata': {'sha256': digest}}
    client.head_object.side_effect = head
    conn = MagicMock()
    conn.execute.return_value.fetchone.return_value = ('audio/wav', source.stat().st_size, importer.media_object_key(digest))
    def insert(*args, **kwargs):
        assert threading.get_ident() == main_thread
        return 0
    monkeypatch.setattr(importer, 'insert_values', insert)
    assert importer._upload_and_insert_assets(conn, package, uuid.uuid4(), 100, client, 'cloud',
        {'active_rows_read': 0, 'records_read': 3, 'records_inserted': 0}) == (0, 0)
    assert len(worker_threads) == 3 and all(thread != main_thread for thread in worker_threads)
    client.upload_file.assert_not_called()
