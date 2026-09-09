package com.lyreo.learner.application;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.learner.domain.LearnerPreferences;
import com.lyreo.learner.domain.LearnerProfile;
import java.util.UUID;

public final class OnboardingService {
    private final LearnerProfileRepository repository;

    public OnboardingService(LearnerProfileRepository repository) {
        this.repository = repository;
    }

    public LearnerProfile onboard(
        UUID learnerId,
        String displayName,
        String currentLevel,
        String goal,
        int dailyMinutes,
        String focusArea
    ) {
        if (dailyMinutes < 5 || dailyMinutes > 240) {
            throw new RequestValidationException("dailyMinutes must be between 5 and 240");
        }
        return repository.save(new LearnerProfile(
            learnerId, displayName, currentLevel, goal, dailyMinutes, focusArea,
            LearnerPreferences.defaults()
        ));
    }
}
