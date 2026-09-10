# CPU/default image. AI_RUNTIME_MODE=mock is suitable for staging smoke tests without GPU.
FROM python:3.12-slim
WORKDIR /app

RUN pip install --no-cache-dir uv
COPY apps/ai-service/pyproject.toml apps/ai-service/uv.lock ./
# Install runtime dependencies only; the application is imported directly from /app/app.
RUN uv sync --locked --no-dev --no-install-project

COPY apps/ai-service/app ./app
ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8000
CMD ["uvicorn","app.main:app","--host","0.0.0.0","--port","8000"]
