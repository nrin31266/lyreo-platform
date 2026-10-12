# Yêu cầu Lexicon và Vocabulary

Data contract: [Data Pipelines](../DATA_PIPELINES.md).

### FR-LEX-001 — Global dictionary

Lexicon hỗ trợ search word, phrase, phrasal verb, idiom và collocation; entries có
forms/lemma, senses, pronunciation, source/license và Vietnamese translation status.

### BR-LEX-001 — Lexicon không phải Vocabulary

Lexicon là global knowledge; Vocabulary là learner-owned SRS card tham chiếu
`headwordId` trường tồn qua các release. Vocabulary không sở hữu dictionary definition.

### FR-LEX-002 — Contextual lexical resolution

Lesson detector có thể link Lexicon entry; nếu chưa có, giữ surface/context
meaning và `UNRESOLVED` để Lesson vẫn dùng được, rồi lazy-resolve sau.

### BR-LEX-002 — Provenance and coverage

Dictionary seed dùng structured Wiktionary/Wiktextract/Kaikki sources, giữ
attribution theo source/license. Corpus Lyreo xếp relevance, không giới hạn dictionary vào corpus;
LLM không generate toàn bộ dictionary.

### FR-LEX-003 — Pronunciation audio strategy

Ưu tiên recording thật; first use có thể fetch/normalize/cache object storage.
Device/server TTS là fallback on-demand, không pre-generate hàng chục nghìn từ.

### FR-VOC-001 — Save and review card

Learner lưu lexical unit cùng optional context, review và nhận next scheduling
state/history/mastery.

### BR-VOC-001 — Scheduler abstraction

Domain dùng `SpacedRepetitionScheduler` port để có thể benchmark/migrate.
Implementation hiện tại là starter FSRS-compatible, không được mô tả là FSRS đầy đủ.

### BR-VOC-002 — Review fact

Review state thuộc Vocabulary; successful review fact có thể được Analytics và
Gamification consume qua public event, không truy cập repository nội bộ.

## User Stories & Acceptance Criteria

<a id="us-lex-001--tra-và-hiểu-lexical-unit"></a>
### US-LEX-001 — Tra và hiểu lexical unit

Là learner, tôi muốn tra word/phrase và xem nghĩa/phát âm/provenance để hiểu cả ngoài Lesson.

#### AC-LEX-001 — Search result

Given query hợp lệ, when search, then kết quả trả summary có entry ID, headword,
entry type, các part of speech, loại/text match, trạng thái còn kết quả và attribution
theo release. Khi mở detail của entry thuộc active release, response trả forms/senses/
pronunciation/source/license hiện có và không bịa Vietnamese translation khi source thiếu.

Search ưu tiên headword exact cùng case, form exact cùng case, headword exact đã
normalize, form exact đã normalize, prefix headword, rồi prefix form. Kết quả có
thứ tự deterministic và dedup theo entry ID; các entry khác ID không bị gộp vì cùng
text. Limit mặc định 20, tối đa 50; `hasMore` được xác định bằng một kết quả unique
bổ sung. Lookup dùng NFKC và full casefold theo clean release.

Trong từng nhóm exact form, xếp sau các match có tag trực tiếp `archaic`, `obsolete`,
`dialectal`, `nonstandard`, `misspelling` hoặc `dated`; cũng áp dụng nếu matching item
có senses và mọi sense đều có ít nhất một tag này. Item không có sense hoặc có bất kỳ
sense không gắn các tag này không bị xếp sau theo rule item. Không suy ra popularity
hay xác nhận standard usage từ việc thiếu tag, không lan nhãn sang item khác hoặc toàn entry.

Detail giữ item/sense references, raw glosses, examples, usage tags, matched qualifier
và pronunciation tags để client trình bày đúng context. Translation candidates giữ
source/scope/link status; `AVAILABLE` không đồng nghĩa đã được curator xác minh.
Cached audio object keys thuộc nội bộ server, không xuất hiện trong detail response.

#### AC-LEX-002 — Unresolved lesson phrase

Given Lesson phát hiện phrase chưa có entry, when enrichment hoàn tất, then Lesson giữ contextual
meaning với trạng thái unresolved và vẫn usable; resolver có thể link sau.

<a id="us-voc-001--on-tu-theo-lich"></a>
### US-VOC-001 — Ôn từ theo lịch

Là learner, tôi muốn lưu lexical unit và review để nhận lịch tiếp theo cùng history cá nhân.

#### AC-VOC-001 — Save/review

Given Lexicon entry và authenticated learner, when save/review , then
card/history/scheduling state thuộc learner được cập nhật và review fact  được publish một lần theo contract.

#### AC-VOC-002 — Scheduler replaceability

Given scheduler implementation thay đổi có migration được duyệt, when application schedules, then
domain port/contract  giữ ổn định và existing learner state được xử lý theo migration plan.
