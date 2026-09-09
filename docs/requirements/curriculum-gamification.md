# Yêu cầu Curriculum và Gamification

Nguồn migration: đặc tả Lyreo trước khi tách owner. Specs: [Curriculum progress](../features/curriculum-progress.md),
[Rewards and analytics](../features/rewards-analytics.md).

### FR-CUR-001 — Curriculum structure

Status: inherited. Curriculum gồm Path → Section → Item cho Beginner/Intermediate/Advanced. Item có
type, content reference, position, required và unlock rule; không copy content tables.

### FR-CUR-002 — Enrollment and progress

Status: inherited. Curriculum sở hữu enrollment, item progress, current position và unlock. Nó
consume completion facts của Lesson/Grammar/TOEIC/Vocabulary; không truy cập internals.

### BR-CUR-001 — Extensible content references

Status: inherited. Item có thể tham chiếu Lesson, Grammar Practice, TOEIC Drill/Test, Vocabulary
Review và future Speaking Scenario mà không redesign ownership.

### FR-GAM-001 — Level and internal XP

Status: inherited. UI dùng Level và Diamond làm concept chính; XP là input nội bộ tính level, không
phải currency thứ hai.

### FR-GAM-002 — Immutable Diamond ledger

Status: inherited. Balance phát sinh từ immutable transactions như Lesson/Mission/Streak reward,
Admin adjustment, future purchase/spend; payment gateway chưa thuộc scope.

### BR-GAM-001 — Server reward and anti-farm

Status: inherited. Reward policy phía server xét first completion, cap, minimum score và idempotency;
không dùng score/reward amount từ client.

### FR-GAM-003 — Missions

Status: inherited. Gamification sở hữu mission definition/progress/completion/reward và consume
domain events cho Lesson, Shadowing, Vocabulary, TOEIC. Không tạo module Mission riêng.
