# FEAT-CURRICULUM-PROGRESS — Curriculum Enrollment and Unlock

Requirements: [Curriculum](../requirements/curriculum-gamification.md); story
[US-CUR-001](../requirements/stories/curriculum-gamification.md#us-cur-001--tien-theo-curriculum).

Curriculum Path contains ordered Sections/Items. Item stores content type/reference, position,
required and unlock rule; it never copies Lesson/Grammar/TOEIC/Vocabulary data. Enrollment and item
progress belong to Curriculum.

`CurriculumCompletionListener` consumes public completion facts, finds matching items, writes
idempotent completion and unlocks next item according to Curriculum policy. Duplicate/nonmatching
events produce no duplicated progress. Adding a content type requires a stable public reference and
consumer compatibility review, not cross-module repository access.

Code: `modules/curriculum` and event definitions under `libs/contracts/src/main/java/com/lyreo/contracts`.
