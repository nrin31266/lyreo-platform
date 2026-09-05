# LYREO PLATFORM SPEC

> **Phiên bản tài liệu:** 1.0-draft  
> **Ngày chốt kiến trúc nền:** 06/09/2026  
> **Sản phẩm:** **Lyreo**  
> **Linh vật:** **Lyrebird**  
> **Repository dự kiến:** `lyreo-platform`  
> **Java groupId / base package:** `com.lyreo`  
> **Mobile application id:** `com.lyreo.mobile`

---

## 0. Mục đích của tài liệu

Đây là **master specification** của Lyreo. Tài liệu này không chỉ mô tả “app có những màn hình nào”, mà cố định các quyết định nền về sản phẩm, domain, dữ liệu, AI, background jobs, bảo mật, cache, object storage, deployment, Clean Architecture, Spring Modulith và quy tắc dành cho developer/AI coding agent.

Một developer hoặc AI agent mới vào dự án phải có thể đọc tài liệu này và trả lời được:

1. Lyreo đang giải quyết bài toán gì?
2. Feature nào là lõi, feature nào để sau?
3. Module nào sở hữu dữ liệu nào?
4. Khi module A cần module B thì được phép giao tiếp ra sao?
5. FastAPI được làm gì và tuyệt đối không được làm gì?
6. Khi build lesson lâu 1–5 phút thì job được lưu, retry, cancel và resume như thế nào?
7. Audio, ảnh và raw AI JSON nằm ở đâu?
8. Lexicon khác Vocabulary ra sao?
9. “Vocabulary note trong Shadowing” khác “Vocabulary Practice” ra sao?
10. TOEIC/Grammar dataset khoảng vài GB được import thế nào?
11. Admin config, learner preference và session setting override nhau như thế nào?
12. Vì sao Lyreo **không dùng Kafka/Redis ở MVP**?
13. Schema DB được quản lý bởi Flyway, JPA và Hibernate đóng vai trò gì?
14. Những thứ nào là quyết định đã chốt, agent không được tự ý thay đổi?

Tài liệu phản ánh các quyết định mới nhất của dự án. Code cũ trong repository English-Learning trước đây chỉ là **legacy reference**, không phải kiến trúc đích.

---

## 1. Tầm nhìn sản phẩm

Lyreo là nền tảng học tiếng Anh tập trung vào **nghe – nhận biết – luyện tập – nói – ghi nhớ theo ngữ cảnh**, thay vì chỉ là một bộ flashcard hoặc chatbot AI.

Trục sản phẩm chính:

- Học từ **Text**, **Audio**, **YouTube**.
- Dictation: nghe và chép chính tả.
- Shadowing: nghe, bắt chước, ghi âm và nhận feedback.
- Annotation theo câu: nghĩa, từ/cụm đáng học, grammar point, IPA tùy chọn, thought group, tip.
- Vocabulary SRS cá nhân.
- Grammar Practice tận dụng ngân hàng câu hỏi có sẵn.
- TOEIC full test + drill.
- Curriculum: lộ trình Beginner / Intermediate / Advanced, có thể trộn Lesson, Grammar, TOEIC và các loại nội dung khác.
- Dashboard/Progress: tổng hợp hoạt động học và điểm yếu.
- Gamification: Level + Diamond + Mission.
- Chat tutor tiếng Anh, ưu tiên thấp.
- Tương lai: speaking scenario thực tế, AI conversation, peer speaking, recommendation.

### 1.1 Triết lý trải nghiệm

Lyreo không cố “AI hóa mọi nút”. AI chỉ được dùng khi nó tạo giá trị rõ ràng.

Mobile phải ưu tiên:

- ít bước để vào học;
- audio mượt;
- animation có mục đích;
- thiết kế hiện đại, sang và mới;
- không dùng visual cliché kiểu gradient “AI”, robot, hoặc mascot chiếm màn hình;
- Lyrebird xuất hiện tinh tế ở onboarding, empty state, reward, level-up, listening/speaking tips.

### 1.2 Câu định vị nội bộ

> **Lyreo giúp người học biến nội dung tiếng Anh thật thành trải nghiệm nghe, shadowing, dictation, từ vựng, ngữ pháp và lộ trình luyện tập có cấu trúc.**

---

## 2. Brand

### 2.1 Tên

**Lyreo**

Tên sản phẩm đứng độc lập với tên cá nhân của bất kỳ thành viên team nào.

### 2.2 Mascot

**Lyrebird** — loài chim nổi tiếng với khả năng mô phỏng âm thanh. Ý nghĩa thương hiệu rất khớp với:

- listening;
- imitation;
- shadowing;
- speaking;
- noticing pronunciation patterns.

### 2.3 Brand trong code

Mascot là concern của presentation/design, **không** được tạo các class domain kiểu:

- `LyrebirdLessonService`;
- `LyrebirdRewardManager`;
- `LyrebirdAiProcessor`.

Backend chỉ dùng brand ở application metadata, OpenAPI title, runtime service names.

Frontend có thể có:

```ts
export const brand = {
  name: "Lyreo",
  mascot: "Lyrebird",
  tagline: "Listen. Notice. Speak."
};
```

Tagline có thể thay đổi sau; không phải domain invariant.

---

## 3. Actor

| Actor | Trách nhiệm / quyền chính |
|---|---|
| **Learner** | Học lesson, dictation, shadowing, vocabulary SRS, grammar, TOEIC, curriculum, xem progress, nhận diamond/mission, dùng chat tutor. |
| **Admin / Content Manager** | Tạo lesson, cấu hình processing, quản lý curriculum, TOEIC/grammar data, lexicon maintenance, AI providers/model routing, feature/config. |
| **System Worker** | Claim background job, gọi AI service, persist output, retry/cancel, phát internal event. |
| **AI Service** | Thực thi capability AI/model; không sở hữu business workflow. |
| **External Identity Provider (Keycloak)** | Đăng nhập, OIDC, role, token. |
| **External Object Storage (R2)** | Lưu object lớn và raw artifacts. |

### 3.1 Không có Guest Mode ở phase đầu

Lyreo bắt đăng nhập khi vào app. Lý do:

- mọi feature quan trọng gắn với learner identity;
- guest làm phức tạp merge progress;
- vocabulary SRS, curriculum, diamonds, missions, learner preferences đều cần account.

Flow mobile:

```text
Launch
  → Splash
  → Check OIDC session
      → valid → Home
      → invalid → Login/Register
                    → first login → Onboarding
                    → Home
```

---

## 4. Phạm vi tính năng và mức ưu tiên

### 4.1 P0 — Foundation

