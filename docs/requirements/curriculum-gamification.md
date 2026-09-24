# Yêu cầu Curriculum và Gamification

Ownership tổng thể: [Architecture](../ARCHITECTURE.md).

### FR-CUR-001 — Curriculum structure

Curriculum gồm Path → Section → Item cho Beginner/Intermediate/Advanced. Item có
type, content reference, position, required và unlock rule; không copy content tables.

### FR-CUR-002 — Enrollment and progress

Curriculum sở hữu enrollment, item progress, current position và unlock. Nó
consume completion facts của Lesson/Grammar/TOEIC/Vocabulary; không truy cập internals.

### BR-CUR-001 — Extensible content references

Item có thể tham chiếu Lesson, Grammar Practice, TOEIC Drill/Test, Vocabulary
Review và future Speaking Scenario mà không redesign ownership.

### FR-GAM-001 — Level and internal XP

UI dùng Level và Diamond làm concept chính; XP là input nội bộ tính level, không
phải currency thứ hai.

### FR-GAM-002 — Immutable Diamond ledger

Balance phát sinh từ immutable transactions như Lesson/Mission/Streak reward,
Admin adjustment, future purchase/spend; payment gateway chưa thuộc scope.

### BR-GAM-001 — Server reward and anti-farm

Reward policy phía server xét first completion, cap, minimum score và idempotency;
không dùng score/reward amount từ client.

### FR-GAM-003 — Missions

Gamification sở hữu mission definition/progress/completion/reward và consume
domain events cho Lesson, Shadowing, Vocabulary, TOEIC. Không tạo module Mission riêng.

## User Stories & Acceptance Criteria

<a id="us-cur-001--tien-theo-curriculum"></a>
### US-CUR-001 — Tiến theo curriculum

Là learner, tôi muốn completion của content mở đúng item tiếp theo mà không copy progress.

#### AC-CUR-001 — Completion projection

Given enrollment có item tham chiếu content, when matching completion event đến, then Curriculum
đánh dấu item idempotently và tính unlock/current position theo rule của nó. Duplicate/nonmatching events không sinh duplicated progress.

#### AC-CUR-002 — Nonmatching/duplicate event

Given event không thuộc enrollment hoặc đã xử lý, when consume, then không duplicate progress hoặc
unlock/reward.

<a id="us-gam-001--nhận-reward-minh-bạch"></a>
### US-GAM-001 — Nhận reward minh bạch

Là learner, tôi muốn Level/Diamond/Mission phản ánh hoạt động hợp lệ mà không thể farm từ client.

#### AC-GAM-001 — Idempotent reward

Given eligible first completion và server score/fact, when reward policy  xử lý, then đúng một
immutable Diamond transaction được ghi theo idempotency key và balance được suy ra.

#### AC-GAM-002 — Ineligible reward

Given duplicate, dưới policy threshold hoặc vượt cap, when event xử lý, then không tạo reward mới;
client-provided amount bị bỏ qua.
