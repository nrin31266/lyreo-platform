import os
import subprocess
from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


def detect_downloads_dir() -> Path:
    """Dynamically detects the user's Downloads directory (XDG, localized, or fallback)."""
    # 1. Check XDG user directory on Linux
    try:
        res = subprocess.check_output(
            ["xdg-user-dir", "DOWNLOAD"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
        if res and Path(res).is_dir():
            return Path(res)
    except Exception:
        pass

    # 2. Check XDG_DOWNLOAD_DIR env var
    xdg_env = os.environ.get("XDG_DOWNLOAD_DIR")
    if xdg_env and Path(xdg_env).is_dir():
        return Path(xdg_env)

    # 3. Check ~/Downloads
    home = Path.home()
    candidate = home / "Downloads"
    if candidate.is_dir():
        return candidate

    # 4. Check localized Vietnamese ~/Tải về
    candidate_vi = home / "Tải về"
    if candidate_vi.is_dir():
        return candidate_vi

    # 5. Default fallback
    candidate.mkdir(parents=True, exist_ok=True)
    return candidate


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    ai_service_url: str = "http://127.0.0.1:8000"
    ai_service_internal_token: str = "change-me-local-internal-token"

    core_api_url: str = "http://localhost:8080"
    keycloak_issuer_uri: str = "http://localhost:8081/realms/lyreo"
    lesson_prep_client_id: str = "lyreo-lesson-prep"
    lesson_prep_redirect_uri: str = "http://127.0.0.1:7860/oidc/callback"

    tool_work_dir: Path = Path(".work")
    tool_export_dir: Path = Field(
        default_factory=lambda: detect_downloads_dir() / "lyreo-lessons"
    )
    ai_timeout_seconds: float = 600.0
    youtube_audio_sample_rate: int = 16000

    @field_validator("tool_export_dir", "tool_work_dir", mode="after")
    @classmethod
    def _expand_path(cls, v: Path) -> Path:
        return Path(v).expanduser().resolve()


@lru_cache
def settings() -> Settings:
    return Settings()

