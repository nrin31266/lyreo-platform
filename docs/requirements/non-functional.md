# Yêu cầu phi chức năng

Mục đích: NFR có điều kiện kiểm chứng và link tới engineering rule/protocol owner. Không đặt ngưỡng
latency, availability hay model accuracy khi chưa có quyết định; xem [GAP-007](gaps.md#gap-007--kpi-va-nguong-nfr-chua-duoc-chot).

### NFR-SEC-001 — Authorization phía server

Status: inherited. Core phải enforce role/resource ownership; ẩn menu phía client không đủ. Learner
chỉ truy cập resource được cấp quyền; Admin-only settings/build phải kiểm tra `ADMIN`. Từ chối quyền
trả về RFC 9457 theo [HTTP API Contract](../architecture/http-api-contract.md).

### NFR-SEC-002 — Secret confidentiality

Status: inherited. Provider keys phải mã hóa AES-GCM bằng master key server-side; API read chỉ trả
metadata như configured/last4/status. Public frontend env, logs và raw response không được lộ secret.

### NFR-SEC-003 — Private learner artifacts

Status: inherited. Speech recording và learner artifacts mặc định private, cấp quyền qua access có
thời hạn; database chỉ lưu object key.

### NFR-OPS-001 — Durable and recoverable jobs

Status: inherited. Job phải sống qua restart, có cancel/retry/lease/heartbeat, resume idempotent step
và ngăn stale worker commit. Điều kiện protocol: [Background Jobs](../architecture/background-jobs.md).

### NFR-OPS-002 — Observable correlation

Status: inherited. Request/job/AI invocation phải có identifiers liên kết được; failure giữ stable
error code và summary an toàn. Chi tiết correlation và RFC 9457 contract: [HTTP API Contract](../architecture/http-api-contract.md). Production metrics/alert threshold còn chưa quyết định.

### NFR-OPS-003 — Dependency resilience

Status: inherited. Expensive/outbound calls cần timeout, cancellation check và policy
retry/circuit-breaker/bulkhead/rate limit phù hợp; non-retryable input không được retry mù.

### NFR-DAT-001 — Data provenance and reproducibility

Status: inherited. Import ghi dataset/version/checksum/status/count/error, hỗ trợ validate/dry-run và
rerun có kiểm soát. Source/license attribution phải được giữ; dataset lớn không commit Git.

### NFR-DAT-002 — Schema authority

Status: inherited. Flyway là schema owner; Hibernate chỉ validate. Mỗi schema change có migration
mới và cần startup/validation evidence phù hợp.

### NFR-UI-001 — Accessible resilient UI states

Status: inherited. Feature UI phải xử lý loading/error/empty và network failure, dùng semantic theme
roles/shared translation resources, tôn trọng learner preference và kiểm tra accessibility phù hợp.

### NFR-AI-001 — Auditable AI execution

Status: inherited. Mỗi invocation lưu capability, actual provider/model, status, timing/cost metadata
khi có và artifact keys cần thiết; không lưu secret. Mock chỉ chứng minh contract path, không phải
quality/accuracy của model thật.

### NFR-OPS-004 — Retention is deliberate

Status: proposed. Speech, raw AI, chat, temp upload và cached media cần retention policy/maintenance
job trước public production; không hard-code xóa tùy tiện. Thời hạn cụ thể chưa được quyết định.