1. Authentication + Keycloak bootstrap.
2. Admin/runtime configuration.
3. AI provider/model routing.
4. PostgreSQL + Flyway.
5. R2 object storage.
6. Background job engine trên PostgreSQL.
7. Spring Modulith module boundaries/events.
8. Caffeine cache.
9. Bucket4j rate limiting.
10. Resilience4j outbound resilience.
11. Observability/log correlation.
12. Admin lesson builder nền.
13. Mobile learning shell.

### 4.2 P0 — Core Lesson

- Source: Text, Audio, YouTube.
- Canonical transcript/sentence/segment.
- Canonical audio reference.
- ASR khi cần.
- Forced alignment khi cần.
- Conditional build plan.
- Dictation.
- Shadowing.
- Contextual annotations.

### 4.3 P0/P1 — Lexicon

- broad English lexicon;
- Vietnamese translation khi có;
- phrase/phrasal verb/idiom/collocation;
- pronunciation metadata/audio;
- global dictionary search;
- lesson lexical annotation link;
- unresolved entry support;
- lazy enrichment/cache.

### 4.4 P1 — Vocabulary

- save lexical unit;
- SRS card;
- review;
- review history;
- progress;
- FSRS-compatible scheduler abstraction.

### 4.5 P1 — Grammar

- taxonomy;
- question bank import;
- contextual grammar annotation;
- grammar practice;
- explanation from dataset first;
- AI explanation only on-demand/fallback.

### 4.6 P1 — TOEIC

- full mock test;
- drill;
- Part 1–7;
- passage/question/options/media;
- answer/score;
- filtering by difficulty/topic;
- attempt history.

### 4.7 P1 — Curriculum

- Beginner / Intermediate / Advanced;
- section + item;
- references to lesson/grammar/toeic/etc.;
- current position;
- unlock rule;
- admin curriculum builder later.

### 4.8 P1 — Onboarding

Rule-based first:

- current level;
- target;
- daily minutes;
- areas to focus;
- optional preferred content.

Output: initial curriculum/path suggestion.

### 4.9 P2 — Analytics / Progress

- weekly activity;
- skill summary;
- weak topics;
- TOEIC trend;
- vocabulary due;
- recent mistakes;
- support future recommendation.

### 4.10 P2 — Gamification

- Level;
- XP internal calculation;
- Diamond wallet + immutable ledger;
- daily/weekly missions;
- no payment gateway yet;
- purchase transaction type prepared for later.

### 4.11 P3 — Chat

English-focused quick tutor. Included as a module boundary from the start, but implementation priority is low.

### 4.12 P3 — Future Speaking

- real-life scenario: order coffee, invite a friend, travel, etc.;
- AI conversation;
- peer speaking by topic;
- recommendation based on accumulated weakness data.

---

## 5. Nguyên tắc kiến trúc tổng thể

Lyreo dùng:

> **Modular Monolith ở cấp hệ thống + Pragmatic Clean/Hexagonal Architecture bên trong từng module.**

Không chọn microservices cho core domain ở phase này.

### 5.1 Lý do Modular Monolith

- team nhỏ;
- domain còn tiếp tục thay đổi;
- nhiều feature phụ thuộc transaction local;
- debug/deploy đơn giản hơn;
- Spring Modulith hỗ trợ kiểm soát module boundary;
- vẫn có thể tách module thành service nếu sau này chứng minh được nhu cầu.

### 5.2 AI service là service riêng

Python/FastAPI tách riêng vì:

- hệ sinh thái AI/ASR/ML mạnh hơn;
- Qwen3-ASR/ForcedAligner dùng Python;
- dependency GPU khác Java;
- lifecycle/model runtime khác core backend.

Nhưng **tách runtime AI không có nghĩa business logic phải chuyển sang Python**.

---

## 6. Module map

Business modules:

```text
identity
learner
ai
lesson
speech-assessment
lexicon
vocabulary
grammar
toeic
curriculum
gamification
analytics
notification
chat
```

Technical platform:

```text
platform/
  jobs
  storage
  cache
  security
  observability
```

### 6.1 `identity`

- JIT provision `app_user` từ Keycloak subject;
- current user public API;
- app identity mapping;
- không tự implement password/token.

### 6.2 `learner`

- learner profile;
- onboarding;
- persistent learning preferences;
- target/level/focus;
- learner-specific settings.

### 6.3 `ai` (Java)

Đây là AI platform/integration module trong core-service, **khác** Python `ai-service`.

Sở hữu:

- provider config;
- encrypted credentials;
- model registry;
- capability routing;
- invocation audit/cost metadata;
- Java gateway gọi FastAPI.

### 6.4 `lesson`

- lesson content;
- source;
- sentence/segment;
- annotation;
- activity configuration;
- build job;
- lesson practice attempts;
- lesson builder planner;
- business prompts liên quan lesson.

### 6.5 `speech-assessment`

Không phải dictionary pronunciation.

Sở hữu:

- user recording attempt;
- ASR transcript;
- timing/alignment feedback;
- fluency;
- word-level speech result;
- optional deep multimodal judge;
- reusable cho Shadowing, speaking scenario, AI conversation, peer speaking.

### 6.6 `lexicon`

Global dictionary.

- word/phrase/etc.;
- senses;
- Vietnamese translation;
- IPA/pronunciation;
- real audio source;
- source/license;
- form/lemma;
- global search.

### 6.7 `vocabulary`

User-owned SRS.

- card;
- review;
- scheduling state;
- mastery;
- references `lexiconEntryId`.

Không sở hữu dictionary definition.

### 6.8 `grammar`

- topic/subtopic/difficulty;
- question bank;
- practice;
- grammar mastery/history;
- lesson grammar reference/public API.

### 6.9 `toeic`

- tests;
- passages;
- questions;
- media;
- attempts;
- scoring;
- drill.

### 6.10 `curriculum`

- path;
- section;
- item;
- enrollment;
- item progress;
- unlock.

### 6.11 `gamification`

- XP → level;
- diamond wallet;
- immutable diamond ledger;
- reward policy;
- missions.

### 6.12 `analytics`

Read/aggregation module.

- listen to domain events;
- build learner daily activity;
- skill summary;
- weakness view;
- dashboard read model.

Không sở hữu progress chi tiết của module khác.

### 6.13 `notification`

- in-app event;
- realtime progress;
- SSE/WebSocket;
- future push.

### 6.14 `chat`

- conversation/session;
- English tutor behavior;
- LLM prompt application logic;
- P3.

---

## 7. Module ownership và progress

Không tạo một God module `progress`.

Quy tắc:

> **Module sở hữu nghiệp vụ nào thì module đó sở hữu progress/attempt chi tiết của nghiệp vụ đó.**

| Domain | Progress owner |
|---|---|
| Lesson/Dictation/Shadowing | `lesson` + `speech-assessment` |
| Vocabulary SRS | `vocabulary` |
| Grammar practice | `grammar` |
| TOEIC | `toeic` |
| Curriculum | `curriculum` |
| Level/Diamond/Mission | `gamification` |
| Dashboard tổng hợp | `analytics` |

