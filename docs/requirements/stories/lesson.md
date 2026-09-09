# Stories — Lesson, Dictation và Shadowing

Requirements: [Lesson](../lesson.md).

<a id="us-lsn-001--tao-lesson-theo-option"></a>
### US-LSN-001 — Tạo Lesson theo option

Là content manager, tôi muốn chọn source, activities và enrichments độc lập để chỉ chạy xử lý cần thiết.

#### AC-LSN-001 — Build được chấp nhận

Given request hợp lệ và quyền Admin, when gửi build, then Core tạo Lesson/job/step plan durable, trả
`202` cùng location job và snapshot final options.

#### AC-LSN-002 — Conditional plan

Given hai tổ hợp option khác nhau, when planner lập plan, then chỉ capability bắt buộc xuất hiện và
không mặc định generate text/TTS/STT/alignment.

<a id="dictation"></a>
## Dictation

<a id="us-lsn-002--lam-dictation"></a>
### US-LSN-002 — Làm Dictation

Là learner, tôi muốn nghe và gửi câu chép để nhận feedback server-side và tiến độ chính xác.

#### AC-LSN-003 — Dictation result

Given sentence thuộc activity/Lesson, when learner gửi answer text, then server lưu attempt, tính
score, trả expected text/progress và chỉ phát completion event khi transition lần đầu xảy ra.

#### AC-LSN-004 — Dictation input không hợp lệ

Given answer thiếu hoặc sentence không thuộc Lesson/activity, when submit, then request bị từ chối và
không có score/reward từ client được chấp nhận.

<a id="shadowing"></a>
## Shadowing

<a id="us-lsn-003--nhan-feedback-shadowing"></a>
### US-LSN-003 — Nhận feedback Shadowing

Là learner, tôi muốn record theo reference audio để nhận word accuracy, timing và fluency feedback.

#### AC-LSN-005 — Speech assessment

Given recording hợp lệ và reference, when server assessment hoàn tất, then result liên kết learner
attempt, actual ASR/alignment audit và server scores; context notes tuân display preference.

#### AC-LSN-006 — Cancel/private artifact

Given assessment bị cancel hoặc user không được quyền, when output trở về/access được yêu cầu, then
stale output không commit và private recording không lộ. End-to-end hiện chưa verified.
