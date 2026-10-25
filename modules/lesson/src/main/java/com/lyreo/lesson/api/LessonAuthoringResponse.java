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
    List<AuthoringLesson.Job> jobs
) {
    static LessonAuthoringResponse from(AuthoringLesson lesson) {
        return new LessonAuthoringResponse(lesson.id, lesson.title, lesson.videoId, lesson.sourceReference,
            lesson.startMs, lesson.endMs, lesson.revision, lesson.transcriptRevision, lesson.approvedRevision,
            lesson.karaokeReviewedRevision, lesson.semanticRevision, lesson.status, lesson.timingStatus,
            lesson.sentences.stream().map(Sentence::from).toList(), lesson.jobs);
    }

    public record Sentence(
        UUID id, String text, String usage, List<AuthoringSentence.Word> words, List<String> issues,
        String translation, List<AuthoringSentence.Annotation> vocabulary, List<AuthoringSentence.Annotation> grammar,
        String dictationPriority, String shadowingPriority, boolean dictationEligible, boolean shadowingEligible,
        boolean dictationSelected, boolean shadowingSelected, List<AuthoringSentence.Hint> hints,
        List<AuthoringSentence.Note> notes, List<AuthoringSentence.Group> thoughtGroups
    ) {
        static Sentence from(AuthoringSentence sentence) {
            return new Sentence(sentence.id, sentence.text, sentence.usage, sentence.words, sentence.issues,
                sentence.translation, sentence.vocabulary, sentence.grammar, sentence.dictationPriority,
                sentence.shadowingPriority, sentence.dictationEligible, sentence.shadowingEligible,
                sentence.dictationSelected, sentence.shadowingSelected, sentence.hints, sentence.notes, sentence.thoughtGroups);
        }
    }
}