`analytics` không query repository nội bộ của tất cả module. Nó nghe event và xây projection riêng.

---

## 8. Clean Architecture bên trong module

Dependency rule:

```text
API / Adapter In
      ↓
Application
      ↓
Domain

Infrastructure → implements Application/Domain ports
```

Domain không biết:

- Spring MVC;
- JPA/Hibernate;
- PostgreSQL;
- R2;
- FastAPI;
- HTTP;
- Keycloak SDK;
- Caffeine.

### 8.1 Pragmatic, không cực đoan

Không tạo 20 interface cho một CRUD reference table đơn giản.

Clean Architecture nghiêm túc dùng ở những domain có business logic:

- lesson;
- jobs;
- vocabulary scheduling;
- curriculum;
- TOEIC attempts;
- gamification;
- speech assessment;
- AI routing.

Reference data CRUD có thể gọn hơn.

---

## 9. Giao tiếp giữa module

Ưu tiên:

1. Public API/interface được module expose.
2. Spring Modulith domain/application events cho side effect / loose coupling.
3. Không access repository/entity của module khác.

Không được:

```text
lesson -> VocabularyJpaRepository
gamification -> ToeicJpaEntity
analytics -> GrammarRepository internals
```

Được:

```text
LessonActivityCompletedEvent
VocabularyReviewCompletedEvent
ToeicAttemptCompletedEvent
CurriculumItemCompletedEvent
```

Spring Modulith architecture tests phải phát hiện vi phạm dependency.

---

## 10. Vì sao không Kafka

Kafka từng được dùng trong legacy architecture để truyền lesson-generation request/result giữa Java/Python.

Kiến trúc mới không cần Kafka ở MVP vì:

- internal events nằm cùng deployable core-service → Spring Modulith;
- Java → FastAPI là request/response capability call → HTTP;
- durable lesson workflow → PostgreSQL-backed job state;
- raw artifact → R2;
- user progress realtime → notification/SSE/WebSocket.

Thêm Kafka lúc này tạo:

- broker operation;
- topic/versioning;
- consumer offset;
- DLQ;
- serialization;
- monitoring;
- harder local development;

mà không giải quyết thêm một nhu cầu business đã được chứng minh.

**Rule:** không introduce Kafka nếu chưa có architecture decision mới với use case cụ thể.

---

## 11. Vì sao không Redis ở MVP

Legacy dùng Redis cho:

- cancel flag;
- cache;
- có thể rate limiting.

Kiến trúc mới phân trách nhiệm:

| Legacy Redis usage | New owner |
|---|---|
| Job cancel flag | PostgreSQL |
| Job state | PostgreSQL |
| Read cache | Caffeine |
| Rate limit | Bucket4j |
| Internal events | Spring Modulith |
| Raw AI artifact | R2 |

Redis có thể quay lại sau này khi thật sự cần distributed cache/rate limiter ở quy mô nhiều instance. Không cấm công nghệ vĩnh viễn, nhưng không đưa vào mặc định.

---

## 12. Background Job Architecture

Đây là một trong các phần quan trọng nhất của Lyreo.

### 12.1 Yêu cầu

Job phải:

- durable;
- cancel được;
- retry được;
- không mất sau restart;
- resume/skip step đã xong;
- nhiều worker instance không claim trùng;
- có heartbeat/lease;
- quan sát được progress;
- UI nhận realtime update;
- không phụ thuộc Redis/Kafka.

### 12.2 PostgreSQL-backed queue

Table kỹ thuật `background_job`:

```text
id
job_type
owner_module
owner_reference_id
status
priority
current_step
progress_percent
cancel_requested_at
attempt_count
max_attempts
next_retry_at
lease_owner
lease_until
heartbeat_at
created_at
started_at
finished_at
error_code
error_message
config_snapshot_json
```

Status:

```text
QUEUED
RUNNING
RETRY_WAIT
CANCEL_REQUESTED
CANCELLED
SUCCEEDED
FAILED
```

### 12.3 Claim

Worker dùng transaction:

```sql
SELECT id
FROM background_job
WHERE status = 'QUEUED'
  AND (next_retry_at IS NULL OR next_retry_at <= now())
ORDER BY priority DESC, created_at
FOR UPDATE SKIP LOCKED
LIMIT :batch;
```

Sau đó update lease.

`SKIP LOCKED` cho phép nhiều worker cùng poll nhưng mỗi job chỉ có một owner.

### 12.4 Lease + heartbeat

Nếu worker chết:

- `lease_until` hết hạn;
- recovery scheduler đưa job quay về `QUEUED`/`RETRY_WAIT`;
- idempotent step handler kiểm tra step đã DONE hay chưa.

### 12.5 Cancellation

API:

```http
POST /api/jobs/{jobId}/cancel
```

DB:

```text
RUNNING → CANCEL_REQUESTED
```

Worker check cancellation:

- trước mỗi step;
- giữa batch dài;
- sau khi external AI call trả về trước khi commit output.

Nếu FastAPI/GPU không hard-cancel được giữa inference, kết quả sẽ bị discard khi job đã `CANCEL_REQUESTED`.

### 12.6 Progress

API polling fallback:

```http
GET /api/jobs/{jobId}
```

Realtime primary:

```text
JobProgressChangedEvent
  → notification
  → SSE/WebSocket
  → Admin UI
```

---

## 13. Lesson Build Job

Generic background job không được chứa business schema của lesson.

`lesson_build_job` tham chiếu generic job và lưu:

```text
job_id
lesson_id
build_plan_json
provider_snapshot_json
```

`lesson_build_job_step`:

```text
id
job_id
step
status
started_at
finished_at
ai_invocation_id
output_artifact_key
error
```

Các step khả dĩ:

```text
SOURCE_PREPARE
STT
TTS
ALIGNMENT
NLP
LEXICAL_ANALYSIS
GRAMMAR_ANALYSIS
PRONUNCIATION_ENRICHMENT
ACTIVITY_BUILD
FINALIZE
```

Không phải job nào cũng chạy tất cả.

---

## 14. Lesson = Content + Annotation + Activity

Đây là domain rule rất quan trọng.

### 14.1 Content

Nội dung gốc đã chuẩn hóa:

- source;
- transcript;
- sentences;
- segments;
- media reference;
- word timestamps.

### 14.2 Annotation

Kiến thức/hỗ trợ gắn lên content:

- translation;
- lexical units;
- grammar points;
- named entities;
- dictation hints;
- thought groups;
- pronunciation/sentence IPA;
- learning tips.

### 14.3 Activity

Cách learner học content:

- Dictation;
- Shadowing;
- Vocabulary Practice;
- Grammar Practice.

