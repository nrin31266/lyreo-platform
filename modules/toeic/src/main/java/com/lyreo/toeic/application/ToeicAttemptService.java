package com.lyreo.toeic.application;

import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.toeic.application.ToeicAttemptRepository.QuestionKey;
import com.lyreo.toeic.application.ToeicAttemptRepository.ScoreSummary;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-owned TOEIC submission/scoring use case.
 *
 * <p>The imported bank is the answer source of truth. The learner client sends only selected
 * answers. Scaled scores remain null until Lyreo imports a reviewed conversion table.</p>
 */
public class ToeicAttemptService {
    public enum Mode { FULL_TEST, DRILL }

    private final ToeicAttemptRepository repository;
    private final ApplicationEventPublisher events;

    public ToeicAttemptService(
        ToeicAttemptRepository repository,
        ApplicationEventPublisher events
    ) {
        this.repository = repository;
        this.events = events;
    }

    @Transactional
    public SubmitResult submit(
        UUID learnerId,
        UUID testId,
        Mode mode,
        Map<UUID, String> answers
    ) {
        if (learnerId == null || testId == null || mode == null) {
            throw new IllegalArgumentException("learnerId, testId and mode are required");
        }
        Map<UUID, String> safeAnswers = answers == null
            ? Map.of()
            : normalizeAnswers(answers);

        Set<UUID> requestedQuestionIds = mode == Mode.DRILL
            ? Set.copyOf(safeAnswers.keySet())
            : Set.of();
        if (mode == Mode.DRILL && requestedQuestionIds.isEmpty()) {
            throw new IllegalArgumentException("DRILL submission requires at least one answer");
        }

        List<QuestionKey> answerKey = repository.answerKey(testId, requestedQuestionIds);
        if (answerKey.isEmpty()) {
            throw new IllegalArgumentException("No TOEIC questions found for this submission");
        }
        if (mode == Mode.DRILL && answerKey.size() != requestedQuestionIds.size()) {
            throw new IllegalArgumentException("One or more drill questions do not belong to the test");
        }

        ScoreSummary score = score(answerKey, safeAnswers);
        UUID attemptId = repository.saveCompletedAttempt(
            learnerId,
            testId,
            mode.name(),
            score,
            safeAnswers,
            answerKey
        );

        Instant occurredAt = Instant.now();
        events.publishEvent(new ToeicAttemptCompletedEvent(
            learnerId,
            attemptId,
            testId,
            mode.name(),
            score.listeningCorrect(),
            score.listeningTotal(),
            score.readingCorrect(),
            score.readingTotal(),
            score.listeningScaledScore(),
            score.readingScaledScore(),
            occurredAt
        ));

        return new SubmitResult(attemptId, score);
    }

    private static ScoreSummary score(
        List<QuestionKey> answerKey,
        Map<UUID, String> answers
    ) {
        int listeningCorrect = 0;
        int listeningTotal = 0;
        int readingCorrect = 0;
        int readingTotal = 0;

        for (QuestionKey question : answerKey) {
            boolean listening = question.part() != null && question.part() >= 1 && question.part() <= 4;
            boolean reading = question.part() != null && question.part() >= 5 && question.part() <= 7;
            if (!listening && !reading) continue;

            String actual = answers.getOrDefault(question.questionId(), "");
            boolean correct = question.correctAnswer() != null
                && question.correctAnswer().equalsIgnoreCase(actual);

            if (listening) {
                listeningTotal++;
                if (correct) listeningCorrect++;
            } else {
                readingTotal++;
                if (correct) readingCorrect++;
            }
        }

        return new ScoreSummary(
            listeningCorrect,
            listeningTotal,
            readingCorrect,
            readingTotal,
            null,
            null
        );
    }

    private static Map<UUID, String> normalizeAnswers(Map<UUID, String> answers) {
        Map<UUID, String> normalized = new LinkedHashMap<>();
        answers.forEach((questionId, answer) -> {
            if (questionId == null) return;
            String value = answer == null ? "" : answer.strip().toUpperCase(Locale.ROOT);
            if (!value.isEmpty() && !Set.of("A", "B", "C", "D").contains(value)) {
                throw new IllegalArgumentException("TOEIC answer must be A, B, C or D");
            }
            normalized.put(questionId, value);
        });
        return Map.copyOf(normalized);
    }

    public record SubmitResult(UUID attemptId, ScoreSummary score) {}
}
