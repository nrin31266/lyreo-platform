from functools import lru_cache
from pathlib import Path

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    ai_service_url: str = "http://127.0.0.1:8000"
    ai_service_internal_token: str = "change-me-local-internal-token"

    tool_work_dir: Path = Path(".work")
    tool_export_dir: Path = Path("exports")
    ai_timeout_seconds: float = 600.0
    youtube_audio_sample_rate: int = 16000
    max_audio_duration_seconds: int = 300  # 5 minutes, matching Qwen aligner capability

    @field_validator("tool_export_dir", "tool_work_dir", mode="after")
    @classmethod
    def _expand_path(cls, v: Path) -> Path:
        return Path(v).expanduser().resolve()


@lru_cache
def settings() -> Settings:
    return Settings()
