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
    List<AuthoringLesson.Job> jobs, boolean alignmentRequired, com.lyreo.lesson.domain.authoring.AuthoringOptions options,
    boolean contentComplete,boolean contentNeeded,int vocabularyBudget,int grammarBudget,
    Profile profile,Statistics statistics
) {
    static LessonAuthoringResponse from(AuthoringLesson lesson) {
        if(lesson.publishedAt==null) com.lyreo.lesson.domain.authoring.AuthoringCloze.generateAll(lesson.sentences);
        var reviewSentences=new java.util.ArrayList<Sentence>();
        AuthoringSentence previous=null;
        for(var sentence:lesson.sentences) {
            int index=reviewSentences.size();
            boolean stale=lesson.publishedAt==null && com.lyreo.lesson.domain.authoring.AuthoringContent.features(lesson.options).stream()
                .filter(feature->!feature.equals("PROFILE")).anyMatch(feature->!com.lyreo.lesson.domain.authoring.AuthoringContent.current(lesson,index,feature));
            reviewSentences.add(Sentence.from(sentence,lesson.endMs-lesson.startMs,previous,stale));
            previous=sentence;
        }
        return new LessonAuthoringResponse(lesson.id, lesson.title, lesson.videoId, lesson.sourceReference,
            lesson.startMs, lesson.endMs>0 ? lesson.endMs : null, lesson.sourceType,lesson.audioSourceId,lesson.ttsVoice,lesson.audioObjectKey!=null,lesson.audioObjectKey==null ? null : UUID.nameUUIDFromBytes(lesson.audioObjectKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),lesson.revision, lesson.transcriptRevision, lesson.approvedRevision,
            lesson.karaokeReviewedRevision, lesson.semanticRevision, lesson.status, lesson.timingStatus,
            reviewSentences, lesson.jobs, lesson.alignmentRequired, lesson.options,
            lesson.publishedAt!=null || com.lyreo.lesson.domain.authoring.AuthoringContent.complete(lesson),
            lesson.publishedAt==null && com.lyreo.lesson.domain.authoring.AuthoringContent.needed(lesson),
            com.lyreo.lesson.domain.authoring.AuthoringContent.vocabularyLimit(lesson),com.lyreo.lesson.domain.authoring.AuthoringContent.grammarLimit(lesson),
            new Profile(lesson.profile.cefr,lesson.profile.contentType,lesson.profile.manual,!com.lyreo.lesson.domain.authoring.AuthoringContent.profileKey(lesson).equals(lesson.profile.key)),
            new Statistics(com.lyreo.lesson.domain.authoring.AuthoringContent.words(lesson),lesson.sentences.size(),Math.max(0,lesson.endMs-lesson.startMs),
                lesson.endMs>lesson.startMs ? (int)Math.round(com.lyreo.lesson.domain.authoring.AuthoringContent.words(lesson)*60000.0/(lesson.endMs-lesson.startMs)) : null,
                (int)lesson.sentences.stream().filter(s->s.dictationSelected).count(),(int)lesson.sentences.stream().filter(s->s.shadowingSelected).count()));
    }
    public record Profile(com.lyreo.lesson.domain.authoring.AuthoringProfile.Cefr cefr,
        com.lyreo.lesson.domain.authoring.AuthoringProfile.ContentType contentType,boolean manual,boolean stale) {}
    public record Statistics(int wordCount,int sentenceCount,long durationMs,Integer wordsPerMinute,int dictationCount,int shadowingCount) {}

    public record Sentence(
        UUID id, String text, String usage, boolean hidden, List<AuthoringSentence.Word> words, List<String> issues,
        String translation, List<AuthoringSentence.Annotation> vocabulary, List<AuthoringSentence.Annotation> grammar,
        String dictationPriority, String shadowingPriority, boolean dictationEligible, boolean shadowingEligible,
        boolean dictationSelected, boolean shadowingSelected, List<AuthoringSentence.Hint> hints,
        List<AuthoringSentence.Note> notes, List<AuthoringSentence.Group> thoughtGroups, java.util.Set<String> manualFields, boolean contentStale, String timingSource, String ipa, String ipaAccent, String ipaVersion, boolean ipaNeedsReview, Long previewStartMs,String ipaRaw,String ipaDisplayVersion,List<AuthoringSentence.IpaReviewIssue> ipaReviewIssues,List<String> contentWarnings,java.util.Map<String,String> exclusions,com.lyreo.lesson.domain.authoring.AuthoringCloze.Targets cloze,boolean clozeManual
    ) {
        static Sentence from(AuthoringSentence sentence, long durationMs, AuthoringSentence previous,boolean stale) {
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
                sentence.dictationSelected, sentence.shadowingSelected, sentence.hints, sentence.notes, sentence.thoughtGroups, sentence.manualFields, stale, sentence.timingSource, sentence.ipa, sentence.ipaAccent, sentence.ipaVersion, sentence.ipaNeedsReview, previewStart,sentence.ipaRaw,sentence.ipaDisplayVersion,sentence.ipaReviewIssues,sentence.contentWarnings,sentence.exclusions,sentence.cloze==null?com.lyreo.lesson.domain.authoring.AuthoringCloze.generate(sentence):sentence.cloze,sentence.clozeManual);
        }
    }
}
