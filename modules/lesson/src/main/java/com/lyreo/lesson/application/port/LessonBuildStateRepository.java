package com.lyreo.lesson.application.port;

import com.lyreo.lesson.domain.build.LessonBuildPlan;
import com.lyreo.lesson.domain.build.LessonBuildStep;
import java.util.UUID;

public interface LessonBuildStateRepository {
    void create(UUID backgroundJobId, UUID lessonId, LessonBuildPlan plan, String providerSnapshotJson);
    UUID lessonIdForJob(UUID backgroundJobId);
    LessonBuildPlan planForJob(UUID backgroundJobId);
    boolean stepDone(UUID backgroundJobId, LessonBuildStep step);
    void stepStarted(UUID backgroundJobId, LessonBuildStep step);
    void stepDone(UUID backgroundJobId, LessonBuildStep step, UUID aiInvocationId, String artifactKey);
    void stepFailed(UUID backgroundJobId, LessonBuildStep step, String error);
}
