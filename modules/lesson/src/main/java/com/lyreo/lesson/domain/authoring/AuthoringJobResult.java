package com.lyreo.lesson.domain.authoring;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** The saved checkpoint at this job, never reconstructed from a later draft. */
public record AuthoringJobResult(int version, Instant recordedAt, long transcriptRevision,
    int sentences, int words, long audioDurationMs, int timedSentences,
    Map<String,Integer> completedFeatures, int vocabularyNotes, int grammarNotes,
    int dictationSentences, int shadowingSentences, int warnings) {
    public static AuthoringJobResult capture(AuthoringLesson lesson) {
        var coverage=new LinkedHashMap<String,Integer>();
        for(String feature:AuthoringContent.features(lesson.options.normalized())) {
            int count=0;
            for(int i=0;i<lesson.sentences.size();i++) if(AuthoringContent.current(lesson,i,feature)) count++;
            coverage.put(feature,count);
        }
        return new AuthoringJobResult(1,Instant.now(),lesson.transcriptRevision,lesson.sentences.size(),
            AuthoringContent.words(lesson),Math.max(0,lesson.endMs-lesson.startMs),
            "VALID".equals(lesson.timingStatus)?(int)lesson.sentences.stream().filter(s->!s.words.isEmpty()).count():0,
            Map.copyOf(coverage),lesson.sentences.stream().mapToInt(s->s.vocabulary.size()).sum(),
            lesson.sentences.stream().mapToInt(s->s.grammar.size()).sum(),
            (int)lesson.sentences.stream().filter(s->s.dictationSelected).count(),
            (int)lesson.sentences.stream().filter(s->s.shadowingSelected).count(),
            lesson.sentences.stream().mapToInt(s->s.contentWarnings.size()).sum());
    }
}
