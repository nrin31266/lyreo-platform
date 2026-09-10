# Docker topology

Lyreo cố ý tách **development infrastructure** khỏi **production/self-host topology**.

## `compose.dev.yml`

Chỉ chạy dependency cần thiết:

- PostgreSQL cho business DB;
- Keycloak dùng database riêng trên cùng PostgreSQL server.

Core/FastAPI/Admin/Mobile chạy trên host để debugger/hot reload nhanh.

Start:

```bash
./scripts/init-dev-env.sh
docker compose --env-file infra/docker/.env -f compose.dev.yml up -d
```

Stop giữ volume:

```bash
docker compose --env-file infra/docker/.env -f compose.dev.yml down
```

Reset **toàn bộ local DB** (destructive):

```bash
docker compose --env-file infra/docker/.env -f compose.dev.yml down -v
```

Sau reset phải chạy lại `make keycloak-seed`.

## `compose.prod.yml`

Build/run:

- PostgreSQL;
- Keycloak;
- FastAPI AI Service;
- Spring Core Service;
- Admin Web static Nginx;
- edge Nginx.

Dùng để test full deployment topology hoặc self-host nhỏ. Không mặc định là HA production.

Trước chạy:

```bash
./scripts/init-dev-env.sh
# Sau đó thay toàn bộ placeholder bằng secret/URL production thật.
```

Starter command:

```bash
docker compose \
  --env-file infra/docker/.env \
  -f compose.prod.yml \
  up -d --build
```

Runtime env của Core/AI vẫn đọc từ file riêng của từng service qua `env_file` trong compose.

## `compose.gpu.yml`

Override AI service bằng GPU image:

```bash
docker compose \
  --env-file infra/docker/.env \
  -f compose.prod.yml \
  -f compose.gpu.yml \
  up -d --build
```

Yêu cầu NVIDIA Container Toolkit/GPU compatible. Không dùng override này cho CI/dev bình thường.

## Environment ownership

- `infra/docker/.env`: Compose/Postgres/Keycloak topology interpolation.
- `apps/core-service/.env`: Spring runtime secrets/config.
- `apps/ai-service/.env`: FastAPI/Qwen runtime.
- frontend `.env`: public metadata only.

Không gom tất cả vào root `.env`.

## Why no Redis/Kafka containers?

MVP đã chia trách nhiệm:

- PostgreSQL: durable jobs/cancel/state;
- Caffeine: read cache;
- Bucket4j: inbound rate limit;
- Spring Modulith: internal domain events;
- HTTP: Core↔AI;
- R2: artifacts.

Thêm broker/cache distributed cần architecture decision dựa trên tải thật.
