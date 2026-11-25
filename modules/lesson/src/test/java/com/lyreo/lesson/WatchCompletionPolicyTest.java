package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.lesson.LessonActivityCompletedEvent;
import com.lyreo.contracts.lesson.LessonCompletedEvent;
import com.lyreo.lesson.application.port.LessonPracticeRepository;
import com.lyreo.lesson.application.port.LessonPracticeRepository.WatchCompletion;
import com.lyreo.lesson.application.port.LessonPracticeRepository.WatchTarget;
import com.lyreo.lesson.application.practice.DictationScoringPolicy;
import com.lyreo.lesson.application.practice.LessonPracticeService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

class WatchCompletionPolicyTest {

    @Test
    void completeWatchEmitsDurableFactIdsOnlyOnFirstTransition() {
        LessonPracticeRepository repository = mock(LessonPracticeRepository.class);
        List<Object> published = new ArrayList<>();
        ApplicationEventPublisher events = published::add;

        LessonPracticeService service = new LessonPracticeService(
            repository,
            new DictationScoringPolicy(),
            events,
            new ObjectMapper()
        );

        UUID learnerId = UUID.randomUUID();
        UUID lessonId = UUID.randomUUID();
        UUID watchId = UUID.randomUUID();

        when(repository.findWatchTarget(lessonId, watchId))
            .thenReturn(Optional.of(new WatchTarget(lessonId, watchId, "WATCH", 4)));
        when(repository.completeWatchActivity(learnerId, lessonId, watchId, 4))
            .thenReturn(new WatchCompletion(4, 4, true))
            .thenReturn(new WatchCompletion(4, 4, false));
        when(repository.tryMarkLessonCompleted(learnerId, lessonId))
            .thenReturn(true)
            .thenReturn(false);

        var first = service.completeWatch(learnerId, lessonId, watchId, null);
        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(first.activityCompleted()).isTrue();
        assertThat(first.lessonCompleted()).isTrue();
        assertThat(first.completedItems()).isEqualTo(4);
        assertThat(first.totalItems()).isEqualTo(4);

        assertThat(published).hasSize(2);
        var activityEvent = (LessonActivityCompletedEvent) published.get(0);
        var lessonEvent = (LessonCompletedEvent) published.get(1);
        assertThat(activityEvent.activityType()).isEqualTo("WATCH");
        assertThat(activityEvent.serverScore()).isEqualTo(100);
        assertThat(activityEvent.factId()).isEqualTo(UUID.nameUUIDFromBytes(
            ("lesson-activity:" + learnerId + ":" + watchId).getBytes(StandardCharsets.UTF_8)
        ));
        assertThat(lessonEvent.factId()).isEqualTo(UUID.nameUUIDFromBytes(
            ("lesson:" + learnerId + ":" + lessonId).getBytes(StandardCharsets.UTF_8)
        ));

        var second = service.completeWatch(learnerId, lessonId, watchId, null);
        assertThat(second.activityCompleted()).isFalse();
        assertThat(second.lessonCompleted()).isFalse();
        assertThat(published).hasSize(2);
        verify(repository, times(2)).completeWatchActivity(learnerId, lessonId, watchId, 4);
    }

    @Test
    void completeWatchRejectsNonWatchActivityTypes() {
        LessonPracticeRepository repository = mock(LessonPracticeRepository.class);
        LessonPracticeService service = new LessonPracticeService(
            repository,
            new DictationScoringPolicy(),
            mock(ApplicationEventPublisher.class),
            new ObjectMapper()
        );

        UUID learnerId = UUID.randomUUID();
        UUID lessonId = UUID.randomUUID();
        UUID dictationId = UUID.randomUUID();
        when(repository.findWatchTarget(lessonId, dictationId))
            .thenReturn(Optional.of(new WatchTarget(lessonId, dictationId, "DICTATION", 2)));

        assertThatThrownBy(() -> service.completeWatch(learnerId, lessonId, dictationId, null))
            .isInstanceOf(RequestValidationException.class);
    }

    @Test
    void submitDictationRejectsOverlongIdempotencyKey() {
        LessonPracticeRepository repository = mock(LessonPracticeRepository.class);
        LessonPracticeService service = new LessonPracticeService(
            repository,
            new DictationScoringPolicy(),
            mock(ApplicationEventPublisher.class),
            new ObjectMapper()
        );

        String overlong = "k".repeat(129);
        assertThatThrownBy(() -> service.submitDictation(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "hello", overlong
        )).isInstanceOf(RequestValidationException.class);
    }
}
