import json
import sys
from pathlib import Path
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
from split_archive import split
from fetch_utils import download_archive


def test_parts_roundtrip_and_corruption_is_rejected(tmp_path):
    source=tmp_path/'release.tar.gz';source.write_bytes(b'content'*23)
    index=split(source,tmp_path/'parts',16);manifest=tmp_path/'parts/release.tar.gz.parts.json'
    downloaded=tmp_path/'download.tar.gz';download_archive(manifest.as_uri(),downloaded)
    assert downloaded.read_bytes()==source.read_bytes()
    Path(tmp_path/'parts'/index['parts'][0]['file']).write_bytes(b'changed')
    with pytest.raises(ValueError,match='verification'):
        download_archive(manifest.as_uri(),downloaded)


def test_remote_index_cannot_fetch_local_files(tmp_path,monkeypatch):
    target=tmp_path/'download';target.write_text(json.dumps({'format':'lyreo.archive-parts.v1','archive_sha256':'a'*64,'parts':[{'url':'file:///etc/passwd','size_bytes':10,'sha256':'b'*64}]}))
    monkeypatch.setattr('fetch_utils._download_file',lambda url,path:None)
    with pytest.raises(ValueError,match='only HTTPS Google Drive'):
        download_archive('https://drive.google.com/file/d/index/view',target)


def test_private_drive_uses_authenticated_remote_without_putting_credentials_in_command(tmp_path,monkeypatch):
    from fetch_utils import _download_file
    calls=[]
    monkeypatch.setenv('DATA_DRIVE_REMOTE','team_drive')
    monkeypatch.setattr('fetch_utils.subprocess.run',lambda args,**kwargs:calls.append(args))
    target=tmp_path/'part'
    _download_file('https://drive.google.com/file/d/file_123/view',target)
    assert calls[0][:6]==['rclone','backend','copyid','team_drive:','file_123',str(target)]
    monkeypatch.setenv('DATA_DRIVE_REMOTE','--unsafe')
    with pytest.raises(ValueError,match='remote name'):
        _download_file('https://drive.google.com/file/d/file_123/view',target)


def test_public_drive_fetches_the_download_endpoint_without_account_cookies(tmp_path, monkeypatch):
    from fetch_utils import _download_file
    calls=[]
    monkeypatch.delenv('DATA_DRIVE_REMOTE', raising=False)
    monkeypatch.setattr('fetch_utils.subprocess.run', lambda args, **kwargs: calls.append((args, kwargs)))
    _download_file('https://drive.google.com/file/d/file_123/view', tmp_path/'part')
    args, kwargs = calls[0]
    assert args[0] == 'curl'
    assert args[-1] == 'https://drive.usercontent.google.com/download?id=file_123&export=download&confirm=t'
    assert '--cookie' not in args and kwargs['timeout'] > 0
    with pytest.raises(ValueError, match='file ID'):
        _download_file('https://drive.google.com/drive/folders/folder_123', tmp_path/'part')
