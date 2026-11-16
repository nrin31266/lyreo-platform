package com.lyreo.lesson.application.port;

import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthoringRepository {
    Optional<AuthoringLesson> find(UUID id, boolean lock);
    List<UsedRange> usedRanges(String videoId);
    void create(AuthoringLesson lesson);
    void renameAutomatic(UUID id, String title);
    void save(AuthoringLesson lesson);
    void addJob(UUID lessonId, UUID jobId, String operation, long transcriptRevision);
    JobInput jobInput(UUID jobId);
    void assertJobLease(UUID jobId, String owner);
    void completeJob(UUID jobId);
    void usage(UUID jobId, AuthoringLesson.Invocation invocation);
    void jobResult(UUID jobId, com.lyreo.lesson.domain.authoring.AuthoringJobResult result);
    default void diagnostic(UUID job,UUID invocation,java.util.Map<String,Object> payload) {}
    default java.util.List<java.util.Map<String,Object>> diagnostics(UUID lesson,UUID job) {return java.util.List.of();}
    default void publish(AuthoringLesson lesson) {throw new UnsupportedOperationException();}
    default void copyMetadata(UUID original,UUID copy) {}
    record UsedRange(UUID lessonId, String title, long startMs, long endMs, String status) {}
    record JobInput(UUID lessonId, String operation, long transcriptRevision, boolean completed) {}
}
