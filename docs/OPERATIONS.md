# Lyreo Operations / Runbook

Tài liệu này là baseline vận hành cho self-host starter. Nó **không tuyên bố compose production là HA production platform**; khi Lyreo có traffic thật cần bổ sung TLS, managed database/backup, secret manager và monitoring phù hợp.

## 1. Runtime components

```text
Edge / TLS terminator
├── Admin Web
├── Core Service
└── Keycloak

Core Service
├── PostgreSQL
├── Cloudflare R2
└── AI Service

AI Service
├── local Qwen runtime (optional GPU)
└── external Groq/Gemini/DeepSeek APIs
```

## 2. Production compose scope

`compose.prod.yml` là **reproducible self-host starter** để team test full topology.

Nó không thay thế:

- managed PostgreSQL backup/PITR;
- TLS certificate management;
- centralized secret manager;
- multi-zone HA;
- log aggregation/APM;
- CDN/domain configuration.

## 3. Database ownership and backup

PostgreSQL là source of truth cho:

- user/product state;
- job state/cancellation;
- normalized lesson/TOEIC/lexicon data;
- diamond ledger;
- analytics projections;
- Spring Modulith event publication registry.

Trước production migration:

1. backup DB;
2. run Flyway migration in staging;
3. inspect destructive/long-lock SQL;
4. verify Core startup with `ddl-auto=validate`;
5. only then promote.

Large import jobs nên có checksum/dataset version để rerun có kiểm soát.

## 4. R2 ownership

R2 chứa artifact/file lớn:

```text
lessons/
lexicon/
toeic/
speech-attempts/
jobs/
```

DB lưu object key. Không persist presigned URL.

Khuyến nghị bucket tách environment:

```text
lyreo-dev
lyreo-staging
lyreo-prod
```

Không cho staging ghi vào prod bucket.

## 5. Background Job Runbook

### 5.1 Job states

```text
QUEUED
RUNNING
RETRY_WAIT
CANCEL_REQUESTED
CANCELLED
SUCCEEDED
FAILED
```

`RUNNING` job có:

- `lease_owner`;
- `lease_until`;
- `heartbeat_at`.

Worker cũ mất lease không được phép ghi success/failure sau khi job đã được worker khác recover. Repository update dùng worker ownership như fencing guard.

### 5.2 Cancel

User/Admin gọi cancel → DB set durable cancellation state. Handler check giữa step và trước commit output AI.

Không dùng cache flag cho cancellation.

### 5.3 Stale RUNNING job

Nếu process chết:

1. heartbeat dừng;
2. lease hết hạn;
3. recovery scheduler đưa job về trạng thái claimable;
4. worker mới resume theo persisted `lesson_build_job_step`;
5. idempotent step skip output đã hoàn thành.

Không reset hàng loạt `RUNNING → QUEUED` thủ công nếu chưa xác định lease semantics.

### 5.4 Retry

Starter exponential backoff bị cap. Provider lỗi lâu dài cuối cùng chuyển `FAILED` sau `max_attempts`.

Failure classification phải phân biệt lỗi input/non-retryable với provider/network/transient failure.
Không retry mù lỗi validation vì chỉ làm tăng cost và delay; xem code job/AI exception policy trước khi bật production.

## 6. AI provider incident

Queued/retry jobs do **not** pin a provider forever. `provider_snapshot_json` records the routes visible when the job was accepted, while each execution resolves the currently enabled route and writes the actual provider/model to `ai_invocation`. This lets an operator disable a broken primary without rewriting queued jobs.

Nếu provider lỗi:

1. Admin Settings disable route/provider hoặc đổi fallback;
2. không sửa business prompt trong FastAPI;
3. kiểm tra `ai_invocation` latency/status/error;
4. raw artifact (nếu lưu) dùng để debug, không phải workflow state;
5. rotate credential nếu nghi leak.

`MASTER_ENCRYPTION_KEY` không được rotate tùy tiện vì provider credential trong DB đang encrypted bằng key đó. Rotation production cần migration/re-encryption procedure.

## 7. Keycloak incident

Keycloak là source of truth cho identity/roles. Core DB chỉ giữ subject/profile snapshot.

