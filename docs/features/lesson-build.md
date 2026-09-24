# FEAT-LESSON-BUILD — Lesson Build

This cross-boundary workflow implements [Lesson requirements](../requirements/lesson.md). It connects an Admin's source and activity choices to a durable, usable Lesson without keeping the initial HTTP request open.

## Acceptance and planning

An authorized Admin submits source, selected activities, enrichment, and pronunciation choices. Presets prefill choices; the final validated options become the immutable build snapshot. Core creates a draft Lesson and durable job, then returns `202 Accepted` and a job location as specified by the [HTTP contract](../architecture/http-api-contract.md).

The plan includes only capabilities required by the chosen source and activities. Text does not inherently need STT or generated text; Audio does not inherently need TTS. YouTube release scope remains [OQ-001](../requirements/open-questions.md#oq-001--youtube-in-the-first-lesson-release).

## Execution and ownership

A worker claims the job, resumes durable unfinished steps, materializes source content, invokes required AI capabilities, builds selected activities, and finalizes the Lesson. `lesson` owns product state and prompts; `platform/jobs` owns generic execution state; `ai` owns route and invocation audit. PostgreSQL stores normalized workflow state and object storage holds media and raw artifacts.

Retry is limited to classified transient failures. Cancellation is durable. Workers check ownership and cancellation before expensive work and before committing output; stale output is discarded. The [jobs protocol](../architecture/background-jobs.md) defines lease, heartbeat, fencing, and recovery. Product acceptance criteria remain in [Lesson requirements](../requirements/lesson.md).
