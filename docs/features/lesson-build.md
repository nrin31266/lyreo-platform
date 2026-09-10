# FEAT-LESSON-BUILD — Lesson Build

Mục đích: hợp đồng hành vi từ Admin request tới Lesson đã finalize. Requirements:
[FR-LSN-001–004, BR-LSN-001–002](../requirements/lesson.md); story [US-LSN-001](../requirements/stories/lesson.md#us-lsn-001--tao-lesson-theo-option).

## Actor, quyền và input

Admin (`ADMIN`) gửi title, source type/text/reference, activity set, annotation set, accent và
pronunciation strategy tới `POST /api/v1/admin/lessons/build`. Source, activities, annotations và
pronunciation strategy độc lập; preset chỉ thuộc form và không thay final snapshot.

## Flow và state

1. `AdminLessonController` map request thành `LessonBuildOptions`.
2. `CreateLessonBuildService` validate, tạo Lesson, gọi `LessonBuildPlanner`, snapshot routing context,
   tạo generic `background_job` cùng Lesson build state/steps.
3. API trả `202` kèm header `Location: /api/v1/jobs/{jobId}` và body ticket `BuildAcceptedResponse(lessonId, jobId)`;
   internal build plan không được serialize ra wire contract. Lesson status, job status và từng step status
   không được coi là một field duy nhất.
4. Worker claim lease. `LessonBuildJobHandler` đọc plan và skip durable `DONE` steps.
5. Mỗi step kiểm tra cancellation, materialize source hoặc gọi Java-owned AI invocation, normalize
   output, persist idempotently và update progress.
6. `FINALIZE` chỉ commit khi job vẫn có quyền tiếp tục; lifecycle listener phản ánh terminal state.

Possible steps gồm source prepare, STT/TTS/alignment, NLP/lexical/grammar/pronunciation enrichment,
activity build và finalize. Planner không đưa tất cả vào mọi build: Text không mặc định cần STT;
Audio không mặc định cần TTS; lesson không mặc định generate text.

## Source semantics

- Text dùng text đã cung cấp; TTS chỉ khi activity cần playable audio.
- Audio materialize canonical media rồi STT nếu transcript cần tạo.
- YouTube adapter có thể extract processing audio; learner playback vẫn ưu tiên official player theo
  assumption hiện hành. Release/policy còn ở [GAP-001](../requirements/gaps.md#gap-001--youtube-p0-hay-sau-lesson-mvp).
- Canonical audio + sentence offsets là default; derived clips tạo on-demand.

## Errors, cancel và retry

Invalid request/source failure là error có stable code/summary. Retry chỉ dành cho failure được phân
loại retryable. Cancel là durable database state; external output trở về sau cancel phải bị discard.
Lease/heartbeat/fencing thuộc [Jobs protocol](../architecture/background-jobs.md), không được định
nghĩa lại trong feature này.

## Side effects và ownership

`lesson` sở hữu content/annotations/activities/build state và product prompts. `platform/jobs` sở hữu
generic execution state. `ai` sở hữu route/invocation audit. Object storage giữ media/raw artifacts;
PostgreSQL giữ normalized/workflow state.

## Code và verification

- Code: `modules/lesson/src/main/java/com/lyreo/lesson/api/AdminLessonController.java`,
  `CreateLessonBuildService.java`, `LessonBuildPlanner.java`, `LessonBuildJobHandler.java`.
- Tests: `modules/lesson/src/test/java/com/lyreo/lesson/LessonBuildPlannerTest.java`,
  `LessonProcessingPolicyTest.java`, `LessonPromptFactoryTest.java`.
- Tests trên chứng minh unit behavior tương ứng khi được chạy; không tự chứng minh worker recovery,
  live provider quality hoặc YouTube policy. Fencing evidence gap: [GAP-006](../requirements/gaps.md#gap-006--fencing-cua-lesson-step-writes-chua-duoc-chung-minh).
