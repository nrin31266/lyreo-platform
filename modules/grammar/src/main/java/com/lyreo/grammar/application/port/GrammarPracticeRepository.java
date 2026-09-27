package com.lyreo.grammar.application.port;

import com.lyreo.grammar.application.GrammarPracticeFilter;
import com.lyreo.grammar.domain.GrammarQuestion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence port for the curated/imported grammar bank and append-only learner attempts. */
public interface GrammarPracticeRepository {
    List<String> findRequiredFeatureKeys();

    List<CatalogAccess> findCatalogAccessPolicies(GrammarPracticeFilter filter);

    List<AccessRequirement> findAccessRequirements(UUID itemId);

    List<GrammarQuestion> findPracticeQuestions(
        GrammarPracticeFilter filter,
        Set<String> allowedFeatureKeys,
        int limit
    );

    Optional<GrammarQuestion> findQuestion(UUID itemId, Set<String> allowedFeatureKeys);

    UUID saveAttempt(
        UUID learnerId,
        UUID itemId,
        String submittedAnswer,
        boolean correct,
        Instant answeredAt
    );

    record CatalogAccess(UUID catalogId, String publicationStatus, String accessMode, String requiredFeatureKey) {}

    /** One membership route; every required feature on that route must be granted. */
    record AccessRequirement(boolean published, List<String> requiredFeatureKeys) {}
}
