package com.lyreo.gamification.application;

import com.lyreo.contracts.lesson.LessonActivityCompletedEvent;
import com.lyreo.gamification.application.port.DiamondLedger;
import com.lyreo.gamification.application.port.RewardPolicyConfigRepository;
import com.lyreo.gamification.domain.DiamondTransaction;
import com.lyreo.gamification.domain.RewardPolicyConfig;
import com.lyreo.platform.config.application.ConsumerReceiptStore;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class LessonRewardListener {
    private static final String CONSUMER_NAME = "gamification.lesson-reward";

    private final DiamondLedger ledger;
    private final RewardPolicy policy;
    private final RewardPolicyConfigRepository policyConfig;
    private final ConsumerReceiptStore receipts;

    public LessonRewardListener(DiamondLedger ledger, RewardPolicy policy) {
        this(ledger, policy, null, null);
    }

    @Autowired
    public LessonRewardListener(
        DiamondLedger ledger,
        RewardPolicy policy,
        RewardPolicyConfigRepository policyConfig,
        ConsumerReceiptStore receipts
    ) {
        this.ledger = ledger;
        this.policy = policy;
        this.policyConfig = policyConfig;
        this.receipts = receipts;
    }

    @ApplicationModuleListener
    public void on(LessonActivityCompletedEvent event) {
        UUID factId = event.factId() != null
            ? event.factId()
            : UUID.nameUUIDFromBytes(
                ("lesson-activity:" + event.learnerId() + ":" + event.activityId())
                    .getBytes(StandardCharsets.UTF_8)
            );
        if (receipts != null && !receipts.tryRecord(CONSUMER_NAME, factId)) {
            return;
        }

        RewardPolicyConfig config = policyConfig != null
            ? policyConfig.current()
            : RewardPolicyConfig.defaults();
        Instant occurredAt = event.occurredAt() != null ? event.occurredAt() : Instant.now();
        boolean beforeCutover = config.cutoverEffectiveAt() != null
            && occurredAt.isBefore(config.cutoverEffectiveAt());
        if (!beforeCutover && !config.directLessonActivityDiamondsEnabled()) {
            return;
        }

        String key = "lesson-activity:" + event.activityId() + ":" + event.learnerId();
        if (ledger.existsByIdempotencyKey(key)) {
            return;
        }

        int reward = policy.lessonActivityDiamonds(event.activityType(), event.serverScore(), true);
        if (reward <= 0) {
            return;
        }

        ledger.append(new DiamondTransaction(
            UUID.randomUUID(),
            event.learnerId(),
            DiamondTransaction.Type.LESSON_REWARD,
            reward,
            key,
            "LESSON_ACTIVITY",
            event.activityId(),
            Instant.now(),
            "Lesson activity reward",
            config.normalizedRuleVersion()
        ));
    }
}
