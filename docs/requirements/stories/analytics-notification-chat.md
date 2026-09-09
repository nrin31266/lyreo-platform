# Stories — Analytics, Notification và Chat

Requirements: [Analytics/Notification/Chat](../analytics-notification-chat.md).

### US-ANL-001 — Xem tiến bộ hữu ích

Là learner, tôi muốn xem activity/weakness/due items gần đây để chọn việc học tiếp theo.

#### AC-ANL-001 — Projection from facts

Given domain event hợp lệ, when Analytics consume, then read model learner được cập nhật idempotently
mà không lấy ownership attempts/progress từ producer.

#### AC-ANL-002 — Không dựng dữ liệu mẫu thành thật

Given chưa có learner history, when render dashboard, then UI dùng empty/onboarding state rõ, không
trình bày fixed sample metrics như dữ liệu người dùng.

### US-NTF-001 — Theo dõi job

Là Admin, tôi muốn nhận progress/completion/failure để không phải đoán trạng thái build.

#### AC-NTF-001 — Realtime plus fallback

Given job changes state, when event phát, then authorized Admin có thể nhận update qua realtime và
vẫn query `/api/v1/jobs/{id}` làm fallback; realtime không là durability.

### US-CHT-001 — Hỏi tutor tiếng Anh

Là learner, tôi muốn hỏi tutor trong phạm vi học tiếng Anh và nhận response có route/audit.

#### AC-CHT-001 — Java-owned tutor behavior

Given Chat được bật và request hợp lệ, when gửi message, then Chat Java owns conversation/product
prompt và AI module route capability; FastAPI không tự quyết learning workflow.
