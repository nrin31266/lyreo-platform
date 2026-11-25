package com.lyreo.gamification.application.port;

import com.lyreo.gamification.domain.AdminMissionDefinition;
import com.lyreo.gamification.domain.MissionView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for event-driven mission progress. */
public interface MissionProgressRepository {
    List<MissionTarget> activeByMetric(String metric);

    default List<MissionRewardTarget> activeRewardTargetsByMetric(String metric) {
        return activeByMetric(metric).stream()
            .map(m -> new MissionRewardTarget(
                m.id(),
                null,
                null,
                m.target(),
                m.recurrence(),
                m.diamondReward(),
                0,
                "dev-v1"
            ))
            .toList();
    }

    void increment(UUID learnerId, UUID missionId, String periodKey, int target, int amount);

    boolean markClaimedIfCompleted(UUID learnerId, UUID missionId, String periodKey);

    default boolean markClaimedIfCompleted(
        UUID learnerId,
        UUID missionId,
        String periodKey,
        String rewardSnapshotJson
    ) {
        return markClaimedIfCompleted(learnerId, missionId, periodKey);
    }

    default List<MissionView> listActiveMissionsForLearner(
        UUID learnerId,
        String dailyPeriodKey,
        String weeklyPeriodKey,
        Instant dailyEndsAt,
        Instant weeklyEndsAt
    ) {
        return List.of();
    }

    default List<AdminMissionDefinition> listAllMissions() {
        return List.of();
    }

    default Optional<AdminMissionDefinition> findMissionById(UUID id) {
        return Optional.empty();
    }

    default AdminMissionDefinition createMission(AdminMissionDefinition mission) {
        return mission;
    }

    default Optional<AdminMissionDefinition> updateMission(AdminMissionDefinition mission) {
        return Optional.empty();
    }

    record MissionTarget(UUID id, int target, String recurrence, int diamondReward) {}

    record MissionRewardTarget(
        UUID id,
        String code,
        String title,
        int target,
        String recurrence,
        int diamondReward,
        int xpReward,
        String ruleVersion
    ) {}
}
