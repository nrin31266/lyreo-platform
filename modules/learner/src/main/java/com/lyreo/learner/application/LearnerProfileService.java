package com.lyreo.learner.application;

import com.lyreo.learner.domain.LearnerPreferences;
import com.lyreo.learner.domain.LearnerProfile;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for learner profile read and preference update use cases.
 * Keeps the controller adapter free from direct persistence port dependencies.
 */
public final class LearnerProfileService {
    private final LearnerProfileRepository repository;

    public LearnerProfileService(LearnerProfileRepository repository) {
        this.repository = repository;
    }

    public Optional<LearnerProfile> findByLearnerId(UUID learnerId) {
        return repository.findByLearnerId(learnerId);
    }

    public LearnerPreferences findPreferences(UUID learnerId) {
        return repository.findByLearnerId(learnerId)
            .map(LearnerProfile::preferences)
            .orElseGet(LearnerPreferences::defaults);
    }

    public LearnerPreferences savePreferences(UUID learnerId, LearnerPreferences preferences) {
        return repository.savePreferences(learnerId, preferences);
    }
}
