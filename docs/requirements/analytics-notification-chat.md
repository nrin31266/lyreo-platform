# Yêu cầu Analytics, Notification và Chat

Ownership tổng thể: [Architecture](../ARCHITECTURE.md).

### FR-ANL-001 — Learner progress projection

Home/Progress tập trung Level/Diamond, Continue Learning, today/week activity,
Vocabulary due, recent weakness và TOEIC snapshot; không cần enterprise dashboard.

### BR-ANL-001 — Projection does not own detailed progress

Analytics listen domain events và xây daily activity/skill summary/weakness read
models; producer module vẫn sở hữu attempts/progress. Không query repository internals.

### FR-ANL-002 — Deterministic initial weakness

Initial projection dùng facts như Grammar wrong topic, Vocabulary Again, TOEIC
wrong part và Shadowing mismatch/timing. AI recommendation chờ đủ learner history.
TOEIC dùng số câu đúng và tổng số câu theo Part từ kết quả đã nộp. Sự kiện cũ chỉ có
tổng Nghe/Đọc vẫn đọc được. Shadowing lấy tỷ lệ lỗi từ và điểm fluency từ lần chấm
có điểm, không đưa bản ghi hoặc transcript sang Analytics. Mỗi lần nộp/chấm chỉ
được tính một lần. Điểm yếu hiển thị cùng số mẫu trên Home/Progress và hồ sơ Admin.
Hồ sơ Admin ghi rõ số Part và dạng bài khi có dữ liệu theo Part, gắn nhãn tổng hợp
cho dữ liệu cũ theo Nghe/Đọc, và hiển thị đủ nhóm kỹ năng được theo dõi kể cả khi chưa có mẫu.
Tab Học tập & kỹ năng dùng điểm của từng hoạt động được chấm (ví dụ Chép chính tả
và Shadowing) và kết quả các bài ngữ pháp/TOEIC đã nộp. Hồ sơ hiển thị chuỗi ngày học
hiện tại, kỷ lục và ngày học gần nhất theo UTC.

### FR-NTF-001 — Realtime and notification boundary

Notification truyền job progress/completed/failed, mission completed, level-up và
system notifications. SSE phù hợp server→client MVP; transport có thể đổi mà không đổi domain event.

Thông báo do Admin soạn xuất hiện trong mục Thông báo trên Trang chủ ứng dụng khi
đã xuất bản, đến thời điểm đăng, chưa hết hạn và đúng đối tượng nhận. Admin có thể
gửi cho tất cả học viên, nhóm Free hoặc Plus theo gói hiện tại, hoặc chọn từ 1 đến 100
học viên. Nhắc học phải có nhóm hoặc học viên cụ thể. Tiến độ và điều kiện nhận
huy hiệu vẫn do hệ thống học tập tính riêng cho từng học viên. Thông báo mới mặc định
là bản nháp. Admin xác nhận đối tượng ở nút công bố. Sau khi công bố,
Admin tạo thông báo mới nếu cần đổi đối tượng nhận. Học viên tải lại danh sách khi
mở mục này và thấy biểu tượng ứng với từng loại thông báo trong ứng dụng.

Nhật ký hoạt động chỉ dành cho Admin và có bộ lọc theo gói thành viên, tài khoản,
thông báo, điều chỉnh kim cương và các thay đổi cấu hình khác. Lịch sử thành viên
hiển thị chuyển sang Plus hoặc trở về Free theo từng giai đoạn có hiệu lực.
Thông báo đã lên lịch có thời điểm dự kiến hiển thị. Nhật ký tài khoản cho thấy
yêu cầu khóa hoặc mở khóa gần nhất trong thời gian chờ đồng bộ với Keycloak.

### FR-CHT-001 — English tutor boundary

Chat là P3, tập trung English-learning; Chat Java owns conversation/product prompt,
AI module route provider. Chat không truy cập credential trực tiếp và không lấn scope Lesson core.

### FR-CHT-002 — Future speaking reuse

Speaking scenarios/AI conversation/peer speaking có thể reuse speech-assessment,
annotations, AI capabilities và Curriculum content reference; không phải MVP completion claim.

## User Stories & Acceptance Criteria

<a id="us-anl-001--xem-tiến-bộ-hữu-ích"></a>
### US-ANL-001 — Xem tiến bộ hữu ích

Là learner, tôi muốn xem activity/weakness/due items gần đây để chọn việc học tiếp theo.

#### AC-ANL-001 — Projection from facts

Given domain event hợp lệ, when Analytics consume , then read model learner được
cập nhật idempotently mà không lấy ownership attempts/progress từ producer.

#### AC-ANL-002 — Không dựng dữ liệu mẫu thành thật

Given chưa có learner history, when render dashboard, then UI dùng empty/onboarding state rõ, không
trình bày fixed sample metrics như dữ liệu người dùng.

<a id="us-ntf-001--theo-dõi-job"></a>
### US-NTF-001 — Theo dõi job

Là Admin, tôi muốn nhận progress/completion/failure để không phải đoán trạng thái build.

#### AC-NTF-001 — Realtime plus fallback

Given job changes state, when event phát, then authorized Admin có thể nhận update qua realtime và
vẫn query `/api/v1/jobs/{id}` làm fallback; realtime không là durability. Admin dùng SSE có heartbeat,
reconnect/refetch snapshot và polling fallback theo [jobs protocol](../architecture/background-jobs.md).
Feed Admin từ chối learner/anonymous; chưa mở WebSocket hoặc learner stream.

<a id="us-cht-001--hỏi-tutor-tiếng-anh"></a>
### US-CHT-001 — Hỏi tutor tiếng Anh

Là learner, tôi muốn hỏi tutor trong phạm vi học tiếng Anh và nhận response có route/audit.

#### AC-CHT-001 — Java-owned tutor behavior

Given Chat được bật và request hợp lệ, when gửi message, then Chat Java owns conversation/product
prompt và AI module route capability; FastAPI không tự quyết learning workflow.
