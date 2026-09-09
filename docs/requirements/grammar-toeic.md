# Yêu cầu Grammar và TOEIC

Nguồn migration: đặc tả Lyreo trước khi tách owner. Import semantics: [Data Pipelines](../DATA_PIPELINES.md). Feature specs:
[Grammar Practice](../features/grammar-practice.md), [TOEIC attempts](../features/toeic-attempts.md).

<a id="grammar"></a>
## Grammar

### FR-GRM-001 — Grammar taxonomy and question bank

Status: inherited. Grammar có topic/subtopic/difficulty, bank sets/questions/memberships và
learner attempts/history; question có answer/explanation/translation/vocabulary metadata khi nguồn có.

### BR-GRM-001 — Dataset-first practice

Status: inherited. Không mặc định generate Grammar questions. Dataset là nguồn chính; AI chỉ hỗ trợ
classify missing taxonomy, detect lesson grammar hoặc giải thích on-demand/fallback.

### BR-GRM-002 — Context annotation không phải practice

Status: inherited. Lesson Grammar annotation có thể link topic và contextual note; Grammar Practice
mới chọn question bank, nhận answer và chấm server-side.

<a id="toeic"></a>
## TOEIC

### FR-TOE-001 — Test and drill content

Status: inherited. TOEIC hỗ trợ test/drill Part 1–7, passages/questions/options/media, filtering,
attempt và history. Content/media được import từ external dataset, không commit vào Git.

### BR-TOE-001 — Server scoring

Status: inherited. Server load answer key và tính raw Listening/Reading correct counts; client chỉ
gửi answers. Scaled score chỉ có khi conversion table được duyệt/provenance rõ; hiện để null theo
[GAP-008](gaps.md#gap-008--toeic-scaled-score-conversion-table).

### FR-TOE-002 — Attempt completion fact

Status: inherited. Successful submit persist attempt/answers/score summary rồi publish fact cho
Analytics/Gamification consumers qua public contract.

### BR-TOE-002 — Media ownership

Status: inherited. Structured data vào PostgreSQL, audio/images vào storage; DB giữ object key.
