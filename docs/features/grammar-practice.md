# FEAT-GRAMMAR-PRACTICE — Grammar Question Practice

Requirements: [Grammar](../requirements/grammar-toeic.md#grammar); story
[US-GRM-001](../requirements/stories/grammar-toeic.md#us-grm-001--luyen-grammar-tu-question-bank).

Learner queries eligible imported questions, submits an option to
`POST /api/v1/grammar/questions/{questionId}/attempts`, and server loads the question/answer key,
normalizes choice, appends attempt and publishes `GrammarQuestionAnsweredEvent`. Explanation and
translation come from dataset when present. AI explanation is on-demand/fallback, never default
question generation.

Lesson contextual Grammar annotations may reference taxonomy but do not create Grammar Practice
progress. Import shape/provenance belongs in [Data Pipelines](../DATA_PIPELINES.md).

Code: `modules/grammar`; unit evidence location:
`apps/core-service/src/test/java/com/lyreo/grammar/GrammarPracticeServiceTest.java`.
