package com.lyreo.grammar;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.contracts.grammar.GrammarQuestionAnsweredEvent;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.grammar.application.GrammarPracticeFilter;
import com.lyreo.grammar.application.GrammarPracticeScorer;
import com.lyreo.grammar.application.GrammarPracticeService;
import com.lyreo.grammar.application.port.GrammarPracticeRepository;
import com.lyreo.grammar.domain.GrammarQuestion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

class GrammarPracticeServiceTest {
    @Test
    void hidesAnswerBeforeSubmitThenScoresFromServerOwnedBank() {
        UUID questionId = UUID.randomUUID();
        GrammarQuestion question = new GrammarQuestion(
            questionId,
            "The lecture will take place at 6:00 P.M, ------- which attendees may ask questions.",
            List.of(
                new GrammarQuestion.Option("A", "across"),
                new GrammarQuestion.Option("B", "after"),
                new GrammarQuestion.Option("C", "inside"),
                new GrammarQuestion.Option("D", "among")
            ),
            "B",
            "Cấu trúc after which.",
            "Bài giảng sẽ diễn ra lúc 6 giờ.",
            "across: băng qua\nafter: sau khi",
            "take place: diễn ra",
            3,
            null,
            null,
            GrammarQuestion.ExplanationPolicy.SOURCE
        );
        FakeRepository repository = new FakeRepository(question);
        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        GrammarPracticeService service = new GrammarPracticeService(
            repository,
            new GrammarPracticeScorer(),
            publisher,
            new AllowNoFeatures()
        );

        var before = service.practice(
            new GrammarPracticeFilter(null, null, null, 3),
            10,
            UUID.randomUUID()
        );
        assertThat(before).singleElement().satisfies(view -> {
            assertThat(view.questionText()).contains("lecture");
            assertThat(view.options()).hasSize(4);
        });

        var result = service.submit(UUID.randomUUID(), questionId, "b");
        assertThat(result.correct()).isTrue();
        assertThat(result.correctAnswer()).isEqualTo("B");
        assertThat(repository.lastSavedAnswer).isEqualTo("B");
        assertThat(events).singleElement().isInstanceOf(GrammarQuestionAnsweredEvent.class);
    }

    @Test
    void blocksFeatureGatedItemWhenLearnerHasNoGrant() {
        UUID itemId = UUID.randomUUID();
        FakeRepository repository = new FakeRepository(new GrammarQuestion(
            itemId, "A stem", List.of(new GrammarQuestion.Option("A", "one")), "A",
            null, null, null, null, 1, null, null, GrammarQuestion.ExplanationPolicy.SOURCE
        ));
        repository.accessRequirements = List.of(new GrammarPracticeRepository.AccessRequirement(true, List.of("grammar.advanced")));
        repository.requiredFeatures = List.of("grammar.advanced");
        GrammarPracticeService service = new GrammarPracticeService(
            repository,
            new GrammarPracticeScorer(),
            event -> {},
            new AllowNoFeatures()
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.submit(UUID.randomUUID(), itemId, "A"))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(repository.savedItemId).isNull();
    }

    private static final class FakeRepository implements GrammarPracticeRepository {
        private final GrammarQuestion question;
        private String lastSavedAnswer;
        private UUID savedItemId;
        private List<AccessRequirement> accessRequirements = List.of(new AccessRequirement(true, List.of()));
        private List<String> requiredFeatures = List.of();

        private FakeRepository(GrammarQuestion question) {
            this.question = question;
        }

        @Override
        public List<String> findRequiredFeatureKeys() {
            return requiredFeatures;
        }

        @Override
        public List<CatalogAccess> findCatalogAccessPolicies(GrammarPracticeFilter filter) {
            return List.of();
        }

        @Override
        public List<AccessRequirement> findAccessRequirements(UUID itemId) {
            return accessRequirements;
        }

        @Override
        public List<GrammarQuestion> findPracticeQuestions(GrammarPracticeFilter filter, Set<String> allowedFeatureKeys, int limit) {
            return List.of(question);
        }

        @Override
        public Optional<GrammarQuestion> findQuestion(UUID itemId, Set<String> allowedFeatureKeys) {
            return question.itemId().equals(itemId) ? Optional.of(question) : Optional.empty();
        }

        @Override
        public UUID saveAttempt(
            UUID learnerId,
            UUID itemId,
            String submittedAnswer,
            boolean correct,
            Instant answeredAt
        ) {
            this.savedItemId = itemId;
            this.lastSavedAnswer = submittedAnswer;
            return UUID.randomUUID();
        }
    }

    private static final class AllowNoFeatures implements EntitlementService {
        @Override
        public boolean hasFeature(UUID userId, FeatureKey featureKey) {
            return false;
        }

        @Override
        public void requireFeature(UUID userId, FeatureKey featureKey) {
            throw new AccessDeniedException("No grant");
        }
    }
}
