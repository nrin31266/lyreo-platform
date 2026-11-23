package com.lyreo.learner.application;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.learner.application.port.LearnerProfileRepository;
import com.lyreo.learner.domain.LearnerDirectory;
import com.lyreo.learner.domain.LearnerPreferences;
import com.lyreo.learner.domain.LearnerProfile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for learner profile read and preference update use cases.
 * Keeps the controller adapter free from direct persistence port dependencies.
 */
public class LearnerProfileService {
    private final LearnerProfileRepository repository;

    public LearnerProfileService(LearnerProfileRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Optional<LearnerProfile> findByLearnerId(UUID learnerId) {
        repository.recordFirstSeen(learnerId);
        return repository.findByLearnerId(learnerId);
    }

    @Transactional
    public LearnerPreferences findPreferences(UUID learnerId) {
        repository.recordFirstSeen(learnerId);
        return repository.findByLearnerId(learnerId)
            .map(LearnerProfile::preferences)
            .orElseGet(LearnerPreferences::defaults);
    }

    @Transactional
    public LearnerPreferences savePreferences(UUID learnerId, LearnerPreferences preferences) {
        repository.recordFirstSeen(learnerId);
        return repository.savePreferences(learnerId, preferences);
    }

    @Transactional
    public LearnerProfile patchProfile(UUID learnerId, LearnerProfile.Patch patch) {
        repository.recordFirstSeen(learnerId);
        return repository.patchProfile(learnerId, patch)
            .orElseThrow(() -> new StateConflictException("Complete onboarding before editing the profile"));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LearnerDirectory directory(String query, LearnerDirectory.Status status, int page, int size) {
        return repository.directory(query.strip(), status, page, size);
    }

    public LearnerDirectory.Entry directoryEntry(UUID learnerId) {
        return repository.directoryEntry(learnerId)
            .orElseThrow(() -> new ResourceNotFoundException("Learner profile not found"));
    }

    @Transactional
    public LearnerDirectory.Entry patchAppearance(UUID learnerId, LearnerProfile.Appearance appearance) {
        if (!repository.patchAppearance(learnerId, appearance)) {
            throw new ResourceNotFoundException("Learner profile not found");
        }
        return directoryEntry(learnerId);
    }

    @Transactional
    public LearnerDirectory.Entry uploadDirectoryAvatar(UUID learnerId, byte[] png) {
        if (!repository.uploadAvatar(learnerId, png, false)) {
            throw new ResourceNotFoundException("Learner profile not found");
        }
        return directoryEntry(learnerId);
    }

    @Transactional
    public LearnerProfile uploadOwnAvatar(UUID learnerId, byte[] png) {
        repository.recordFirstSeen(learnerId);
        if (!repository.uploadAvatar(learnerId, png, true)) {
            throw new StateConflictException("Complete onboarding before editing the profile");
        }
        return repository.findByLearnerId(learnerId).orElseThrow();
    }

    public byte[] avatar(UUID learnerId) {
        return repository.avatar(learnerId).orElseThrow(() -> new ResourceNotFoundException("Avatar not found"));
    }
}