### 14.4 Hai distinction bắt buộc

> **Lexical note trong Shadowing/Dictation ≠ Vocabulary Practice.**

> **Grammar note trong Shadowing/Dictation ≠ Grammar Practice.**

Context note là hỗ trợ để hiểu câu.

Practice là một learning loop có scoring/progress/attempt riêng.

---

## 15. Lesson Builder và option-based processing

Admin form có:

### Source

- Text;
- Audio;
- YouTube.

### Activities

- Dictation;
- Shadowing;
- Vocabulary Practice;
- Grammar Practice.

### Annotations / enrichment

- Translation;
- Important vocabulary/phrases;
- Grammar detection;
- Sentence IPA;
- Thought groups;
- Dictation proper-noun hints;
- Learning tips.

### Preset

- Standard;
- Listening Focus;
- Speaking Focus;
- Rich Lesson;
- Custom.

Preset chỉ pre-fill form. DB lưu **final snapshot**, không chỉ tên preset.

### 15.1 Planner

`LessonBuildPlanner` chuyển source + option thành required capability.

Ví dụ Text + Vocabulary + Grammar, không Dictation/Shadowing:

```text
text
 → tokenize/NLP
 → lexical analysis
 → grammar analysis
 → activity build
```

Không TTS, không align.

Text + Dictation + Shadowing:

```text
text
 → TTS
 → forced align
 → annotations
 → activity build
```

Audio:

```text
audio
 → ASR
 → optional forced align
 → annotations
```

YouTube:

```text
YouTube source
 → processing audio extraction tool
 → Qwen3-ASR
 → ForcedAligner khi cần
 → annotations
```

Learner-facing playback vẫn ưu tiên YouTube official player theo giả định hiện tại của dự án.

---

## 16. Audio strategy

Không cắt mặc định một file audio thành hàng trăm sentence MP3.

Một lesson có canonical media:

```text
lesson.mp3
```

Sentence có:

```text
start_ms
end_ms
audio_clip_object_key NULLABLE
```

React Native:

- seek start;
- play;
- pause at end;
- repeat;
- playback speed.

Nếu sau này cần clip chính xác/offline performance, tạo derived asset on-demand bằng FFmpeg và lưu R2.

---

## 17. Dictation

### 17.1 Trải nghiệm

Trước khi nghe:

- optional proper-noun hint;
- optional number/acronym hint.

Trong lúc làm:

- audio controls;
- input;
- repeat/speed.

Sau submit:

- correct answer;
- diff;
- score;
- translation theo preference;
- lexical notes;
- grammar notes;
- optional IPA.

### 17.2 Proper-noun hint

NER detect:

- PERSON;
- ORG;
- GPE;
- DATE;
- NUMBER;
- CURRENCY;
- ACRONYM.

Ví dụ câu có tên khó spelling, learner có thể được thấy danh sách tên trước khi chép để bài tập đo listening thay vì khả năng đoán tên riêng.

### 17.3 Scoring

Server tính score.

Không tin client gửi score.

Normalization policy có thể config:

- punctuation: STRICT / RELAXED / IGNORE;
- capitalization;
- common apostrophe normalization;
- number policy.

---

## 18. Shadowing

### 18.1 Trải nghiệm hiện đại

Tập trung vào:

- reference audio;
- karaoke word highlighting;
- thought groups;
- content word emphasis;
- user recording;
- ASR/alignment;
- word accuracy;
- timing;
- fluency;
- optional deep judge.

Không bắt buộc hiển thị full-sentence IPA.

### 18.2 Context note sau attempt

Có thể hiện:

- meaning;
- useful phrases;
- grammar;
- IPA;
- learning tip.

Default UX nên thiên về `AFTER_ATTEMPT` để màn hình lúc luyện sạch.

### 18.3 Speech assessment

Shadowing recording được gửi vào `speech-assessment`.

Server, không client, tạo final scoring.

---

## 19. Sentence IPA / Pronunciation Annotation

IPA cả câu là optional.

Admin policy:

```text
DISABLED
ON_DEMAND
PREGENERATE
```

Learner display:

```text
OFF
TAP_TO_SHOW
AFTER_ATTEMPT
ALWAYS
```

Cache key phải bao gồm:

- sentence text hash;
- accent;
- provider;
- model/version.

Data model:

```text
sentence_id
accent
ipa
provider
model
text_hash
generated_at
```

Nếu sentence text thay đổi → annotation cũ invalid.

---

## 20. Config hierarchy

Một app giàu AI/config không nên fix cứng mọi thứ.

Nhưng không được biến domain invariant thành toggle.

### 20.1 Tầng config

```text
Deployment capability/secrets
        ↓
Admin runtime policy
        ↓
Lesson build snapshot
        ↓
Learner persistent preference
        ↓
Current session override
```

### 20.2 Deployment config

`.env`, secret manager:

- DB URL/password;
- Keycloak server credentials;
- R2 credentials;
- master encryption key;
- internal service token.

Không sửa trên Admin UI.

### 20.3 Admin runtime config

DB:

- AI provider;
- model routing;
- lesson processing defaults;
- pronunciation enrichment;
- feature flag;
- reward policy;
- limits.

### 20.4 Learner preference

DB, cross-device:

- preferred accent;
- translation display;
- IPA display;
- thought-group;
- word highlight;
- dictation hints;
- playback defaults.

### 20.5 Session override

Client/local:

- current playback speed;
- current loop;
- currently expanded annotation.

Nếu learner chọn “Always use this”, mới persist.

---

## 21. AI architecture

### 21.1 Java là orchestrator

Java quyết định:

- tại sao gọi model;
- lúc nào gọi;
- prompt business;
- JSON schema expectation;
- retry/fallback policy;
- persist output;
- state transition.

### 21.2 FastAPI là capability service

FastAPI chỉ thực thi:

- STT;
- align;
- TTS;
- NLP;
- generic LLM generate;
- multimodal judge.

Không có:

- Lesson state machine;
- Curriculum rule;
- Diamond logic;
- Vocabulary SRS;
- TOEIC scoring;
- business prompt orchestration.

### 21.3 Endpoint định hướng capability

```text
POST /v1/stt
POST /v1/align
POST /v1/tts
POST /v1/nlp/analyze
POST /v1/llm/generate
POST /v1/multimodal/judge
GET  /health
```

Không thiết kế FastAPI endpoint theo business name kiểu `/generate-lesson`.

---

## 22. AI provider/model routing

Capabilities:

```text
STT
ALIGNMENT
GENERAL_LLM
REASONING_LLM
TTS
PRONUNCIATION_JUDGE
NLP
```

Default:

