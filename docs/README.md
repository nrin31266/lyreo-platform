# Lyreo documentation routes

Mục đích: đây là bảng định tuyến duy nhất để chọn đúng tài liệu, code và test theo công việc. Đọc
[`AGENTS.md`](../AGENTS.md) trước, chọn một hàng bên dưới, rồi chỉ mở section cần thiết. Không đọc
toàn bộ PRD, stories hoặc trang tương thích master cho một thay đổi nhỏ.

## Chọn tài liệu theo task

| Task / từ khóa / code prefix | Owner docs cần đọc | Đọc thêm khi nào | Code / test entrypoints |
|---|---|---|---|
| Theme, dark mode, locale, translation Mobile/Admin; `packages/design-system`, `packages/i18n` | [Frontend convention](architecture/frontend-conventions.md), package/app README | Mở app còn lại chỉ khi đổi shared token/resource contract | `packages/*/src`; `apps/*/src/providers`; `tooling/validate_repo.py` |
| Auth, OIDC, PKCE, JIT provisioning, onboarding; `identity`, `learner` | [Identity/Learner requirements](requirements/identity-learner.md), [stories](requirements/stories/identity-learner.md), [auth/onboarding spec](features/auth-onboarding.md) | Mở config/Keycloak README khi đổi issuer, roles, redirect, session hoặc bootstrap | `modules/identity`; `modules/learner`; `platform/security`; `infra/keycloak`; Core tests |
| Lesson builder, source, activity, enrichment, planner/handler; `modules/lesson` | [Lesson requirements](requirements/lesson.md), [stories](requirements/stories/lesson.md), [Lesson Build](features/lesson-build.md) | Mở Jobs khi đổi lifecycle; AI khi đổi capability; storage/data khi đổi source | `AdminLessonController`; `CreateLessonBuildService`; `LessonBuildPlanner`; `LessonBuildJobHandler`; `LessonBuildPlannerTest` |
| Dictation, score, completion | [Lesson requirements](requirements/lesson.md#dictation), [Lesson stories](requirements/stories/lesson.md#dictation), [Dictation spec](features/dictation.md) | Mở event consumers nếu đổi completion/reward; mở config nếu policy trở thành runtime config | `LessonPracticeController`; `LessonPracticeService`; `DictationScoringPolicy`; module and Core tests |
| Shadowing, recording, speech scoring | [Lesson requirements](requirements/lesson.md#shadowing), [Lesson stories](requirements/stories/lesson.md#shadowing), [Shadowing spec](features/shadowing.md) | Mở AI execution for ASR/alignment/deep judge; gaps for current wiring status | `modules/speech-assessment`; Mobile lesson screen; `SpeechScoringPolicyTest` |
| AI provider, model routing, fallback, credential, audit | [AI requirements](requirements/ai.md), [AI routing spec](features/ai-routing.md), [configuration](CONFIGURATION.md#3-admin-runtime-policy) | Read both Java/FastAPI contracts when HTTP/output/credential contract or consuming job step changes | `modules/ai`; `apps/ai-service/app/schemas.py`; `test_api.py` |
| FastAPI capability/runtime/provider | [AI execution protocol](architecture/ai-execution.md), AI Service README | Mở product feature only when its Java-owned input/schema semantics change | `apps/ai-service/app`; `modules/ai/src/main/java/com/lyreo/ai/infrastructure/FastApiAiExecutionGateway.java` |
| Lesson source preparation, STT/TTS/alignment, YouTube acquisition, prepared JSON export; `tools/lesson-prep` | [AI execution protocol](architecture/ai-execution.md), Lesson Prep Tool README | Mở Core media upload controller khi đổi media contract; mở AI Service khi đổi capability | `tools/lesson-prep/lesson_prep`; `AdminLessonMediaController`; `LessonMediaUploadService`; tool tests |
| Job lease, heartbeat, fencing, cancel, retry, progress | [Background jobs protocol](architecture/background-jobs.md), [NFR](requirements/non-functional.md) | Mở Lesson lifecycle and Admin API/realtime consumers when response/event/state changes | `platform/jobs`; `JobController`; `JdbcLessonBuildStateRepository`; `AdminRealtimeController`; Admin `api.ts`/`Jobs.tsx` |
| Lexicon search, entry, pronunciation, license | [Lexicon/Vocabulary requirements](requirements/lexicon-vocabulary.md), [stories](requirements/stories/lexicon-vocabulary.md), [data pipelines](DATA_PIPELINES.md) | Mở Lesson when changing contextual resolution; importer when changing source shape | `modules/lexicon`; `import_lexicon.py`; importer tests |
| Vocabulary card, SRS, review | [Lexicon/Vocabulary requirements](requirements/lexicon-vocabulary.md), [stories](requirements/stories/lexicon-vocabulary.md), [Vocabulary SRS](features/vocabulary-srs.md) | Mở analytics/gamification consumers for review event changes | `modules/vocabulary`; `StarterSchedulerTest` |
| Grammar/TOEIC practice, scoring | [Grammar/TOEIC requirements](requirements/grammar-toeic.md), [stories](requirements/stories/grammar-toeic.md), [Grammar](features/grammar-practice.md), [TOEIC](features/toeic-attempts.md) | Mở data pipeline when question/media/conversion inputs change | `modules/grammar`; `modules/toeic`; module unit and Core tests |
| Grammar/TOEIC importer, dataset | [Data pipelines](DATA_PIPELINES.md), importer README | Mở requirements/service when imported fields alter learner behavior or scoring | `tools/data-import`; importer tests; Flyway schemas in Core |
| Curriculum path, enrollment, unlock, completion | [Curriculum/Gamification requirements](requirements/curriculum-gamification.md), [stories](requirements/stories/curriculum-gamification.md), [Curriculum progress](features/curriculum-progress.md) | Mở source event contract and consumers when completion facts change | `modules/curriculum`; `libs/contracts`; related listeners |
| Level, XP, Diamond, reward, mission | [Curriculum/Gamification requirements](requirements/curriculum-gamification.md), [stories](requirements/stories/curriculum-gamification.md), [Rewards](features/rewards-analytics.md) | Mở Lesson/Grammar/TOEIC/Vocabulary event producers when reward inputs change | `modules/gamification`; `RewardPolicyTest`; `libs/contracts` |
| Analytics, dashboard, weakness, notification, realtime, Chat | [Analytics/Notification/Chat requirements](requirements/analytics-notification-chat.md), [stories](requirements/stories/analytics-notification-chat.md), [Rewards/analytics](features/rewards-analytics.md) | Mở producing domain and contracts for projection changes; AI spec for Chat capability | `modules/analytics`; `modules/notification`; `modules/chat`; Admin/Mobile consumers |
| “Progress” không rõ owner | [Domain ownership](ARCHITECTURE.md#4-business-module-boundaries), [requirements analysis](requirements/analysis.md) | Xác định Lesson/Curriculum/Analytics/Gamification/UI từ symbol; hỏi nếu vẫn mơ hồ | Search symbol/event before selecting a module |
| Config/env/storage/security | [Configuration](CONFIGURATION.md), [NFR](requirements/non-functional.md), owning `.env.example` | Mở Operations for production impact; feature owner for business policy semantics | `platform/config`; `platform/storage`; `platform/security`; env consumers |
| HTTP API, status, validation, ProblemDetail, correlation ID, Swagger, OpenAPI, security error, rate limit | [HTTP API contract](architecture/http-api-contract.md), [Architecture](ARCHITECTURE.md#16-api-path-conventions) | Mở feature controller/tests khi đổi API payload | `apps/core-service/src/main/java/com/lyreo/platform/web/ApiExceptionHandler.java`; `apps/core-service/src/main/java/com/lyreo/platform/bootstrap/SecurityConfiguration.java` |
| Schema/Flyway/module boundary | [Architecture](ARCHITECTURE.md), [Development](DEVELOPMENT.md#7-database-workflow), [contract](../AGENTS.md#8-database-storage-and-data) | Mở feature owner, consumers and compatibility when schema/API/event changes | `apps/core-service/src/main/resources/db/migration`; `ArchitectureTest.java`; affected module tests |
| Deploy, incident, retention, backup | [Operations](OPERATIONS.md), [Configuration](CONFIGURATION.md) | Mở architecture protocol for job/AI/storage incidents | compose/infra scripts and config validators |
| Product scope, priority, value, roadmap | [PRD](product/prd.md), [Discovery](product/discovery.md), [gaps](requirements/gaps.md) | Mở detailed requirements/spec only for affected area | No implementation status may be inferred from roadmap |
| Viết Chương 3 / AI usage evidence | [Chapter 03](coursework/chapter-03.md), [AI usage log](coursework/ai-usage-log.md) | Mở Discovery/PRD/analysis/story/spec theo đúng subsection; code only for a claim | Evidence links in traceability and repository history |
| Sửa tài liệu, IDs, links, routes | [Documentation guide](documentation.md), [traceability](requirements/traceability.md), [gaps](requirements/gaps.md) | Open owner docs affected; compatibility page only for migrated anchors | `tooling/validate_docs.py`; `tooling/tests/test_validate_docs.py` |

## Các điểm vào theo loại tài liệu

- Product: [Discovery](product/discovery.md), [PRD](product/prd.md).
- Requirements: [analysis](requirements/analysis.md), [NFR](requirements/non-functional.md),
  [stories index](requirements/user-stories.md), [traceability](requirements/traceability.md),
  [gaps](requirements/gaps.md).
- Feature workflows: [`docs/features/`](features/lesson-build.md).
- Technical reference: [Architecture](ARCHITECTURE.md), [Configuration](CONFIGURATION.md),
  [Data Pipelines](DATA_PIPELINES.md), [Development](DEVELOPMENT.md), [Operations](OPERATIONS.md),
  [Tech Choices](TECH_CHOICES.md), [Decisions](DECISIONS.md).
- Compatibility only: [legacy master path](LYREO_PLATFORM_SPEC.md).

Nếu index thiếu route, tìm owner từ code và các heading/ID hiện có, thêm đúng một hàng vào bảng này,
rồi tiếp tục. Không tạo routing manifest hoặc skill thứ hai phải đồng bộ song song.

## Routing smoke simulation — 2026-09-09

Các mô phỏng này kiểm tra khả năng bắt đầu chỉ từ `AGENTS.md` + index. Chúng không phải test result
của feature và không tạo bảng route thứ hai.

1. **UI — đổi locale/theme Mobile.** Chọn Frontend convention, Mobile README và hai shared package
   READMEs; mở Mobile providers/resources. Chỉ mở Admin provider khi semantic token/resource contract
   dùng chung đổi. Không cần PRD, Lesson hoặc Jobs.

2. **Business rule — đổi chấm Dictation.** Chọn Lesson requirement `Dictation`, story/AC và Dictation
   feature; mở `LessonPracticeService`, `DictationScoringPolicy`, repository và Core unit test. Nếu
   completion fact đổi mới mở contracts cùng Curriculum/Gamification/Analytics consumers.

3. **AI/backend — đổi routing/fallback.** Chọn AI requirements, AI Routing feature, Configuration và
   execution protocol; mở Java router/invocation/gateway với FastAPI schemas. Job step chỉ mở khi
   capability input/output hoặc retry semantics tác động consumer.

4. **Jobs — sửa cancel/retry.** Chọn Background Jobs protocol/NFR; mở worker, state transition,
   repository và `JobController`. Vì response/event có consumer, mở thêm Admin `api.ts`/`Jobs.tsx`
   và notification SSE; không chỉ đọc `platform/jobs`.

5. **Data — đổi Grammar/TOEIC importer.** Chọn Data Pipelines + importer README, script/validator và
   importer tests. Chỉ mở Grammar/TOEIC requirements/service/schema khi source fields thay đổi hành
   vi practice/scoring. Không cần AI/Lesson specs.

6. **Coursework — viết mục 3.4.** Chọn Chapter 03 mục 3.4, AI usage log, Stories index và đúng area
   stories đang trình bày. Chỉ mở code/traceability để kiểm chứng claim cụ thể; không đọc toàn master
   compatibility hoặc tất cả technical docs.

Kết quả: cả sáu intent đều chọn được owner, code/test location và điều kiện mở rộng mà không cần
nạp toàn bộ master/PRD/stories. Nếu cấu trúc đổi, chạy lại mô phỏng cùng docs checker.
