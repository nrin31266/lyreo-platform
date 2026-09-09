# Stories — Lexicon và Vocabulary

Requirements: [Lexicon/Vocabulary](../lexicon-vocabulary.md).

### US-LEX-001 — Tra và hiểu lexical unit

Là learner, tôi muốn tra word/phrase và xem nghĩa/phát âm/provenance để hiểu cả ngoài Lesson.

#### AC-LEX-001 — Search result

Given query hợp lệ, when search, then kết quả trả entry types/forms/senses/pronunciation hiện có và
không bịa Vietnamese translation khi source thiếu.

#### AC-LEX-002 — Unresolved lesson phrase

Given Lesson phát hiện phrase chưa có entry, when enrichment hoàn tất, then Lesson giữ contextual
meaning với trạng thái unresolved và vẫn usable; resolver có thể link sau.

<a id="us-voc-001--on-tu-theo-lich"></a>
### US-VOC-001 — Ôn từ theo lịch

Là learner, tôi muốn lưu lexical unit và review để nhận lịch tiếp theo cùng history cá nhân.

#### AC-VOC-001 — Save/review

Given Lexicon entry và authenticated learner, when save/review, then card/history/scheduling state
thuộc learner được cập nhật và review fact được publish một lần theo contract.

#### AC-VOC-002 — Scheduler replaceability

Given scheduler implementation thay đổi có migration được duyệt, when application schedules, then
domain port/contract giữ ổn định và existing learner state được xử lý theo migration plan.
