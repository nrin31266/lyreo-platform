# Background jobs protocol

Mục đích: owner kỹ thuật của queue, lease, heartbeat, fencing, retry, cancellation và progress.
Business-specific step behavior thuộc feature owner như [Lesson Build](../features/lesson-build.md).

## State model

`QUEUED → RUNNING → SUCCEEDED|FAILED`, với `RETRY_WAIT`, `CANCEL_REQUESTED` và `CANCELLED` cho các
nhánh tương ứng. PostgreSQL `background_job` là authoritative. Claim dùng transaction và
`FOR UPDATE SKIP LOCKED` để nhiều worker không cùng sở hữu job.

## Lease and fencing

Claim gắn `lease_owner`, `lease_until`, `heartbeat_at`. Handler heartbeat theo protocol; worker mất
lease phải dừng. Mọi transition/progress/terminal update và business side effect sau expensive work
phải chứng minh ownership/fencing để stale worker không overwrite recovered job. Step handler
idempotent và durable `DONE` state cho phép skip sau recovery.

## Cancellation

`POST /api/v1/jobs/{id}/cancel` (Admin) chuyển durable state phù hợp sang cancel requested; handler
check trước/giữa expensive steps và sau external inference trước commit. Capability không hard-cancel
được vẫn có thể chạy xong, nhưng output phải discard nếu context không còn được phép.

## Retry and recovery

Retryable failure tăng attempt, tính capped backoff và vào `RETRY_WAIT`; invalid input không retry
mù. Lease expiry cho phép recovery scheduler đưa work về trạng thái claimable. Raw AI JSON hoặc UI
event không được dùng làm checkpoint.

## Progress and consumers

Fallback query là `GET /api/v1/jobs/{id}`; `JobProgressChangedEvent` có thể qua notification/SSE tới
Admin. Realtime feed không thay database state.

## Verification

Changes to leasing and fencing require concurrent transaction tests and affected business side-effect tests.