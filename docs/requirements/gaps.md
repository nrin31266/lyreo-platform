# Gaps register

Mục đích: một nơi duy nhất cho câu hỏi, xung đột, implementation gap và verification gap. Normative
intent vẫn ở owner requirement/spec; status ở đây không tự thay đổi requirement.

## GAP-001 — YouTube P0 hay sau Lesson MVP

- Type/status: `conflict` / `open`.
- Sources: [PRD scope](../product/prd.md#6-scope-va-uu-tien); đặc tả trước migration ghi YouTube P0,
  trong khi roadmap cùng nguồn chỉ nêu Text/Audio ở Lesson MVP.
- Impact: release scope, legal/policy hardening và verification của adapter.
- Next: product owner quyết định YouTube thuộc first Lesson release hay phase sau.

## GAP-002 — Ngưỡng hoàn thành Dictation 70

- Type/status: `question` / `open`.
- Sources: [Dictation requirement](lesson.md#dictation),
  `modules/lesson/src/main/java/com/lyreo/lesson/application/LessonPracticeService.java`.
- Evidence: code dùng `DEFAULT_COMPLETION_SCORE = 70`; chưa thấy nguồn product approval.
- Impact: activity/lesson completion events và downstream reward/progress.
- Next: duyệt threshold/config policy trước khi ghi 70 thành business rule được chốt.

## GAP-003 — Shadowing chưa nối end-to-end

- Type/status: `implementation-gap` / `open`.
- Sources: [Shadowing spec](../features/shadowing.md), `modules/speech-assessment`,
  `apps/mobile/app/lesson.tsx`.
- Evidence: service/scoring có thật; khảo sát tĩnh chưa thấy API/UI nối đầy đủ; Mobile có thao tác
  minh họa và score cố định.
- Impact: không được claim Shadowing production-complete.
- Next: thiết kế/implement API upload/private artifact và Mobile integration trong task riêng.

## GAP-004 — Admin Jobs chưa dùng realtime

- Type/status: `implementation-gap` / `open`.
- Sources: [Jobs protocol](../architecture/background-jobs.md), `AdminRealtimeController.java`,
  `apps/admin-web/src/ui/Jobs.tsx`.
- Evidence: server có SSE; trang Jobs chỉ load theo thao tác/URL, không subscribe SSE hoặc poll định kỳ.
- Impact: UI không tự cập nhật progress như target behavior.
- Next: nối SSE hoặc quyết định polling UX; transport dài hạn vẫn là open decision.

## GAP-005 — Cancel 202 body và Admin API helper

- Type/status: `implementation-gap` / `observed-static-unreproduced`.
- Sources: `apps/core-service/src/main/java/com/lyreo/platform/web/JobController.java`,
  `apps/admin-web/src/api.ts`.
- Evidence: cancel trả empty `202`; helper chỉ bỏ JSON parse cho `204`, nên có khả năng parse body rỗng.
- Impact: Admin cancel có thể báo lỗi dù server đã nhận request.
- Next: tái hiện bằng test/runtime rồi sửa contract/helper trong task product-code riêng.

## GAP-006 — Fencing của Lesson step writes chưa được chứng minh

- Type/status: `verification-gap` / `open`.
- Sources: [Jobs protocol](../architecture/background-jobs.md),
  `JdbcBackgroundJobRepository.java`, `JdbcLessonBuildStateRepository.java`.
- Evidence: generic job updates guard `lease_owner`; một số step writes chỉ filter job/step. Chưa
  phân tích đủ transaction/context để kết luận stale worker có thể overwrite.
- Impact: risk đối với invariant fencing nếu lease bị mất giữa expensive call và step commit.
- Next: concurrency/integration test và transaction review; không hạ requirement để khớp code.

## GAP-007 — KPI và ngưỡng NFR chưa được chốt

- Type/status: `question` / `open`.
- Sources: [PRD](../product/prd.md), [NFR](non-functional.md).
- Impact: chưa thể đánh dấu success/latency/availability/model accuracy bằng số đo.
- Next: thu baseline và product/operations approval trước khi đặt thresholds.

## GAP-008 — TOEIC scaled score conversion table

- Type/status: `implementation-gap` / `open`.
- Sources: [TOEIC requirements](grammar-toeic.md#toeic), `ToeicAttemptService.java`.
- Evidence: raw correct counts tính server-side; scaled score để `null` khi chưa có bảng quy đổi được duyệt.
- Impact: UI/report không được diễn giải raw score là official scaled score.
- Next: chọn nguồn conversion có provenance/version rồi import/test.

## GAP-009 — Starter/mocked implementation maturity

- Type/status: `verification-gap` / `open`.
- Sources: [traceability](traceability.md), historical [`TESTING_NOTES`](../../TESTING_NOTES.md).
- Evidence: Vocabulary scheduler là starter; Admin Curriculum/Lexicon và Mobile progress còn
  placeholder/sample data; live provider/native/Docker integration chưa được chứng nhận trong docs setup.
- Impact: feature presence không đồng nghĩa end-to-end completeness.
- Next: mỗi feature task cập nhật evidence cụ thể thay vì một bảng “done” tổng quát.

## GAP-010 — Frontend dependency install policy lệch CI

- Type/status: `verification-gap` / `open`.
- Sources: `Makefile`, `.github/workflows/ci.yml`, `pnpm-lock.yaml`.
- Evidence: lockfile hiện tồn tại; Makefile dùng frozen install, CI còn `--no-frozen-lockfile` từ
  lịch sử trước lockfile.
- Impact: CI có thể không phát hiện lock drift theo policy local.
- Next: đổi CI dependency gate trong task dependency/workflow riêng; docs migration không sửa policy này.