| Capability | Primary | Fallback |
|---|---|---|
| STT | Qwen3-ASR | Groq Whisper |
| ALIGNMENT | Qwen3-ForcedAligner | — |
| GENERAL_LLM | Groq | Gemini |
| REASONING_LLM | DeepSeek | Groq/Gemini |
| TTS | Gemini | configurable later |
| PRONUNCIATION_JUDGE | Gemini multimodal | configurable later |

Model name là DB string/config, không Java enum.

Admin có thể chọn:

```text
Provider
Model
Enabled
Priority
Fallback
Rate/cost policy
```

---

## 23. AI invocation audit

Một lesson build có thể gọi nhiều model.

`ai_invocation`:

```text
id
capability
provider
model
status
started_at
finished_at
latency_ms
input_tokens
output_tokens
estimated_cost
request_hash
raw_request_artifact_key
raw_response_artifact_key
error_code
```

Dùng cho:

- debug;
- cost analytics;
- provider comparison;
- tracing;
- retry idempotency.

Không lưu secret.

---

## 24. API key và secret

Admin Web có màn:

```text
Settings
  → AI Providers
  → Model Routing
```

Provider card:

```text
Groq                    Connected
API key                 gsk_••••••••91F2
Base URL                ...
[Test connection]
[Replace key]
```

Server:

- `MASTER_ENCRYPTION_KEY` nằm env;
- API key encrypt AES-GCM trước khi lưu DB;
- GET chỉ trả `configured`, `last4`, `status`;
- không bao giờ trả plaintext key về browser.

Frontend env tuyệt đối không chứa Gemini/Groq/DeepSeek secret.

---

## 25. Qwen3-ASR / ForcedAligner

Qwen3-ASR là Python package/model runtime, Docker chỉ là cách đóng gói/deploy.

Official Qwen3-ASR project hiện có:

- Qwen3-ASR-0.6B;
- Qwen3-ASR-1.7B;
- Qwen3-ForcedAligner-0.6B;
- Transformers backend;
- vLLM backend;
- timestamp output khi kết hợp forced aligner;
- Python 3.12 recommended.

Lyreo AI service hỗ trợ runtime mode:

```text
mock
local
remote
```

### Dev không GPU

`mock` hoặc route STT sang Groq.

### Dev có GPU

load qwen-asr local.

### Production GPU

Docker GPU/vLLM có thể dùng để reproducible/scale.

MVP không bắt buộc tách Qwen thành một microservice riêng.

---

## 26. Cache

### 26.1 Caffeine

Local read cache:

- Lexicon hot entries;
- Grammar taxonomy;
- Curriculum metadata;
- AI routing config;
- runtime admin config.

Cache **không** là source of truth.

Restart mất cache là bình thường.

Admin config update:

```text
DB commit
 → ConfigChangedEvent
 → evict Caffeine
```

### 26.2 Không cache job state trong Caffeine

Job/cancel là durable state → PostgreSQL.

---

## 27. Rate limit và resilience

### 27.1 Bucket4j

Inbound API protection:

- login-sensitive endpoints;
- lesson build;
- chat;
- pronunciation judge;
- expensive AI trigger.

MVP local backend bằng Caffeine/in-memory.

Khi multi-instance lớn hơn có thể chuyển Bucket4j PostgreSQL/distributed backend.

### 27.2 Resilience4j

Outbound dependency protection:

- Retry;
- CircuitBreaker;
- TimeLimiter;
- Bulkhead;
- provider-specific RateLimiter.

Ví dụ Qwen GPU concurrency → Bulkhead.

Groq quota → provider rate limiter.

---

## 28. Object storage: Cloudflare R2

Production: Cloudflare R2 Standard.

Lý do:

- S3-compatible;
- phù hợp audio/raw JSON/images;
- egress Internet hiện miễn phí;
- free tier hiện tại phù hợp team/dev;
- vendor portability tốt qua S3 abstraction.

Không ghi “forever free” trong architecture contract vì pricing có thể thay đổi.

### 28.1 Storage abstraction

Java:

```java
public interface ObjectStoragePort {
    StoredObject put(...);
    InputStream get(...);
    void delete(...);
    URI createDownloadUrl(...);
}
```

Infrastructure S3 adapter kết nối:

- R2 production;
- optional MinIO;
- future AWS S3.

### 28.2 DB lưu object key, không signed URL

Đúng:

```text
lessons/{id}/audio/final.mp3
```

Sai:

```text
https://...signature=...&expires=...
```

### 28.3 R2 object layout

```text
lessons/{lessonId}/
  source/
  audio/
  raw/
  alignment/

speech-attempts/{learnerId}/{attemptId}/
  input/
  normalized/
  raw/

lexicon/audio/{entryId}/
toeic/{year}/{testId}/
jobs/{jobId}/
```

---

## 29. Raw AI JSON vs normalized DB

Rule:

> PostgreSQL = queryable/normalized source of truth.  
> R2 = large/immutable/debug artifacts.

Ví dụ Qwen raw output:

R2:

```text
jobs/{jobId}/alignment/raw.json
```

PostgreSQL:

```text
lesson_sentence
lesson_word_timestamp
lesson_annotation
```

Không dùng raw JSON trên R2 làm workflow checkpoint như legacy.

---

## 30. Lexicon

Lexicon là global dictionary, không phải lesson-specific vocabulary.

App có nút dictionary toàn cục để learner tra bất kỳ từ/cụm nào.

### 30.1 Entry type

```text
WORD
PHRASE
PHRASAL_VERB
IDIOM
COLLOCATION
```

### 30.2 Core entities

```text
LexiconEntry
LexiconForm
LexiconSense
LexiconPronunciation
DataSource
DataLicense
```

`LexiconForm` map inflection:

```text
went → go
children → child
postponed → postpone
```

### 30.3 Seed strategy

Không generate dictionary bằng LLM.

Nguồn chính:

- English Wiktionary / Wiktextract / Kaikki structured data;
- Vietnamese Wiktionary;
- translation/pronunciation data khi có.

Mục tiêu: lấy broad useful coverage, sau đó dùng corpus Lyreo để rank relevance.

### 30.4 Corpus relevance

Nguồn:

- TOEIC data;
- Curriculum;
- seed lessons;
- runtime lessons.

Dùng để:

- đếm tần suất;
- đánh relevance;
- ưu tiên cleanup/enrichment;
- không giới hạn global dictionary chỉ vào corpus.

### 30.5 Vietnamese translation status

```text
AVAILABLE
MISSING
NEEDS_REVIEW
AI_GENERATED
VERIFIED
```

Nếu thiếu tiếng Việt, seed vẫn hoàn thành. Không bắt buộc gọi AI.

### 30.6 Lesson lexical annotation

AI/NLP detect:

```text
take into account
make up your mind
```

Nếu Lexicon có:

```text
lexicon_entry_id = ...
```

Nếu chưa có:

```text
lexicon_entry_id = null
surface_text
context_meaning_vi
resolution_status = UNRESOLVED
```

