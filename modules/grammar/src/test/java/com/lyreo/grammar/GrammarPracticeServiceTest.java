package com.lyreo.grammar;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.contracts.grammar.GrammarQuestionAnsweredEvent;
import com.lyreo.grammar.application.GrammarPracticeFilter;
import com.lyreo.grammar.application.GrammarPracticeRepository;
import com.lyreo.grammar.application.GrammarPracticeScorer;
import com.lyreo.grammar.application.GrammarPracticeService;
import com.lyreo.grammar.domain.GrammarQuestion;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

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
            publisher
        );

        var before = service.practice(
            new GrammarPracticeFilter(null, null, null, 3),
            10
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

    private static final class FakeRepository implements GrammarPracticeRepository {
        private final GrammarQuestion question;
        private String lastSavedAnswer;

        private FakeRepository(GrammarQuestion question) {
            this.question = question;
        }

        @Override
        public List<GrammarQuestion> findPracticeQuestions(GrammarPracticeFilter filter, int limit) {
            return List.of(question);
        }

        @Override
        public Optional<GrammarQuestion> findQuestion(UUID questionId) {
            return question.id().equals(questionId) ? Optional.of(question) : Optional.empty();
        }

        @Override
        public UUID saveAttempt(
            UUID learnerId,
            UUID questionId,
            String submittedAnswer,
            boolean correct,
            Instant answeredAt
        ) {
            this.lastSavedAnswer = submittedAnswer;
            return UUID.randomUUID();
        }
    }
}
