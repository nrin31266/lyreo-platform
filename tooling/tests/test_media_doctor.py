from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class MediaDoctorTest(unittest.TestCase):
    def run_doctor(self, enabled: bool, quote: str = "", installed: bool = False) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            shutil.copy(Path(__file__).resolve().parents[2] / "scripts/doctor.sh", root / "scripts")
            (root / "apps/core-service").mkdir(parents=True)
            command = "__lyreo_missing_downloader__"
            if installed:
                executable = root / "media tools/yt-dlp"
                executable.parent.mkdir()
                executable.write_text("#!/bin/sh\nexit 0\n")
                executable.chmod(0o755)
                command = str(executable)
            (root / "apps/core-service/.env").write_text(
                f"YOUTUBE_MEDIA_TOOL_ENABLED={quote}{str(enabled).lower()}{quote}\n"
                f"YOUTUBE_MEDIA_TOOL_COMMAND={quote}{command}{quote}\n"
            )
            (root / ".java-version").write_text("25\n")
            (root / ".nvmrc").write_text("24\n")
            (root / "package.json").write_text('{"packageManager":"pnpm@12.3.1"}\n')
            return subprocess.run(["bash", str(root / "scripts/doctor.sh"), "--strict"],
                                  capture_output=True, text=True, check=False)

    def test_enabled_acquisition_requires_its_configured_executable(self):
        result = self.run_doctor(True)
        self.assertIn("WARN yt-dlp is missing", result.stdout)
        self.assertEqual(1, result.returncode)

    def test_disabled_acquisition_reports_downloader_as_optional(self):
        result = self.run_doctor(False)
        self.assertIn("INFO yt-dlp is not installed", result.stdout)
        self.assertNotIn("WARN yt-dlp is missing", result.stdout)

    def test_matching_quotes_keep_enabled_media_tools_required(self):
        for quote in ("'", '\"'):
            with self.subTest(quote=quote):
                result = self.run_doctor(True, quote=quote)
                self.assertIn("WARN yt-dlp is missing", result.stdout)
                self.assertEqual(1, result.returncode)

    def test_matching_quotes_allow_configured_executable_paths_with_spaces(self):
        for quote in ("'", '\"'):
            with self.subTest(quote=quote):
                result = self.run_doctor(True, quote=quote, installed=True)
                self.assertIn("OK   yt-dlp:", result.stdout)
                self.assertIn("media tools/yt-dlp", result.stdout)
                self.assertNotIn("WARN yt-dlp is missing", result.stdout)
