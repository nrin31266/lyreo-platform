# Lyreo

**Lyreo** là nền tảng học tiếng Anh tập trung vào Listening, Dictation, Shadowing,
Vocabulary, Grammar, TOEIC và Curriculum có cấu trúc.

- Product: **Lyreo**
- Mascot: **Lyrebird**
- Java namespace: `com.lyreo`
- Repository: `lyreo-platform`

Lyreo được thiết kế lại từ đầu để tránh lặp lại kiểu backend chắp vá nhiều service/cache/job
state. Repository này ưu tiên **boundary rõ, config rõ, durable state rõ và dễ cho developer/AI
agent tiếp tục làm mà không phải đọc lịch sử chat**.

## 1. Architecture snapshot

```text
Admin Web (React/Vite) ─┐
                         ├─ OIDC/API ─→ Core Service (Spring Boot Modular Monolith)
Mobile (Expo/RN) ────────┘                  │
                                            ├─ PostgreSQL
                                            ├─ Cloudflare R2 / local storage adapter
                                            └─ HTTP → AI Service (FastAPI/Python)
                                                           ├─ Qwen3-ASR
                                                           ├─ Qwen3-ForcedAligner
                                                           ├─ Groq
                                                           ├─ Gemini
                                                           └─ DeepSeek

Keycloak ← OIDC Authorization Code + PKCE → Admin/Mobile
```

Core architecture:

- Spring Boot 4.1.x + Java 25;
- Spring Modulith;
- Pragmatic Clean/Hexagonal Architecture inside modules;
- PostgreSQL + Flyway;
- Hibernate/JPA schema validation only (`ddl-auto=validate`);
- PostgreSQL background jobs (`FOR UPDATE SKIP LOCKED` + lease + heartbeat + retry + cancel + fencing);
- Caffeine read cache;
- Bucket4j inbound rate limiting;
- Resilience4j outbound resilience;
- Cloudflare R2 through S3-compatible abstraction;
- FastAPI AI capability runtime;
- Keycloak OIDC.

**Không Kafka. Không Redis ở MVP.** Xem `docs/DECISIONS.md` để biết rationale.

## 2. Read this first

Theo thứ tự:

1. `AGENTS.md` — engineering contract bắt buộc cho developer/coding agent.
2. `docs/LYREO_PLATFORM_SPEC.md` — master specification tiếng Việt.
3. `docs/ARCHITECTURE.md` — system/module/job/content boundaries.
4. `docs/CONFIGURATION.md` — env, secret, admin/user/session config precedence.
5. `docs/TECH_CHOICES.md` — technology + trade-off.
6. `docs/DATA_PIPELINES.md` — Lexicon/Grammar/TOEIC/Curriculum data import.
7. `docs/DEVELOPMENT.md` — setup/running/troubleshooting.
8. `docs/OPERATIONS.md` — runbook jobs/storage/backup/deployment.
9. `docs/DECISIONS.md` — compact architecture decision log.

`CLAUDE.md`, `GEMINI.md`, `AGENT.md` là symlink về `AGENTS.md`; chỉ duy trì một bộ luật.

> `TESTING_NOTES.md` chỉ là **temporary artifact handoff** đi kèm ZIP này. Nó không nằm
> trong canonical documentation order và có thể xóa sau khi team chạy full verification/CI đầu tiên.

## 3. Repository map

```text
lyreo-platform/
├── apps/
│   ├── core-service/          # Spring Boot deployable
│   ├── admin-web/             # React + Vite admin
│   └── mobile/                # Expo/React Native learner app
│
├── modules/                   # business modules
│   ├── identity/
│   ├── learner/
│   ├── ai/
│   ├── lesson/
│   ├── speech-assessment/
│   ├── lexicon/
│   ├── vocabulary/
│   ├── grammar/
│   ├── toeic/
│   ├── curriculum/
│   ├── gamification/
│   ├── analytics/
│   ├── notification/
│   └── chat/                  # low priority
│
├── platform/                  # technical cross-cutting building blocks
│   ├── cache/
│   ├── config/
│   ├── jobs/
│   ├── storage/
│   ├── security/
│   └── observability/
│
├── services/
│   └── ai-service/            # FastAPI/Python AI capability runtime
│
├── tools/
│   └── data-import/           # Lexicon/Grammar/TOEIC importers
│
├── packages/
│   └── design-system/         # shared Lyreo brand/design tokens
│
├── infra/
│   ├── docker/
│   ├── keycloak/
│   ├── nginx/
│   └── postgres/
│
├── docs/
├── tooling/
├── compose.dev.yml
├── compose.prod.yml
├── compose.gpu.yml
├── Makefile
└── AGENTS.md
```

## 4. Toolchain baseline

Khuyến nghị tại architecture lock 09/2026:

