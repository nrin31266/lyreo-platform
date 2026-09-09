# Yêu cầu Analytics, Notification và Chat

Nguồn migration: đặc tả Lyreo trước khi tách owner. Ownership tổng thể: [Architecture](../ARCHITECTURE.md).

### FR-ANL-001 — Learner progress projection

Status: inherited. Home/Progress tập trung Level/Diamond, Continue Learning, today/week activity,
Vocabulary due, recent weakness và TOEIC snapshot; không cần enterprise dashboard.

### BR-ANL-001 — Projection does not own detailed progress

Status: inherited. Analytics listen domain events và xây daily activity/skill summary/weakness read
models; producer module vẫn sở hữu attempts/progress. Không query repository internals.

### FR-ANL-002 — Deterministic initial weakness

Status: inherited. Initial projection dùng facts như Grammar wrong topic, Vocabulary Again, TOEIC
wrong part và Shadowing mismatch/timing. AI recommendation chờ đủ learner history.

### FR-NTF-001 — Realtime and notification boundary

Status: inherited. Notification truyền job progress/completed/failed, mission completed, level-up và
system notifications. SSE phù hợp server→client MVP; transport có thể đổi mà không đổi domain event.
Current Admin integration gap: [GAP-004](gaps.md#gap-004--admin-jobs-chua-dung-realtime).

### FR-CHT-001 — English tutor boundary

Status: inherited. Chat là P3, tập trung English-learning; Chat Java owns conversation/product prompt,
AI module route provider. Chat không truy cập credential trực tiếp và không lấn scope Lesson core.

### FR-CHT-002 — Future speaking reuse

Status: proposed. Speaking scenarios/AI conversation/peer speaking có thể reuse speech-assessment,
annotations, AI capabilities và Curriculum content reference; không phải MVP completion claim.
