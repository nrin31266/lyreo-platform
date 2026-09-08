package com.lyreo.learner.application;

import com.lyreo.learner.domain.LearnerPreferences;
import com.lyreo.learner.domain.LearnerProfile;
import java.util.Optional;
import java.util.UUID;

/** Persistence port owned by the learner application layer. */
public interface LearnerProfileRepository {
    Optional<LearnerProfile> findByLearnerId(UUID learnerId);
    LearnerProfile save(LearnerProfile profile);
    LearnerPreferences savePreferences(UUID learnerId, LearnerPreferences preferences);
}