- Java 25;
- Maven Wrapper trong repo;
- Python 3.12 + `uv`;
- Node 24 LTS;
- pnpm 12;
- Docker + Docker Compose;
- Android Studio/Xcode theo platform;
- Expo Development Build/Prebuild.

Repo có `.java-version`, `.nvmrc`, `mise.toml`.

## 5. Environment model: không có root `.env`

Mỗi executable/tool có env riêng:

```text
infra/docker/.env
infra/keycloak/.env
apps/core-service/.env
services/ai-service/.env
apps/admin-web/.env
apps/mobile/.env
tools/data-import/.env
```

Khởi tạo local:

```bash
./scripts/init-dev-env.sh
```

Script:

- copy `.env.example` nếu `.env` chưa tồn tại;
- generate `MASTER_ENCRYPTION_KEY` local;
- generate/sync `AI_SERVICE_INTERNAL_TOKEN`;
- generate/sync `DEV_BOOTSTRAP_TOKEN`;
- synchronize Keycloak confidential Core client secret;
- synchronize local PostgreSQL credentials into Core env.

**Không commit `.env` thật.** `VITE_*` và `EXPO_PUBLIC_*` là public bundle variables,
không chứa secret.

Chi tiết: `docs/CONFIGURATION.md`.

## 6. Development topology

Development cố ý chạy source app trên host:

```text
Host
├── apps/core-service        Spring Boot
├── services/ai-service      FastAPI
├── apps/admin-web           Vite
└── apps/mobile              Expo Development Build

Docker
├── PostgreSQL
└── Keycloak

Storage
└── local filesystem default (.data/storage)
    hoặc R2 dev bucket khi STORAGE_MODE=r2
```

### 6.1 First start

```bash
make init-env
make dev-infra
```

Tương đương:

```bash
docker compose --env-file infra/docker/.env -f compose.dev.yml up -d
```

### 6.2 Bootstrap Keycloak

```bash
make keycloak-seed
```

Realm/client/roles/dev users được bootstrap idempotently. Không cần mỗi dev click UI tạo lại.

### 6.3 Run Core

```bash
make core
```

API default: `http://localhost:8080`.

### 6.4 Run AI Service

```bash
cd services/ai-service
uv sync --extra dev
cd ../..
make ai
```

Default `AI_RUNTIME_MODE=mock`, không GPU/model download.

### 6.5 Run Admin/Mobile

```bash
pnpm install
make admin
make mobile
```

Mobile dùng Development Build; không thiết kế production quanh Expo Go-only.

Chi tiết/troubleshooting: `docs/DEVELOPMENT.md`.


## Starter implementation depth

Repository này cố ý là **foundation có code thật**, không giả vờ là sản phẩm đã hoàn thiện.
Các đường xương sống đã có implementation để agent/dev tiếp tục đúng pattern:

- Identity JIT provisioning + Keycloak bootstrap;
- PostgreSQL durable job queue/cancel/retry/lease/fencing;
- Lesson build planning, persisted steps, text/audio/YouTube source hooks, annotations và server-owned Dictation scoring;
- AI provider/model routing + encrypted credentials + invocation audit;
- FastAPI capability runtime với mock/Qwen/Groq/Gemini/DeepSeek adapters;
- Lexicon/Vocabulary, Grammar Bank practice, TOEIC attempts, Curriculum projection,
  Gamification/Missions và Analytics boundaries;
- Flyway schema, R2/local object-storage port, cache/rate-limit/resilience foundation;
- Admin Web và Expo Mobile foundation để team xây UX thật tiếp theo;
- Lexicon/Grammar/TOEIC import tools với dry-run/integrity checks.

`chat`, recommendation, peer speaking, payment gateway, full curriculum editor và advanced
real-life speaking là roadmap/low priority: boundary được giữ rõ nhưng không được ghi nhận như
feature production-complete. Xem master spec để biết priority và expected evolution.

## 7. Keycloak

Realm: `lyreo`.

Roles:

```text
ADMIN
LEARNER
```

Clients:

```text
lyreo-admin-web      public + PKCE
lyreo-mobile         public + PKCE
lyreo-core-service   confidential/service account
```

Keycloak là source of truth identity/role. Product DB giữ `app_user.keycloak_subject` và profile.
User production được JIT-provision từ JWT subject; dev script có optional mirror convenience.

Chi tiết: `infra/keycloak/README.md`.

## 8. Database / Flyway / Hibernate

Phân vai:

```text
Flyway      → schema history + small stable reference seed
Hibernate   → ORM/JPA provider + schema validation
Spring Data → repository convenience khi dùng JPA
JDBC        → explicit SQL cho queue/import/query adapter
```

Lyreo đặt:

```yaml
spring.jpa.hibernate.ddl-auto: validate
```

**Không sửa thành `update` để chữa migration.**

Large Lexicon/TOEIC/Grammar data đi qua importer riêng, không Flyway.

## 9. Lesson model

