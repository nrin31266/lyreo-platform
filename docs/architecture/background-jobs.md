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

Fallback query là `GET /api/v1/jobs/{id}`. Response và authoring job summary bổ sung `createdAt`,
`startedAt`, `finishedAt` từ các cột hiện có, không cần migration. UI đo chờ từ `createdAt`, chạy từ
`startedAt`, và cố định duration bằng `finishedAt` khi kết thúc. Thời gian chạy bao gồm retry wait;
không phải latency inference. Thiếu timestamp ở dữ liệu cũ thì hiện chưa biết, không tự đo từ lúc mở trang.

`JobProgressChangedEvent` sau commit được chuyển qua SSE `/api/v1/admin/realtime/jobs`; chỉ Admin
được subscribe. Feed hiện nằm trong một Core instance, không có replay/event log bền vững.
Heartbeat comment mỗi 15 giây; disconnect/timeout/error dọn subscription và heartbeat. Proxy tắt
buffer/cache stream. Realtime feed không thay database state.

Admin mở một streaming fetch với Bearer header, không truyền token trong URL. Event chỉ kích hoạt
invalidation/refetch snapshot job/draft liên quan, gom burst 100 ms. Reconnect phải refetch snapshot để
bù event mất. Refresh token dùng OIDC hiện có; logout/unmount abort stream. Backoff 1–30 giây có
jitter; idle 45 giây reconnect. Active job polling 2,5 giây khi mất SSE, đối soát 30 giây khi kết nối.
UI hiện trạng thái transport và lần query thành công gần nhất; lỗi mạng không đổi job thành FAILED.
Learner và multi-instance fanout cần hợp đồng/kiến trúc riêng trước khi mở rộng transport.

## Verification

Changes to leasing and fencing require concurrent transaction tests and affected business side-effect tests.

Unexpected worker failures are logged with the job ID on the server. Persisted client-facing
messages use a safe generic detail; explicit permanent and quota failures retain their application
messages. Database statements and arbitrary exception details are not returned in job progress.