Lesson vẫn sử dụng bình thường.

Lazy resolver có thể bổ sung Lexicon sau.

---

## 31. Lexicon pronunciation audio

Ưu tiên pronunciation recording thật từ Wiktionary/Wikimedia khi có.

Không pre-generate hàng chục nghìn từ US/UK bằng TTS.

Strategy:

```text
external audio URL
  → first use
  → fetch/normalize
  → cache R2
  → store object key
```

Fallback:

- device TTS;
- server TTS on-demand;
- tùy config.

---

## 32. License/source model

Không copy license string vào mọi record.

```text
data_license
data_source
```

Sense/audio có `source_id`.

Một Lexicon entry có thể phối hợp:

- IPA từ source A;
- Vietnamese meaning từ source B;
- audio từ source C.

Attribution được giữ theo source.

---

## 33. Vocabulary

Vocabulary = **personal learning system**, không phải dictionary.

Core:

```text
VocabularyCard
VocabularyReview
VocabularyProgress
```

Card reference:

```text
learner_id
lexicon_entry_id
context_reference optional
```

### 33.1 SRS

Dùng `SpacedRepetitionScheduler` port.

Default direction: FSRS.

Không khóa app vào implementation ngay trong domain để có thể benchmark/đổi scheduler.

Review event được analytics/gamification consume.

---

## 34. Grammar

Nguồn initial quan trọng là `dautoeic/grammar_data`.

Data hiện có:

- grammar topics;
- subtopics;
- difficulty levels;
- bank sets;
- questions;
- memberships;
- manifest;
- schema reference.

Question data có thể có:

- text;
- A/B/C/D;
- correct answer;
- explanation VI;
- translation VI;
- option translation;
- vocabulary;
- difficulty;
- original test metadata;
- `prefer_ai_explanation`.

### 34.1 Không generate grammar question mặc định

Dữ liệu hiện có là tài sản chính.

AI dùng cho:

- classify missing topic/subtopic;
- detect grammar in lesson;
- explain on demand khi dataset thiếu/learner hỏi.

### 34.2 Grammar contextual annotation

Lesson:

```text
LessonGrammarAnnotation
  → grammarTopicId nullable
  → surface/structure
  → contextual note
```

Grammar Practice mới kéo question bank.

---

## 35. TOEIC dataset

`dautoeic` hiện có:

### `grammar_data`

- `grammar_topics.*`
- `grammar_subtopics.*`
- `grammar_difficulty_levels.*`
- `grammar_bank_sets.*`
- `grammar_questions.*`
- `grammar_question_memberships.*`
- manifest/schema.

### `mock_test_data`

- all mock tests;
- passages;
- questions;
- difficulty stats;
- tests 2019–2026;
- mỗi test có `audio`, `data`, `images`.

Dataset media lớn không commit vào Lyreo repo.

Config:

```text
DAUTOEIC_DATA_DIR=/absolute/path/to/dautoeic
```

---

## 36. Data import

Không dùng Flyway để nhét hàng GB data.

```text
tools/data-import/
  common/
  lexicon/
  grammar/
  toeic/
```

### 36.1 Dataset import record

```text
dataset
version
checksum
status
started_at
completed_at
records_imported
error
```

Importer:

- validate;
- dry-run;
- idempotent;
- checksum;
- batch/bulk insert;
- resume/re-run rõ ràng.

### 36.2 TOEIC

Structured → PostgreSQL.

Audio/images → R2.

DB giữ object key.

### 36.3 Lexicon

Stream JSONL, không load full dump vào RAM.

---

## 37. Flyway, JPA, Hibernate

### JPA / Jakarta Persistence

Specification/API cho ORM mapping.

### Hibernate

JPA implementation, chịu trách nhiệm:

- entity mapping;
- SELECT/INSERT/UPDATE;
- dirty checking;
- persistence context;
- relationships/lazy loading.

### Flyway

Schema version/migration owner.

Config:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
```

Hibernate **không được tự sửa schema**.

Workflow:

```text
Developer sửa Entity
 → tạo Flyway migration
 → app start
 → Flyway migrate
 → Hibernate validate
```

Flyway seed chỉ dùng cho reference data nhỏ, không big dataset.

---

## 38. Curriculum

Curriculum không copy content.

```text
CurriculumPath
  → CurriculumSection
      → CurriculumItem
```

Item:

```text
content_type
content_reference_id
position
required
unlock_rule
```

Types:

```text
LESSON
GRAMMAR_PRACTICE
TOEIC_DRILL
TOEIC_TEST
VOCABULARY_REVIEW
SPEAKING_SCENARIO (future)
```

### 38.1 Progress

`curriculum` sở hữu:

```text
curriculum_enrollment
curriculum_item_progress
```

Khi Lesson completed:

```text
LessonCompletedEvent
 → curriculum
 → find matching item
 → mark completed
 → unlock next
```

---

## 39. Analytics / Mobile Progress

Mobile không cần “enterprise dashboard”.

Home/Progress tập trung:

```text
Level + Diamond
Continue Learning
Today/This Week
Vocabulary Due
Recent Weakness
TOEIC snapshot
```

Analytics projection:

```text
learner_daily_activity
learner_skill_summary
learner_weakness
```

Initial weakness deterministic:

- grammar wrong → topic counters;
- vocabulary Again → weakness;
- TOEIC wrong part → skill weakness;
- shadowing mismatch/timing → speech weakness.

AI recommendation để sau khi có đủ dữ liệu.

---

## 40. Gamification

Chỉ hai concept UI chính:

- Level;
- Diamond.

XP là internal score để tính level, không phải currency thứ hai.

### 40.1 Diamond ledger

Không chỉ lưu:

```text
user.diamond = 500
```

Dùng immutable transaction:

```text
DiamondWallet
DiamondTransaction
```

Types:

```text
LESSON_REWARD
MISSION_REWARD
STREAK_REWARD
ADMIN_ADJUSTMENT
PURCHASE
AI_FEATURE_SPEND
```

Payment gateway chưa implement.

### 40.2 Anti-farm

Reward policy server-side:

- first completion;
- daily cap;
- min score;
- idempotency key;
- no reward based on client-submitted score.

---

## 41. Mission

Mission thuộc `gamification`, chưa cần module riêng.

```text
MissionDefinition
MissionProgress
MissionCompletion
RewardPolicy
```

Ví dụ:

```text
Học 1 lesson
Shadowing 5 câu
Ôn 10 từ
Hoàn thành TOEIC drill
```

Gamification listen events từ module khác, không query internals.

---

## 42. Authentication / Keycloak

Realm:

```text
lyreo
```

Roles:

```text
ADMIN
LEARNER
```

Clients:

```text
lyreo-admin-web
lyreo-mobile
lyreo-core-service
```

Admin/mobile:

- OIDC Authorization Code;
- PKCE;
- public client.

Core service resource server + service account khi cần Keycloak Admin API.

### 42.1 Bootstrap

Repo phải có realm import + shell scripts để developer không phải click UI tạo lại.

### 42.2 JIT provisioning

JWT `sub`:

```text
app_user exists?
  yes → continue
  no → create app_user
