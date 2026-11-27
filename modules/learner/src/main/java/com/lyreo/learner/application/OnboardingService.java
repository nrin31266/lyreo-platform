package com.lyreo.learner.application;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.learner.application.port.LearnerProfileRepository;
import com.lyreo.learner.domain.LearnerProfile;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class OnboardingService {
    private final LearnerProfileRepository repository;

    public OnboardingService(LearnerProfileRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public LearnerProfile onboard(
        UUID learnerId,
        LearnerProfile.Goal goal,
        LearnerProfile.Level currentLevel,
        int dailyMinutes
    ) {
        return onboard(learnerId, goal, currentLevel, dailyMinutes, null);
    }

    @Transactional
    public LearnerProfile onboard(
        UUID learnerId,
        LearnerProfile.Goal goal,
        LearnerProfile.Level currentLevel,
        int dailyMinutes,
        Integer toeicTargetScore
    ) {
        if (dailyMinutes < 5 || dailyMinutes > 240) {
            throw new RequestValidationException("dailyMinutes must be between 5 and 240");
        }
        validateToeicTargetScore(toeicTargetScore);
        repository.recordFirstSeen(learnerId);
        return repository.completeOnboarding(learnerId, goal, currentLevel, dailyMinutes, toeicTargetScore);
    }

    public static void validateToeicTargetScore(Integer toeicTargetScore) {
        if (toeicTargetScore != null && (toeicTargetScore < 10 || toeicTargetScore > 990 || toeicTargetScore % 5 != 0)) {
            throw new RequestValidationException("toeicTargetScore must be between 10 and 990 in increments of 5");
        }
    }
}
