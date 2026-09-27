package com.lyreo.toeic.application;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.toeic.application.port.ToeicAttemptRepository;
import com.lyreo.toeic.application.port.ToeicTestContentRepository;
import com.lyreo.toeic.domain.ToeicTestContent;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Returns learner-safe TOEIC content from the currently active, published test version. */
public class ToeicTestQueryService {
    private final ToeicAttemptRepository attempts;
    private final ToeicTestContentRepository content;
    private final EntitlementService entitlements;

    public ToeicTestQueryService(
        ToeicAttemptRepository attempts,
        ToeicTestContentRepository content,
        EntitlementService entitlements
    ) {
        this.attempts = attempts;
        this.content = content;
        this.entitlements = entitlements;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ToeicTestContent getActiveTest(UUID learnerId, UUID catalogId) {
        var test = attempts.findActiveTest(catalogId)
            .orElseThrow(() -> new ResourceNotFoundException("TOEIC test not found in the active release: " + catalogId));
        if (!"PUBLISHED".equals(test.publicationStatus())) {
            throw new ResourceNotFoundException("TOEIC test is not published: " + catalogId);
        }
        if ("FEATURE".equals(test.accessMode())) {
            if (learnerId == null) {
                throw new AccessDeniedException("A user account is required for this TOEIC feature");
            }
            entitlements.requireFeature(learnerId, FeatureKey.of(test.requiredFeatureKey()));
        }
        return content.findActiveTestContent(catalogId, test.testVersionId())
            .orElseThrow(() -> new ResourceNotFoundException("TOEIC test version is no longer active: " + catalogId));
    }
}
