# Lyreo Development Guide

Mục tiêu của dev topology: **dependency chạy container, source code chạy local** để debugger/hot reload nhanh. Không ép developer rebuild cả stack sau mỗi thay đổi Java/Python/TypeScript.

## 1. Toolchain

Baseline tại thời điểm khóa kiến trúc:

- Java 25;
- Maven Wrapper của repo;
- Python 3.12 + `uv`;
- Node 24 LTS + pnpm 12;
- Docker + Docker Compose;
- Android Studio / Xcode khi chạy native mobile;
- Expo Development Build, không phụ thuộc Expo Go-only.

Repo có `.java-version`, `.nvmrc`, `mise.toml` để team đồng bộ.

## 2. First run

```bash
git clone <repo>
cd lyreo-platform

./scripts/init-dev-env.sh
make dev-infra
```

Docker dev chỉ chạy:

```text
PostgreSQL
Keycloak
```

Sau khi Keycloak healthy:

```bash
make keycloak-seed
```

Dev users/password nằm trong `infra/keycloak/.env`.

## 3. Start executable apps

Mở terminal riêng cho từng process.

### Core Service

```bash
make core
```

hoặc:

```bash
cd apps/core-service
set -a; source .env; set +a
../../mvnw spring-boot:run
```

### AI Service

```bash
cd services/ai-service
uv sync --extra dev
make -C ../.. ai
```

Default `AI_RUNTIME_MODE=mock` để CI/dev không tải model nhiều GB.

### Admin Web

```bash
pnpm install
make admin
```

### Mobile

```bash
make mobile
```

Khi cần native build:

```bash
pnpm --filter @lyreo/mobile android
# hoặc iOS trên macOS
```

Sau khi thêm native module/đổi Expo SDK cần rebuild Development Build.

## 4. Recommended start order

```text
1. PostgreSQL + Keycloak
2. Keycloak bootstrap
3. AI Service
4. Core Service
5. Admin Web
6. Mobile
```

Core có thể start trước AI nhưng lesson AI calls sẽ fail/circuit-break cho tới khi AI Service available.

## 5. AI runtime modes

### `mock`

Dùng cho UI/backend/test thông thường. Trả contract deterministic, không GPU/API bill.

### `local`

FastAPI import `qwen-asr` Python package và lazy-load:

- `Qwen3-ASR` cho STT;
- `Qwen3-ForcedAligner` cho alignment.

Docker GPU chỉ là packaging option; developer GPU có thể chạy Python local trực tiếp.

Provider SaaS (Groq/Gemini/DeepSeek) được gọi theo route do Core quyết định. FastAPI không persist provider credential.

## 6. Storage during development

Default:

```dotenv
STORAGE_MODE=local
```

Artifact đi vào `.data/storage`, đã gitignore.

Khi team muốn test R2 integration:

```dotenv
STORAGE_MODE=r2
R2_ENDPOINT=...
R2_ACCESS_KEY_ID=...
R2_SECRET_ACCESS_KEY=...
R2_BUCKET=lyreo-dev
```

Nên dùng bucket dev riêng, không dùng chung production.

## 7. Database workflow

### Không dùng Hibernate auto-update

`ddl-auto=validate` nghĩa là Entity/JDBC mapping không tự sửa schema.

Khi thay schema:

1. tạo migration mới `Vxxx__description.sql`;
2. không sửa migration đã vào shared environment;
3. start Core hoặc chạy tests để Flyway migrate;
4. Hibernate validate schema;
5. review rollback/compatibility nếu migration destructive.

Large seed không để trong Flyway. Lexicon/Grammar/TOEIC dùng importer.

## 8. Data importer development

```bash
cd tools/data-import
uv sync
cp .env.example .env
```

Dùng dry-run trước:

```bash
uv run python import_grammar.py --data-dir "$DAUTOEIC_DATA_DIR"
uv run python import_toeic.py --data-dir "$DAUTOEIC_DATA_DIR"
uv run python import_lexicon.py --english "$KAIKKI_EN_JSONL" --limit 1000
```

Chỉ thêm `--apply` sau khi counts/checksum/validation hợp lý.

## 9. Test/validation loop

Offline policy check nhanh:

```bash
make validate
```

Full:

```bash
make test-java
make test-ai
pnpm typecheck
```

Nếu artifact còn `TESTING_NOTES.md`, dùng nó như handoff tạm để biết test nào cần chạy lại trên máy có network/Docker/GPU; không dùng file đó làm architecture doc.

## 10. Common troubleshooting

### Maven không tải dependency

Kiểm tra network/DNS tới Maven Central. Maven Wrapper cần tải Maven/dependency lần đầu.

### Core báo schema validation fail

Không bật `ddl-auto=update`. Kiểm tra migration mới có được tạo/chạy đúng thứ tự không.

### Keycloak dev user login không được

```bash
make keycloak-seed
```

Sau đó kiểm tra `infra/keycloak/.env` và redirect URI của client.

### Android emulator không gọi được localhost

Android emulator dùng host alias `10.0.2.2`; `.env.example` mobile đã dùng giá trị này.

Physical device phải dùng IP LAN hoặc dev tunnel phù hợp.

### Qwen tải model/GPU OOM

Quay về:

```dotenv
AI_RUNTIME_MODE=mock
```

Chỉ bật `local` trên máy đủ VRAM; giảm concurrency trước khi đổi model.

### Job kẹt RUNNING

Không sửa DB trực tiếp trước. Xem `docs/OPERATIONS.md` phần Job Runbook: heartbeat/lease/retry/cancel có semantics riêng.

## 11. Coding-agent workflow

Agent phải đọc `AGENTS.md` trước khi sửa code. `CLAUDE.md`, `GEMINI.md`, `AGENT.md` chỉ là symlink.

Trước khi hoàn tất task:

- chạy test applicable;
- thêm migration nếu schema đổi;
- cập nhật docs/env example nếu config đổi;
- không đưa secret/dataset raw vào commit;
- nếu không chạy được test, ghi lý do cụ thể vào handoff/PR.
