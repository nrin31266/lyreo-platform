# Yêu cầu Grammar và TOEIC

Import semantics: [Data Pipelines](../DATA_PIPELINES.md).

<a id="grammar"></a>
## Grammar

### FR-GRM-001 — Grammar taxonomy and question bank

Grammar có topic/subtopic/difficulty, bank sets/questions/memberships và
learner attempts/history; question có answer/explanation/translation/vocabulary metadata khi nguồn có.

### BR-GRM-001 — Dataset-first practice

Không mặc định generate Grammar questions. Dataset là nguồn chính; AI chỉ hỗ trợ
classify missing taxonomy, detect lesson grammar hoặc giải thích on-demand/fallback.

### BR-GRM-002 — Context annotation không phải practice

Lesson Grammar annotation có thể link topic và contextual note; Grammar Practice
mới chọn question bank, nhận answer và chấm server-side.

<a id="toeic"></a>
## TOEIC

### FR-TOE-001 — Test and drill content

TOEIC hỗ trợ test/drill Part 1–7, passages/questions/options/media, filtering,
attempt và history. Content/media được import từ external dataset, không commit vào Git.

### BR-TOE-001 — Server scoring

Server load answer key và tính raw Listening/Reading correct counts; client chỉ
gửi answers. Scaled score chỉ có khi conversion table được duyệt/provenance rõ; hiện để null theo
[OQ-004](open-questions.md#oq-004--toeic-scaled-score-conversion).

### FR-TOE-002 — Attempt completion fact

Successful submit persist attempt/answers/score summary rồi publish fact cho
Analytics/Gamification consumers qua public contract.

### BR-TOE-002 — Media ownership

Structured data vào PostgreSQL, audio/images vào storage; DB giữ object key.

## User Stories & Acceptance Criteria

<a id="us-grm-001--luyen-grammar-tu-question-bank"></a>
### US-GRM-001 — Luyện Grammar từ question bank

Là learner, tôi muốn trả lời question có taxonomy/explanation để luyện đúng topic.

#### AC-GRM-001 — Server-checked answer

Given question tồn tại, when learner submit option , then server
load answer key, persist attempt, publish a grammar answer fact và trả correct/explanation phù hợp; client không quyết định correctness.

#### AC-GRM-002 — Missing/invalid question

Given question hoặc option không hợp lệ, when submit, then không tạo successful attempt/event; AI
không tự generate câu thay thế mặc định.

<a id="us-toe-001--nop-toeic-attempt"></a>
### US-TOE-001 — Nộp TOEIC attempt

Là learner, tôi muốn nộp answers để nhận Listening/Reading result và history chính xác.

#### AC-TOE-001 — Raw scoring

Given active test và answer key, when submit , then server persist answers,
tính raw correct counts và publish a TOEIC completion fact; missing answers được xử lý theo service contract.

#### AC-TOE-002 — Scaled score honesty

Given chưa có conversion table được duyệt, when trả score, then scaled Listening/Reading fields là
null và UI không diễn giải raw counts thành official scaled score (xem [OQ-004](open-questions.md#oq-004--toeic-scaled-score-conversion)).
