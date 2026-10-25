package com.lyreo.lesson.application.port;

import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthoringRepository {
    Optional<AuthoringLesson> find(UUID id, boolean lock);
    List<UsedRange> usedRanges(String videoId);
    void create(AuthoringLesson lesson);
    void save(AuthoringLesson lesson);
    void addJob(UUID lessonId, UUID jobId, String operation, long transcriptRevision);
    JobInput jobInput(UUID jobId);
    void assertJobLease(UUID jobId, String owner);
    Optional<List<com.lyreo.lesson.domain.authoring.AuthoringSentence>> chunk(UUID jobId,int index);
    void stageChunk(UUID jobId,int index,List<com.lyreo.lesson.domain.authoring.AuthoringSentence> sentences);
    void completeJob(UUID jobId);
    void usage(UUID jobId, AuthoringLesson.Invocation invocation);
    void publish(AuthoringLesson lesson);
    record UsedRange(UUID lessonId, String title, long startMs, long endMs, String status) {}
    record JobInput(UUID lessonId, String operation, long transcriptRevision, boolean completed) {}
}
