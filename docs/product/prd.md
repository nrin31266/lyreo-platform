# PRD — Lyreo Platform

Mục đích: owner của mục tiêu, giá trị, scope, ưu tiên và non-goals. Nghĩa vụ chi tiết nằm trong
[requirements](../requirements/analysis.md), workflow nằm trong [`features/`](../features/lesson-build.md),
và trạng thái implementation nằm trong [traceability](../requirements/traceability.md).

## 1. Tóm tắt

Lyreo giúp learner biến nội dung tiếng Anh thực thành Lesson có Dictation, Shadowing, annotation,
Vocabulary/Grammar practice và lộ trình học có cấu trúc. Admin quản lý nội dung và AI policy, trong
khi Core giữ quyền quyết định nghiệp vụ và AI service chỉ thực thi capability.

## 2. Bối cảnh và người liên quan

| Vai trò | Trách nhiệm |
|---|---|
| Product owner / team | duyệt scope, policy học tập, KPI và open decisions |
| Learner | học, gửi evidence/attempt và xem tiến bộ cá nhân |
| Admin / Content Manager | tạo nội dung, cấu hình policy/provider và vận hành build |
| Engineering / Operations | bảo toàn boundary, dữ liệu, bảo mật và workflow durable |

Tên cá nhân/người duyệt chưa được ghi nhận trong repository; không tự gán approval.

## 3. Mục tiêu và giá trị

- Rút ngắn đường từ Text/Audio/YouTube tới trải nghiệm nghe–nhận biết–luyện tập–nói–ghi nhớ.
- Duy trì một learning record có ownership rõ cho Lesson, Vocabulary, Grammar, TOEIC, Curriculum
  và Gamification.
- Cho phép thay provider/model mà không chuyển business orchestration khỏi Java/Core.
- Tạo trải nghiệm Mobile hiện đại, ít bước, audio-first; AI chỉ xuất hiện khi tạo giá trị rõ.
- Cho Admin xây và quan sát nội dung dài mà không giữ HTTP request mở hoặc phụ thuộc UI để durable.

Kết quả định lượng chưa được quyết định. Không có baseline hợp lệ để đặt SMART KPI; xem
[GAP-007](../requirements/gaps.md#gap-007--kpi-va-nguong-nfr-chua-duoc-chot).

## 4. Phân khúc theo nhu cầu

- Learner muốn luyện nghe/nói bằng nội dung có ngữ cảnh thay vì chỉ flashcard/chatbot.
- Learner luyện Grammar/TOEIC cần question bank, chấm điểm phía server và lịch sử attempt.
- Content manager cần biến nguồn thành Lesson theo option và theo dõi lỗi/retry/cancel.
- Operator cần mock/local runtime, dữ liệu lớn tách khỏi Git và trạng thái workflow có thể phục hồi.

Các nhóm này là phân loại nhu cầu từ đặc tả, chưa phải market segmentation đã được nghiên cứu.

## 5. Giá trị cốt lõi

- Một Lesson thống nhất content, annotation và nhiều activity nhưng không làm lẫn ownership.
- Lexicon toàn cục tách khỏi Vocabulary SRS cá nhân; note ngữ cảnh tách khỏi practice loop.
- Curriculum tham chiếu content thay vì copy, còn Analytics dựng projection từ event.
- Server giữ quyền scoring/reward; learner nhận feedback có thể truy vết.
- AI routing có audit và fallback, trong khi provider/model có thể thay đổi.

## 6. Scope và ưu tiên

| Mức | Scope sản phẩm |
|---|---|
| P0 Foundation | auth/Keycloak, config, AI routing, PostgreSQL/Flyway, object storage, jobs, module boundary, cache/rate-limit/resilience, Admin/Mobile shell |
| P0 Core Lesson | source và canonical content, conditional build plan, Dictation, Shadowing, contextual annotations |
| P0/P1 | Lexicon toàn cục và lesson lexical linking |
| P1 | Vocabulary SRS, Grammar bank/practice, TOEIC, Curriculum, rule-based onboarding |
| P2 | Analytics/Progress projection, Level/Diamond/Mission |
| P3 | English tutor Chat và speaking scenarios mở rộng |

YouTube được liệt kê trong P0 Core Lesson nhưng roadmap cũ Phase 1 chỉ nêu Text/Audio. Quyết định
release còn pending ở [GAP-001](../requirements/gaps.md#gap-001--youtube-p0-hay-sau-lesson-mvp);
khả năng adapter hiện có không tự quyết scope.

## 7. Solution outline

### Trải nghiệm learner

Login → onboarding lần đầu → Home/Curriculum → Lesson/Grammar/TOEIC/Vocabulary → attempt được chấm
phía server → progress/reward/projection cập nhật qua contract/event. Mobile ưu tiên audio controls,
karaoke/thought groups và context note đúng thời điểm theo preference.

### Trải nghiệm admin

Admin chọn source, activity, enrichment, pronunciation strategy và preset; preset chỉ pre-fill,
request cuối cùng tạo snapshot và durable build job. Admin xem trạng thái, cancel/retry theo protocol,
quản AI provider/routing và các content areas theo từng giai đoạn.

### Phụ thuộc và assumptions

- Keycloak là identity/role authority; PostgreSQL là workflow/product state authority.
- Grammar/TOEIC dùng dataset bên ngoài; Lexicon dùng Kaikki/Wiktextract khi nguồn được chuẩn bị.
- Normal dev/CI dùng mock AI; mock không chứng minh chất lượng provider/model thật.
- Learner-facing YouTube playback ưu tiên official player là giả định hiện hành, cần policy review.

## 8. Release direction và non-goals

Hướng release kế thừa: Platform → Lesson MVP → Learning Data → TOEIC/Curriculum → Retention →
Intelligence → Speaking expansion. Đây là thứ tự tương đối, không phải cam kết ngày hoặc trạng thái
“done”.

Non-goals hiện tại:

- microservices hóa core domain, Kafka/Redis mặc định hoặc business orchestration trong FastAPI;
- guest mode ở phase đầu;
- payment gateway và purchasable Diamond;
- full distributed tracing/HA stack trước nhu cầu vận hành;
- LLM-generate toàn bộ dictionary/Grammar questions hoặc pre-generate synthetic audio đại trà;
- final mascot artwork hay một UI implementation dùng chung Web/Mobile.

Chi tiết open decisions và implementation gaps: [gaps register](../requirements/gaps.md).