```

Roles vẫn lấy từ JWT/Keycloak, không duplicate không cần thiết trong DB.

---

## 43. Environment files

Không có một root `.env` chứa tất cả.

Mỗi app/service có `.env.example` riêng:

```text
apps/core-service/.env.example
apps/admin-web/.env.example
apps/mobile/.env.example
services/ai-service/.env.example
infra/keycloak/.env.example
infra/docker/.env.example
```

Biến khó phải có comment ngay trong file.

Frontend public env không được chứa secret.

---

## 44. Development topology

Dev container chủ yếu chạy dependency; code chạy local để debug nhanh.

```text
Local:
  Spring Boot
  FastAPI
  Admin Vite
  Expo

Docker dev:
  PostgreSQL
  Keycloak

External:
  R2 dev bucket
```

Optional MinIO profile có thể thêm sau.

Không Kafka, không Redis.

---

## 45. Production/self-host topology

```text
Docker/host:
  core-service
  ai-service
  admin-web
  PostgreSQL
  Keycloak
  reverse proxy

External:
  Cloudflare R2

Optional:
  GPU runtime / vLLM host
```

Mobile build/deploy riêng.

---

## 46. Frontend

### 46.1 Mobile

Khuyến nghị 09/2026:

- Expo SDK 57;
- React Native 0.86;
- Expo Router;
- Development Build / Prebuild;
- New Architecture.

Không phụ thuộc Expo Go-only vì app có audio recording/native concerns.

### 46.2 Admin Web

- React;
- Vite;
- TypeScript;
- routing;
- typed API client;
- design system.

### 46.3 Design system

Token:

- colors;
- typography;
- spacing;
- radius;
- elevation;
- motion.

Phase đầu chỉ default theme nhưng code không hard-code style khắp component.

---

## 47. Lyrebird trong UI

Mascot nên có mood/illustration:

- default;
- listening;
- speaking;
- thinking;
- celebrating;
- empty state.

Không commit/ship artwork giả như final asset trong starter. Codebase chỉ chuẩn bị contract và placeholder rõ ràng.

---

## 48. Admin Settings IA

```text
Settings
  AI Providers
  Model Routing
  Lesson Processing
  Shadowing
  Dictation
  Vocabulary
  Grammar
  Pronunciation
  Object Storage
  Gamification
  Curriculum
  Features
```

Không gom 50 toggle vào một page.

---

## 49. Learner Settings IA

```text
Learning Preferences
  General
  Audio & Playback
  Shadowing
  Dictation
  Vocabulary & Grammar
  Pronunciation
  Notifications
  Accessibility
