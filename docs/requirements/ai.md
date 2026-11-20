# Yêu cầu AI administration và execution

See boundary kỹ thuật: [AI execution](../architecture/ai-execution.md).

### FR-AI-006 — Suggestions for library editors

Lexicon dùng cùng nghĩa đang chọn trong editor: MATCH đối chiếu ứng viên hiện có (kèm source context),
TRANSLATE dịch riêng nghĩa, EXAMPLE thêm ví dụ. Core gửi JSON schema và kiểm tra ID/phạm vi/văn bản
ứng viên, field theo operation và kết quả hoàn chỉnh; không chấp nhận output bị cắt. Mỗi lần tối đa
100 ứng viên trong tối đa 20 nghĩa; UI thao tác từng nghĩa. Nhận bản dịch chọn thay/thêm và xem trước,
không tạo VERIFIED. TTS chọn phạm vi item hoặc toàn mục từ, không xác minh IPA.
AI hỗ trợ dịch, giải thích và phân loại
câu Grammar/TOEIC, hoặc tạo tối đa 10 biến thể từ câu mẫu/brief. Tối đa 20 đề xuất biên tập/lần.
Kết quả là đề xuất cần duyệt, chấp nhận từng trường/câu chỉ cập nhật hoặc tạo nháp, không tự công bố.
AI không được đổi đáp án nguồn; nghi ngờ đáp án được ghi thành cảnh báo để Admin quyết định.
Audio TTS từ text/transcript đã duyệt được đánh dấu tổng hợp. Không tự gọi AI khi mở màn hình.

Request/job/output bền vững và có audit qua invocation service. Hủy, lỗi route, output sai schema
hoặc timeout không được mất bản sửa. Khi nháp đổi sau request, kết quả vẫn có thể xem nhưng accept
phải báo conflict; không ghi đè. Retry là request có snapshot mới. FastAPI chỉ thực thi capability,
không sở hữu prompt/nghiệp vụ biên tập. Mock chỉ chứng minh contract, không chứng minh chất lượng.

### BR-AI-001 — Java sở hữu business AI intent

Java/Core quyết định lý do/thời điểm gọi, product prompt, expected schema,
orchestration, route, retry/fallback, persistence và state transition.

### FR-AI-001 — Capability execution

FastAPI cung cấp STT, alignment, TTS, NLP analysis, generic LLM generation và
multimodal judging qua capability endpoints; không có Lesson/Curriculum/Reward/SRS/TOEIC workflow.

### FR-AI-002 — Configurable routing and fallback

Admin có thể cấu hình provider, model string, enabled state, priority/fallback và
rate/cost policy theo capability. Model name không khóa vào Java enum.

### BR-AI-002 — Routing time and audit snapshot

Snapshot khi accept job phục vụ audit context; execution resolve route đang
enabled và invocation lưu actual provider/model. Snapshot không hứa pin provider để tái lập output.

### FR-AI-003 — Invocation audit

Audit liên kết capability/provider/model/status/timing/token/cost khi có,
request hash, artifact keys và stable error code; không lưu secret.

### BR-AI-003 — Provider credential confidentiality

Master encryption key nằm ở environment; provider key mã hóa AES-GCM trong Core.
Browser chỉ nhận configured/last4/status, không plaintext. FastAPI không persist credential.

### FR-AI-004 — Runtime modes

`mock` hỗ trợ dev/CI deterministic không GPU/paid provider; `local` lazy-load
Qwen ASR/alignment. SaaS provider routing độc lập với runtime mode; External provider execution follows the configured route.

### FR-AI-005 — Configured paid providers

DeepSeek là provider LLM thông thường. Admin nhập key qua màn hình provider, bật kết nối,
chọn model và đặt priority/fallback trong route `GENERAL_LLM` như các provider khác.
Core mã hóa key, chỉ chuyển credential qua gateway phía server và ghi provider/model thực tế
vào audit. DeepSeek không được bật ngầm hoặc gọi khi provider chưa enabled.

### BR-AI-004 — Raw versus normalized output

Raw provider output là artifact debug/audit trong object storage; normalized
queryable result và workflow checkpoint ở PostgreSQL. Cancellation được kiểm tra trước commit.

## User Stories & Acceptance Criteria

### US-AI-001 — Quản lý route provider

Là Admin, tôi muốn cấu hình provider/model/fallback theo capability mà không lộ credential.

#### AC-AI-001 — Credential write/read

Given Admin gửi key qua protected endpoint, when lưu/read settings, then server mã hóa key và response
chỉ trả metadata masked/configured, không plaintext. Admin có thể xoá credential: key và last4 bị xoá,
provider cùng các route liên quan bị tắt; metadata và invocation audit được giữ lại.

#### AC-AI-002 — Live route resolution

Given route thay đổi sau khi job accepted, when step execute, then Core resolve route đang enabled,
ghi actual provider/model và vẫn giữ acceptance snapshot cho audit.

### US-AI-002 — Thực thi capability có audit

Là workflow owner, tôi muốn gọi capability bằng contract thống nhất để retry/cancel và truy vết result.

#### AC-AI-003 — Authorized capability call

Given internal token và request schema hợp lệ, when Core gọi FastAPI capability, then adapter thực
thi và trả normalized response; invocation audit ghi status/timing/artifact keys cần thiết.

#### AC-AI-004 — Failure/cancellation

Given timeout/provider error/cancel, when call kết thúc, then policy phân loại retry, không expose raw
error cho client, và không commit output nếu job không còn quyền tiếp tục.

### BR-AI-006 — Bounded library maintenance pilot

Luồng bảo trì dữ liệu có thể dùng provider đã cấu hình hoặc credential vận hành riêng, không tự bật
route hay phát sinh gọi trả phí. Core sở hữu prompt,
context, schema, cache và ngân sách; FastAPI chỉ thực thi capability. Pilot tối đa 100 đơn vị/1,5 USD,
khóa ngân sách trước mỗi gọi, tính cả output/failure và không gọi lại mục đã thành công khi resume.
Xác nhận giá chính thức ngày chạy; key đọc từ `.env` root phía server. Kết quả có diff/provenance,
chỉ đề xuất các trường ngôn ngữ được cho phép, không sửa answer key, passage/hình thiếu hoặc đoán
IPA–audio. Sau khi đánh giá mẫu, batch chốt dữ liệu chạy hàng đợi đã xác định với dự toán riêng.
Ledger giữ các lượt đã trả phí và tách lỗi từng nội dung khỏi lỗi xác thực, số dư hoặc giới hạn tài khoản.
Khi gặp lỗi tài khoản hoặc giới hạn, dừng lên lịch và xuất phần đã xử lý.
Mặc định bảo trì dùng Flash với thinking mức high và trần output bao gồm cả reasoning; không lưu
reasoning nội bộ vào package hoặc trả về trình duyệt. Capability chỉ nhận text không được suy luận
lời giải hình ảnh từ answer key; các mục thiếu ngữ cảnh hình phải được phân loại riêng.
