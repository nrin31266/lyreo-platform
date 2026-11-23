package com.lyreo.learner.application.port;

import com.lyreo.learner.domain.LearnerPreferences;
import com.lyreo.learner.domain.LearnerDirectory;
import com.lyreo.learner.domain.LearnerProfile;
import java.util.Optional;
import java.util.UUID;

/** Persistence port owned by the learner application layer. */
public interface LearnerProfileRepository {
    Optional<LearnerProfile> findByLearnerId(UUID learnerId);
    void recordFirstSeen(UUID learnerId);
    LearnerProfile completeOnboarding(UUID learnerId, LearnerProfile.Goal goal, LearnerProfile.Level level, int minutes);
    Optional<LearnerProfile> patchProfile(UUID learnerId, LearnerProfile.Patch patch);
    boolean patchAppearance(UUID learnerId, LearnerProfile.Appearance appearance);
    boolean uploadAvatar(UUID learnerId, byte[] png, boolean completedOnly);
    Optional<byte[]> avatar(UUID learnerId);
    LearnerPreferences savePreferences(UUID learnerId, LearnerPreferences preferences);
    LearnerDirectory directory(String query, LearnerDirectory.Status status, int page, int size);
    Optional<LearnerDirectory.Entry> directoryEntry(UUID learnerId);
}
