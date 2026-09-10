# LYREO PLATFORM SPEC — compatibility index

> Từ 2026-09-09, file này chỉ bảo toàn đường dẫn và headings/anchors của master spec cũ. Nội dung
> normative đã được chuyển tới owner docs; bắt đầu từ [docs routes](README.md). Git history giữ bản
> master trước migration. Không chỉnh requirement mới tại đây.

## 0. Mục đích của tài liệu

Owner mới: [Documentation routes](README.md) và [documentation conventions](documentation.md).

## 1. Tầm nhìn sản phẩm

Owner mới: [Discovery](product/discovery.md) và [PRD](product/prd.md).

### 1.1 Triết lý trải nghiệm

### 1.2 Câu định vị nội bộ

## 2. Brand

Owner mới: [PRD](product/prd.md) và [frontend conventions](architecture/frontend-conventions.md).

### 2.1 Tên

### 2.2 Mascot

### 2.3 Brand trong code

## 3. Actor

Owner mới: [Requirements analysis](requirements/analysis.md) và
[Identity/Learner requirements](requirements/identity-learner.md).

### 3.1 Không có Guest Mode ở phase đầu

## 4. Phạm vi tính năng và mức ưu tiên

Owner mới: [PRD scope](product/prd.md#6-scope-va-uu-tien). Xung đột YouTube:
[GAP-001](requirements/gaps.md#gap-001--youtube-p0-hay-sau-lesson-mvp).

### 4.1 P0 — Foundation

### 4.2 P0 — Core Lesson

### 4.3 P0/P1 — Lexicon

### 4.4 P1 — Vocabulary

### 4.5 P1 — Grammar

### 4.6 P1 — TOEIC

### 4.7 P1 — Curriculum

### 4.8 P1 — Onboarding

### 4.9 P2 — Analytics / Progress

### 4.10 P2 — Gamification

### 4.11 P3 — Chat

### 4.12 P3 — Future Speaking

## 5. Nguyên tắc kiến trúc tổng thể

Owner mới: [Architecture](ARCHITECTURE.md) và [engineering contract](../AGENTS.md).

### 5.1 Lý do Modular Monolith

### 5.2 AI service là service riêng

## 6. Module map

Owner mới: [Architecture module boundaries](ARCHITECTURE.md#4-business-module-boundaries).

### 6.1 `identity`

### 6.2 `learner`

### 6.3 `ai` (Java)

### 6.4 `lesson`

### 6.5 `speech-assessment`

### 6.6 `lexicon`

### 6.7 `vocabulary`

### 6.8 `grammar`

### 6.9 `toeic`

### 6.10 `curriculum`

### 6.11 `gamification`

### 6.12 `analytics`

### 6.13 `notification`

### 6.14 `chat`

## 7. Module ownership và progress

Owner mới: [Architecture](ARCHITECTURE.md#11-progress-architecture) và
[requirements analysis](requirements/analysis.md#phu-thuoc-xuyen-module).

## 8. Clean Architecture bên trong module

Owner mới: [Architecture](ARCHITECTURE.md#3-clean-architecture-dependency-direction) và
[`AGENTS.md`](../AGENTS.md#3-dependency-and-module-communication-rules).

### 8.1 Pragmatic, không cực đoan

## 9. Giao tiếp giữa module

Owner mới: [Architecture](ARCHITECTURE.md#5-cross-module-communication).

## 10. Vì sao không Kafka

Owner mới: [Tech Choices](TECH_CHOICES.md#kafka-deliberately-not-selected) và
[D-002](DECISIONS.md#d-002--no-kafka-at-mvp).

## 11. Vì sao không Redis ở MVP

Owner mới: [Tech Choices](TECH_CHOICES.md#redis-deliberately-not-selected) và
[D-003](DECISIONS.md#d-003--no-redis-at-mvp).

## 12. Background Job Architecture

Owner mới: [Background jobs protocol](architecture/background-jobs.md).

### 12.1 Yêu cầu

### 12.2 PostgreSQL-backed queue

### 12.3 Claim

### 12.4 Lease + heartbeat

### 12.5 Cancellation

### 12.6 Progress

## 13. Lesson Build Job

Owner mới: [Lesson Build](features/lesson-build.md), với generic protocol ở
[Background jobs](architecture/background-jobs.md).

## 14. Lesson = Content + Annotation + Activity

Owner mới: [Lesson requirements](requirements/lesson.md#br-lsn-001--content-annotation-activity-tach-biet).

### 14.1 Content

### 14.2 Annotation

### 14.3 Activity

### 14.4 Hai distinction bắt buộc

## 15. Lesson Builder và option-based processing

Owner mới: [Lesson Build](features/lesson-build.md).

### Source

### Activities

### Annotations / enrichment

### Preset

### 15.1 Planner

## 16. Audio strategy

Owner mới: [Lesson requirements](requirements/lesson.md#fr-lsn-003--canonical-audio-playback) và
[Lesson Build source semantics](features/lesson-build.md#source-semantics).

## 17. Dictation

Owner mới: [Dictation requirements](requirements/lesson.md#dictation) và
[Dictation feature](features/dictation.md).

### 17.1 Trải nghiệm

### 17.2 Proper-noun hint

### 17.3 Scoring

## 18. Shadowing

Owner mới: [Shadowing requirements](requirements/lesson.md#shadowing) và
[Shadowing feature](features/shadowing.md).

### 18.1 Trải nghiệm hiện đại

### 18.2 Context note sau attempt

### 18.3 Speech assessment

## 19. Sentence IPA / Pronunciation Annotation

Owner mới: [Lesson requirements](requirements/lesson.md#fr-lsn-004--contextual-pronunciation).

## 20. Config hierarchy

Owner mới: [Configuration](CONFIGURATION.md).

### 20.1 Tầng config

### 20.2 Deployment config

### 20.3 Admin runtime config

### 20.4 Learner preference

### 20.5 Session override

## 21. AI architecture

Owner mới: [AI requirements](requirements/ai.md) và [AI execution protocol](architecture/ai-execution.md).

### 21.1 Java là orchestrator

### 21.2 FastAPI là capability service

### 21.3 Endpoint định hướng capability

## 22. AI provider/model routing

Owner mới: [AI Routing](features/ai-routing.md) và [Configuration](CONFIGURATION.md#3-admin-runtime-policy).

## 23. AI invocation audit

Owner mới: [AI requirements](requirements/ai.md#fr-ai-003--invocation-audit) và
[AI Routing](features/ai-routing.md).

## 24. API key và secret

Owner mới: [AI requirements](requirements/ai.md#br-ai-003--provider-credential-confidentiality) và
[NFR security](requirements/non-functional.md#nfr-sec-002--secret-confidentiality).

## 25. Qwen3-ASR / ForcedAligner

Owner mới: [AI execution](architecture/ai-execution.md#runtime-and-routing),
[AI Service README](../apps/ai-service/README.md), và [Tech Choices](TECH_CHOICES.md).

### Dev không GPU

### Dev có GPU

### Production GPU

## 26. Cache

Owner mới: [Architecture](ARCHITECTURE.md#14-cache-rate-limit-resilience) và [Operations](OPERATIONS.md#8-cache-behavior).

### 26.1 Caffeine

### 26.2 Không cache job state trong Caffeine

## 27. Rate limit và resilience

Owner mới: [Architecture](ARCHITECTURE.md#14-cache-rate-limit-resilience), [Operations](OPERATIONS.md#9-rate-limiting),
và [NFR](requirements/non-functional.md#nfr-ops-003--dependency-resilience).

### 27.1 Bucket4j

### 27.2 Resilience4j

## 28. Object storage: Cloudflare R2

Owner mới: [Configuration](CONFIGURATION.md#7-storage-configuration), [Architecture](ARCHITECTURE.md#13-storage),
và [Operations](OPERATIONS.md#4-r2-ownership).

### 28.1 Storage abstraction

### 28.2 DB lưu object key, không signed URL

### 28.3 R2 object layout

## 29. Raw AI JSON vs normalized DB

Owner mới: [AI requirements](requirements/ai.md#br-ai-004--raw-versus-normalized-output) và
[Data Pipelines](DATA_PIPELINES.md#7-r2-raw-artifacts-vs-database-state).

## 30. Lexicon

Owner mới: [Lexicon requirements](requirements/lexicon-vocabulary.md) và
[Data Pipelines](DATA_PIPELINES.md#2-lexicon-pipeline).

### 30.1 Entry type

### 30.2 Core entities

### 30.3 Seed strategy

### 30.4 Corpus relevance

### 30.5 Vietnamese translation status

### 30.6 Lesson lexical annotation

## 31. Lexicon pronunciation audio

Owner mới: [Lexicon requirements](requirements/lexicon-vocabulary.md#fr-lex-003--pronunciation-audio-strategy).

## 32. License/source model

Owner mới: [Lexicon provenance](requirements/lexicon-vocabulary.md#br-lex-002--provenance-and-coverage)
và [Data Pipelines](DATA_PIPELINES.md).

## 33. Vocabulary

Owner mới: [Vocabulary requirements](requirements/lexicon-vocabulary.md) và
[Vocabulary SRS](features/vocabulary-srs.md).

### 33.1 SRS

## 34. Grammar

Owner mới: [Grammar requirements](requirements/grammar-toeic.md#grammar) và
[Grammar Practice](features/grammar-practice.md).

### 34.1 Không generate grammar question mặc định

### 34.2 Grammar contextual annotation

## 35. TOEIC dataset

Owner mới: [Data Pipelines](DATA_PIPELINES.md#5-toeic-importer-mock_test_data) và
[TOEIC requirements](requirements/grammar-toeic.md#toeic).

### `grammar_data`

### `mock_test_data`

## 36. Data import

Owner mới: [Data Pipelines](DATA_PIPELINES.md) và [importer README](../tools/data-import/README.md).

### 36.1 Dataset import record

### 36.2 TOEIC

### 36.3 Lexicon

### 36.4 Developer dataset bootstrap

## 37. Flyway, JPA, Hibernate

Owner mới: [`AGENTS.md`](../AGENTS.md#8-database-storage-and-data),
[Architecture](ARCHITECTURE.md#15-schema-ownership), và [Development](DEVELOPMENT.md#7-database-workflow).

### JPA / Jakarta Persistence

### Hibernate

### Flyway

## 38. Curriculum

Owner mới: [Curriculum requirements](requirements/curriculum-gamification.md) và
[Curriculum Progress](features/curriculum-progress.md).

### 38.1 Progress

## 39. Analytics / Mobile Progress

Owner mới: [Analytics requirements](requirements/analytics-notification-chat.md) và
[Rewards/Analytics](features/rewards-analytics.md).

## 40. Gamification

Owner mới: [Gamification requirements](requirements/curriculum-gamification.md) và
[Rewards/Analytics](features/rewards-analytics.md).

### 40.1 Diamond ledger

### 40.2 Anti-farm

## 41. Mission

Owner mới: [Gamification requirements](requirements/curriculum-gamification.md#fr-gam-003--missions).

## 42. Authentication / Keycloak

Owner mới: [Identity/Learner requirements](requirements/identity-learner.md),
[Auth/Onboarding](features/auth-onboarding.md), và [Keycloak README](../infra/keycloak/README.md).

### 42.1 Bootstrap

### 42.2 JIT provisioning

## 43. Environment files

Owner mới: [Configuration](CONFIGURATION.md#2-deployment-configuration-env-secret-manager).

## 44. Development topology

Owner mới: [Development](DEVELOPMENT.md), root [README](../README.md), và [Docker README](../infra/docker/README.md).

## 45. Production/self-host topology

Owner mới: [Operations](OPERATIONS.md#2-production-compose-scope) và [Docker README](../infra/docker/README.md).

## 46. Frontend

Owner mới: [Frontend conventions](architecture/frontend-conventions.md) và app/package READMEs.

### 46.1 Mobile

### 46.2 Admin Web

### 46.3 Design system và i18n

## 47. Lyrebird trong UI

Owner mới: [Frontend conventions](architecture/frontend-conventions.md#experience-constraints) và
[PRD](product/prd.md).

## 48. Admin Settings IA

Owner mới: [Frontend conventions](architecture/frontend-conventions.md#experience-constraints),
[AI Routing](features/ai-routing.md), và routed domain requirements.

## 49. Learner Settings IA

Owner mới: [Identity/Learner requirements](requirements/identity-learner.md#fr-idn-005--learner-settings-ia)
và [Frontend conventions](architecture/frontend-conventions.md).

## 50. Notification / realtime

Owner mới: [Notification requirements](requirements/analytics-notification-chat.md#fr-ntf-001--realtime-and-notification-boundary)
và [Jobs protocol](architecture/background-jobs.md#progress-and-consumers).

## 51. Chat

Owner mới: [Chat requirements](requirements/analytics-notification-chat.md#fr-cht-001--english-tutor-boundary).

## 52. Future speaking scenario

Owner mới: [Future speaking requirement](requirements/analytics-notification-chat.md#fr-cht-002--future-speaking-reuse)
và [PRD scope](product/prd.md#6-scope-va-uu-tien).

## 53. Security rules

Owner mới: [`AGENTS.md`](../AGENTS.md#11-security-authority) và [Security NFRs](requirements/non-functional.md).

## 54. Observability

Owner mới: [NFR-OPS-002](requirements/non-functional.md#nfr-ops-002--observable-correlation) và
[Operations](OPERATIONS.md#10-observability-baseline).

## 55. Data retention

Owner mới: [NFR-OPS-004](requirements/non-functional.md#nfr-ops-004--retention-is-deliberate) và
[Operations](OPERATIONS.md#11-data-retention).

## 56. Error handling

Owner mới: [NFRs](requirements/non-functional.md) và từng feature/protocol owner.

## 57. API versioning

Owner mới: [Architecture](ARCHITECTURE.md) và code controller/FastAPI contracts được route từ [docs index](README.md).

## 58. Legacy migration map

Owner mới: [Decisions](DECISIONS.md), [Tech Choices](TECH_CHOICES.md), và [`AGENTS.md`](../AGENTS.md#5-hard-prohibitions).

## 59. Technology baseline — September 2026

Owner mới: [Tech Choices](TECH_CHOICES.md) và manifests/lockfiles tương ứng.

## 60. Những thứ agent được tự quyết

Owner mới: [`AGENTS.md`](../AGENTS.md#12-docscode-divergence-and-change-workflow).

## 61. Những thứ agent không được tự ý làm

Owner mới: [`AGENTS.md`](../AGENTS.md#5-hard-prohibitions).

## 62. Roadmap đề xuất

Owner mới: [PRD release direction](product/prd.md#8-release-direction-va-non-goals) và
[Gaps](requirements/gaps.md).

### Phase 0 — Platform

### Phase 1 — Lesson MVP

### Phase 2 — Learning data

### Phase 3 — TOEIC + Curriculum

### Phase 4 — Retention

### Phase 5 — Intelligence

### Phase 6 — Speaking expansion

## 63. Definition of Done cho một feature

Owner mới: [`AGENTS.md`](../AGENTS.md#14-testing-and-completion),
[documentation guide](documentation.md#kiem-tra), và feature-specific AC/evidence.

## 64. Open decisions có chủ đích

Owner mới: [Gaps register](requirements/gaps.md) và [Decisions](DECISIONS.md).

## 65. MCP / NotebookLM

Owner mới: [Optional MCP tooling README](../tooling/mcp/README.md). Đây không phải runtime dependency.

## 66. Official references kiểm tra tại thời điểm viết

Owner mới: source links đặt tại luận điểm liên quan trong [Tech Choices](TECH_CHOICES.md),
[Discovery](product/discovery.md) hoặc owner technical doc; version truth nằm ở manifest/lockfile.

## 67. Kết luận kiến trúc

Owner mới: [Architecture](ARCHITECTURE.md), [engineering contract](../AGENTS.md), và
[Decision log](DECISIONS.md).
