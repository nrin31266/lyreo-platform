package com.lyreo.lesson.api;

import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import java.util.List;
import java.util.UUID;

/** Review contract excludes private media keys and worker coordination state. */
public record LessonAuthoringResponse(
    UUID id, String title, String videoId, String sourceReference, long startMs, long endMs,
    long revision, long transcriptRevision, Long approvedRevision, Long karaokeReviewedRevision,
    Long semanticRevision, String status, String timingStatus, List<Sentence> sentences,
    List<AuthoringLesson.Job> jobs, boolean alignmentRequired
) {
    static LessonAuthoringResponse from(AuthoringLesson lesson) {
        return new LessonAuthoringResponse(lesson.id, lesson.title, lesson.videoId, lesson.sourceReference,
            lesson.startMs, lesson.endMs, lesson.revision, lesson.transcriptRevision, lesson.approvedRevision,
            lesson.karaokeReviewedRevision, lesson.semanticRevision, lesson.status, lesson.timingStatus,
            lesson.sentences.stream().map(sentence -> Sentence.from(sentence, lesson.endMs - lesson.startMs)).toList(), lesson.jobs, lesson.alignmentRequired);
    }

    public record Sentence(
        UUID id, String text, String usage, List<AuthoringSentence.Word> words, List<String> issues,
        String translation, List<AuthoringSentence.Annotation> vocabulary, List<AuthoringSentence.Annotation> grammar,
        String dictationPriority, String shadowingPriority, boolean dictationEligible, boolean shadowingEligible,
        boolean dictationSelected, boolean shadowingSelected, List<AuthoringSentence.Hint> hints,
        List<AuthoringSentence.Note> notes, List<AuthoringSentence.Group> thoughtGroups, String timingSource, Long previewStartMs
    ) {
        static Sentence from(AuthoringSentence sentence, long durationMs) {
            Long previewStart = sentence.coarseStartMs;
            if (!sentence.words.isEmpty()) previewStart = sentence.words.getFirst().startMs();
            if (previewStart != null && (previewStart < 0 || previewStart >= durationMs)) previewStart = null;
            var textIssues = com.lyreo.lesson.domain.authoring.AuthoringText.issues(sentence).stream()
                .filter(issue -> issue.equals("SHORT_SENTENCE") || issue.equals("LONG_SENTENCE"));
            var reviewIssues = java.util.stream.Stream.concat(textIssues, sentence.issues.stream()).distinct().toList();
            return new Sentence(sentence.id, sentence.text, sentence.usage, sentence.words, reviewIssues,
                sentence.translation, sentence.vocabulary, sentence.grammar, sentence.dictationPriority,
                sentence.shadowingPriority, sentence.dictationEligible, sentence.shadowingEligible,
                sentence.dictationSelected, sentence.shadowingSelected, sentence.hints, sentence.notes, sentence.thoughtGroups, sentence.timingSource, previewStart);
        }
    }
}
