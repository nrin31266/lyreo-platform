# CPU/default image. AI_RUNTIME_MODE=mock is suitable for staging smoke tests without GPU.
FROM python:3.12-slim
WORKDIR /app

RUN pip install --no-cache-dir uv
COPY services/ai-service/pyproject.toml ./pyproject.toml
# Install runtime dependencies only; the application is imported directly from /app/app.
RUN uv sync --no-dev --no-install-project

COPY services/ai-service/app ./app
ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8000
CMD ["uvicorn","app.main:app","--host","0.0.0.0","--port","8000"]
