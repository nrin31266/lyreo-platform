package com.lyreo.entitlement.application.port;

import com.lyreo.entitlement.domain.LearnerPlan;
import java.time.Instant;
import java.util.UUID;

public interface LearnerPlanRepository {
    boolean isPlus(UUID learnerId, Instant now);

    void lockPlan(UUID learnerId);

    LearnerPlan readPlan(UUID learnerId, Instant now);

    void startPlus(UUID learnerId, Instant now, Instant endsAt, String actor);

    void endPlus(UUID learnerId, Instant now, String actor);

    void cancelScheduledPlus(UUID learnerId, Instant now);
}
