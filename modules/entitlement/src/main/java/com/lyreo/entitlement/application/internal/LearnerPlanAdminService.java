package com.lyreo.entitlement.application.internal;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.entitlement.application.port.LearnerPlanRepository;
import com.lyreo.entitlement.domain.LearnerPlan;
import com.lyreo.entitlement.domain.LearnerPlan.Tier;
import com.lyreo.identity.application.AppUserProvisioningService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearnerPlanAdminService {
    private final LearnerPlanRepository plans;
    private final AppUserProvisioningService users;
    private final Clock clock;

    @Autowired
    public LearnerPlanAdminService(LearnerPlanRepository plans, AppUserProvisioningService users) {
        this(plans, users, Clock.systemUTC());
    }

    public LearnerPlanAdminService(LearnerPlanRepository plans, AppUserProvisioningService users, Clock clock) {
        this.plans = plans;
        this.users = users;
        this.clock = clock;
    }

    public LearnerPlan read(UUID learnerId) {
        requireLearner(learnerId);
        return plans.readPlan(learnerId, now());
    }

    @Transactional
    public LearnerPlan change(UUID learnerId, Tier tier, Integer durationDays, String actor) {
        if (durationDays != null && (tier != Tier.PLUS
            || (durationDays != 30 && durationDays != 90 && durationDays != 365))) {
            throw new RequestValidationException("Plus duration must be 30, 90, 365 days or unlimited");
        }
        requireLearner(learnerId);
        plans.lockPlan(learnerId);

        Instant now = now();
        plans.cancelScheduledPlus(learnerId, now);
        boolean plus = plans.isPlus(learnerId, now);
        if (tier == Tier.PLUS && !plus) {
            Instant endsAt = durationDays == null ? null : now.plus(durationDays, ChronoUnit.DAYS);
            plans.startPlus(learnerId, now, endsAt, actor);
        } else if (tier == Tier.FREE && plus) {
            plans.endPlus(learnerId, now, actor);
        }
        return plans.readPlan(learnerId, now);
    }

    private void requireLearner(UUID learnerId) {
        if (!users.exists(learnerId)) {
            throw new ResourceNotFoundException("Learner not found: " + learnerId);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
