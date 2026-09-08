from __future__ import annotations

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

from .config import settings
from .providers.gemini import GeminiProvider
from .providers.groq import GroqProvider
from .providers.openai_compatible import OpenAICompatibleProvider
from .runtime.mock import MockRuntime
from .runtime.nlp import LightweightNlpRuntime
from .runtime.qwen import QwenRuntime
from .schemas import ExecuteRequest, ExecuteResponse, HealthResponse
from .security import verify_internal_token

cfg = settings()
mock = MockRuntime()
qwen = QwenRuntime(cfg)
nlp_runtime = LightweightNlpRuntime()
groq = GroqProvider(cfg.groq_base_url)
gemini = GeminiProvider(cfg.gemini_base_url)

app = FastAPI(
    title='Lyreo AI Service',
    version='0.1.0',
    description=(
        'Thin AI capability runtime. Business orchestration and product prompts '
        'live in Java Core Service.'
    ),
)


@app.middleware('http')
async def reject_oversized_declared_requests(request: Request, call_next):
    """Cheap safety guard before FastAPI parses a large JSON body.

    Core normally passes media by short-lived object URL rather than base64. The edge/reverse
    proxy should still enforce its own body limit; this guard catches accidental oversized
    requests when the AI service is reached directly on a private network.
    """
    raw_length = request.headers.get('content-length')
    if raw_length:
        try:
            if int(raw_length) > cfg.max_request_bytes:
                return JSONResponse(status_code=413, content={'detail': 'request body too large'})
        except ValueError:
            return JSONResponse(status_code=400, content={'detail': 'invalid content-length'})
    return await call_next(request)


@app.exception_handler(ValueError)
async def value_error_handler(_: Request, error: ValueError):
    return JSONResponse(status_code=400, content={'detail': str(error)})


@app.exception_handler(httpx.HTTPStatusError)
async def provider_http_error_handler(_: Request, error: httpx.HTTPStatusError):
    # Do not proxy full provider bodies/headers: they can contain sensitive operational detail.
    return JSONResponse(
        status_code=502,
        content={
            'detail': 'upstream AI provider request failed',
            'provider_status': error.response.status_code,
        },
    )


@app.get('/health', response_model=HealthResponse)
async def health():
    return HealthResponse(status='ok', runtime_mode=cfg.ai_runtime_mode)


def is_mock() -> bool:
    return cfg.ai_runtime_mode.lower() == 'mock'


def _require_provider_credential(value: str | None) -> str:
    # 401 is intentionally route-fallback-safe in the Java adapter. A missing provider secret is
    # deployment/runtime configuration failure, not an invalid learner lesson payload.
    if value is None or not value.strip():
        raise HTTPException(status_code=401, detail='provider credential is not configured')
    return value


@app.post('/v1/stt', response_model=ExecuteResponse, dependencies=[Depends(verify_internal_token)])
async def stt(
    req: ExecuteRequest,
    x_lyreo_provider_credential: str | None = Header(default=None),
):
    if is_mock():
        return await mock.stt(req)
    provider = req.provider.upper()
    if provider in {'QWEN', 'QWEN3', 'QWEN3_ASR', 'LOCAL_QWEN'}:
        return await qwen.stt(req)
    if provider == 'GROQ':
        return await groq.transcribe(req, _require_provider_credential(x_lyreo_provider_credential))
    raise HTTPException(501, f'STT provider {req.provider} is not implemented')


@app.post('/v1/align', response_model=ExecuteResponse, dependencies=[Depends(verify_internal_token)])
async def align(req: ExecuteRequest):
    if is_mock():
        return await mock.align(req)
    provider = req.provider.upper()
    if provider in {'QWEN', 'QWEN3', 'QWEN3_FORCED_ALIGNER', 'LOCAL_QWEN'}:
        return await qwen.align(req)
    raise HTTPException(501, f'Alignment provider {req.provider} is not implemented')


@app.post('/v1/tts', response_model=ExecuteResponse, dependencies=[Depends(verify_internal_token)])
async def tts(
    req: ExecuteRequest,
    x_lyreo_provider_credential: str | None = Header(default=None),
):
    if is_mock():
        return await mock.tts(req)
    provider = req.provider.upper()
    if provider == 'GEMINI':
        return await gemini.tts(req, _require_provider_credential(x_lyreo_provider_credential))
    if provider == 'GROQ':
        return await groq.tts(req, _require_provider_credential(x_lyreo_provider_credential))
    raise HTTPException(501, f'TTS provider {req.provider} is not implemented')


@app.post('/v1/nlp/analyze', response_model=ExecuteResponse, dependencies=[Depends(verify_internal_token)])
async def nlp(req: ExecuteRequest):
    if is_mock():
        return await mock.nlp(req)
    return await nlp_runtime.analyze(req)


@app.post(
    '/v1/multimodal/judge',
    response_model=ExecuteResponse,
    dependencies=[Depends(verify_internal_token)],
)
async def judge(
    req: ExecuteRequest,
    x_lyreo_provider_credential: str | None = Header(default=None),
):
    if is_mock():
        return await mock.judge(req)
    if req.provider.upper() == 'GEMINI':
        return await gemini.judge(req, _require_provider_credential(x_lyreo_provider_credential))
    raise HTTPException(501, f'Pronunciation judge provider {req.provider} is not implemented')


@app.post('/v1/llm/generate', response_model=ExecuteResponse, dependencies=[Depends(verify_internal_token)])
async def llm(
    req: ExecuteRequest,
    x_lyreo_provider_credential: str | None = Header(default=None),
):
    if is_mock():
        return await mock.llm(req, x_lyreo_provider_credential)

    provider = req.provider.upper()
    credential = _require_provider_credential(x_lyreo_provider_credential)
    if provider == 'GROQ':
        return await OpenAICompatibleProvider(cfg.groq_base_url).complete(req, credential)
    if provider == 'DEEPSEEK':
        return await OpenAICompatibleProvider(cfg.deepseek_base_url).complete(req, credential)
    if provider == 'GEMINI':
        return await gemini.complete(req, credential)
    raise HTTPException(
        501,
        f'LLM provider {req.provider} is not implemented. '
        'Add a capability adapter, not business orchestration.',
    )
