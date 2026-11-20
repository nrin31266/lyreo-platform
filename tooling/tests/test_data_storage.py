from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "scripts/check-data-storage.sh"


def run_check(tmp_path, core, data):
    target = tmp_path / "scripts/check-data-storage.sh"
    target.parent.mkdir()
    shutil.copyfile(SCRIPT, target)
    for owner, content in [("apps/core-service", core), ("tools/data-import", data)]:
        directory = tmp_path / owner
        directory.mkdir(parents=True)
        if 'DATABASE_URL=' not in content:
            content += '\nDATABASE_URL=postgresql://localhost:5432/test\n'
        (directory / ".env").write_text(content)
    return subprocess.run(["bash", str(target)], capture_output=True, text=True)


class DataStorageTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name)

    def test_shared_r2_and_legacy_importer_default(self):
        tmp_path = self.path
        credentials = "R2_ENDPOINT=https://example.invalid\nR2_BUCKET=shared\nR2_ACCESS_KEY_ID=test-key\nR2_SECRET_ACCESS_KEY=test-secret\n"
        result = run_check(tmp_path, "STORAGE_MODE=r2\n" + credentials, credentials)
        assert result.returncode == 0, result.stderr
        assert "(r2)" in result.stdout
        assert "test-secret" not in result.stdout + result.stderr


    def test_storage_mismatch_fails_without_printing_credentials(self):
        tmp_path = self.path
        credentials = "R2_ENDPOINT=https://example.invalid\nR2_BUCKET=shared\nR2_ACCESS_KEY_ID=test-key\nR2_SECRET_ACCESS_KEY=test-secret\n"
        result = run_check(tmp_path, "STORAGE_MODE=local\nLOCAL_STORAGE_ROOT=../../.data/storage\n", credentials)
        assert result.returncode == 1
        assert "different storage" in result.stderr
        assert "test-secret" not in result.stdout + result.stderr


    def test_local_paths_resolve_from_each_owner(self):
        tmp_path = self.path
        env = "STORAGE_MODE=local\nLOCAL_STORAGE_ROOT=../../.data/storage\n"
        result = run_check(tmp_path, env, env)
        assert result.returncode == 0, result.stderr


    def test_database_mismatch_is_detected_without_credentials(self):
        tmp_path = self.path
        env = 'STORAGE_MODE=local\nLOCAL_STORAGE_ROOT=../../.data/storage\n'
        result = run_check(tmp_path, env + 'DATABASE_URL=jdbc:postgresql://localhost:5432/core\n',
                           env + 'DATABASE_URL=postgresql://user:secret@localhost:5432/other\n')
        assert result.returncode == 1
        assert 'different databases' in result.stderr
        assert 'secret' not in result.stdout + result.stderr


    def test_jdbc_and_psycopg_database_targets_match(self):
        tmp_path = self.path
        env = 'STORAGE_MODE=local\nLOCAL_STORAGE_ROOT=../../.data/storage\n'
        result = run_check(tmp_path, env + 'DATABASE_URL=jdbc:postgresql://localhost:5432/team\n',
                           env + 'DATABASE_URL=postgresql://user:secret@127.0.0.1/team\n')
        assert result.returncode == 0, result.stderr
