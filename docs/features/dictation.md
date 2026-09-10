# FEAT-DICTATION — Dictation Practice

Requirements: [Dictation](../requirements/lesson.md#dictation); story [US-LSN-002](../requirements/stories/lesson.md#us-lsn-002--lam-dictation).

## Flow

Learner nghe canonical media theo sentence offsets, dùng repeat/speed và optional proper-name hints,
rồi gửi `sentenceId` + answer text tới
`POST /api/v1/lessons/{lessonId}/activities/{activityId}/dictation/attempts`.
Core provision authenticated learner, xác nhận sentence thuộc đúng Lesson/activity, normalize/chấm
answer, append attempt, refresh progress và trả server score, expected text, item counts cùng hai cờ
activity/lesson completion.

Current `DictationScoringPolicy` dùng relaxed token edit distance: case/punctuation không giảm điểm,
word insertion/deletion/substitution có ảnh hưởng. Đây là implementation policy hiện có; product
approval của completion threshold 70 còn ở [GAP-002](../requirements/gaps.md#gap-002--nguong-hoan-thanh-dictation-70).

## Transitions và events

Attempt luôn là record riêng. `LessonActivityCompletedEvent` chỉ phát khi activity transition lần đầu;
`LessonCompletedEvent` chỉ phát khi mọi enabled activity theo repository rule hoàn tất. Downstream
Curriculum/Gamification/Analytics consume public facts và phải idempotent.

## Errors và security

Null answer hoặc mismatched lesson/activity/sentence bị reject. Client không gửi trusted score,
completion hay reward. Feedback annotations hiển thị theo learner preference; raw provider error
không lộ ra Mobile.

## Code/evidence

Code: `LessonPracticeController.java`, `LessonPracticeService.java`, `DictationScoringPolicy.java`,
`JdbcLessonPracticeRepository.java`. Unit evidence locations:
`modules/lesson/src/test/java/com/lyreo/lesson/DictationScoringPolicyTest.java`.
