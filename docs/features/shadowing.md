# FEAT-SHADOWING — Shadowing and Speech Assessment

Requirements: [Shadowing](../requirements/lesson.md#shadowing); story [US-LSN-003](../requirements/stories/lesson.md#us-lsn-003--nhan-feedback-shadowing).

## Target experience

Learner nghe reference audio với karaoke/thought-group cues, record attempt, rồi server thực thi ASR,
alignment và deterministic Tier-1 scoring (word accuracy, timing, fluency). Optional Tier-2
multimodal judge bổ sung đánh giá sâu. Context meaning/lexical/grammar/IPA/tip ưu tiên sau attempt.

## Ownership và flow

Lesson sở hữu activity/reference/context; `speech-assessment` sở hữu recording attempt, transcript,
alignment and score result; AI module/FastAPI thực thi capabilities; storage giữ private media. Final
score là server authority. Deep judge không được thay durable/cancel checks hoặc tự award reward.

Expected flow: authorized attempt create → private upload/reference resolution → ASR → alignment →
Tier-1 score → optional deep judge → persist result → publish completion fact. Nếu cancel/lease loss
xảy ra sau inference, result phải discard trước commit.

## Current implementation boundary

`SpeechAssessmentService`, repository và scoring policy tồn tại, nhưng khảo sát tĩnh chưa thấy API và
Mobile nối end-to-end. `apps/mobile/app/lesson.tsx` có recording/score minh họa. Vì vậy target behavior
không được ghi `verified`; xem [GAP-003](../requirements/gaps.md#gap-003--shadowing-chua-noi-end-to-end).

Code/evidence: `modules/speech-assessment/src/main/java/com/lyreo/speechassessment/` và
`modules/speech-assessment/src/test/java/com/lyreo/speechassessment/SpeechScoringPolicyTest.java`.
