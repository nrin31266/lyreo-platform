from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file='.env', extra='ignore')

    ai_runtime_mode: str = 'mock'
    ai_service_internal_token: str = 'change-me-local-internal-token'

    whisperx_asr_model: str = 'small.en'
    whisperx_aligner_model: str = 'WAV2VEC2_ASR_BASE_960H'
    whisperx_device: str = 'cuda'

    kokoro_model: str = 'hexgrad/Kokoro-82M'
    kokoro_default_voice: str = 'af_heart'
    kokoro_device: str = 'cpu'
    kokoro_sample_rate: int = 24000

    groq_base_url: str = 'https://api.groq.com/openai/v1'
    deepseek_base_url: str = 'https://api.deepseek.com'
    gemini_base_url: str = 'https://generativelanguage.googleapis.com'

    azure_speech_region: str = ''
    azure_speech_endpoint: str = ''
    azure_speech_language: str = 'en-US'
    azure_speech_enable_prosody: bool = False

    max_request_bytes: int = 10_485_760


@lru_cache
def settings() -> Settings:
    return Settings()
