package com.lyreo.gamification.application;

import com.lyreo.contracts.lesson.LessonActivityCompletedEvent;
import com.lyreo.gamification.domain.DiamondTransaction;
import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public final class LessonRewardListener {
    private final DiamondLedger ledger;
    private final RewardPolicy policy;

    public LessonRewardListener(DiamondLedger ledger, RewardPolicy policy) {
        this.ledger = ledger;
        this.policy = policy;
    }

    @ApplicationModuleListener
    public void on(LessonActivityCompletedEvent event) {
        String key = "lesson-activity:" + event.activityId() + ":" + event.learnerId();
        if (ledger.existsByIdempotencyKey(key)) return;

        // The idempotency key makes this a one-time reward per learner/activity.
        int reward = policy.lessonActivityDiamonds(event.activityType(), event.serverScore(), true);
        if (reward <= 0) return;

        ledger.append(new DiamondTransaction(
            UUID.randomUUID(),
            event.learnerId(),
            DiamondTransaction.Type.LESSON_REWARD,
            reward,
            key,
            "LESSON_ACTIVITY",
            event.activityId(),
            Instant.now()
        ));
    }
}