```

Ví dụ:

```text
Translation           AFTER_ATTEMPT
Sentence IPA          TAP_TO_SHOW
Vocabulary notes      AFTER_ATTEMPT
Grammar notes         AFTER_ATTEMPT
Thought groups        ON
Karaoke highlighting  ON
```

---

## 50. Notification / realtime

Primary use:

- job progress;
- build completed/failed;
- mission completed;
- level-up;
- system notifications.

MVP có thể dùng SSE cho server→client đơn giản. WebSocket chỉ cần khi thực sự có bidirectional realtime use case.

Chọn transport có thể thay đổi mà không ảnh hưởng domain event.

---

## 51. Chat

`chat` là P3 nhưng module boundary tồn tại.

Rule:

- English-learning use case;
- prompt business nằm Java;
- module `ai` chọn provider/model;
- chat không được truy cập provider credential trực tiếp.

---

## 52. Future speaking scenario

Không implement đầy đủ ở MVP nhưng architecture phải cho phép reuse:

- `speech-assessment`;
- lesson/context annotation;
- AI TTS/STT;
- chat conversation;
- future realtime transport.

Scenario có thể trở thành CurriculumItem type mà không redesign curriculum.

---

## 53. Security rules

1. Không trust client score.
2. Không plaintext API key.
3. Không signed URL trong DB.
4. Private speech recording dùng signed access.
5. User can only access own attempt/progress.
6. Admin-only settings/content builder.
7. Internal FastAPI protected bằng internal auth/token/mTLS later.
8. Log không được chứa provider secret.
9. Raw prompt/audio retention policy phải cấu hình.
10. Dev-only endpoints tuyệt đối tắt ở production.

---

## 54. Observability

MVP cần:

- correlation/request id;
- job id;
- AI invocation id;
- structured log;
- latency;
- job failure count;
- provider error count;
- health endpoints.

Không cần full distributed tracing stack ngay ngày đầu, nhưng correlation identifiers phải có từ code foundation.

---

## 55. Data retention

Các record cần cân nhắc:

- speech recordings;
- raw AI request/response;
- old job artifacts;
- temporary upload;
- TOEIC source media;
- cached lexicon audio.

Retention nên là config/maintenance job, không hard-code delete tùy tiện.

---

## 56. Error handling

API error envelope thống nhất:

```json
{
  "code": "LESSON_BUILD_FAILED",
  "message": "...",
  "correlationId": "...",
  "details": {}
}
```

Job error:

- stable error code;
- human-readable summary;
- raw provider detail artifact/log;
- retryable boolean.

Không expose raw provider error/stack trace cho mobile.

---

## 57. API versioning

Base:

```text
/api/v1
```

Internal:

```text
/internal
```

FastAPI capability:

```text
/v1/*
```

Không version bằng tên provider.

---

## 58. Legacy migration map

| Legacy | Lyreo |
|---|---|
| Kafka lesson consumer | PostgreSQL background job + Java orchestrator |
| Redis cancel flag | PostgreSQL cancel status |
| Redis read cache | Caffeine |
| Redis rate limiter | Bucket4j |
| Python lesson business pipeline | Java lesson application |
| AI metadata JSON checkpoint | PostgreSQL job/step |
| Raw AI JSON | R2 |
| Kafka progress event | Spring Modulith + notification |
| Client supplied score | Server scoring |
| Reward inside lesson service | Gamification event consumer |
| JSONB progress map | Normalized attempts/progress |
| Per-word AI dictionary generation | Broad Lexicon seed + lazy enrichment |
| Pre-generated synthetic word audio | Real dictionary audio + lazy R2 cache |

---

## 59. Technology baseline — September 2026

Target baseline dùng bản stable/production-ready tại thời điểm chốt:

| Technology | Baseline |
|---|---|
| Java | 25 LTS target |
| Spring Boot | 4.1.1 |
| Spring Modulith | 2.1.1 |
| PostgreSQL | 18.x (18.6 current at writing) |
| Keycloak | 26.7.3 |
| Python | 3.12 |
| FastAPI | 0.141.x |
| Qwen3-ASR | 0.6B/1.7B family |
| Qwen3-ForcedAligner | 0.6B |
| React Native | 0.86 via Expo SDK 57 |
| Expo | SDK 57 |
| Cloudflare R2 | S3-compatible Standard |
| Bucket4j | 8.19.x |
| Resilience4j | 2.4.x |
| Caffeine | Spring Cache integration |

Versions trong starter có thể pin patch cụ thể. Khi nâng version phải chạy full test và cập nhật TECH_CHOICES.

---

## 60. Những thứ agent được tự quyết

Agent được:

- thêm test;
- refactor nội bộ không phá public contract;
- thêm mapper/helper;
- fix bug;
- thêm migration cho schema change;
- thêm adapter implementation cho port đã chốt;
- cải thiện UI trong design system;
- thêm observability;
- thêm importer validation.

---

## 61. Những thứ agent không được tự ý làm

Không có architecture decision mới thì **không được**:

1. introduce Kafka;
2. introduce Redis;
3. cho Hibernate `ddl-auto=update/create`;
4. chuyển business orchestration vào FastAPI;
5. chuyển business prompt sang Python;
6. cho module A access repository/entity internals module B;
7. biến R2 raw JSON thành workflow source of truth;
8. lưu provider key plaintext;
9. lưu signed R2 URL;
10. tin client score/reward amount;
11. tạo một God progress module;
12. gộp Lexicon và Vocabulary;
13. coi contextual lexical note là Vocabulary Practice;
14. hard-code model names khắp code;
15. hard-code UI colors trong feature component;
16. commit TOEIC/lexicon raw dump nhiều GB vào Git;
17. copy legacy Kafka pipeline sang app mới.

---

## 62. Roadmap đề xuất

### Phase 0 — Platform

- repo/bootstrap;
- Keycloak;
- DB/Flyway;
- jobs;
- R2;
- AI service;
- admin provider settings;
- module boundaries.

### Phase 1 — Lesson MVP

- Text;
- Audio;
- build planner;
- TTS/STT/alignment;
- sentence model;
- annotations;
- Dictation;
- Shadowing;
- admin builder;
- mobile player/practice.

### Phase 2 — Learning data

- Lexicon importer;
- global dictionary;
- Vocabulary SRS;
- Grammar importer/practice.

### Phase 3 — TOEIC + Curriculum

- TOEIC importer;
- test/drill;
- learning paths;
- onboarding.

### Phase 4 — Retention

- Analytics/Progress;
- Level;
- Diamond;
- Mission;
- notification.

### Phase 5 — Intelligence

- recommendation;
- chat;
- advanced pronunciation judge;
- richer lesson annotation.

### Phase 6 — Speaking expansion

- real-life scenario;
- AI conversation;
- peer speaking.

---

## 63. Definition of Done cho một feature

Một feature backend chưa “done” nếu thiếu:

- domain/application behavior;
- authorization;
- migration nếu có schema;
- server validation;
- test;
- event/public API contract;
- error mapping;
- config documentation;
- observability cơ bản;
- no cross-module violation.

Một feature AI chưa “done” nếu:

- provider bị hard-code;
- không có timeout/cancellation policy;
- không audit invocation;
- raw artifact/normalized output không rõ;
- retry không idempotent.

Một feature mobile chưa “done” nếu:

- loading/error/empty chưa xử lý;
- accessibility bỏ qua hoàn toàn;
- style hard-code ngoài token;
- network failure không có state;
- learner preference không được tôn trọng.

---

## 64. Open decisions có chủ đích

Các điểm chưa cần khóa ngay:

- exact FSRS library/implementation;
- final reverse proxy;
- final production hosting provider;
- payment gateway;
- push notification provider;
- exact deep pronunciation scoring formula;
- final recommendation algorithm;
- whether SSE or WebSocket becomes long-term standard;
- exact NotebookLM MCP integration;
- final mascot artwork;
- final custom theme import format.

Không được dùng “open decision” làm lý do tự ý introduce dependency lớn.

---

## 65. MCP / NotebookLM

NotebookLM/MCP chỉ là developer tooling.

Mục đích tiềm năng:

- đưa SRS/architecture/research vào notebook;
- Codex/Claude/Cursor query project knowledge;
- hỗ trợ onboarding developer;
- không runtime dependency.

Community NotebookLM MCP có thể dùng unofficial API; phải xem nó là optional experiment, không production contract.

Expo MCP/tooling có thể dùng cho frontend development nếu phù hợp.

---

## 66. Official references kiểm tra tại thời điểm viết

- Spring Boot 4.1.1 release: https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/
- Spring Modulith 2.1.1 release: https://spring.io/blog/2026/08/26/spring-modulith-2-2-m1-2-1-1-2-0-8-and-1-4-13-released/
- Spring Modulith event docs: https://docs.spring.io/spring-modulith/reference/events.html
- Keycloak 26.7.3: https://www.keycloak.org/2026/08/keycloak-2673-released
- PostgreSQL 18.6: https://www.postgresql.org/docs/release/18.6/
- Cloudflare R2 pricing: https://developers.cloudflare.com/r2/pricing/
- Cloudflare R2 S3 API: https://developers.cloudflare.com/r2/api/s3/
- Qwen3-ASR official repo: https://github.com/QwenLM/Qwen3-ASR
- FastAPI: https://fastapi.tiangolo.com/
- Expo SDK 57: https://expo.dev/changelog/sdk-57
- Expo SDK reference: https://docs.expo.dev/versions/latest/
- Bucket4j: https://github.com/bucket4j/bucket4j
- Resilience4j: https://resilience4j.readme.io/
- Wiktextract: https://github.com/tatuylonen/wiktextract
- Kaikki: https://kaikki.org/

---

## 67. Kết luận kiến trúc

Lyreo không được xây như một collection các AI API.

Xương sống của hệ thống là:

```text
Domain có ownership rõ
        +
Modular Monolith
        +
Clean Architecture
        +
Durable PostgreSQL jobs
        +
FastAPI AI capability service
        +
R2 artifacts
        +
Event-driven module integration
        +
Configurable learning experience
```

AI/model có thể đổi.

Provider có thể đổi.

UI theme có thể đổi.

Curriculum có thể thêm content type.

Nhưng domain boundary, source-of-truth rule và nguyên tắc ownership phải giữ cho hệ thống không quay lại trạng thái chắp vá của legacy app.

**Lyreo phải dễ phát triển tiếp bởi cả developer và AI coding agent mà không cần “nhớ lịch sử chat” mới hiểu đúng hệ thống.**

