package com.lyreo.toeic;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.toeic.application.ToeicAttemptRepository;
import com.lyreo.toeic.application.ToeicAttemptService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class ToeicAttemptServiceTest {

    @Test
    void computesRawScoresOnServerAndDoesNotInventScaledScore() {
        UUID testId = UUID.randomUUID();
        UUID listening = UUID.randomUUID();
        UUID reading = UUID.randomUUID();
        var repository = new FakeRepository(List.of(
            new ToeicAttemptRepository.QuestionKey(listening, 2, "B"),
            new ToeicAttemptRepository.QuestionKey(reading, 5, "A")
        ));
        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        var service = new ToeicAttemptService(repository, publisher);

        var result = service.submit(
            UUID.randomUUID(),
            testId,
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
    }

    private static final class FakeRepository implements ToeicAttemptRepository {
        private final List<QuestionKey> keys;

        private FakeRepository(List<QuestionKey> keys) {
            this.keys = keys;
        }

        @Override
        public List<QuestionKey> answerKey(UUID testId, Set<UUID> questionIds) {
            if (questionIds.isEmpty()) return keys;
            return keys.stream().filter(key -> questionIds.contains(key.questionId())).toList();
        }

        @Override
        public UUID saveCompletedAttempt(
            UUID learnerId,
            UUID testId,
            String mode,
            ScoreSummary score,
            Map<UUID, String> submittedAnswers,
            List<QuestionKey> answerKey
        ) {
            return UUID.randomUUID();
        }
    }
}
