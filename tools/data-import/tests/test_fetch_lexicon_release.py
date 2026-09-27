from __future__ import annotations

import io
import sys
import tarfile
from pathlib import Path
from unittest import mock

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from fetch_lexicon_release import (  # noqa: E402
    Config,
    archive_root,
    config_from_env,
    extract,
    run,
    sha256,
    verify_release,
)


def make_archive(path: Path, members: list[tuple[str, bytes, str]]) -> None:
    with tarfile.open(path, "w:gz") as stream:
        for name, content, kind in members:
            info = tarfile.TarInfo(name)
            info.size = len(content) if kind == "file" else 0
            if kind == "link":
                info.type = tarfile.SYMTYPE
                info.linkname = "/etc/passwd"
            stream.addfile(info, io.BytesIO(content) if kind == "file" else None)


def test_lexicon_release_config(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    env = {
        "LEXICON_RELEASE_VERSION": "1.0.0",
        "LEXICON_RELEASE_SCHEMA_VERSION": "1.0.0",
        "LEXICON_RELEASE_DIR": "releases/lexicon/1.0.0",
    }
    config = config_from_env(env)
    assert config.version == "1.0.0"
    assert config.schema_version == "1.0.0"
    assert config.release_dir == tmp_path / "releases/lexicon/1.0.0"

    with pytest.raises(ValueError, match="must end in lexicon/1.0.0"):
        config_from_env({**env, "LEXICON_RELEASE_DIR": "releases/lexicon/latest"})


@pytest.mark.parametrize(
    "member",
    [
        ("lexicon-1.0.0/../escape", b"x", "file"),
        ("lexicon-1.0.0/linked", b"", "link"),
        ("/absolute/manifest.json", b"x", "file"),
    ],
)
def test_unsafe_lexicon_archive_rejected_before_extract(tmp_path: Path, member: tuple[str, bytes, str]) -> None:
    archive = tmp_path / "unsafe.tar.gz"
    make_archive(archive, [member])
    with pytest.raises(ValueError, match="Unsafe"):
        extract(archive, tmp_path / "out", "1.0.0")
    assert not (tmp_path / "out").exists()


def test_archive_requires_one_expected_root(tmp_path: Path) -> None:
    archive = tmp_path / "wrong.tar.gz"
    make_archive(archive, [("other/manifest.json", b"{}", "file")])
    with pytest.raises(ValueError, match="Expected one"):
        archive_root(archive, "1.0.0")


def test_bad_checksum_cannot_replace_existing_release(tmp_path: Path) -> None:
    archive = tmp_path / "source.tar.gz"
    make_archive(archive, [("lexicon-1.0.0/manifest.json", b"{}", "file")])
    installed = tmp_path / "releases/lexicon/1.0.0"
    installed.mkdir(parents=True)
    marker = installed / "marker"
    marker.write_text("original")

    config = Config("1.0.0", "1.0.0", archive.as_uri(), "0" * 64, installed, tmp_path / "dataset-fetch")
    with mock.patch("fetch_lexicon_release.verify_release", side_effect=ValueError("damaged")):
        with pytest.raises(ValueError, match="SHA-256 mismatch"):
            run(config, force=True)
    assert marker.read_text() == "original"


def test_local_archive_installs_once_and_check_is_repeatable(tmp_path: Path) -> None:
    archive = tmp_path / "source.tar.gz"
    make_archive(archive, [("lexicon-1.0.0/manifest.json", b"{}", "file")])
    installed = tmp_path / "releases/lexicon/1.0.0"
    config = Config("1.0.0", "1.0.0", archive.as_uri(), sha256(archive), installed, tmp_path / "dataset-fetch")
    with mock.patch("fetch_lexicon_release.verify_release") as verify:
        run(config)
        run(config)
        run(config, check=True)
    assert (installed / "manifest.json").read_bytes() == b"{}"
    assert verify.call_count == 3
