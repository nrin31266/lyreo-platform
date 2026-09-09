# Stories — Curriculum và Gamification

Requirements: [Curriculum/Gamification](../curriculum-gamification.md).

### US-CUR-001 — Tiến theo curriculum

Là learner, tôi muốn completion của content mở đúng item tiếp theo mà không copy progress.

#### AC-CUR-001 — Completion projection

Given enrollment có item tham chiếu content, when matching completion event đến, then Curriculum đánh
dấu item idempotently và tính unlock/current position theo rule của nó.

#### AC-CUR-002 — Nonmatching/duplicate event

Given event không thuộc enrollment hoặc đã xử lý, when consume, then không duplicate progress hoặc
unlock/reward.

### US-GAM-001 — Nhận reward minh bạch

Là learner, tôi muốn Level/Diamond/Mission phản ánh hoạt động hợp lệ mà không thể farm từ client.

#### AC-GAM-001 — Idempotent reward

Given eligible first completion và server score/fact, when reward policy xử lý, then đúng một
immutable Diamond transaction được ghi theo idempotency key và balance được suy ra.

#### AC-GAM-002 — Ineligible reward

Given duplicate, dưới policy threshold hoặc vượt cap, when event xử lý, then không tạo reward mới;
client-provided amount bị bỏ qua.
