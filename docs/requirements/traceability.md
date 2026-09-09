# Requirement traceability and evidence

Mục đích: owner duy nhất của quan hệ requirement → AC/story → feature → code/test và trạng thái
kiểm chứng. Status tại đây mô tả phạm vi evidence, không thay decision status trong requirement.

Baseline và post-migration docs checks: commit nền
`58ca5e7e6f960b4e3f15ddb165fb3169027d7e20`, ngày 2026-09-09. `make validate-docs`,
`make validate`, checker fixtures và diff/heading/alias/CI wiring checks đã chạy exit 0. Các kết quả
này không chạy Java/Python product suites, frontend/native build hoặc integration/live-provider gates.

| Requirements | Stories / AC | Feature / code entrypoints | Evidence location | Status và phạm vi |
|---|---|---|---|---|
| [FR-IDN-001–002, BR-IDN-001](identity-learner.md) | [US-IDN-001; AC-IDN-001–002](stories/identity-learner.md) | [Auth/onboarding](../features/auth-onboarding.md); `modules/identity`, `platform/security` | `infra/keycloak` config; no focused Core test identified | `implemented-unverified`: backend/config code inspected, frontend/runtime flow not run |
| [FR-IDN-003–005, BR-IDN-002](identity-learner.md) | [US-IDN-002; AC-IDN-003–004](stories/identity-learner.md) | [Auth/onboarding](../features/auth-onboarding.md); `modules/learner`; app providers | no focused automated test identified | `partial`: backend/profile and preference adapters exist; end-to-end onboarding not proven |
| [FR-LSN-001–004, BR-LSN-001–002](lesson.md) | [US-LSN-001; AC-LSN-001–002](stories/lesson.md) | [Lesson Build](../features/lesson-build.md); planner/service/handler | `apps/core-service/src/test/java/com/lyreo/lesson/LessonBuildPlannerTest.java`, `LessonProcessingPolicyTest.java`, `LessonPromptFactoryTest.java` | `implemented-unverified`: tests exist but Java suite not run in docs setup baseline |
| [FR-LSN-005, BR-LSN-003–004](lesson.md#dictation) | [US-LSN-002; AC-LSN-003–004](stories/lesson.md#dictation) | [Dictation](../features/dictation.md); controller/service/repository | `apps/core-service/src/test/java/com/lyreo/lesson/DictationScoringPolicyTest.java` | `partial`: score unit test location exists; API/DB/events and threshold approval not verified |
| [FR-LSN-006, BR-LSN-005](lesson.md#shadowing) | [US-LSN-003; AC-LSN-005–006](stories/lesson.md#shadowing) | [Shadowing](../features/shadowing.md); `modules/speech-assessment` | `apps/core-service/src/test/java/com/lyreo/speechassessment/SpeechScoringPolicyTest.java` | `partial`: service/policy exist; API/Mobile end-to-end gap GAP-003 |
| [BR-AI-001–004, FR-AI-001–004](ai.md) | [US-AI-001–002; AC-AI-001–004](stories/ai.md) | [AI routing](../features/ai-routing.md), [execution protocol](../architecture/ai-execution.md); Java/FastAPI | `services/ai-service/tests/test_api.py` | `implemented-unverified`: mock/API tests exist but not run; live provider quality not covered |
| [FR-LEX-001–003, BR-LEX-001–002](lexicon-vocabulary.md) | [US-LEX-001; AC-LEX-001–002](stories/lexicon-vocabulary.md) | `modules/lexicon`; [Data Pipelines](../DATA_PIPELINES.md) | `tools/data-import/tests/test_import_lexicon.py` | `partial`: search/importer code exists; source data not downloaded and runtime import not run |
| [FR-VOC-001, BR-VOC-001–002](lexicon-vocabulary.md) | [US-VOC-001; AC-VOC-001–002](stories/lexicon-vocabulary.md) | [Vocabulary SRS](../features/vocabulary-srs.md) | `apps/core-service/src/test/java/com/lyreo/vocabulary/StarterSchedulerTest.java` | `partial`: starter scheduler only; test not run in docs setup baseline |
| [FR-GRM-001, BR-GRM-001–002](grammar-toeic.md#grammar) | [US-GRM-001; AC-GRM-001–002](stories/grammar-toeic.md) | [Grammar Practice](../features/grammar-practice.md) | `apps/core-service/src/test/java/com/lyreo/grammar/GrammarPracticeServiceTest.java`; `tools/data-import/tests/test_import_grammar.py` | `implemented-unverified`: tests exist, dependency-aware suites not run |
| [FR-TOE-001–002, BR-TOE-001–002](grammar-toeic.md#toeic) | [US-TOE-001; AC-TOE-001–002](stories/grammar-toeic.md) | [TOEIC attempts](../features/toeic-attempts.md) | `apps/core-service/src/test/java/com/lyreo/toeic/ToeicAttemptServiceTest.java`; `tools/data-import/tests/test_import_toeic.py` | `partial`: raw scoring exists; scaled conversion and applied dataset run missing |
| [FR-CUR-001–002, BR-CUR-001](curriculum-gamification.md) | [US-CUR-001; AC-CUR-001–002](stories/curriculum-gamification.md) | [Curriculum progress](../features/curriculum-progress.md); listeners/contracts | no focused automated test identified | `implemented-unverified`: listener/repository inspected, integration/idempotency test not identified |
| [FR-GAM-001–003, BR-GAM-001](curriculum-gamification.md) | [US-GAM-001; AC-GAM-001–002](stories/curriculum-gamification.md) | [Rewards/analytics](../features/rewards-analytics.md); gamification listeners | `apps/core-service/src/test/java/com/lyreo/gamification/RewardPolicyTest.java` | `partial`: policy unit test exists; event/ledger integration not proven |
| [FR-ANL-001–002, BR-ANL-001](analytics-notification-chat.md) | [US-ANL-001; AC-ANL-001–002](stories/analytics-notification-chat.md) | [Rewards/analytics](../features/rewards-analytics.md); analytics listener/Mobile | no focused automated test identified | `partial`: projection listener exists; Mobile has sample data and runtime not verified |
| [FR-NTF-001](analytics-notification-chat.md) | [US-NTF-001; AC-NTF-001](stories/analytics-notification-chat.md) | [Jobs protocol](../architecture/background-jobs.md); SSE/Admin | no focused automated test identified | `partial`: server SSE exists; Admin subscription missing (GAP-004) |
| [FR-CHT-001–002](analytics-notification-chat.md) | [US-CHT-001; AC-CHT-001](stories/analytics-notification-chat.md) | `modules/chat`; [AI execution](../architecture/ai-execution.md) | no focused automated test identified | `partial`: module boundary exists; feature priority P3 |
| [NFR-SEC-001–003](non-functional.md) | Security ACs across stories | security/config/storage owners | repository validator + future auth/resource integration tests | `partial`: static guardrails exist; authorization/private artifact end-to-end not run |
| [NFR-OPS-001–004](non-functional.md) | Job/cancel/retry and retention ACs | [Jobs](../architecture/background-jobs.md), [Operations](../OPERATIONS.md) | no concurrency/retention suite identified | `partial`: generic lease guards inspected; GAP-006/GAP-007 remain |
| [NFR-DAT-001–002](non-functional.md) | importer/schema behavior | [Data Pipelines](../DATA_PIPELINES.md), Flyway | importer tests; Core startup/Flyway validation | `not-run`: docs setup does not download/apply data or boot database |
| [NFR-UI-001](non-functional.md) | UI ACs by feature | [Frontend conventions](../architecture/frontend-conventions.md) | `tooling/validate_repo.py`; frontend typecheck/build | `partial`: static guardrails baseline passed; dependency-aware frontend gates not run yet |
| [NFR-AI-001](non-functional.md) | [AC-AI-003–004](stories/ai.md) | [AI routing](../features/ai-routing.md) | FastAPI tests + live provider smoke | `not-run`: mock/live AI suites not run in docs migration baseline |

## Cách cập nhật evidence

Chỉ chuyển sang `verified` khi ghi command/source mới, ngày, commit và phạm vi cụ thể. Một unit test
không phải evidence end-to-end cho cả feature; `ArchitectureTest` chỉ chứng minh boundary rules mà
nó assert, không chứng minh lease/fencing concurrency. Historical results ở
[`TESTING_NOTES.md`](../../TESTING_NOTES.md) không tự động làm evidence hiện hành.
