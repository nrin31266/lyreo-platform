package com.lyreo.lesson.api;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.lesson.application.port.AuthoringRepository;
import com.lyreo.lesson.domain.authoring.CaptionCueText;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;

@Component
@NamedInterface(value = "shadowing", propagate = false)
public class ShadowingReference {
    private final AuthoringRepository lessons;

    public ShadowingReference(AuthoringRepository lessons) {
        this.lessons = lessons;
    }

    public Sentence read(UUID lessonId, UUID sentenceId, long revision, boolean lock) {
        var lesson = lessons.find(lessonId, lock)
            .orElseThrow(() -> new ResourceNotFoundException("Lesson not found"));
        if (lesson.revision != revision) {
            throw new StateConflictException("Reload the lesson before starting a new pronunciation attempt");
        }
        var sentence = lesson.sentences.stream().filter(row -> sentenceId.equals(row.id)).findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Lesson sentence not found"));
        boolean playable = "YOUTUBE".equals(lesson.sourceType) ? lesson.videoId != null : lesson.audioObjectKey != null;
        if (!playable || sentence.hidden || !"LEARN".equals(sentence.usage) || !sentence.shadowingSelected
            || !lesson.options.activities().contains("SHADOWING")) {
            throw new RequestValidationException("Choose a saved Shadowing sentence with a playable reference");
        }
        Long start = sentence.coarseStartMs;
        Long end = sentence.coarseEndMs;
        if (!sentence.words.isEmpty()) {
            start = sentence.words.getFirst().startMs();
            end = sentence.words.getLast().endMs();
        }
        String text = CaptionCueText.speech(sentence.text);
        if (start == null || end == null || start < 0 || end <= start || end > lesson.endMs - lesson.startMs
            || text.isBlank() || text.length() > 6_000) {
            throw new RequestValidationException("The sentence needs text and a valid reference audio range");
        }
        return new Sentence(revision, text, start, end);
    }

    @NamedInterface(value = "shadowing", propagate = false)
    public record Sentence(long revision, String text, long startMs, long endMs) {}
}
