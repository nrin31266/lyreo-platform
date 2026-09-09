# Chương 3 — AI hỗ trợ xây dựng tài liệu yêu cầu Lyreo

Mục đích: lớp trình bày môn học map 3.1–3.5 tới owner docs; không phải bản sao normative. Bằng chứng
đợt làm: [AI usage log](ai-usage-log.md). Chưa có rubric ngoài tên năm mục, nên tài liệu này không tự
tuyên bố đáp ứng toàn bộ yêu cầu của trường.

## 3.1 Discovery

Mục tiêu là nêu đúng vấn đề/người dùng, tách evidence khỏi assumption. AI hỗ trợ tổng hợp master spec,
repo slices và chỉ ra dữ liệu nghiên cứu còn thiếu; con người cần duyệt vấn đề, thực hiện phỏng vấn và
quyết định giả thuyết đáng kiểm chứng. Kết quả chuẩn: [Discovery](../product/discovery.md).

Nhận xét: code chứng minh capability/implementation đang có, không chứng minh nhu cầu hay tác động
học tập. Vì chưa có user research trong repo, không tạo persona/quote giả.

## 3.2 PRD

Mục tiêu là thống nhất vision, value, scope, priority, non-goals và outcome direction. AI hỗ trợ tách
product layer khỏi API/schema/architecture; con người phải quyết YouTube phase, KPI và release tradeoff.
Kết quả chuẩn: [PRD](../product/prd.md); issues: [Gaps](../requirements/gaps.md).

Nhận xét: target product không bị thu nhỏ theo starter screens, nhưng roadmap không được dùng như
implementation status.

## 3.3 Phân tích yêu cầu

Mục tiêu là actors/terms/boundaries và FR/BR/NFR nguyên tử có ID. AI hỗ trợ phân loại nội dung legacy,
đối chiếu ownership/code path và tạo links; con người duyệt business policy, thresholds, privacy và
provenance. Kết quả chuẩn: [Analysis](../requirements/analysis.md) và các area files được index tại đó.

Nhận xét: trạng thái `inherited` bảo toàn ý cũ nhưng không giả định approval. Evidence status được tách
sang [Traceability](../requirements/traceability.md).

## 3.4 User Stories và Acceptance Criteria

Mục tiêu là diễn đạt giá trị actor và kết quả có thể quan sát, gồm happy path, invalid/permission/
state branches có ý nghĩa. AI hỗ trợ draft story/AC và nối IDs; con người cần kiểm tra ngôn ngữ,
policy và độ phù hợp với người dùng. Kết quả chuẩn: [Stories index](../requirements/user-stories.md).

Nhận xét: AC không viết “đã code xong/test pass”; command và kết quả chạy thuộc traceability.

## 3.5 Feature specification

Mục tiêu là đủ flow/state/error/side effect/boundary/code entrypoints để implement/review mà không kể
lại PRD. AI hỗ trợ đọc vertical slices và phân biệt target/current status; con người cần duyệt wire
contract, UX, concurrency/fencing và feature decisions còn mở. Kết quả chuẩn: [`features/`](../features/lesson-build.md)
và technical protocols trong [`architecture/`](../architecture/background-jobs.md).

Nhận xét: Lesson Build, Dictation và Shadowing được tách; AI administration khác execution; Job
protocol không bị copy vào từng feature. Shadowing/realtime/mock/starter limitations được ghi rõ.
