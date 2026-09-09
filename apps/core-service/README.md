# Lyreo Core Service

Spring Boot deployable chính của Lyreo. Đây là **Modular Monolith**, không phải một “shared service” chứa tùy tiện mọi logic.

## Responsibilities

- expose REST API;
- authenticate JWT từ Keycloak;
- orchestrate business workflows;
- persist PostgreSQL state;
- run durable background jobs;
- publish/listen Spring Modulith events;
- call AI Service qua internal HTTP;
- access object storage qua `ObjectStoragePort`.

FastAPI không thay Core làm business orchestration.

## Run

```bash
cp .env.example .env
set -a; source .env; set +a
../../mvnw spring-boot:run
```

## Database

Flyway owns schema. Hibernate:

```yaml
spring.jpa.hibernate.ddl-auto: validate
```

Không đổi thành `update` để chữa lỗi migration.

## Module rule

Business module chỉ cross-module qua public API/event. Xem `../../AGENTS.md` và architecture test.

## Long-running work

Controller tạo durable `background_job` và trả HTTP 202. Không giữ request mở trong suốt STT/TTS/alignment/LLM pipeline.

## HTTP API Contract

Core public endpoints tuân thủ convention `/api/v1/**`:
- Responses thành công trả trực tiếp resource/DTO, không dùng global envelope.
- Responses lỗi sử dụng RFC 9457 Problem Details (`application/problem+json`) với mã lỗi ổn định và `correlationId`.
- Swagger UI khả dụng tại `/swagger-ui.html` và OpenAPI schema tại `/v3/api-docs` (bật mặc định trong profile `dev`/`test`, tắt mặc định trong `prod`).
- Xem chi tiết tại [`http-api-contract.md`](../../docs/architecture/http-api-contract.md).
