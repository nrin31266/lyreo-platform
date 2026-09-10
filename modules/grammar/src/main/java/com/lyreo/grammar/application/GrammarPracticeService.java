package com.lyreo.grammar.application;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.grammar.GrammarQuestionAnsweredEvent;
import com.lyreo.grammar.domain.GrammarQuestion;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
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

    public GrammarPracticeService(
        GrammarPracticeRepository repository,
        GrammarPracticeScorer scorer,
        ApplicationEventPublisher events
    ) {
        this.repository = repository;
        this.scorer = scorer;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<QuestionView> practice(
        GrammarPracticeRepository.PracticeFilter filter,
        Integer requestedLimit
    ) {
        int limit = requestedLimit == null
            ? DEFAULT_LIMIT
            : Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
        return repository.findPracticeQuestions(filter, limit).stream()
            .map(QuestionView::from)
            .toList();
    }

    @Transactional
    public SubmitResult submit(UUID learnerId, UUID questionId, String answer) {
        if (learnerId == null || questionId == null) {
            throw new RequestValidationException("learnerId and questionId are required");
        }
        String normalized = normalizeAnswer(answer);
        GrammarQuestion question = repository.findQuestion(questionId)
            .orElseThrow(() -> new ResourceNotFoundException("Grammar question not found: " + questionId));
        boolean correct = scorer.correct(question, normalized);
        Instant now = Instant.now();
        UUID attemptId = repository.saveAttempt(
            learnerId,
            question.id(),
            normalized.isBlank() ? null : normalized,
            correct,
            now
        );

        events.publishEvent(new GrammarQuestionAnsweredEvent(
            learnerId,
            attemptId,
            question.id(),
            question.topicId(),
            question.subtopicId(),
            question.difficultyLevel(),
            correct,
            now
        ));

        return new SubmitResult(
            attemptId,
            question.id(),
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

    /** Safe pre-submit view: no answer key or explanation leakage. */
    public record QuestionView(
        UUID id,
        String questionText,
        List<GrammarQuestion.Option> options,
        int difficultyLevel,
        UUID topicId,
        UUID subtopicId
    ) {
        static QuestionView from(GrammarQuestion question) {
            return new QuestionView(
                question.id(),
                question.questionText(),
                question.options(),
                question.difficultyLevel(),
                question.topicId(),
                question.subtopicId()
            );
        }
    }

    /** Post-submit feedback may reveal canonical answer/explanation because the attempt is persisted. */
    public record SubmitResult(
        UUID attemptId,
        UUID questionId,
        boolean correct,
        String correctAnswer,
        String explanationVi,
        String translationVi,
        String answerTranslationVi,
        String vocabularyNote,
        GrammarQuestion.ExplanationPolicy explanationPolicy
    ) {}
}
