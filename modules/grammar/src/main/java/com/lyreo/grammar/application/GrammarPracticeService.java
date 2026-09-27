package com.lyreo.grammar.application;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.grammar.GrammarQuestionAnsweredEvent;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.grammar.application.port.GrammarPracticeRepository;
import com.lyreo.grammar.domain.GrammarQuestion;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-owned Grammar Bank use cases.
 *
 * <p>Question discovery never exposes the answer key. Submission loads the canonical imported
 * question again, scores it on the server, appends an attempt, then publishes a cross-module fact.
 * AI may explain or classify content elsewhere, but it is not the source of truth for correctness.</p>
 */
public class GrammarPracticeService {
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final GrammarPracticeRepository repository;
    private final GrammarPracticeScorer scorer;
    private final ApplicationEventPublisher events;
    private final EntitlementService entitlements;

    public GrammarPracticeService(
        GrammarPracticeRepository repository,
        GrammarPracticeScorer scorer,
        ApplicationEventPublisher events,
        EntitlementService entitlements
    ) {
        this.repository = repository;
        this.scorer = scorer;
        this.events = events;
        this.entitlements = entitlements;
    }

    @Transactional(readOnly = true)
    public List<QuestionView> practice(
        GrammarPracticeFilter filter,
        Integer requestedLimit,
        UUID learnerId
    ) {
        GrammarPracticeFilter safeFilter = filter == null
            ? new GrammarPracticeFilter(null, null, null, null)
            : filter;
        validateFilter(safeFilter);
        enforceSelectedCatalogAccess(safeFilter, learnerId);
        int limit = requestedLimit == null
            ? DEFAULT_LIMIT
            : Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
        Set<String> allowedFeatures = grantedFeatureKeys(learnerId);
        return repository.findPracticeQuestions(safeFilter, allowedFeatures, limit).stream()
            .map(QuestionView::from)
            .toList();
    }

    @Transactional
    public SubmitResult submit(UUID learnerId, UUID itemId, String answer) {
        if (learnerId == null || itemId == null) {
            throw new RequestValidationException("learnerId and itemId are required");
        }
        String normalized = normalizeAnswer(answer);
        List<GrammarPracticeRepository.AccessRequirement> requirements = repository.findAccessRequirements(itemId);
        boolean published = requirements.stream().anyMatch(GrammarPracticeRepository.AccessRequirement::published);
        if (!published) {
            throw new ResourceNotFoundException("Grammar item not found: " + itemId);
        }
        Set<String> allowedFeatures = grantedFeatureKeys(learnerId);
        boolean accessible = requirements.stream()
            .filter(GrammarPracticeRepository.AccessRequirement::published)
            .anyMatch(requirement -> allowedFeatures.containsAll(requirement.requiredFeatureKeys()));
        if (!accessible) {
            throw new AccessDeniedException("No published Grammar catalog route grants access to this item");
        }
        GrammarQuestion question = repository.findQuestion(itemId, allowedFeatures)
            .orElseThrow(() -> new ResourceNotFoundException("Grammar item not found: " + itemId));
        boolean correct = scorer.correct(question, normalized);
        Instant now = Instant.now();
        UUID attemptId = repository.saveAttempt(
            learnerId,
            question.itemId(),
            normalized.isBlank() ? null : normalized,
            correct,
            now
        );

        events.publishEvent(new GrammarQuestionAnsweredEvent(
            learnerId,
            attemptId,
            question.itemId(),
            question.topicCatalogId(),
            question.subtopicCatalogId(),
            question.difficultyLevel(),
            correct,
            now
        ));

        return new SubmitResult(
            attemptId,
            question.itemId(),
            correct,
            question.correctAnswer(),
            question.explanationVi(),
            question.translationVi(),
            question.answerTranslationVi(),
            question.vocabularyNote(),
            question.explanationPolicy()
        );
    }

    private static String normalizeAnswer(String answer) {
        String normalized = answer == null ? "" : answer.strip().toUpperCase(Locale.ROOT);
        if (!normalized.isEmpty() && !List.of("A", "B", "C", "D").contains(normalized)) {
            throw new RequestValidationException("Grammar answer must be A, B, C or D");
        }
        return normalized;
    }

    private void validateFilter(GrammarPracticeFilter filter) {
        if (filter.bankCatalogId() != null
            && (filter.topicCatalogId() != null || filter.subtopicCatalogId() != null)) {
            throw new RequestValidationException("Choose a Grammar topic/subtopic catalog or a bank catalog");
        }
    }

    private void enforceSelectedCatalogAccess(GrammarPracticeFilter filter, UUID learnerId) {
        if (!filter.hasCatalogFilter()) return;

        List<GrammarPracticeRepository.CatalogAccess> policies = repository.findCatalogAccessPolicies(filter);
        Set<UUID> foundCatalogs = policies.stream()
            .map(GrammarPracticeRepository.CatalogAccess::catalogId)
            .collect(java.util.stream.Collectors.toSet());
        if ((filter.topicCatalogId() != null && !foundCatalogs.contains(filter.topicCatalogId()))
            || (filter.subtopicCatalogId() != null && !foundCatalogs.contains(filter.subtopicCatalogId()))
            || (filter.bankCatalogId() != null && !foundCatalogs.contains(filter.bankCatalogId()))) {
            throw new ResourceNotFoundException("Grammar catalog not found in the active release");
        }
        for (GrammarPracticeRepository.CatalogAccess policy : policies) {
            if (!"PUBLISHED".equals(policy.publicationStatus())) {
                throw new ResourceNotFoundException("Grammar catalog is not published");
            }
            if ("FEATURE".equals(policy.accessMode())) {
                if (learnerId == null) {
                    throw new AccessDeniedException("A user account is required for this Grammar feature");
                }
                entitlements.requireFeature(learnerId, FeatureKey.of(policy.requiredFeatureKey()));
            }
        }
    }

    private Set<String> grantedFeatureKeys(UUID learnerId) {
        if (learnerId == null) return Set.of();
        Set<String> allowed = new HashSet<>();
        for (String rawKey : repository.findRequiredFeatureKeys()) {
            FeatureKey key = FeatureKey.of(rawKey);
            if (entitlements.hasFeature(learnerId, key)) allowed.add(key.value());
        }
        return Set.copyOf(allowed);
    }

    /** Safe pre-submit view: no answer key or explanation leakage. */
    public record QuestionView(
        UUID itemId,
        String questionText,
        List<GrammarQuestion.Option> options,
        int difficultyLevel,
        UUID topicCatalogId,
        UUID subtopicCatalogId
    ) {
        static QuestionView from(GrammarQuestion question) {
            return new QuestionView(
                question.itemId(),
                question.questionText(),
                question.options(),
                question.difficultyLevel(),
                question.topicCatalogId(),
                question.subtopicCatalogId()
            );
        }
    }

    /** Post-submit feedback may reveal canonical answer/explanation because the attempt is persisted. */
    public record SubmitResult(
        UUID attemptId,
        UUID itemId,
        boolean correct,
        String correctAnswer,
        String explanationVi,
        String translationVi,
        String answerTranslationVi,
        String vocabularyNote,
        GrammarQuestion.ExplanationPolicy explanationPolicy
    ) {}
}