Backup Keycloak DB riêng (`lyreo_keycloak` trong dev compose). Khi restore phải giữ realm/client IDs/secrets nhất quán.

Dev bootstrap scripts chỉ dùng local/staging controlled environment. Production user provisioning dùng login/JIT provisioning, không seed password mẫu.

## 8. Cache behavior

Caffeine là local cache:

- mất khi restart là bình thường;
- không chứa durable state;
- multi-instance có thể tạm thời stale theo TTL nếu chưa có cross-instance invalidation.

Nếu sau này yêu cầu globally consistent cache/rate quota, cần architecture decision thay vì tự thêm Redis trong một feature PR.

## 9. Rate limiting

Inbound: Bucket4j local token buckets. Với một Core instance là đủ cho MVP.

Khi scale nhiều Core instances, local quota không còn global. Chuyển backend distributed/PostgreSQL sau benchmark/decision.

Outbound: Resilience4j dùng retry/circuit breaker/bulkhead/timeouts theo dependency/capability.

## 10. Observability baseline

Core expose:

```text
/actuator/health
/actuator/info
/actuator/metrics
```

Correlation ID filter giúp trace request trong log. Job/AI tables cung cấp operational audit cơ bản.

Trước public production nên thêm:

- structured JSON logging;
- centralized logs;
- metrics scraping/dashboard;
- alerts cho failed jobs, provider error rate, DB connection saturation, disk/object failures;
- exception tracking.

## 11. Data retention

Không giữ audio learner vô thời hạn chỉ vì storage rẻ. Trước production phải chốt policy:

- speech recording retention;
- raw AI request/response retention;
- chat history retention;
- deleted-account purge;
- audit data retention.

Raw artifact có thể chứa user content và cần privacy policy tương ứng.

## 12. Deployment checklist

Trước release:

- [ ] `.env` production không chứa placeholder;
- [ ] secret không commit;
- [ ] Keycloak redirect URI/issuer đúng public URL;
- [ ] DB backup hoàn thành;
- [ ] Flyway validation pass;
- [ ] Java/Python/frontend tests pass;
- [ ] R2 bucket/credential đúng environment;
- [ ] Core↔AI internal token đồng bộ;
- [ ] CORS chỉ cho origin thực;
- [ ] dev bootstrap endpoint disabled;
- [ ] test login ADMIN + LEARNER;
- [ ] test build/cancel/retry lesson job;
- [ ] test Diamond ledger idempotency;
- [ ] smoke test mobile audio/recording;
- [ ] review các limitation/runbook trong tài liệu này; `TESTING_NOTES.md` chỉ là artifact handoff tạm nếu file còn tồn tại.

## 13. Starter maturity / production-hardening backlog

Các boundary dưới đây cố ý được thiết kế thay thế được, nhưng **không được hiểu nhầm là đã production-certified**:

- Vocabulary scheduler hiện là `StarterFsrsCompatibleScheduler`; trước release học thật cần benchmark/đối chiếu FSRS chuẩn và migration strategy cho state nếu đổi thuật toán.
- Speech Tier-1 scoring là deterministic baseline (transcript/timing), không tự nhận là phoneme/prosody science. Tier-2 multimodal judge là capability tùy chọn.
- Caffeine/Bucket4j quota mặc định theo từng Core instance; multi-instance global quota là scaling decision riêng.
- Notification realtime starter dùng in-memory SSE feed; nếu scale nhiều Core instance phải thiết kế fan-out/shared transport thay vì lén thêm broker/cache.
- `compose.prod.yml` là self-host topology có thể reproduce để staging/small deployment; HA/TLS/PITR/secret manager/centralized logs vẫn là production work.
- Live provider contract/model quality/cost phải smoke-test bằng credential thật trước khi enable route.
- YouTube media processing giữ theo product assumption hiện tại; legal/policy hardening không được trộn với Lesson domain.
- Recommendation/advanced analytics cần learner history thật trước khi tune.
- Chat có boundary thật nhưng vẫn là P3, không được kéo ưu tiên khỏi Lesson/TOEIC/Vocabulary/Curriculum.

