# Discovery sản phẩm Lyreo

Mục đích: phân biệt vấn đề, bằng chứng hiện có, giả thuyết và việc cần khám phá. Scope/ưu tiên được
quản lý ở [PRD](prd.md); tài liệu này không phải danh sách tính năng.

## Problem statement

Người học tiếng Anh cần biến nội dung thực thành một vòng học có cấu trúc: nghe, nhận biết, luyện
tập, nói và ghi nhớ trong ngữ cảnh. Các công cụ rời rạc như trình phát media, flashcard hoặc chatbot
không tự tạo ra transcript chuẩn hóa, hoạt động luyện tập, phản hồi và lộ trình liên tục.

## Nhóm người dùng và nhu cầu

| Nhóm | Nhu cầu được thừa hưởng từ đặc tả | Mức bằng chứng |
|---|---|---|
| Learner | vào học ít bước; luyện Dictation/Shadowing; lưu từ; học Grammar/TOEIC; theo lộ trình và xem tiến bộ | Quyết định sản phẩm trong master cũ; chưa có nghiên cứu người dùng được lưu trong repo |
| Admin / Content Manager | tạo Lesson từ nhiều nguồn, chọn hoạt động/enrichment và theo dõi build | Quyết định sản phẩm và một phần implementation hiện có |
| Operator/Developer | chạy local không cần GPU/provider trả phí; workflow dài durable và quan sát được | Engineering decision + code/runbook hiện có |

## Bằng chứng hiện có

- Repository có vertical slices cho Lesson build/Dictation, AI capabilities, Vocabulary, Grammar,
  TOEIC, Curriculum, Gamification, Analytics và notification. Đây là bằng chứng implementation,
  không phải bằng chứng nhu cầu thị trường.
- Bộ dữ liệu Grammar/TOEIC bên ngoài và importer hiện có cho thấy nguồn nội dung sẵn có có thể tái sử
  dụng; dataset lớn không nằm trong Git.
- Tài liệu dự án đã chốt hướng Text/Audio/YouTube, structured practice và contextual annotation.

Chưa có trong repository: transcript phỏng vấn, khảo sát, cohort usage, benchmark học tập, phân tích
đối thủ có nguồn, hoặc KPI production. Vì vậy không có persona “đã xác thực”, quote người dùng hay
claim cải thiện kết quả học trong tài liệu này.

## Giả thuyết cần kiểm chứng

1. Learner coi trọng việc biến nội dung họ chọn thành bài tập hơn một thư viện nội dung đóng.
2. Context note xuất hiện sau attempt giúp tập trung hơn so với hiển thị tất cả từ đầu.
3. Dictation và Shadowing trong cùng Lesson tạo vòng luyện nghe–nói có giá trị hơn dùng rời rạc.
4. Level, Diamond và Mission tăng retention mà không khuyến khích farm hành vi.
5. Rule-based onboarding đủ cho giai đoạn đầu trước khi có dữ liệu để recommendation.

## Rủi ro discovery

- Scope rộng có thể làm mờ vòng giá trị đầu tiên; phạm vi YouTube của phase đầu còn xung đột
  ([GAP-001](../requirements/gaps.md#gap-001--youtube-p0-hay-sau-lesson-mvp)).
- Chất lượng ASR/alignment/pronunciation model thật chưa được chứng minh bởi mock runtime.
- Công thức completion, reward và TOEIC scaled score cần quyết định/nguồn đáng tin cậy.
- YouTube processing cần đánh giá pháp lý/chính sách độc lập với khả năng kỹ thuật adapter.

## Kế hoạch thu thập bằng chứng tiếp theo

- Phỏng vấn learner theo mức độ và mục tiêu khác nhau; quan sát quy trình hiện tại từ nội dung đến
  luyện tập thay vì chỉ hỏi danh sách feature mong muốn.
- Prototype một Lesson Text/Audio hoàn chỉnh; đo thời gian vào bài, tỷ lệ hoàn thành và điểm rơi bỏ
  cuộc. Không đặt threshold thành KPI trước khi có baseline.
- Đánh giá chất lượng Dictation/Shadowing bằng bộ mẫu có người chấm và nhiều accent/noise level.
- Kiểm thử content-manager workflow với dữ liệu thật, gồm cancel/retry và output cần sửa thủ công.

Phương pháp ưu tiên học nhu cầu trước giải pháp tham khảo
[GOV.UK Service Manual](https://www.gov.uk/service-manual/user-research/start-by-learning-user-needs);
các lựa chọn Lyreo ở trên vẫn là giả thuyết dự án cho tới khi có bằng chứng.
