# FEAT-TOEIC-ATTEMPTS — TOEIC Test and Drill Attempts

Requirements: [TOEIC](../requirements/grammar-toeic.md#toeic); story
[US-TOE-001](../requirements/stories/grammar-toeic.md#us-toe-001--nop-toeic-attempt).

Learner submits answers to `POST /api/v1/toeic/tests/{testId}/attempts`. Server loads test/questions,
normalizes answer map, persists attempt/answers, computes raw Listening/Reading correct counts and
publishes `ToeicAttemptCompletedEvent`. Missing/unanswered items are not fabricated as correct.

Scaled Listening/Reading scores remain null until a reviewed, versioned conversion table exists;
see [GAP-008](../requirements/gaps.md#gap-008--toeic-scaled-score-conversion-table). Structured content
is in PostgreSQL and media uses object keys.

Code: `modules/toeic`; unit evidence location:
`modules/toeic/src/test/java/com/lyreo/toeic/ToeicAttemptServiceTest.java`.
