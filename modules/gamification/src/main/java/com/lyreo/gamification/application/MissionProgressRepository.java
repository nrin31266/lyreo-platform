package com.lyreo.gamification.application;

import java.util.List;
import java.util.UUID;

/** Persistence port for event-driven mission progress. */
public interface MissionProgressRepository {
    List<MissionTarget> activeByMetric(String metric);

    void increment(UUID learnerId, UUID missionId, String periodKey, int target, int amount);

    boolean markClaimedIfCompleted(UUID learnerId, UUID missionId, String periodKey);

    record MissionTarget(UUID id, int target, String recurrence, int diamondReward) {}
}
