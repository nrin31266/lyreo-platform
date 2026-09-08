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
