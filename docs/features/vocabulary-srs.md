# FEAT-VOCABULARY-SRS — Vocabulary Save and Review

Requirements: [Lexicon/Vocabulary](../requirements/lexicon-vocabulary.md); story
[US-VOC-001](../requirements/stories/lexicon-vocabulary.md#us-voc-001--on-tu-theo-lich).

Authenticated learner saves a card referencing global `lexicon_entry_id` with optional context.
Review input is learner evidence/rating; `VocabularyCommandService` loads learner-owned state, calls
`SpacedRepetitionScheduler`, appends history/next schedule and publishes
`VocabularyReviewCompletedEvent`. Analytics/Gamification consume the fact; they do not own the card.

The current `StarterFsrsCompatibleScheduler` is a starter abstraction, not a claim of full FSRS
equivalence. Replacing it requires benchmark, state migration and compatibility evidence.

Code: `modules/vocabulary/src/main/java/com/lyreo/vocabulary/`; unit evidence location:
`modules/vocabulary/src/test/java/com/lyreo/vocabulary/StarterSchedulerTest.java`.
