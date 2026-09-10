from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file='.env', extra='ignore')

    ai_runtime_mode: str = 'mock'
    ai_service_internal_token: str = 'change-me-local-internal-token'

    qwen_asr_model: str = 'Qwen/Qwen3-ASR-0.6B'
    qwen_aligner_model: str = 'Qwen/Qwen3-ForcedAligner-0.6B'
    qwen_device: str = 'cuda:0'
    qwen_dtype: str = 'bfloat16'
    qwen_max_inference_batch_size: int = 8
    qwen_max_new_tokens: int = 512

    groq_base_url: str = 'https://api.groq.com/openai/v1'
    deepseek_base_url: str = 'https://api.deepseek.com'
    gemini_base_url: str = 'https://generativelanguage.googleapis.com'
    gemini_openai_base_url: str = 'https://generativelanguage.googleapis.com/v1beta/openai'

    max_request_bytes: int = 10_485_760


@lru_cache
def settings() -> Settings:
    return Settings()
