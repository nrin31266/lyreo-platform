package com.lyreo.toeic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.toeic.application.ToeicAttemptService;
import com.lyreo.toeic.application.port.ToeicAttemptRepository;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

class ToeicAttemptServiceTest {

    @Test
    void computesRawScoresOnServerAndDoesNotInventScaledScore() {
        UUID catalogId = UUID.randomUUID();
        UUID testVersionId = UUID.randomUUID();
        UUID listening = UUID.randomUUID();
        UUID reading = UUID.randomUUID();
        var repository = new FakeRepository(List.of(
            new ToeicAttemptRepository.QuestionKey(listening, 2, "B"),
            new ToeicAttemptRepository.QuestionKey(reading, 5, "A")
        ), new ToeicAttemptRepository.TestAccess(catalogId, testVersionId, "PUBLISHED", "PUBLIC", null));
        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        var service = new ToeicAttemptService(repository, publisher, new GrantingEntitlementService());

        var result = service.submit(
            UUID.randomUUID(),
            catalogId,
            ToeicAttemptService.Mode.FULL_TEST,
            Map.of(listening, "b", reading, "C")
        );

        assertThat(result.score().listeningCorrect()).isEqualTo(1);
        assertThat(result.score().listeningTotal()).isEqualTo(1);
        assertThat(result.score().readingCorrect()).isZero();
        assertThat(result.score().readingTotal()).isEqualTo(1);
        assertThat(result.score().listeningScaledScore()).isNull();
        assertThat(result.score().readingScaledScore()).isNull();
        assertThat(events).singleElement().isInstanceOf(ToeicAttemptCompletedEvent.class);
        assertThat(repository.usedTestVersionId).isEqualTo(testVersionId);
        assertThat(repository.savedTestVersionId).isEqualTo(testVersionId);
        assertThat(((ToeicAttemptCompletedEvent) events.getFirst()).testCatalogId()).isEqualTo(catalogId);
    }

    @Test
    void rejectsFeatureRestrictedPublishedTestBeforeLoadingAnswerKey() {
        UUID catalogId = UUID.randomUUID();
        var repository = new FakeRepository(
            List.of(),
            new ToeicAttemptRepository.TestAccess(catalogId, UUID.randomUUID(), "PUBLISHED", "FEATURE", "toeic.full-access")
        );
        var service = new ToeicAttemptService(repository, event -> {}, new GrantingEntitlementService());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.submit(
            UUID.randomUUID(), catalogId, ToeicAttemptService.Mode.FULL_TEST, Map.of()
        )).isInstanceOf(AccessDeniedException.class);
        assertThat(repository.answerKeyCalls).isZero();
    }

    @Test
    void rejectsFullTestAnswersForPlacementsOutsideActiveTestVersion() {
        UUID catalogId = UUID.randomUUID();
        UUID knownPlacementId = UUID.randomUUID();
        UUID foreignPlacementId = UUID.randomUUID();
        FakeRepository repository = new FakeRepository(
            List.of(new ToeicAttemptRepository.QuestionKey(knownPlacementId, 5, "A")),
            new ToeicAttemptRepository.TestAccess(catalogId, UUID.randomUUID(), "PUBLISHED", "PUBLIC", null)
        );
        var service = new ToeicAttemptService(repository, event -> {}, new GrantingEntitlementService());

        assertThatThrownBy(() -> service.submit(
            UUID.randomUUID(), catalogId, ToeicAttemptService.Mode.FULL_TEST,
            Map.of(foreignPlacementId, "A")
        )).isInstanceOf(RequestValidationException.class);
        assertThat(repository.savedTestVersionId).isNull();
    }

    private static final class FakeRepository implements ToeicAttemptRepository {
        private final List<QuestionKey> keys;
        private final TestAccess test;
        private UUID usedTestVersionId;
        private UUID savedTestVersionId;
        private int answerKeyCalls;

        private FakeRepository(List<QuestionKey> keys, TestAccess test) {
            this.keys = keys;
            this.test = test;
        }

        @Override
        public Optional<TestAccess> findActiveTest(UUID catalogId) {
            return test.catalogId().equals(catalogId) ? Optional.of(test) : Optional.empty();
        }

        @Override
        public List<QuestionKey> answerKey(UUID testVersionId, Set<UUID> placementIds) {
            this.usedTestVersionId = testVersionId;
            this.answerKeyCalls++;
            if (placementIds.isEmpty()) return keys;
            return keys.stream().filter(key -> placementIds.contains(key.placementId())).toList();
        }

        @Override
        public UUID saveCompletedAttempt(
            UUID learnerId,
            UUID testVersionId,
            String mode,
            ScoreSummary score,
            Map<UUID, String> submittedAnswers,
            List<QuestionKey> answerKey
        ) {
            this.savedTestVersionId = testVersionId;
            return UUID.randomUUID();
        }
    }

    private static final class GrantingEntitlementService implements EntitlementService {
        private final Set<String> grants = new HashSet<>();

        @Override
        public boolean hasFeature(UUID userId, FeatureKey featureKey) {
            return grants.contains(featureKey.value());
        }

        @Override
        public void requireFeature(UUID userId, FeatureKey featureKey) {
            if (!hasFeature(userId, featureKey)) throw new AccessDeniedException("No grant");
        }
    }
}
