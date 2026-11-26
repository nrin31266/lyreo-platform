package com.lyreo.gamification.application;

import com.lyreo.gamification.application.port.AchievementRepository;
import com.lyreo.gamification.application.port.DiamondLedger;
import com.lyreo.gamification.application.port.RewardPolicyConfigRepository;
import com.lyreo.gamification.application.port.XpLedger;
import com.lyreo.gamification.domain.AchievementDefinition;
import com.lyreo.gamification.domain.DiamondTransaction;
import com.lyreo.gamification.domain.RewardPolicyConfig;
import com.lyreo.gamification.domain.XpTransaction;
import com.lyreo.platform.config.application.ConsumerReceiptStore;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Evaluates lifetime achievements and awards one-time XP/Diamond milestones. */
public class AchievementService {
    private static final String CONSUMER_NAME = "gamification.achievement";
    public static final String METRIC_DISTINCT_LESSON_COMPLETED = "DISTINCT_LESSON_COMPLETED";
    public static final String METRIC_STREAK_DAYS = "STREAK_DAYS";

    private final AchievementRepository achievements;
    private final XpLedger xpLedger;
    private final DiamondLedger diamondLedger;
    private final ConsumerReceiptStore receipts;
    private final RewardPolicyConfigRepository policyConfig;
    private final ObjectMapper mapper;

    public AchievementService(
        AchievementRepository achievements,
        XpLedger xpLedger,
        DiamondLedger diamondLedger,
        ConsumerReceiptStore receipts,
        RewardPolicyConfigRepository policyConfig,
        ObjectMapper mapper
    ) {
        this.achievements = Objects.requireNonNull(achievements, "achievements");
        this.xpLedger = xpLedger;
        this.diamondLedger = diamondLedger;
        this.receipts = receipts;
        this.policyConfig = policyConfig;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    @Transactional
    public void recordLessonCompleted(UUID factId, UUID learnerId, UUID lessonId) {
        if (learnerId == null || lessonId == null) {
            return;
        }
        if (factId != null && receipts != null && !receipts.tryRecord(CONSUMER_NAME, factId)) {
            return;
        }
        achievements.recordDistinctItem(learnerId, METRIC_DISTINCT_LESSON_COMPLETED, lessonId);
        int distinctLessons = achievements.countDistinctItems(learnerId, METRIC_DISTINCT_LESSON_COMPLETED);
        evaluateMetric(learnerId, METRIC_DISTINCT_LESSON_COMPLETED, distinctLessons);
    }

    @Transactional
    public void evaluateStreak(UUID learnerId, int streakDays) {
        if (learnerId == null || streakDays <= 0) {
            return;
        }
        evaluateMetric(learnerId, METRIC_STREAK_DAYS, streakDays);
    }

    private void evaluateMetric(UUID learnerId, String metric, int value) {
        RewardPolicyConfig config = policyConfig != null
            ? policyConfig.current()
            : RewardPolicyConfig.defaults();

        for (AchievementDefinition def : achievements.findEnabledByMetric(metric)) {
            int progress = Math.min(value, def.target());
            achievements.upsertProgress(learnerId, def.id(), progress);
            if (value < def.target()) {
                continue;
            }

            String ruleVersion = (def.ruleVersion() != null && !def.ruleVersion().isBlank())
                ? def.ruleVersion()
                : config.normalizedRuleVersion();
            String snapshotJson = buildSnapshotJson(def, ruleVersion);
            if (!achievements.markUnlockedIfEligible(learnerId, def.id(), def.target(), snapshotJson)) {
                continue;
            }

            Instant now = Instant.now();
            if (def.xpReward() > 0 && xpLedger != null) {
                xpLedger.append(
                    new XpTransaction(
                        UUID.randomUUID(),
                        learnerId,
                        "ACHIEVEMENT_REWARD",
                        def.xpReward(),
                        "achievement-xp:" + def.id() + ":" + learnerId,
                        ruleVersion,
                        def.id(),
                        now
                    ),
                    config
                );
            }

            if (def.diamondReward() > 0 && diamondLedger != null) {
                diamondLedger.append(new DiamondTransaction(
                    UUID.randomUUID(),
                    learnerId,
                    DiamondTransaction.Type.ACHIEVEMENT_REWARD,
                    def.diamondReward(),
                    "achievement:" + def.id() + ":" + learnerId,
                    "ACHIEVEMENT",
                    def.id(),
                    now,
                    "Achievement unlocked: " + def.code(),
                    ruleVersion
                ));
            }
        }
    }

    private String buildSnapshotJson(AchievementDefinition def, String ruleVersion) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("achievementCode", def.code());
        snapshot.put("xp", def.xpReward());
        snapshot.put("diamonds", def.diamondReward());
        snapshot.put("ruleVersion", ruleVersion);
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize achievement reward snapshot", ex);
        }
    }
}
