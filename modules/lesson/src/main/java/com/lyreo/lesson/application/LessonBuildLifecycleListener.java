package com.lyreo.lesson.application;

import com.lyreo.contracts.jobs.JobProgressChangedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Keeps the user-visible Lesson lifecycle consistent with the durable background job. */
@Component
public class LessonBuildLifecycleListener {
    private final LessonBuildStateRepository state;
    private final LessonRepository lessons;

    public LessonBuildLifecycleListener(
        LessonBuildStateRepository state,
        LessonRepository lessons
    ) {
        this.state = state;
        this.lessons = lessons;
    }

    @ApplicationModuleListener
    public void onJobProgress(JobProgressChangedEvent event) {
        if (!CreateLessonBuildService.JOB_TYPE.equals(event.jobType())) {
            return;
        }

        var lessonId = state.lessonIdForJob(event.jobId());
        switch (event.status()) {
            case "FAILED" -> lessons.markFailed(lessonId);
            case "CANCELLED" -> lessons.markCancelled(lessonId);
            case "SUCCEEDED" -> lessons.markReady(lessonId);
            default -> {
                // QUEUED/RUNNING/RETRY_WAIT remain PROCESSING. The job table contains detailed
                // operational state, while Lesson exposes a stable learner/admin lifecycle.
            }
        }
    }
}
