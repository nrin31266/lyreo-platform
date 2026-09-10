# FEAT-REWARDS-ANALYTICS — Rewards, Missions and Learning Projections

Requirements: [Curriculum/Gamification](../requirements/curriculum-gamification.md) and
[Analytics/Notification/Chat](../requirements/analytics-notification-chat.md). Stories:
[curriculum/gamification](../requirements/stories/curriculum-gamification.md),
[analytics/notification/chat](../requirements/stories/analytics-notification-chat.md).

Domain modules publish completed/reviewed/answered facts. `LessonRewardListener` and
`MissionEventListener` apply server-owned policies and idempotency; Diamond balance derives from an
immutable ledger. XP computes Level and is not a second learner currency. Payment/purchase remains
future scope.

`LearningAnalyticsListener` consumes the same public facts into daily activity/skill/weakness read
models. Analytics owns projections only. It must not query every domain repository or present sample
Mobile values as learner facts.

Notification may deliver mission/level/job events to UI, but transport is not durability. Code:
`modules/gamification`, `modules/analytics`, `modules/notification`, `libs/contracts`. Unit evidence
for reward policy: `modules/gamification/src/test/java/com/lyreo/gamification/RewardPolicyTest.java`.
