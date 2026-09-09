package com.lyreo.vocabulary.application;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.vocabulary.VocabularyReviewCompletedEvent;
import com.lyreo.vocabulary.domain.VocabularyCard;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

public class VocabularyCommandService {
    private final VocabularyRepository repository;
    private final SpacedRepetitionScheduler scheduler;
    private final ApplicationEventPublisher events;

    public VocabularyCommandService(
        VocabularyRepository repository,
        SpacedRepetitionScheduler scheduler,
        ApplicationEventPublisher events
    ) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.events = events;
    }

    public VocabularyCard add(
        UUID learnerId,
        UUID lexiconEntryId,
        String contextType,
        UUID contextId
    ) {
        return repository.addIfAbsent(learnerId, lexiconEntryId, contextType, contextId, Instant.now());
    }

    public List<VocabularyCard> due(UUID learnerId, int limit) {
        return repository.due(learnerId, Instant.now(), Math.min(Math.max(limit, 1), 100));
    }

    @Transactional
    public SpacedRepetitionScheduler.ScheduleResult review(
        UUID learnerId,
        UUID cardId,
        SpacedRepetitionScheduler.Rating rating
    ) {
        VocabularyCard card = repository.findCard(learnerId, cardId)
            .orElseThrow(() -> new ResourceNotFoundException("Vocabulary card not found: " + cardId));
        Instant at = Instant.now();
        var result = scheduler.schedule(card, rating, at);
        repository.applyReview(card.id(), rating, result, at);
        events.publishEvent(new VocabularyReviewCompletedEvent(
            learnerId, card.lexiconEntryId(), rating.name(), rating != SpacedRepetitionScheduler.Rating.AGAIN, at
        ));
        return result;
    }
}
