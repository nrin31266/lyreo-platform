package com.lyreo.gamification.application.port;

import com.lyreo.gamification.domain.AchievementDefinition;
import com.lyreo.gamification.domain.AchievementView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AchievementRepository {
    boolean recordDistinctItem(UUID learnerId, String metric, UUID itemId);

    int countDistinctItems(UUID learnerId, String metric);

    List<AchievementDefinition> findEnabledByMetric(String metric);

    void upsertProgress(UUID learnerId, UUID achievementId, int progress);

    default int incrementAndClampProgress(UUID learnerId, UUID achievementId, int delta, int target) {
        return 0;
    }

    boolean markUnlockedIfEligible(
        UUID learnerId,
        UUID achievementId,
        int target,
        String rewardSnapshotJson
    );

    List<AchievementView> listLearnerAchievements(UUID learnerId);

    default List<AchievementDefinition> listAllAchievements() {
        return List.of();
    }

    default Optional<AchievementDefinition> findAchievementById(UUID id) {
        return Optional.empty();
    }

    default AchievementDefinition createAchievement(AchievementDefinition achievement) {
        return achievement;
    }

    default Optional<AchievementDefinition> updateAchievement(AchievementDefinition achievement) {
        return Optional.empty();
    }
}
