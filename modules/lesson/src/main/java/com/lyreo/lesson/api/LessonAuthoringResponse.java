package com.lyreo.lesson.api;

import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import java.util.List;
import java.util.UUID;

/** Review contract excludes private media keys and worker coordination state. */
public record LessonAuthoringResponse(
    UUID id, String title, String videoId, String sourceReference, long startMs, Long endMs,
    String sourceType, UUID audioSourceId, String ttsVoice, boolean audioReady, String audioVersion,
    long revision, long transcriptRevision, Long approvedRevision, Long karaokeReviewedRevision,
    Long semanticRevision, String status, String timingStatus, List<Sentence> sentences,
    List<AuthoringLesson.Job> jobs, boolean alignmentRequired, com.lyreo.lesson.domain.authoring.AuthoringOptions options
) {
    static LessonAuthoringResponse from(AuthoringLesson lesson) {
        var reviewSentences=new java.util.ArrayList<Sentence>();
        AuthoringSentence previous=null;
        for(var sentence:lesson.sentences) {
            reviewSentences.add(Sentence.from(sentence,lesson.endMs-lesson.startMs,previous));
            previous=sentence;
        }
        return new LessonAuthoringResponse(lesson.id, lesson.title, lesson.videoId, lesson.sourceReference,
            lesson.startMs, lesson.endMs>0 ? lesson.endMs : null, lesson.sourceType,lesson.audioSourceId,lesson.ttsVoice,lesson.audioObjectKey!=null,lesson.audioObjectKey==null ? null : UUID.nameUUIDFromBytes(lesson.audioObjectKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),lesson.revision, lesson.transcriptRevision, lesson.approvedRevision,
            lesson.karaokeReviewedRevision, lesson.semanticRevision, lesson.status, lesson.timingStatus,
            reviewSentences, lesson.jobs, lesson.alignmentRequired, lesson.options);
    }

    public record Sentence(
        UUID id, String text, String usage, boolean hidden, List<AuthoringSentence.Word> words, List<String> issues,
        String translation, List<AuthoringSentence.Annotation> vocabulary, List<AuthoringSentence.Annotation> grammar,
        String dictationPriority, String shadowingPriority, boolean dictationEligible, boolean shadowingEligible,
        boolean dictationSelected, boolean shadowingSelected, List<AuthoringSentence.Hint> hints,
        List<AuthoringSentence.Note> notes, List<AuthoringSentence.Group> thoughtGroups, java.util.Set<String> manualFields, boolean contentStale, String timingSource, String ipa, String ipaAccent, String ipaVersion, boolean ipaNeedsReview, Long previewStartMs
    ) {
        static Sentence from(AuthoringSentence sentence, long durationMs, AuthoringSentence previous) {
            Long previewStart = sentence.coarseStartMs;
            if (!sentence.words.isEmpty()) previewStart = sentence.words.getFirst().startMs();
            if (previewStart != null && (previewStart < 0 || previewStart >= durationMs)) previewStart = null;
            var textIssues = com.lyreo.lesson.domain.authoring.AuthoringText.issues(sentence).stream()
                .filter(issue -> issue.equals("SHORT_SENTENCE") || issue.equals("LONG_SENTENCE"));
            var reviewIssues = new java.util.ArrayList<>(java.util.stream.Stream.concat(textIssues, sentence.issues.stream()).distinct().toList());
            if(previous!=null && com.lyreo.lesson.domain.authoring.AuthoringText.repeatsPreviousSuffix(previous.text,sentence.text))
                reviewIssues.add("POSSIBLE_REPEAT");
            return new Sentence(sentence.id, sentence.text, sentence.usage, sentence.hidden, sentence.words, reviewIssues,
                sentence.translation, sentence.vocabulary, sentence.grammar, sentence.dictationPriority,
                sentence.shadowingPriority, sentence.dictationEligible, sentence.shadowingEligible,
                sentence.dictationSelected, sentence.shadowingSelected, sentence.hints, sentence.notes, sentence.thoughtGroups, sentence.manualFields, sentence.contentStale, sentence.timingSource, sentence.ipa, sentence.ipaAccent, sentence.ipaVersion, sentence.ipaNeedsReview, previewStart);
        }
    }
}
