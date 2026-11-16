import json
import re

import httpx
from fastapi import Request


def provider_error_message(error: httpx.HTTPStatusError, request: Request) -> str | None:
    """Keep a bounded standard error message, never a provider body or request dump."""
    if len(error.response.content) > 64 * 1024:
        return None
    try:
        body = error.response.json()
    except (ValueError, RecursionError):
        return None
    detail = body.get('error', body.get('message')) if isinstance(body, dict) else None
    message = detail.get('message') if isinstance(detail, dict) else detail
    if not isinstance(message, str) or not message.strip():
        return None

    # Remove actual secrets even if an upstream service echoes them without a label.
    for name in ('x-lyreo-provider-credential', 'x-lyreo-internal-token'):
        secret = request.headers.get(name)
        if secret:
            message = message.replace(secret, '[redacted]')
    for name in ('authorization', 'x-goog-api-key', 'x-api-key'):
        secret = error.request.headers.get(name)
        if secret:
            message = message.replace(secret, '[redacted]')
            if name == 'authorization':
                token = secret.strip().rsplit(' ', 1)[-1]
                if token:
                    message = message.replace(token, '[redacted]')

    # Provider validation errors sometimes quote prompt/input rather than just the error.
    try:
        sent = json.loads(error.request.content) if len(error.request.content) <= 1024 * 1024 else None
    except (ValueError, RecursionError, httpx.RequestNotRead):
        sent = None
    if isinstance(sent, dict):
        for key in ('prompt', 'input', 'messages'):
            message = _remove_input(message, sent.get(key))
    message = re.sub(r'https?://[^\s"<>]+|data:[^\s"<>]+', '[URL removed]', message)
    message = re.sub(r'(?i)\b(?:bearer|basic)\s+[^\s"\x27,;}]+', '[redacted]', message)
    message = re.sub(
        r'(?i)\b(?:api[_ -]?key|authorization|credential|password|secret|token)\b'
        r'(?:["\x27]?\s*[:=]\s*|\s+)(?:"[^"]*"|\x27[^\x27]*\x27|[^\s,;}]+)',
        '[redacted]', message,
    )
    message = re.sub(r'(?is)\b(?:prompt|input|messages|audio_base64|headers)\b["\x27\s]*[:=].*',
                     '[request detail removed]', message)
    return ' '.join(message.split())[:2048]


def _remove_input(message: str, value: object) -> str:
    if isinstance(value, str) and value:
        message = message.replace(value, '[request detail removed]')
        message = message.replace(json.dumps(value, ensure_ascii=True)[1:-1], '[request detail removed]')
    elif isinstance(value, dict):
        for item in value.values():
            message = _remove_input(message, item)
    elif isinstance(value, list):
        for item in value:
            message = _remove_input(message, item)
    return message
