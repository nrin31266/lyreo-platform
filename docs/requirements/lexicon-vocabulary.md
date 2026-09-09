# Yêu cầu Lexicon và Vocabulary

Nguồn migration: đặc tả Lyreo trước khi tách owner. Data contract: [Data Pipelines](../DATA_PIPELINES.md); Vocabulary flow:
[Vocabulary SRS](../features/vocabulary-srs.md).

### FR-LEX-001 — Global dictionary

Status: inherited. Lexicon hỗ trợ search word, phrase, phrasal verb, idiom và collocation; entries có
forms/lemma, senses, pronunciation, source/license và Vietnamese translation status.

### BR-LEX-001 — Lexicon không phải Vocabulary

Status: inherited. Lexicon là global knowledge; Vocabulary là learner-owned SRS card tham chiếu
`lexiconEntryId`. Vocabulary không sở hữu dictionary definition.

### FR-LEX-002 — Contextual lexical resolution

Status: inherited. Lesson detector có thể link Lexicon entry; nếu chưa có, giữ surface/context
meaning và `UNRESOLVED` để Lesson vẫn dùng được, rồi lazy-resolve sau.

### BR-LEX-002 — Provenance and coverage

Status: inherited. Dictionary seed dùng structured Wiktionary/Wiktextract/Kaikki sources, giữ
attribution theo source/license. Corpus Lyreo xếp relevance, không giới hạn dictionary vào corpus;
LLM không generate toàn bộ dictionary.

### FR-LEX-003 — Pronunciation audio strategy

Status: inherited. Ưu tiên recording thật; first use có thể fetch/normalize/cache object storage.
Device/server TTS là fallback on-demand, không pre-generate hàng chục nghìn từ.

### FR-VOC-001 — Save and review card

Status: inherited. Learner lưu lexical unit cùng optional context, review và nhận next scheduling
state/history/mastery.

### BR-VOC-001 — Scheduler abstraction

Status: inherited. Domain dùng `SpacedRepetitionScheduler` port để có thể benchmark/migrate.
Implementation hiện tại là starter FSRS-compatible, không được mô tả là FSRS đầy đủ.

### BR-VOC-002 — Review fact

Status: inherited. Review state thuộc Vocabulary; successful review fact có thể được Analytics và
Gamification consume qua public event, không truy cập repository nội bộ.