Lyreo tách ba khái niệm:

```text
Lesson Content
├─ source/transcript/audio/sentences/timestamps

Lesson Annotation
├─ translation
├─ lexical units
├─ grammar points
├─ entity/dictation hints
├─ sentence IPA optional
├─ thought groups
└─ tips

Lesson Activity
├─ Dictation
├─ Shadowing
├─ Vocabulary Practice
└─ Grammar Practice
```

`Vocabulary note` sau một câu Dictation **không phải** Vocabulary Practice. Tương tự Grammar note.

Admin creator options được kiểm tra qua persisted Lesson processing policy và snapshot vào build job.

## 10. Background jobs

Long-running Lesson build:

```text
POST /api/v1/admin/lessons/build
  → DRAFT lesson
  → background_job + lesson_build_job
  → HTTP 202 {lessonId, jobId}
```

Worker:

```text
SELECT ... FOR UPDATE SKIP LOCKED
→ lease
→ heartbeat
→ persisted step state
→ AI calls
→ fencing against stale worker
→ SUCCEEDED / RETRY_WAIT / CANCELLED / FAILED
```

Inspect/cancel:

```text
GET  /api/v1/jobs/{jobId}
POST /api/v1/jobs/{jobId}/cancel
```

PostgreSQL là workflow source of truth. R2 JSON là artifact/debug.

## 11. Cache / rate limit / resilience

Old Redis responsibilities được tách:

```text
job/cancel state → PostgreSQL
read cache       → Caffeine
API rate limit   → Bucket4j + bounded Caffeine bucket cache
raw artifacts    → R2
module events    → Spring Modulith
```

Outbound dependency protection dùng Resilience4j.

## 12. AI Service / Qwen

FastAPI chỉ execute capability. Business prompt và orchestration ở Java.

Implemented runtime modes:

```text
mock   # dev/CI
local  # load qwen-asr / Qwen3 ForcedAligner in Python process
```

Qwen3-ASR/ForcedAligner là Python runtime. Docker GPU chỉ là packaging option, không phải bắt buộc để code chạy.

## 13. Object storage

Dev default:

```dotenv
STORAGE_MODE=local
```

Production target:

```text
Cloudflare R2
```

DB lưu object key, không lưu signed URL.

Typical layout:

```text
lessons/{lessonId}/...
lexicon/...
toeic/...
speech-attempts/{learnerId}/{attemptId}/...
jobs/{jobId}/.../raw.json
```

## 14. Data import

Dataset `dautoeic` không commit vào repo.

```bash
export DAUTOEIC_DATA_DIR=/home/<user>/KeepDownloads/dautoeic
```

Grammar dry run:

```bash
python tools/data-import/import_grammar.py --data-dir "$DAUTOEIC_DATA_DIR"
```

TOEIC dry run:

```bash
python tools/data-import/import_toeic.py --data-dir "$DAUTOEIC_DATA_DIR"
```

Lexicon:

```bash
python tools/data-import/import_lexicon.py \
  --english /data/kaikki-en.jsonl \
  --vietnamese /data/kaikki-vi.jsonl
```

Applied import writes checksum/status/count to `dataset_import`.

Chi tiết: `docs/DATA_PIPELINES.md`.

## 15. Docker full topology

Dev Docker details: `infra/docker/README.md`.

Self-host starter:

```bash
docker compose \
  --env-file infra/docker/.env \
  -f compose.prod.yml \
  up -d --build
```

GPU override:

```bash
docker compose \
  --env-file infra/docker/.env \
  -f compose.prod.yml \
  -f compose.gpu.yml \
  up -d --build
```

`compose.prod.yml` là staging/self-host baseline, không tự động biến thành HA production.
Đọc `docs/OPERATIONS.md` trước public deployment.

## 16. Validation

Fast policy/syntax checks:

```bash
make validate
```

Full intended checks:

```bash
make test-java
make test-ai
pnpm typecheck
pnpm build
```

Artifact generation environment không có Docker/Java 25/Maven Central đầy đủ. Xem
`TESTING_NOTES.md` (nếu còn trong artifact) chỉ ghi verification tạm: test nào đã chạy và test nào bị môi trường chặn.

## 17. Hard architecture rules

Không được:

- thêm Kafka chỉ để “enterprise hơn”;
- thêm Redis khi chưa có measured use case/decision;
- đưa Lesson/Curriculum/Gamification state machine vào FastAPI;
- đưa business prompt sang Python;
- dùng Hibernate auto schema update;
- dùng raw R2 JSON làm workflow checkpoint;
- trust score/XP/Diamond từ client;
- persist provider API key plaintext;
- persist presigned R2 URL;
- access repository/JPA entity module khác;
- tạo God `progress` module;
- merge Lexicon với Vocabulary;
- hard-code provider model names vào Java enum.

`AGENTS.md` là source of truth đầy đủ.
