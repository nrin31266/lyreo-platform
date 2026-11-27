from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import sys
from types import SimpleNamespace
import unittest
import uuid
from unittest import mock

TOOLS_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = TOOLS_DIR.parents[1]
sys.path.insert(0, str(TOOLS_DIR))

from clean_import_common import (  # noqa: E402
    ReleasePackage,
    ensure_single_install,
    finalize_install,
    scoped_id,
    validate_clean_release,
)
from common import stable_uuid  # noqa: E402
import import_clean_grammar_toeic as gt_importer  # noqa: E402
import import_clean_lexicon as lex_importer  # noqa: E402
import import_clean_vocabulary as vocab_importer  # noqa: E402
from local_media import LocalMediaClient  # noqa: E402
from release_media import backup_media, media_object_key, restore_media  # noqa: E402


def _make_fake_vocab_db():
    """In-memory DB emulator supporting the SQL statements used by import_clean_vocabulary."""

    class FakeResult:
        def __init__(self, rows=None, rowcount=0):
            self._rows = list(rows or [])
            self.rowcount = rowcount

        def fetchone(self):
            return self._rows[0] if self._rows else None

        def fetchall(self):
            return list(self._rows)

    class FakeCursor:
        def __init__(self, db):
            self.db = db
            self.rowcount = 0

        def __enter__(self):
            return self

        def __exit__(self, *args):
            pass

        def execute(self, sql, params=None):
            res = self.db.execute(sql, params)
            self.rowcount = res.rowcount
            return res

    class FakeTx:
        def __enter__(self):
            return self

        def __exit__(self, *args):
            pass

    class FakeConn:
        def __init__(self):
            self.releases = {}
            self.runs = {}
            self.active_release = {}
            self.media_blob = {}
            self.release_media_asset = {}
            self.content_asset = {}
            self.packs = {}
            self.units = {}
            self.entries = {}
            self.lineage = {}
            self.counter = 0

        def __enter__(self):
            return self

        def __exit__(self, *args):
            pass

        def transaction(self):
            return FakeTx()

        def cursor(self):
            return FakeCursor(self)

        @staticmethod
        def _unwrap(val):
            return val.obj if hasattr(val, "obj") else val

        def _assert_unique_positions(self):
            pack_pos = set()
            for p in self.packs.values():
                key = (p["release_id"], p["position"])
                if key in pack_pos:
                    raise AssertionError(f"Duplicate pack position {key}")
                pack_pos.add(key)
            unit_pos = set()
            for u in self.units.values():
                key = (u["pack_id"], u["position"])
                if key in unit_pos:
                    raise AssertionError(f"Duplicate unit position {key}")
                unit_pos.add(key)
            entry_pos = set()
            for e in self.entries.values():
                key = (e["unit_id"], e["position"])
                if key in entry_pos:
                    raise AssertionError(f"Duplicate entry position {key}")
                entry_pos.add(key)

        def execute(self, sql, params=None):
            norm = " ".join(sql.split())
            params = params or ()

            if norm.startswith("SELECT pg_advisory_lock") or norm.startswith("SELECT pg_advisory_unlock"):
                return FakeResult([])

            if norm.startswith("SELECT release_id FROM dataset_active_release WHERE domain="):
                domain = params[0] if params else "vocabulary"
                rid = self.active_release.get(domain)
                return FakeResult([(rid,)] if rid else [])

            if norm.startswith("SELECT dr.id, dr.package_version, dr.checksum_sha256 FROM dataset_release"):
                domain = params[0]
                active = self.active_release.get(domain)
                if active and active in self.releases:
                    r = self.releases[active]
                    return FakeResult([(r["id"], r["version"], r["checksum"])])
                return FakeResult([])

            if norm.startswith("INSERT INTO dataset_release("):
                rid, domain, version, schema_v, checksum, manifest, _ = params
                if (domain, version) not in {(r["domain"], r["version"]) for r in self.releases.values()}:
                    self.releases[rid] = {
                        "id": rid,
                        "domain": domain,
                        "version": version,
                        "schema": schema_v,
                        "checksum": checksum,
                        "manifest": self._unwrap(manifest),
                    }
                return FakeResult([], 1)

            if norm.startswith("SELECT id,checksum_sha256,schema_version FROM dataset_release"):
                domain, version = params
                for r in self.releases.values():
                    if r["domain"] == domain and r["version"] == version:
                        return FakeResult([(r["id"], r["checksum"], r["schema"])])
                return FakeResult([])

            if norm.startswith("UPDATE dataset_import_run SET status='FAILED'") and "WHERE release_id=" in norm:
                return FakeResult([])

            if norm.startswith("INSERT INTO dataset_import_run("):
                run_id, rid, rtype = params
                self.runs[run_id] = {"id": run_id, "release_id": rid, "run_type": rtype, "status": "RUNNING"}
                return FakeResult([], 1)

            if norm.startswith("UPDATE dataset_import_run SET status='SUCCEEDED'"):
                _, _, _, run_id = params
                self.runs[run_id]["status"] = "SUCCEEDED"
                return FakeResult([], 1)

            if norm.startswith("UPDATE dataset_import_run SET status='FAILED'"):
                _, run_id = params
                if run_id in self.runs:
                    self.runs[run_id]["status"] = "FAILED"
                return FakeResult([], 1)

            if norm.startswith("INSERT INTO dataset_active_release("):
                domain, rid, _ = params
                if "DO UPDATE" in norm or domain not in self.active_release:
                    self.active_release[domain] = rid
                return FakeResult([], 1)

            if norm.startswith("INSERT INTO media_blob "):
                rows = 0
                for idx in range(0, len(params), 4):
                    sha, mime, size, key = params[idx:idx + 4]
                    if sha not in self.media_blob:
                        self.media_blob[sha] = (sha, mime, size, key)
                        rows += 1
                return FakeResult([], rows)

            if norm.startswith("SELECT sha256,mime_type,size_bytes,storage_object_key FROM media_blob"):
                shas = set(params[0])
                return FakeResult([v for k, v in self.media_blob.items() if k in shas])

            if norm.startswith("INSERT INTO release_media_asset "):
                rows = 0
                for idx in range(0, len(params), 6):
                    aid, rid, pid, sha, path, sp = params[idx:idx + 6]
                    if aid not in self.release_media_asset:
                        self.release_media_asset[aid] = {
                            "id": aid, "release_id": rid, "package_asset_id": pid,
                            "blob_sha256": sha, "package_path": path, "source_paths": self._unwrap(sp),
                        }
                        rows += 1
                return FakeResult([], rows)

            if norm.startswith("INSERT INTO content_asset "):
                rows = 0
                for idx in range(0, len(params), 8):
                    aid = params[idx]
                    if aid not in self.content_asset:
                        self.content_asset[aid] = params[idx:idx + 8]
                        rows += 1
                return FakeResult([], rows)

            if norm.startswith("INSERT INTO vocabulary_pack "):
                rows = 0
                for idx in range(0, len(params), 6):
                    pid, rid, topic, pos, en, vi = params[idx:idx + 6]
                    pid = uuid.UUID(str(pid))
                    if pid not in self.packs:
                        self.packs[pid] = {
                            "id": pid, "release_id": rid, "source_topic": topic,
                            "position": pos, "title_en": en, "title_vi": vi, "hidden": False, "revision": 0,
                        }
                        rows += 1
                self._assert_unique_positions()
                return FakeResult([], rows)

            if norm.startswith("INSERT INTO vocabulary_unit "):
                rows = 0
                for idx in range(0, len(params), 5):
                    uid, pid, pos, level, title = params[idx:idx + 5]
                    uid = uuid.UUID(str(uid))
                    pid = uuid.UUID(str(pid))
                    if uid not in self.units:
                        self.units[uid] = {
                            "id": uid, "pack_id": pid, "position": pos,
                            "level": level, "title": title, "hidden": False, "revision": 0,
                        }
                        rows += 1
                self._assert_unique_positions()
                return FakeResult([], rows)

            if norm.startswith("INSERT INTO vocabulary_unit_entry "):
                rows = 0
                for idx in range(0, len(params), 11):
                    eid, uid, pos, sid, term, content, img, uk, us, ex1, ex2 = params[idx:idx + 11]
                    eid = uuid.UUID(str(eid))
                    uid = uuid.UUID(str(uid))
                    if eid not in self.entries:
                        self.entries[eid] = {
                            "id": eid, "unit_id": uid, "position": pos, "source_id": sid,
                            "term": term, "content": self._unwrap(content),
                            "image_id": img, "uk_audio_id": uk, "us_audio_id": us,
                            "example_audio_id": ex1, "second_example_audio_id": ex2,
                            "hidden": False, "revision": 0,
                        }
                        rows += 1
                self._assert_unique_positions()
                return FakeResult([], rows)

            if norm.startswith("INSERT INTO vocabulary_release_lineage "):
                for idx in range(0, len(params), 8):
                    lid, etype, eid, rid, skey, bsnap, admin_mod, conflict = params[idx:idx + 8]
                    self.lineage[(etype, eid)] = {
                        "id": lid, "entity_type": etype, "entity_id": eid,
                        "release_id": rid, "stable_key": skey,
                        "base_snapshot": self._unwrap(bsnap),
                        "admin_modified": bool(admin_mod),
                        "conflict_json": self._unwrap(conflict),
                    }
                return FakeResult([], len(params) // 8)

            if norm.startswith("SELECT entity_type, entity_id, stable_key, base_snapshot, admin_modified FROM vocabulary_release_lineage"):
                return FakeResult([
                    (r["entity_type"], r["entity_id"], r["stable_key"], r["base_snapshot"], r["admin_modified"])
                    for r in self.lineage.values()
                ])

            if norm.startswith("SELECT id, source_topic, position, title_en, title_vi, hidden, revision FROM vocabulary_pack"):
                rid = params[0]
                return FakeResult([
                    (p["id"], p["source_topic"], p["position"], p["title_en"], p["title_vi"], p["hidden"], p["revision"])
                    for p in self.packs.values() if p["release_id"] == rid
                ])

            if norm.startswith("SELECT u.id, u.pack_id, p.source_topic, u.position, u.level, u.title, u.hidden, u.revision FROM vocabulary_unit"):
                rid = params[0]
                return FakeResult([
                    (u["id"], u["pack_id"], self.packs[u["pack_id"]]["source_topic"],
                     u["position"], u["level"], u["title"], u["hidden"], u["revision"])
                    for u in self.units.values() if self.packs[u["pack_id"]]["release_id"] == rid
                ])

            if norm.startswith("SELECT e.id, e.unit_id, e.position, e.source_id, e.term, e.content"):
                rid = params[0]
                return FakeResult([
                    (e["id"], e["unit_id"], e["position"], e["source_id"], e["term"], e["content"],
                     e["image_id"], e["uk_audio_id"], e["us_audio_id"], e["example_audio_id"],
                     e["second_example_audio_id"], e["hidden"], e["revision"])
                    for e in self.entries.values()
                    if self.packs[self.units[e["unit_id"]]["pack_id"]]["release_id"] == rid
                ])

            if norm.startswith("UPDATE vocabulary_pack SET position = position + 1000000"):
                rid = params[0]
                for p in self.packs.values():
                    if p["release_id"] == rid:
                        p["position"] += 1000000
                return FakeResult([])

            if norm.startswith("UPDATE vocabulary_unit SET position = position + 1000000"):
                rid = params[0]
                for u in self.units.values():
                    if self.packs[u["pack_id"]]["release_id"] == rid:
                        u["position"] += 1000000
                return FakeResult([])

            if norm.startswith("UPDATE vocabulary_unit_entry SET position = position + 1000000"):
                rid = params[0]
                for e in self.entries.values():
                    if self.packs[self.units[e["unit_id"]]["pack_id"]]["release_id"] == rid:
                        e["position"] += 1000000
                return FakeResult([])

            if norm.startswith("UPDATE vocabulary_pack SET release_id = %s, position = %s, title_en = %s"):
                rel, pos, en, vi, hid, pid = params
                self.packs[pid].update(release_id=rel, position=pos, title_en=en, title_vi=vi, hidden=hid)
                self._assert_unique_positions()
                return FakeResult([], 1)

            if norm.startswith("UPDATE vocabulary_pack SET release_id = %s, position = %s, hidden = true"):
                rel, pos, pid = params
                self.packs[pid].update(release_id=rel, position=pos, hidden=True)
                self._assert_unique_positions()
                return FakeResult([], 1)

            if norm.startswith("UPDATE vocabulary_unit SET pack_id = %s, position = %s, level = %s, title = %s, hidden = %s"):
                pid, pos, level, title, hid, uid = params
                self.units[uid].update(pack_id=pid, position=pos, level=level, title=title, hidden=hid)
                self._assert_unique_positions()
                return FakeResult([], 1)

            if norm.startswith("UPDATE vocabulary_unit SET position = %s, hidden = true"):
                pos, uid = params
                self.units[uid].update(position=pos, hidden=True)
                self._assert_unique_positions()
                return FakeResult([], 1)

            if norm.startswith("UPDATE vocabulary_unit_entry SET unit_id = %s, position = %s, term = %s, content = %s"):
                uid, pos, term, content, img, uk, us, ex1, ex2, hid, eid = params
                self.entries[eid].update(
                    unit_id=uid, position=pos, term=term, content=self._unwrap(content),
                    image_id=img, uk_audio_id=uk, us_audio_id=us,
                    example_audio_id=ex1, second_example_audio_id=ex2, hidden=hid,
                )
                self._assert_unique_positions()
                return FakeResult([], 1)

            if norm.startswith("UPDATE vocabulary_unit_entry SET position = %s, hidden = true"):
                pos, eid = params
                self.entries[eid].update(position=pos, hidden=True)
                self._assert_unique_positions()
                return FakeResult([], 1)

            if norm.startswith("SELECT count(*) FROM vocabulary_pack WHERE release_id="):
                rid = params[0]
                return FakeResult([(sum(1 for p in self.packs.values() if p["release_id"] == rid),)])

            if norm.startswith("SELECT count(*) FROM release_media_asset WHERE release_id="):
                rid = params[0]
                return FakeResult([(sum(1 for a in self.release_media_asset.values() if a["release_id"] == rid),)])

            if norm.startswith("SELECT count(*) FROM vocabulary_unit u"):
                rid = params[0]
                return FakeResult([(sum(1 for u in self.units.values() if self.packs[u["pack_id"]]["release_id"] == rid),)])

            if norm.startswith("SELECT count(*) FROM vocabulary_unit_entry e"):
                rid = params[0]
                return FakeResult([(sum(
                    1 for e in self.entries.values() if self.packs[self.units[e["unit_id"]]["pack_id"]]["release_id"] == rid
                ),)])

            raise AssertionError(f"Unhandled SQL in FakeConn: {norm}")

    return FakeConn()


def _write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class DemoUpgradeAndMediaTest(unittest.TestCase):
    def test_prepare_uses_verified_local_archive_even_when_url_is_set(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / "vocabulary-catalog-1.3.0.tar.gz"
            archive.write_bytes(b"fake-archive")
            expected_sha = _sha256_bytes(b"fake-archive")
            target = root / "vocabulary" / "1.3.0"
            extracted_pkg = ReleasePackage(root / "extracted", {"package": {"domain": "vocabulary"}}, {"status": "PASS"}, "c" * 64, uuid.uuid4())

            def fake_extract(arch, dest, expected_roots):
                staged = dest / "vocabulary-1.3.0"
                staged.mkdir(parents=True)
                return staged

            with (
                mock.patch.object(vocab_importer, "LOCAL_ARCHIVE", archive),
                mock.patch.object(vocab_importer, "ARCHIVE_SHA256", expected_sha),
                mock.patch.object(vocab_importer, "download_archive") as download,
                mock.patch.object(vocab_importer, "safe_extract_archive", side_effect=fake_extract),
                mock.patch.object(vocab_importer, "validated", return_value=extracted_pkg),
                mock.patch.dict("os.environ", {"VOCABULARY_RELEASE_URL": "https://drive.google.com/file/d/abc123/view"}),
            ):
                pkg = vocab_importer.prepare(target)
                self.assertEqual(target.resolve(), pkg.root)
                download.assert_not_called()

    def test_prepare_rejects_corrupt_local_archive_before_downloading(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / "vocabulary-catalog-1.3.0.tar.gz"
            archive.write_bytes(b"corrupted")
            with (
                mock.patch.object(vocab_importer, "LOCAL_ARCHIVE", archive),
                mock.patch.object(vocab_importer, "ARCHIVE_SHA256", "0" * 64),
                mock.patch.object(vocab_importer, "download_archive") as download,
                mock.patch.dict("os.environ", {"VOCABULARY_RELEASE_URL": "https://drive.google.com/file/d/abc123/view"}),
            ):
                with self.assertRaisesRegex(ValueError, "SHA-256 verification"):
                    vocab_importer.prepare(root / "vocabulary" / "1.3.0")
                download.assert_not_called()

    def test_vocabulary_three_way_lineage_upgrade_preserves_admin_edits_records_conflicts_and_hides_removed(self):
        import tempfile

        db = _make_fake_vocab_db()
        storage_client = mock.MagicMock()
        storage_client.head_object.return_value = {"ContentLength": 5, "Metadata": {"sha256": _sha256_bytes(b"media")}}

        media_sha = _sha256_bytes(b"media")
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)

            def build_release(name: str, version: str, entries: list[dict], units: list[dict]) -> ReleasePackage:
                rdir = tmp_path / name
                packs = [{
                    "id": str(stable_uuid("vocabulary-pack", "daily")),
                    "source_topic": "daily",
                    "position": 1,
                    "title_en": "Everyday",
                    "title_vi": "Hằng ngày",
                }]
                assets = [{
                    "id": media_sha,
                    "path": f"media/{media_sha[:2]}/{media_sha}.mp3",
                    "mime_type": "audio/mpeg",
                    "size_bytes": 5,
                    "source_paths": ["public/atlas/audio/uk/a.mp3"],
                }]
                _write_jsonl(rdir / "packs.jsonl", packs)
                _write_jsonl(rdir / "units.jsonl", units)
                _write_jsonl(rdir / "entries.jsonl", entries)
                _write_jsonl(rdir / "assets.jsonl", assets)
                counts = {"packs": 1, "units": len(units), "entries": len(entries), "assets": 1}
                manifest = {"package": {"domain": "vocabulary", "version": version, "schema_version": version}, "counts": counts}
                return ReleasePackage(
                    rdir,
                    manifest,
                    {"status": "PASS"},
                    _sha256_bytes(version.encode()),
                    stable_uuid("dataset-release", f"vocabulary:{version}"),
                )

            def make_entry(eid_seed: str, unit_id: str, pos: int, source_id: str, term: str, meaning_vi: str, synonyms: list[str]):
                return {
                    "id": str(stable_uuid("vocabulary-entry", eid_seed)),
                    "unit_id": unit_id,
                    "position": pos,
                    "source_id": source_id,
                    "term": term,
                    "content": {
                        "ipaUk": "/t/",
                        "ipaUs": "/t/",
                        "senses": [{"pos": "n", "definitionEn": f"def {term}", "meaningVi": meaning_vi}],
                        "examples": [f"Ex 1 {term}", f"Ex 2 {term}"],
                        "examplesVi": ["VD 1", "VD 2"],
                        "collocations": [],
                        "collocationsVi": [],
                        "synonyms": synonyms,
                        "antonyms": [],
                        "family": [],
                        "contextNotes": [],
                        "needsReview": False,
                    },
                    "media": {"image": media_sha, "ukAudio": media_sha, "usAudio": media_sha, "exampleAudio": media_sha},
                }

            pack_id = str(stable_uuid("vocabulary-pack", "daily"))
            u1_v1_id = str(stable_uuid("vocabulary-unit", "daily:A1:1"))
            units_v1 = [{"id": u1_v1_id, "pack_id": pack_id, "position": 1, "level": "A1", "title": "A1 · 01"}]
            entries_v1 = [
                make_entry("e1", u1_v1_id, 1, "admin_only", "alpha", "bản gốc alpha", ["old_syn"]),
                make_entry("e2", u1_v1_id, 2, "source_only", "beta", "bản gốc beta", ["syn_b1"]),
                make_entry("e3", u1_v1_id, 3, "conflict_both", "gamma", "bản gốc gamma", ["syn_g1"]),
                make_entry("e4", u1_v1_id, 4, "removed_entry", "delta", "bản gốc delta", []),
            ]
            pkg_v1 = build_release("v1", "1.2.1", entries_v1, units_v1)

            with mock.patch.object(vocab_importer, "connect", return_value=db):
                vocab_importer.install(pkg_v1, "postgresql://unused", storage_client=storage_client, bucket="test")

            # Verify initial lineage rows exist
            self.assertEqual(len(db.lineage), 1 + 1 + 4)

            # Simulate Admin edits in DB:
            # e1: Admin changed senses[0].meaningVi (revision=1)
            e1_id = stable_uuid("vocabulary-entry", "e1")
            db.entries[e1_id]["revision"] = 1
            db.entries[e1_id]["content"] = {
                **db.entries[e1_id]["content"],
                "senses": [{"pos": "n", "definitionEn": "def alpha", "meaningVi": "sửa bởi Admin"}],
            }
            # e3: Admin changed senses[0].meaningVi (revision=1) AND source v2 also changes senses
            e3_id = stable_uuid("vocabulary-entry", "e3")
            db.entries[e3_id]["revision"] = 1
            db.entries[e3_id]["content"] = {
                **db.entries[e3_id]["content"],
                "senses": [{"pos": "n", "definitionEn": "def gamma", "meaningVi": "Admin giữ gamma"}],
            }
            # Simulate unit title edit by Admin
            u1_id = uuid.UUID(u1_v1_id)
            db.units[u1_id]["revision"] = 1
            db.units[u1_id]["title"] = "Unit Admin Custom"

            # Rebalanced v2: different unit UUID, entries reordered, e4 removed, e2 updated by source, e3 conflicting
            u1_v2_id = str(stable_uuid("vocabulary-unit", "daily:A1–A2:1"))
            units_v2 = [{"id": u1_v2_id, "pack_id": pack_id, "position": 1, "level": "A1", "title": "A1–A2 · 01"}]
            entries_v2 = [
                make_entry("e2", u1_v2_id, 1, "source_only", "beta", "nghĩa mới beta", ["syn_b2"]),
                make_entry("e1", u1_v2_id, 2, "admin_only", "alpha", "bản gốc alpha", ["old_syn"]),
                make_entry("e3", u1_v2_id, 3, "conflict_both", "gamma", "source mới gamma", ["syn_g1"]),
            ]
            pkg_v2 = build_release("v2", "1.3.0", entries_v2, units_v2)

            with mock.patch.object(vocab_importer, "connect", return_value=db):
                report = vocab_importer.install(
                    pkg_v2, "postgresql://unused", allow_upgrade=True, storage_client=storage_client, bucket="test"
                )

            self.assertTrue(report["upgraded"])
            self.assertEqual(pkg_v2.release_id, db.active_release["vocabulary"])
            # Unit kept existing ID (matched by stable key daily:A1:1) and Admin title
            self.assertIn(u1_id, db.units)
            self.assertEqual("Unit Admin Custom", db.units[u1_id]["title"])
            # e1 kept Admin edit
            self.assertEqual("sửa bởi Admin", db.entries[e1_id]["content"]["senses"][0]["meaningVi"])
            self.assertIsNone(db.lineage[("ENTRY", e1_id)]["conflict_json"])
            self.assertTrue(db.lineage[("ENTRY", e1_id)]["admin_modified"])
            # e2 applied source update
            e2_id = stable_uuid("vocabulary-entry", "e2")
            self.assertEqual("nghĩa mới beta", db.entries[e2_id]["content"]["senses"][0]["meaningVi"])
            self.assertEqual(["syn_b2"], db.entries[e2_id]["content"]["synonyms"])
            self.assertFalse(db.lineage[("ENTRY", e2_id)]["admin_modified"])
            # e3 kept Admin value and recorded conflict_json + admin_modified=true
            self.assertEqual("Admin giữ gamma", db.entries[e3_id]["content"]["senses"][0]["meaningVi"])
            self.assertTrue(db.lineage[("ENTRY", e3_id)]["admin_modified"])
            conflict = db.lineage[("ENTRY", e3_id)]["conflict_json"]
            self.assertIsNotNone(conflict)
            self.assertEqual("content.senses", conflict["conflicts"][0]["field"])
            # e4 was NOT hard-deleted, marked hidden=true
            e4_id = stable_uuid("vocabulary-entry", "e4")
            self.assertIn(e4_id, db.entries)
            self.assertTrue(db.entries[e4_id]["hidden"])

    def test_backup_and_restore_media_with_prefix_and_checksum_verification(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            release_root = tmp_path / "release"
            release_root.mkdir()
            payload = b"RIFF\x00\x00\x00\x00WAVEfmt "
            sha = _sha256_bytes(payload)
            media_rel = f"media/{sha[:2]}/{sha}.wav"
            src_storage = LocalMediaClient(tmp_path / "src_storage")
            src_file = tmp_path / "temp.wav"
            src_file.write_bytes(payload)
            src_storage.upload_file(str(src_file), "local", media_object_key(sha), {"Metadata": {"sha256": sha}, "ContentType": "audio/wav"})

            _write_jsonl(release_root / "assets.jsonl", [{
                "id": sha,
                "path": media_rel,
                "mime_type": "audio/wav",
                "size_bytes": len(payload),
                "source_paths": ["sample.wav"],
            }])
            manifest = {
                "format": "lyreo.dataset-release",
                "manifest_schema_version": 1,
                "package": {"domain": "vocabulary", "version": "1.3.0", "schema_version": "1.3.0"},
                "files": [{"path": media_rel, "sha256": sha, "bytes": len(payload)}],
            }
            (release_root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

            backup_dir = tmp_path / "backup"
            backup_res = backup_media(release_root, backup_dir, src_storage, "local")
            self.assertEqual(1, backup_res["objects_exported"])
            self.assertTrue((backup_dir / "backup-manifest.json").is_file())
            self.assertTrue((backup_dir / media_object_key(sha)).is_file())

            dst_storage = LocalMediaClient(tmp_path / "dst_storage")
            restore_res = restore_media(backup_dir, dst_storage, "local", release_root=release_root, prefix="restore-test")
            self.assertEqual(1, restore_res["objects_uploaded"])
            self.assertEqual(0, restore_res["objects_reused"])
            restored_file = tmp_path / "dst_storage" / "restore-test" / media_object_key(sha)
            self.assertEqual(payload, restored_file.read_bytes())

            # Rerunning restore reuses the verified object without re-uploading
            restore_again = restore_media(backup_dir, dst_storage, "local", release_root=release_root, prefix="restore-test")
            self.assertEqual(0, restore_again["objects_uploaded"])
            self.assertEqual(1, restore_again["objects_reused"])

            # Corrupting backup file fails SHA-256 verification before upload
            (backup_dir / media_object_key(sha)).write_bytes(b"corrupted")
            with self.assertRaisesRegex(ValueError, "changed after validation"):
                restore_media(backup_dir, dst_storage, "local", release_root=release_root, prefix="restore-other")

    def test_grammar_toeic_catalog_only_skips_missing_media_dir_and_verifies_storage_when_present(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "gt-release"
            root.mkdir()
            (root / "shared").mkdir()
            media_bytes = b"audio-bytes-123"
            sha = _sha256_bytes(media_bytes)
            media_rel = f"media/{sha[:2]}/{sha}.mp3"

            # Notice: media_rel file is NOT written under root/ (simulating missing 3.15 GB media folder)
            _write_jsonl(root / "shared/assets.jsonl", [{
                "id": sha,
                "path": media_rel,
                "mime_type": "audio/mpeg",
                "size_bytes": len(media_bytes),
                "source_paths": ["raw/q1.mp3"],
            }])
            manifest = {
                "format": "lyreo.dataset-release",
                "manifest_schema_version": 1,
                "package": {"domain": "grammar-toeic", "version": "2.0.12", "schema_version": "2.0.12"},
                "files": [{"path": media_rel, "sha256": sha, "size_bytes": len(media_bytes)}],
                "counts": {"shared/assets": 1},
            }
            (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            package = ReleasePackage(root, manifest, {"status": "PASS"}, "c" * 64, uuid.uuid4())

            # Validate --catalog-only path via validate_clean_release when validator passes
            with mock.patch("clean_import_common.subprocess.run") as subproc:
                subproc.return_value = SimpleNamespace(returncode=0, stdout='{"status":"PASS"}', stderr="")
                validated_pkg = validate_clean_release(
                    root, domain="grammar-toeic", validator_name="validate_grammar_toeic_release.py",
                    validator_args=("--catalog-only",),
                )
                self.assertEqual("grammar-toeic", validated_pkg.domain)
                self.assertIn("--catalog-only", subproc.call_args[0][0])

            # _upload_and_insert_assets with catalog_only=True succeeds without file on disk and reuses storage
            storage = LocalMediaClient(Path(tmp) / "storage")
            tmp_audio = Path(tmp) / "tmp.mp3"
            tmp_audio.write_bytes(media_bytes)
            storage.upload_file(str(tmp_audio), "local", media_object_key(sha), {"Metadata": {"sha256": sha}, "ContentType": "audio/mpeg"})

            conn = mock.MagicMock()
            conn.execute.return_value.fetchone.return_value = ("audio/mpeg", len(media_bytes), media_object_key(sha))
            with mock.patch.object(gt_importer, "insert_values", return_value=1):
                uploads, inserted = gt_importer._upload_and_insert_assets(
                    conn, package, uuid.uuid4(), 100, storage, "local",
                    {"active_rows_read": 0, "records_read": 1, "records_inserted": 0},
                    catalog_only=True,
                )
            self.assertEqual((0, 1), (uploads, inserted))

            # If storage has corrupted bytes, catalog_only=True still catches the checksum error!
            (Path(tmp) / "storage" / media_object_key(sha)).write_bytes(b"corrupt-bytes12")
            with self.assertRaisesRegex(ValueError, "SHA-256 verification"):
                gt_importer._upload_and_insert_assets(
                    conn, package, uuid.uuid4(), 100, storage, "local",
                    {"active_rows_read": 0, "records_read": 1, "records_inserted": 0},
                    catalog_only=True,
                )

    def test_allow_upgrade_cuts_over_active_release_while_single_install_still_blocks_without_flag(self):
        class CutoverDb:
            def __init__(self, initial_release):
                self.active = {"lexicon": initial_release}

            def transaction(self):
                class Tx:
                    def __enter__(self): return self
                    def __exit__(self, *a): pass
                return Tx()

            def execute(self, sql, params=None):
                norm = " ".join(sql.split())
                if norm.startswith("SELECT dr.id"):
                    return SimpleNamespace(fetchone=lambda: (self.active["lexicon"], "1.0.0", "a" * 64))
                if norm.startswith("INSERT INTO dataset_active_release"):
                    domain, rid, _ = params
                    if "DO UPDATE" in norm or domain not in self.active:
                        self.active[domain] = rid
                    return SimpleNamespace()
                if norm.startswith("SELECT release_id FROM dataset_active_release"):
                    return SimpleNamespace(fetchone=lambda: (self.active.get(params[0]),))
                raise AssertionError(norm)

        v1_id = uuid.UUID(int=1)
        v2_id = uuid.UUID(int=2)
        pkg_v2 = ReleasePackage(Path("."), {"package": {"domain": "lexicon", "version": "2.0.0", "schema_version": "2.0.0"}, "counts": {}}, {"status": "PASS"}, "b" * 64, v2_id)
        db = CutoverDb(v1_id)

        with self.assertRaisesRegex(ValueError, "already installed"):
            ensure_single_install(db, pkg_v2)
        with self.assertRaisesRegex(ValueError, "different release"):
            finalize_install(db, pkg_v2)

        ensure_single_install(db, pkg_v2, allow_upgrade=True)
        finalize_install(db, pkg_v2, allow_upgrade=True)
        self.assertEqual(v2_id, db.active["lexicon"])

        # Verify CLI parsers accept --allow-upgrade and --catalog-only / --backup-media / --restore-media
        with mock.patch.object(sys, "argv", ["import_clean_lexicon.py", "--allow-upgrade", "--backup-media", "/tmp/b", "--prefix", "p"]):
            lex_args = lex_importer.parse_args()
            self.assertTrue(lex_args.allow_upgrade)
            self.assertEqual("p", lex_args.prefix)
        with mock.patch.object(sys, "argv", ["import_clean_grammar_toeic.py", "--catalog-only", "--allow-upgrade"]):
            gt_args = gt_importer.parse_args()
            self.assertTrue(gt_args.catalog_only)
            self.assertTrue(gt_args.allow_upgrade)

    def test_learner_demo_sql_and_seed_script_are_idempotent_and_consistent(self):
        import hashlib

        sql = (REPO_ROOT / "infra/postgres/seed/demo.sql").read_text(encoding="utf-8")
        seed_sh = (REPO_ROOT / "scripts/seed-demo.sh").read_text(encoding="utf-8")

        self.assertIn("BEGIN;", sql)
        self.assertIn("COMMIT;", sql)
        self.assertIn("ON CONFLICT", sql)
        self.assertNotIn("TRUNCATE", sql)
        self.assertIn("rglob(\"*\")", seed_sh)

        # Deterministic WebP artwork hashes exist on disk and match demo.sql
        assets_dir = REPO_ROOT / "infra/postgres/seed/assets"
        path_webps = sorted((assets_dir / "paths").glob("*.webp"))
        badge_webps = sorted((assets_dir / "badges").glob("*.webp"))
        self.assertEqual(len(path_webps), 5)
        self.assertEqual(len(badge_webps), 14)
        for webp in (*path_webps, *badge_webps):
            digest = hashlib.sha256(webp.read_bytes()).hexdigest()
            self.assertIn(digest, sql, f"Missing SHA-256 for {webp.name} in demo.sql")

        # Polluting fake mock vocab/flashcard generators must stay removed
        self.assertNotIn("generate_series(1, 35)", sql)
        self.assertNotIn("generate_series(1, 200)", sql)

        # Key curated-slice paths, badges, Plus gates & notifications present
        for marker in (
            "TOEIC_A1_STARTER",
            "REAL_WORLD_ENGLISH_A2_B1",
            "WORKPLACE_FLUENCY_B1_B2",
            "TOEIC_500_FOUNDATION",
            "TOEIC_800_MASTERY",
            "xpLevelMultiplier",
            "milestoneInterval",
            "STREAK_365",
            "LESSONS_150",
            "VOCAB_2500",
            "TOEIC_100",
            "lesson.plus-lessons",
            "vocabulary.plus-packs",
            "learner_notification",
        ):
            self.assertIn(marker, sql)

        # Every INSERT INTO statement in demo.sql must have ON CONFLICT
        inserts = re.findall(r"INSERT INTO\s+([a-z_]+).*?;", sql, flags=re.DOTALL)
        self.assertGreaterEqual(len(inserts), 12)
        for stmt_match in re.finditer(r"INSERT INTO\s+([a-z_]+).*?;", sql, flags=re.DOTALL):
            self.assertIn("ON CONFLICT", stmt_match.group(0), f"Missing ON CONFLICT in INSERT INTO {stmt_match.group(1)}")


if __name__ == "__main__":
    unittest.main()
