# Yêu cầu Lesson, Dictation và Shadowing

Nguồn migration: đặc tả Lyreo trước khi tách owner. Feature contracts: [Lesson Build](../features/lesson-build.md),
[Dictation](../features/dictation.md), [Shadowing](../features/shadowing.md).

### FR-LSN-001 — Lesson source

Status: inherited. Admin có thể tạo Lesson từ Text, Audio hoặc YouTube; source material được chuẩn
hóa thành content/media phù hợp. Release timing của YouTube còn mở tại [GAP-001](gaps.md#gap-001--youtube-p0-hay-sau-lesson-mvp).

### FR-LSN-002 — Independent build dimensions

Status: inherited. Source, selected activities, annotations/enrichment và pronunciation strategy là
các chiều độc lập. Preset chỉ pre-fill; request/snapshot cuối mới điều khiển build.

<a id="br-lsn-001--content-annotation-activity-tach-biet"></a>
### BR-LSN-001 — Content, annotation, activity tách biệt

Status: inherited. Content chứa source/transcript/sentence/segment/media/timestamp; annotation gắn
hỗ trợ ngữ cảnh; activity là learning loop có attempt/scoring/progress. Contextual lexical/grammar
note không phải Vocabulary/Grammar Practice.

### BR-LSN-002 — Conditional build plan

Status: inherited. Planner chỉ thêm capability thật sự cần; không mặc định generate text, TTS, STT,
alignment hoặc mọi enrichment cho mọi Lesson.

### FR-LSN-003 — Canonical audio playback

Status: inherited. Lesson ưu tiên một canonical media với sentence start/end offsets. Derived clips
chỉ tạo on-demand khi offline/precision need biện minh; không mặc định cắt hàng trăm MP3.

### FR-LSN-004 — Contextual pronunciation

Status: inherited. Sentence IPA là optional theo admin policy `DISABLED|ON_DEMAND|PREGENERATE` và
learner display `OFF|TAP_TO_SHOW|AFTER_ATTEMPT|ALWAYS`; cache key phụ thuộc text hash, accent,
provider và model/version, và phải invalid khi text đổi.

<a id="dictation"></a>
## Dictation

### FR-LSN-005 — Dictation attempt feedback

Status: inherited. Learner nghe với repeat/speed, gửi answer text và nhận expected answer, diff,
server score, cùng annotation theo preference. Proper-name/number/acronym hints có thể hiển thị trước.

### BR-LSN-003 — Dictation scoring authority

Status: inherited. Server chuẩn hóa và chấm answer; client không gửi score. Punctuation,
capitalization, apostrophe và number policy có thể được cấu hình khi có owner policy rõ.

### BR-LSN-004 — Completion facts riêng biệt

Status: inherited. Một sentence attempt, activity completion và lesson completion là các fact khác
nhau. Event chỉ phát khi transition thật sự xảy ra. Threshold 70 hiện là implementation, chưa là
approved rule: [GAP-002](gaps.md#gap-002--nguong-hoan-thanh-dictation-70).

<a id="shadowing"></a>
## Shadowing

### FR-LSN-006 — Shadowing practice

Status: inherited. Experience gồm reference audio, karaoke word highlight, thought groups/content
emphasis, recording, ASR/alignment, word accuracy, timing, fluency và optional deep judge.

### BR-LSN-005 — Speech score authority

Status: inherited. `speech-assessment` tạo final score; client không tự chấm. Context notes ưu tiên
`AFTER_ATTEMPT` để practice screen gọn. Current wiring status: [GAP-003](gaps.md#gap-003--shadowing-chua-noi-end-to-end).
